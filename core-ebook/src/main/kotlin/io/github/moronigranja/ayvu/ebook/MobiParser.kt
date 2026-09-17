/*
 * Ported from KindleUnpack — https://github.com/kevinhendricks/KindleUnpack
 * (GPL-3.0). This is a Kotlin reimplementation of its MOBI parsing semantics
 * (text-record handling and trailing-data trim), adapted to Ayvu's parser
 * contracts; see NOTICE.md.
 */

package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.ZipEntries.lookup
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * MOBI-family parser covering both halves of the format family:
 *
 * - **KF8 (.azw3/.kf8)** — MOBI header type 0xFFFFFFFF or 248; the text records
 *   concatenate into a ZIP whose root holds content.opf + XHTML; parsed through the
 *   shared [OpfBookReader] (nav/NCX chapter titles included).
 * - **MOBI7 (.mobi/.azw, plain PalmDOC)** — text records decompressed per
 *   compression (none / PalmDOC LZ77 / HUFF-CDIC), decoded with the header's
 *   codepage. When the container carries an NCX index its navPoint "filepos"
 *   targets (byte offsets into the decompressed records) split the text into
 *   chapters titled from the navPoint labels ([MobiNcx]); without an NCX the whole
 *   book stays one chapter and headings surface as passages.
 *
 * Real-world record trailing-data entries (MOBI extra-flags at 0xF0) are trimmed per
 * KindleUnpack semantics. DRM-encrypted files are rejected by [MobiContainer].
 */
object MobiParser : EBookParser {
    override fun parse(source: EBookSource): Book {
        val bytes = source.readCapped()
        val base = source.fileName.substringBeforeLast('.').substringAfterLast('/')
        return parse(bytes, fallbackTitle = base.ifBlank { "Untitled" })
    }

    fun parse(
        bytes: ByteArray,
        fallbackTitle: String = "Untitled",
    ): Book = parse(bytes, fallbackTitle, ImportLimits.DEFAULT)

    /**
     * Raw-bytes parse with injectable ceilings. The app path always reads through the
     * [EBookSource] overload (which caps the container); tests inject small limits instead of
     * building a decompression bomb.
     */
    internal fun parse(
        bytes: ByteArray,
        fallbackTitle: String,
        limits: ImportLimits,
    ): Book {
        val id = Bytes.sha256Hex(bytes)
        val container = MobiContainer(bytes)
        val mobiHeader = container.mobiHeader()
        if (mobiHeader != null && isKf8Type(mobiHeader)) {
            return parseKf8(id, container, mobiHeader, fallbackTitle, limits)
        }
        return parseMobi7(id, container, mobiHeader, fallbackTitle, limits)
    }

    private fun isKf8Type(mobiHeader: ByteArray): Boolean =
        mobiHeader.size >= 0x1C && (Bytes.u32(mobiHeader, 0x18) == 0xFFFFFFFFL || Bytes.u32(mobiHeader, 0x18) == 248L)

    // ------------------------------------------------------------------
    // KF8: text records = ZIP stream
    // ------------------------------------------------------------------

    private fun parseKf8(
        id: String,
        container: MobiContainer,
        mobiHeader: ByteArray,
        fallbackTitle: String,
        limits: ImportLimits,
    ): Book {
        if (container.compression == 2) {
            throw EBookParseException("KF8 with PalmDOC compression is invalid")
        }
        val huff = if (container.compression == MobiContainer.HUFF_CDIC) loadHuffCdic(container) else null
        val zipBytes = readTextRecords(container, huff, limits)
        val entries = ZipEntries.readUntilBroken(zipBytes)
        val opfPath =
            entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
                ?: throw EBookParseException("KF8 archive has no OPF file")
        return OpfBookReader.parseBook(id, entries, opfPath, fallbackTitle)
    }

    // ------------------------------------------------------------------
    // MOBI7: text records = rawML markup
    // ------------------------------------------------------------------

    private fun parseMobi7(
        id: String,
        container: MobiContainer,
        mobiHeader: ByteArray?,
        fallbackTitle: String,
        limits: ImportLimits,
    ): Book {
        val huff = if (container.compression == MobiContainer.HUFF_CDIC) loadHuffCdic(container) else null
        val rawMl = readTextRecords(container, huff, limits)
        val charset =
            if (mobiHeader != null && mobiHeader.size >= 0x20 && Bytes.u32(mobiHeader, 0x1C) == 65001L) {
                Charsets.UTF_8
            } else {
                Charset.forName("windows-1252")
            }
        val text = String(rawMl, charset)
        val passages = OpfBookReader.extractParagraphs(text).map(::TextPassage)
        if (passages.isEmpty()) throw EBookParseException("no readable text in mobi")

        val fullName = mobiHeader?.let { fullNameTitle(it) }
        val exthTitle = mobiHeader?.let { exthTitle(it, charset) }
        val title = exthTitle ?: fullName ?: container.palmName.takeIf { it.isNotBlank() } ?: fallbackTitle
        val authors = mobiHeader?.let { exthAuthors(it, charset) } ?: emptyList()
        val chapters =
            splitMobi7Chapters(rawMl, charset, text, MobiNcx.navPoints(container, mobiHeader))
                ?: listOf(Chapter(0, null, passages))
        return Book(id = id, title = title, authors = authors, chapters = chapters)
    }

    /**
     * Chapter split at NCX navPoint "filepos" byte offsets. `navPoints` are in
     * document order; each carries the navPoint label and a byte offset into the
     * decompressed records ([rawMl]). Chapter *i* spans the text between boundary
     * *i* and *i + 1* (the last runs to the end); text before the first boundary is
     * folded into the first chapter so front matter is never dropped. Offsets are
     * translated to the decoded string via [byteToCharOffsets].
     *
     * Regression safety: returns null when there are no usable nav points (caller
     * keeps today's single whole-book chapter). Unusable nav points — zero/absent
     * target, out-of-range, or non-increasing (duplicate) — are skipped, and a slice
     * whose extracted paragraphs are empty is dropped: no empty chapters, no crash.
     */
    private fun splitMobi7Chapters(
        rawMl: ByteArray,
        charset: Charset,
        text: String,
        navPoints: List<MobiNcx.NavPoint>,
    ): List<Chapter>? {
        if (navPoints.isEmpty()) return null
        val byteToChar = byteToCharOffsets(rawMl, charset)
        val kept = mutableListOf<Pair<String?, Int>>()
        for (point in navPoints) {
            val byte = point.posByte
            if (byte in 1 until rawMl.size) {
                val charOffset = byteToChar[byte.toInt()]
                if (kept.isEmpty() || charOffset > kept.last().second) kept += point.label to charOffset
            }
        }
        if (kept.isEmpty()) return null

        val chapters = mutableListOf<Chapter>()
        var start = 0
        for (i in kept.indices) {
            val end = if (i + 1 < kept.size) kept[i + 1].second else text.length
            val slicePassages = OpfBookReader.extractParagraphs(text.substring(start, end)).map(::TextPassage)
            if (slicePassages.isNotEmpty()) chapters += Chapter(chapters.size, kept[i].first, slicePassages)
            start = end
        }
        return chapters.takeIf { it.isNotEmpty() }
    }

    /**
     * Character offset of every byte position in `String(bytes, charset)` — turns NCX
     * byte offsets into split points for the decoded text. A REPLACE-mode decoder (the
     * exact semantics of the `String(ByteArray, Charset)` constructor used above) is
     * fed one byte at a time so character emission is observable per byte. OpenJDK
     * decoders underflow without consuming a trailing incomplete sequence, so any
     * unconsumed tail is carried into the next call (the caller-supplied prefix rule
     * of `java.nio.charset`); the mapping is then exact for single- and multi-byte
     * charsets alike. A boundary inside a multi-byte character lands on that
     * character's start (the character belongs to the earlier chapter; nothing is
     * corrupted or dropped).
     */
    private fun byteToCharOffsets(
        bytes: ByteArray,
        charset: Charset,
    ): IntArray {
        val decoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val offsets = IntArray(bytes.size + 1)
        val work = ByteBuffer.allocate(8) // carry prefix + one new byte is at most 4 bytes
        val outBuf = CharBuffer.allocate(4)
        val carry = ByteArray(4)
        var carryLen = 0
        var chars = 0
        for (i in bytes.indices) {
            work.clear()
            if (carryLen > 0) {
                work.put(carry, 0, carryLen)
                carryLen = 0
            }
            work.put(bytes[i])
            work.flip()
            outBuf.clear()
            decoder.decode(work, outBuf, false)
            val remaining = work.remaining()
            if (remaining > 0) {
                val pos = work.position()
                work.get(carry, 0, remaining)
                carryLen = remaining
            }
            chars += outBuf.position()
            offsets[i + 1] = chars
        }
        outBuf.clear()
        decoder.decode(ByteBuffer.allocate(0), outBuf, true) // flush a trailing partial sequence
        offsets[bytes.size] = chars + outBuf.position()
        return offsets
    }

    // ------------------------------------------------------------------
    // Shared record handling
    // ------------------------------------------------------------------

    private fun loadHuffCdic(container: MobiContainer): HuffCdicDecoder {
        val mobiHeader = container.mobiHeader() ?: throw EBookParseException("HUFF/CDIC compression without a MOBI header")
        if (mobiHeader.size < 0x78) throw EBookParseException("MOBI header too short for HUFF fields")
        val reader = HuffCdicDecoder()
        val huffOffset = Bytes.u32(mobiHeader, 0x70).toInt()
        val huffCount = Bytes.u32(mobiHeader, 0x74).toInt()
        if (huffCount < 2) throw EBookParseException("HUFF/CDIC compression without dictionary records")
        val huff =
            container.records.getOrNull(huffOffset)
                ?: throw EBookParseException("HUFF record missing (section $huffOffset)")
        reader.loadHuff(huff)
        for (i in 1 until huffCount) {
            val cdic =
                container.records.getOrNull(huffOffset + i)
                    ?: throw EBookParseException("CDIC record missing (section ${huffOffset + i})")
            reader.loadCdic(cdic)
        }
        return reader
    }

    /**
     * The book's decompressed text: every text record is expanded through [limits], with the
     * per-record ceiling AND the cumulative one over the whole book, so one crafted record — or a
     * long run of them — raises [EBookLimitExceededException] instead of filling the heap.
     */
    private fun readTextRecords(
        container: MobiContainer,
        huff: HuffCdicDecoder?,
        limits: ImportLimits,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val mobiHeader = container.mobiHeader()
        val trim = trailingTrimSetup(mobiHeader)
        var expanded = 0L
        for (i in 0 until container.textRecords) {
            val record = container.records.getOrNull(1 + i) ?: break
            val data = trim?.let { trimTrailingDataEntries(record, it.first, it.second) } ?: record
            val decoded =
                when (container.compression) {
                    1 -> data
                    2 -> Palmdoc.unpack(data, expanded, limits)
                    // HUFF_CDIC — [MobiContainer] admits no other compression.
                    else -> huff!!.unpack(data, expanded, limits)
                }
            expanded += decoded.size
            out.write(decoded)
        }
        return out.toByteArray()
    }

    /** (trailer count, multibyte) per the MOBI extra-flags at 0xF0 (v5/6+). */
    private fun trailingTrimSetup(mobiHeader: ByteArray?): Pair<Int, Boolean>? {
        if (mobiHeader == null || mobiHeader.size < 0xF4) return null
        val headerLength = Bytes.u32(mobiHeader, 0x14)
        val version = Bytes.u32(mobiHeader, 0x24)
        if (headerLength < 0xE4 || version < 5) return null
        var flags = Bytes.u16(mobiHeader, 0xF0)
        val multibyte = (flags and 1) != 0
        var trailers = 0
        while (flags > 1) {
            if ((flags and 2) != 0) trailers++
            flags = flags ushr 1
        }
        return trailers to multibyte
    }

    private fun trimTrailingDataEntries(
        data: ByteArray,
        trailers: Int,
        multibyte: Boolean,
    ): ByteArray {
        var result = data
        repeat(trailers) {
            var num = 0
            for (i in maxOf(0, result.size - 4) until result.size) {
                val v = result[i].toInt() and 0xFF
                if ((v and 0x80) != 0) num = 0
                num = (num shl 7) or (v and 0x7F)
            }
            if (num in 1 until result.size) result = result.copyOf(result.size - num)
        }
        if (multibyte && result.isNotEmpty()) {
            val num = (result.last().toInt() and 3) + 1
            if (num < result.size) result = result.copyOf(result.size - num)
        }
        return result
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    /** EXTH record bodies for [id] (100 = author, may repeat; 503 = "Updated
     * Title"). EXTH starts at 0x10 + header length; the flag bit at 0x80 is
     * advisory — the "EXTH" magic is authoritative. */
    private fun exthRecords(
        mobiHeader: ByteArray,
        id: Int,
    ): List<ByteArray> {
        if (mobiHeader.size < 0x84) return emptyList()
        val headerLength = Bytes.u32(mobiHeader, 0x14).toInt()
        val exthStart = 0x10 + headerLength
        if (exthStart + 12 > mobiHeader.size || !Bytes.hasText(mobiHeader, exthStart, "EXTH")) return emptyList()
        val count = Bytes.u32(mobiHeader, exthStart + 8).toInt()
        var pos = exthStart + 12
        val out = mutableListOf<ByteArray>()
        for (i in 0 until count) {
            if (pos + 8 > mobiHeader.size) break
            val recordId = Bytes.u32(mobiHeader, pos).toInt()
            val size = Bytes.u32(mobiHeader, pos + 4).toInt()
            if (size < 8 || pos + size > mobiHeader.size) break
            if (recordId == id) out += mobiHeader.copyOfRange(pos + 8, pos + size)
            pos += size
        }
        return out
    }

    /**
     * EXTH string decode. UTF-16LE is detected by STRUCTURE, never by decoded
     * plausibility: genuine UTF-16LE for Latin-script text carries 0x00 at
     * most odd offsets, while single-byte text (cp1252 / UTF-8) has none —
     * decoding dense ASCII as UTF-16LE yields plausible CJK, so a "looks like
     * letters" heuristic accepts garbage (jumper.mobi's EXTH 503 "Jumper"
     * rendered as 畊灭牥; the old heuristic only survived the suite because
     * the shared fixture's 19-byte title had an odd trailing byte).
     */
    private fun decodeExthString(
        bytes: ByteArray,
        charset: Charset,
    ): String? {
        val oddZeroBytes = bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() }
        val looksUtf16Le = bytes.size >= 4 && oddZeroBytes >= bytes.size / 4
        if (looksUtf16Le) {
            // EXTH records pad to 4-byte boundaries — a trailing byte (or
            // NUL-padded tail) rides along; decode the even prefix and trim.
            val usable = bytes.size - (bytes.size % 2)
            val utf16 = String(bytes, 0, usable, Charsets.UTF_16LE).trimEnd('\u0000')
            if (!utf16.contains('\uFFFD')) return utf16.trim().ifEmpty { null }
        }
        val declared = String(bytes, charset)
        if (!declared.contains('\uFFFD')) return declared.trim().ifEmpty { null }
        if (bytes.size % 2 == 0) {
            val utf16 = String(bytes, Charsets.UTF_16LE)
            return utf16.trim().takeIf { it.isNotEmpty() && !it.contains('\uFFFD') }
        }
        return null
    }

    private fun exthTitle(
        mobiHeader: ByteArray,
        charset: Charset,
    ): String? = decodeExthString(exthRecords(mobiHeader, 503).firstOrNull() ?: return null, charset)

    private fun exthAuthors(
        mobiHeader: ByteArray,
        charset: Charset,
    ): List<String> = exthRecords(mobiHeader, 100).mapNotNull { decodeExthString(it, charset) }

    /** Full-name field (0x54 offset / 0x58 length, record-0-relative). */
    private fun fullNameTitle(mobiHeader: ByteArray): String? {
        if (mobiHeader.size < 0x5C) return null
        val offset = Bytes.u32(mobiHeader, 0x54).toInt()
        val length = Bytes.u32(mobiHeader, 0x58).toInt()
        if (offset < 0 || length <= 0 || offset + length > mobiHeader.size) return null
        return String(mobiHeader, offset, length, Charsets.UTF_8).trim().ifEmpty { null }
    }
}
