package io.github.moronigranja.ayvu.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * MOBI decompression ceilings (A3). A text record is untrusted input: PalmDOC back-references
 * inflate ~5×, HUFF/CDIC phrases expand recursively, and a CDIC header word alone can ask for 2^31
 * dictionary slots. Every crafted record here must fail TYPED and bounded — the ceilings are
 * exercised with injected limits and small records, never by building the bomb they exist to stop.
 */
class MobiDecompressionTest {
    // ------------------------------------------------------------------
    // HUFF/CDIC fixtures (the shape tools/gen_mobi_fixtures.py writes: 256 terminal
    // 8-bit codes, so a stream byte `b` decodes to dictionary entry `255 - b`)
    // ------------------------------------------------------------------

    /** One CDIC phrase and whether the record stores it PLAIN or as a compressed slice. */
    private class Phrase(
        val bytes: ByteArray,
        val plain: Boolean,
    )

    private fun plain(bytes: ByteArray): Phrase = Phrase(bytes, plain = true)

    private fun compressed(bytes: ByteArray): Phrase = Phrase(bytes, plain = false)

    private fun huffRecord(): ByteArray {
        val huff = ByteArray(HUFF_HEADER_BYTES + 4 * 256 + 8 * 32)
        "HUFF".toByteArray(Charsets.US_ASCII).copyInto(huff, 0)
        putU32(huff, 4, 0x18)
        putU32(huff, 8, HUFF_HEADER_BYTES.toLong())
        putU32(huff, 12, (HUFF_HEADER_BYTES + 4 * 256).toLong())
        for (i in 0 until 256) putU32(huff, HUFF_HEADER_BYTES + 4 * i, 0xFF88) // codelen 8, terminal, max
        return huff
    }

    /**
     * A CDIC record carrying [phrases] — with the header's phrase count and bit width left free,
     * because THEY are the crafted part: a header that declares more phrases than the record holds.
     */
    private fun cdicRecord(
        phrases: List<Phrase>,
        declaredPhrases: Long = phrases.size.toLong(),
        bits: Int = 8,
    ): ByteArray {
        val table = ByteArray(2 * phrases.size)
        val area = ByteArrayOutputStream()
        phrases.forEachIndexed { k, phrase ->
            putU16(table, 2 * k, 2 * phrases.size + area.size())
            val lenAndFlag = phrase.bytes.size or if (phrase.plain) 0x8000 else 0
            area.write(lenAndFlag ushr 8)
            area.write(lenAndFlag and 0xFF)
            area.write(phrase.bytes, 0, phrase.bytes.size)
        }
        val out = ByteArray(CDIC_HEADER_BYTES + table.size + area.size())
        "CDIC".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        putU32(out, 4, 0x10)
        putU32(out, 8, declaredPhrases)
        putU32(out, 12, bits.toLong())
        table.copyInto(out, CDIC_HEADER_BYTES)
        area.toByteArray().copyInto(out, CDIC_HEADER_BYTES + table.size)
        return out
    }

    /**
     * A CDIC record whose header claims [declaredPhrases] phrases but whose body carries none: the
     * only offsets it can offer are its own zero bytes (phrase 0 and 1 are both empty).
     */
    private fun overClaimingRecord(
        declaredPhrases: Long,
        bits: Int,
    ): ByteArray {
        val out = ByteArray(CDIC_HEADER_BYTES + 4)
        "CDIC".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        putU32(out, 4, 0x10)
        putU32(out, 8, declaredPhrases)
        putU32(out, 12, bits.toLong())
        return out
    }

    private fun huffDecoder(): HuffCdicDecoder =
        HuffCdicDecoder().apply {
            loadHuff(huffRecord())
        }

    // ------------------------------------------------------------------
    // (a) the CDIC header's phrase count
    // ------------------------------------------------------------------

    /**
     * The header is a claim, the record is the truth. `bits = 20` used to turn the 18-byte header
     * word into 2^20 placeholder dictionary slots before the first offset was even read; the table
     * is now the record's own size, so the phantom phrases simply do not exist — provable because
     * entry 2 (which the header claimed) is refused while entry 0 still resolves.
     */
    @Test
    fun `a cdic header cannot add phrases the record does not carry`() {
        val decoder = huffDecoder()
        // A record holding two empty phrases; the header claims 2^20 of them.
        decoder.loadCdic(overClaimingRecord(declaredPhrases = 1L shl 20, bits = 20))

        assertEquals(
            0,
            decoder.unpack(byteArrayOf(255.toByte())).size,
            "the record's own first phrase resolves",
        )
        val thrown =
            assertThrows(EBookParseException::class.java) {
                decoder.unpack(byteArrayOf(253.toByte())) // dictionary entry 2: claimed, never carried
            }
        assertTrue(thrown.message.orEmpty().contains("index out of range"), thrown.message.toString())
    }

    /**
     * The pathological header — 2^31 phrases, the width that used to allocate ~2^31 placeholder
     * pairs from an 18-byte record — is a typed refusal, not an allocation. (Both orders are
     * asserted: the table is bounded by the record's 2-byte-per-phrase capacity either way.)
     */
    @Test
    fun `an absurd cdic phrase header is refused typed, not allocated`() {
        val phrases = listOf(plain("AB".toByteArray()), plain("CD".toByteArray()))
        for ((declared, bits) in listOf((1L shl 20) to 20, 0x7FFFFFFFL to 31)) {
            val decoder = huffDecoder()
            val thrown =
                assertThrows(EBookParseException::class.java) {
                    decoder.loadCdic(cdicRecord(phrases, declaredPhrases = declared, bits = bits))
                }
            assertTrue(thrown.message.orEmpty().contains("CDIC"), thrown.message.toString())
        }
    }

    // ------------------------------------------------------------------
    // (b) expansion ceilings
    // ------------------------------------------------------------------

    /**
     * Nested expansion: phrase 1 is itself a compressed stream of 200 codes that every one resolves
     * back into phrase 0, so ONE reference to phrase 1 is ~800 bytes of text. The old decoder
     * expanded that recursively with no ceiling at all; now the nested expansion draws on the same
     * per-record budget as the top-level stream and fails typed once it is spent.
     */
    @Test
    fun `nested huffcdic phrase expansion past the ceiling fails typed`() {
        val limits = ImportLimits(maxEntryBytes = 4 * 1024, maxTotalExpandedBytes = 8 * 1024)
        val decoder = huffDecoder()
        decoder.loadCdic(
            cdicRecord(
                listOf(
                    plain("ABCD".toByteArray()),
                    compressed(ByteArray(200) { 255.toByte() }), // each code -> phrase 0
                ),
            ),
        )

        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                decoder.unpack(ByteArray(16) { 254.toByte() }, limits = limits) // 16 refs -> ~12 KB
            }
        assertTrue(thrown.message.orEmpty().contains("text record is too large"), thrown.message.toString())
    }

    /** A PalmDOC stream that inflates ~5×: one literal seed, then back-references to it
     *  (distance 1, length 10 — two input bytes produce ten output bytes). */
    private fun palmdocBomb(tokens: Int): ByteArray {
        val out = ByteArray(1 + 2 * tokens)
        out[0] = 'A'.code.toByte()
        for (i in 0 until tokens) {
            out[1 + 2 * i] = 0x80.toByte()
            out[2 + 2 * i] = 0x0F.toByte()
        }
        return out
    }

    @Test
    fun `palmdoc expansion past the ceiling fails typed`() {
        val bomb = palmdocBomb(tokens = 1000) // 2 KB in, ~10 KB out
        assertEquals(10_001, Palmdoc.unpack(bomb).size, "under the default ceilings the decode is unchanged")

        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                Palmdoc.unpack(bomb, limits = ImportLimits(maxEntryBytes = 4 * 1024))
            }
        assertTrue(thrown.message.orEmpty().contains("text record is too large"), thrown.message.toString())
    }

    /**
     * The ceiling is threaded ACROSS records, not reset per record: two records that each stay
     * under the per-record ceiling still fail once the book as a whole crosses the cumulative one.
     */
    @Test
    fun `mobi text records expanding past the cumulative ceiling fail typed`() {
        val record = palmdocBomb(tokens = 1000) // 10 001 bytes expanded
        val bytes = palmdocPdb(records = listOf(record, record))

        val under =
            MobiParser.parse(
                bytes,
                "Two Records",
                ImportLimits(maxEntryBytes = 64 * 1024, maxTotalExpandedBytes = 64 * 1024),
            )
        assertEquals(
            20_002,
            under.chapters.flatMap { it.passages }.sumOf { it.text.length },
            "the crafted container is a valid book under the ceilings",
        )

        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                MobiParser.parse(
                    bytes,
                    "Two Records",
                    ImportLimits(maxEntryBytes = 16 * 1024, maxTotalExpandedBytes = 16 * 1024),
                )
            }
        assertTrue(thrown.message.orEmpty().contains("expands too far"), thrown.message.toString())
    }

    // ------------------------------------------------------------------
    // A minimal PalmDOC PDB: record 0 is the 16-byte PalmDOC header, then one
    // record per text record (no MOBI header, so no NCX/EXTH/trailing-data path)
    // ------------------------------------------------------------------

    private fun palmdocPdb(records: List<ByteArray>): ByteArray {
        val all = listOf(palmdocHeader(records.size)) + records
        val out = ByteArrayOutputStream()
        val head = ByteArray(78)
        "Two Records".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        putU16(head, 76, all.size)
        out.write(head)
        var offset = 78 + 8 * all.size
        val table = ByteArray(8 * all.size)
        all.forEachIndexed { i, record ->
            putU32(table, 8 * i, offset.toLong())
            offset += record.size
        }
        out.write(table)
        all.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** PalmDOC header: compression 2 (LZ77) over [textRecords] records, no encryption. */
    private fun palmdocHeader(textRecords: Int): ByteArray {
        val header = ByteArray(16)
        putU16(header, 0, 2)
        putU16(header, 8, textRecords)
        return header
    }

    // ------------------------------------------------------------------
    // big-endian writers (the readers under test live in Bytes)
    // ------------------------------------------------------------------

    private fun putU16(
        bytes: ByteArray,
        off: Int,
        value: Int,
    ) {
        bytes[off] = (value ushr 8).toByte()
        bytes[off + 1] = value.toByte()
    }

    private fun putU32(
        bytes: ByteArray,
        off: Int,
        value: Long,
    ) {
        for (i in 0 until 4) bytes[off + i] = (value ushr (8 * (3 - i))).toByte()
    }

    private companion object {
        /** The decoder's own header sizes, spelled out so the crafted records are literal. */
        const val HUFF_HEADER_BYTES = 16
        const val CDIC_HEADER_BYTES = 16
    }
}
