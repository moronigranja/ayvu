package io.github.moronigranja.ayvu.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class MobiParserTest {
    private fun fixture(name: String): ByteArray = Files.readAllBytes(Path.of("core-ebook/src/test/resources", name))

    /** Expected passages from the shared MOBI7 fixture body. */
    private val expectedPassages =
        listOf(
            "Chapter 1",
            "It is a truth universally acknowledged\u00A0— that a single man in possession of a good " +
                "fortune, must be in want of a wife.",
            "A second paragraph with café and \u2019quotes\u2019.", // &rsquo; decodes to U+2019
        )

    private fun assertMobi7Content(book: io.github.moronigranja.ayvu.model.Book) {
        assertEquals("Pride and Prejudice", book.title)
        assertEquals(1, book.chapters.size)
        assertNull(book.chapters[0].title) // MOBI7 has no real chapter structure yet
        assertEquals(expectedPassages, book.chapters[0].passages.map { it.text })
        assertEquals(64, book.id.length)
        assertTrue(book.id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `parses uncompressed mobi7`() {
        assertMobi7Content(MobiParser.parse(fixture("mobi7_plain.mobi")))
    }

    @Test
    fun `parses palmdoc compressed mobi7`() {
        assertMobi7Content(MobiParser.parse(fixture("mobi7_palmdoc.mobi")))
    }

    @Test
    fun `parses huffcdic compressed mobi7`() {
        val book = MobiParser.parse(fixture("mobi7_huffcdic.mobi"))
        assertMobi7Content(book)
        // stable content hash across parses
        assertEquals(book.id, MobiParser.parse(fixture("mobi7_huffcdic.mobi")).id)
    }

    @Test
    fun `rejects drm-encrypted mobi`() {
        val error =
            assertThrows(EBookParseException::class.java) {
                MobiParser.parse(fixture("mobi7_encrypted.mobi"))
            }
        assertTrue(error.message.orEmpty().contains("DRM"), error.message.toString())
    }

    @Test
    fun `uses the full-name title when no EXTH exists`() {
        val book = MobiParser.parse(fixture("mobi7_noname_exth.mobi"))
        assertEquals("Moby-Dick", book.title)
    }

    @Test
    fun `parses kf8 azw3 with nav chapter titles`() {
        val book = MobiParser.parse(fixture("kf8_test.azw3"))
        assertEquals("Alice's Adventures in Wonderland", book.title)
        assertEquals(listOf("Lewis Carroll"), book.authors)
        assertEquals(2, book.chapters.size)
        assertEquals("Down the Rabbit-Hole", book.chapters[0].title)
        assertEquals("The Pool of Tears", book.chapters[1].title)
        assertEquals(
            "Alice was beginning to get very tired of sitting by her sister on the bank.",
            book.chapters[0].passages[0].text,
        )
        assertEquals("Curiouser and curiouser! cried Alice.", book.chapters[1].passages[0].text)
    }

    @Test
    fun `garbage bytes throw instead of crashing`() {
        assertThrows(EBookParseException::class.java) {
            MobiParser.parse("definitely not a mobi file".toByteArray())
        }
    }

    // ------------------------------------------------------------------
    // MOBI7 NCX chapter boundaries
    // ------------------------------------------------------------------

    /** Whole-book passages of the NCX fixture body, in document order. */
    private val expectedNcxPassages =
        listOf(
            "Chapter 1",
            "First paragraph of the first chapter with café and \u2019quotes\u2019.",
            "Second paragraph of the first chapter.",
            "Chapter 2",
            "Only paragraph of the second chapter.",
            "Chapter 3",
            "First paragraph of the third chapter.",
            "Second paragraph of the third chapter.",
        )

    @Test
    fun `splits mobi7 chapters at ncx navpoint boundaries`() {
        val book = MobiParser.parse(fixture("mobi7_ncx.mobi"))
        assertEquals("Pride and Prejudice", book.title)
        assertEquals(listOf(0, 1, 2), book.chapters.map { it.index })
        // titles come from the navPoint labels (decoded), not from in-body headings
        assertEquals(
            listOf("First Chapter", "Second Chapter", "Third & Final Chapter"),
            book.chapters.map { it.title },
        )
        // passage boundaries respect the navPoint byte offsets
        assertEquals(
            listOf(
                "Chapter 1",
                "First paragraph of the first chapter with café and \u2019quotes\u2019.",
                "Second paragraph of the first chapter.",
            ),
            book.chapters[0].passages.map { it.text },
        )
        assertEquals(
            listOf("Chapter 2", "Only paragraph of the second chapter."),
            book.chapters[1].passages.map { it.text },
        )
        assertEquals(
            listOf(
                "Chapter 3",
                "First paragraph of the third chapter.",
                "Second paragraph of the third chapter.",
            ),
            book.chapters[2].passages.map { it.text },
        )
        // nothing lost or duplicated across the split
        assertEquals(expectedNcxPassages, book.chapters.flatMap { it.passages }.map { it.text })
        assertEquals(book.id, MobiParser.parse(fixture("mobi7_ncx.mobi")).id)
    }

    @Test
    fun `skips out-of-range duplicate and targetless ncx navpoints`() {
        val book = MobiParser.parse(fixture("mobi7_ncx_malformed.mobi"))
        // out-of-range (huge pos), duplicate pos and missing tag-1 entries are skipped
        assertEquals(listOf("First Chapter", "Chapter Two", "Final Chapter"), book.chapters.map { it.title })
        assertEquals(3, book.chapters.size)
        assertTrue(book.chapters.all { it.passages.isNotEmpty() })
        assertEquals(expectedNcxPassages, book.chapters.flatMap { it.passages }.map { it.text })
    }

    @Test
    fun `splits utf8 mobi7 chapters at ncx byte offsets`() {
        val book = MobiParser.parse(fixture("mobi7_ncx_utf8.mobi"))
        assertEquals(listOf("First Chapter", "Second Chapter", "Third & Final Chapter"), book.chapters.map { it.title })
        assertEquals(
            listOf(
                "Chapter 1",
                "Zürich café \u20ACuro first chapter.",
                "Second paragraph éèê of the first chapter.",
            ),
            book.chapters[0].passages.map { it.text },
        )
        assertEquals(
            listOf("Chapter 2", "Only paragraph \u2014 of the second chapter."),
            book.chapters[1].passages.map { it.text },
        )
        assertEquals(
            listOf("Chapter 3", "Third chapter with café and \u20AC."),
            book.chapters[2].passages.map { it.text },
        )
        assertEquals(
            listOf(
                "Chapter 1",
                "Zürich café \u20ACuro first chapter.",
                "Second paragraph éèê of the first chapter.",
                "Chapter 2",
                "Only paragraph \u2014 of the second chapter.",
                "Chapter 3",
                "Third chapter with café and \u20AC.",
            ),
            book.chapters.flatMap { it.passages }.map { it.text },
        )
    }

    @Test
    fun `mobi7 with an unresolvable ncx pointer falls back to a single chapter`() {
        val bytes = fixture("mobi7_ncx.mobi")
        val numRecords = ((bytes[76].toInt() and 0xFF) shl 8) or (bytes[77].toInt() and 0xFF)
        val ncxField = 78 + 8 * numRecords + 0xF4
        // Point the INDX field at the plain text record, which is not an INDX index.
        bytes[ncxField] = 0
        bytes[ncxField + 1] = 0
        bytes[ncxField + 2] = 0
        bytes[ncxField + 3] = 1
        val book = MobiParser.parse(bytes)
        assertEquals(1, book.chapters.size)
        assertNull(book.chapters[0].title)
        assertEquals(expectedNcxPassages, book.chapters[0].passages.map { it.text })
    }

    @Test
    fun `source name is used as fallback title for plain palmdoc books`() {
        // A pure PalmDOC book (no MOBI header, no name beyond PDB): title = file name.
        val rec0 = ByteArray(16)
        rec0[0] = 0
        rec0[1] = 1 // compression = 1, big-endian u16
        rec0[8] = 0
        rec0[9] = 1 // text records = 1
        val body = "<html><body><p>Plain PalmDOC text.</p></body></html>".toByteArray(Charsets.UTF_8)
        val tableSize = 8 * 2
        val rec0Off = 78 + tableSize
        val bodyOff = rec0Off + rec0.size
        val bytes = ByteArray(bodyOff + body.size)
        val bytesPerRecord = 8

        fun putOffset(
            slot: Int,
            off: Int,
        ) {
            bytes[78 + slot] = (off ushr 24).toByte()
            bytes[79 + slot] = (off ushr 16).toByte()
            bytes[80 + slot] = (off ushr 8).toByte()
            bytes[81 + slot] = off.toByte()
        }
        "Plain".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        bytes[76] = 0
        bytes[77] = 2 // numRecords
        putOffset(0, rec0Off)
        putOffset(bytesPerRecord, bodyOff)
        rec0.copyInto(bytes, rec0Off)
        body.copyInto(bytes, bodyOff)
        val book = MobiParser.parse(EBookSource("Plain.mobi") { ByteArrayInputStream(bytes) })
        assertEquals("Plain", book.title)
        assertEquals(listOf("Plain PalmDOC text."), book.chapters[0].passages.map { it.text })
    }

    // ------------------------------------------------------------------
    // EXTH string decode (2026-09-14: ASCII 503 record decoded as UTF-16LE
    // produced CJK mojibake — the old heuristic tested decoded plausibility)
    // ------------------------------------------------------------------

    /** Rewrites the shared fixture's EXTH 503 record body with [title]
     * (US-ASCII, space-padded to the record's size). */
    private fun withExth503Title(
        bytes: ByteArray,
        title: String,
    ): ByteArray {
        val copy = bytes.copyOf()
        val rec0 =
            ((copy[78].toInt() and 0xFF) shl 24) or ((copy[79].toInt() and 0xFF) shl 16) or
                ((copy[80].toInt() and 0xFF) shl 8) or (copy[81].toInt() and 0xFF)
        val headerLength = readU32(copy, rec0 + 0x14)
        var pos = rec0 + 16 + headerLength + 12
        val count = readU32(copy, rec0 + 16 + headerLength + 8)
        repeat(count) {
            val id = readU32(copy, pos)
            val size = readU32(copy, pos + 4)
            if (id == 503) {
                val ascii = title.toByteArray(Charsets.US_ASCII)
                require(ascii.size <= size - 8) { "title does not fit the record" }
                for (j in 0 until size - 8) copy[pos + 8 + j] = 0x20
                ascii.copyInto(copy, pos + 8)
                return copy
            }
            pos += size
        }
        throw IllegalStateException("fixture has no EXTH 503 record")
    }

    private fun readU32(
        data: ByteArray,
        at: Int,
    ): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    @Test
    fun `even-length ascii exth title decodes via the declared charset`() {
        // "Jumper" is 6 bytes — even, so the old UTF-16-first heuristic
        // produced clean CJK and won. Structure, not plausibility, decides.
        val book = MobiParser.parse(withExth503Title(fixture("mobi7_plain.mobi"), "Jumper"))
        assertEquals("Jumper", book.title)
    }

    @Test
    fun `genuine utf16le exth title still decodes`() {
        val book =
            MobiParser.parse(
                withExth503Title(fixture("mobi7_plain.mobi"), "Jumper").let { bytes ->
                    // Re-encode the padded title bytes as genuine UTF-16LE.
                    val rec0 =
                        ((bytes[78].toInt() and 0xFF) shl 24) or ((bytes[79].toInt() and 0xFF) shl 16) or
                            ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
                    val headerLength = readU32(bytes, rec0 + 0x14)
                    var pos = rec0 + 16 + headerLength + 12
                    val count = readU32(bytes, rec0 + 16 + headerLength + 8)
                    val out = bytes.copyOf()
                    repeat(count) {
                        val id = readU32(out, pos)
                        val size = readU32(out, pos + 4)
                        if (id == 503) {
                            "Jumper".toByteArray(Charsets.UTF_16LE).copyInto(out, pos + 8)
                            // zero the record tail so no stray bytes pollute the decode
                            for (j in pos + 8 + "Jumper".toByteArray(Charsets.UTF_16LE).size until pos + size) {
                                out[j] = 0
                            }
                        }
                        pos += size
                    }
                    out
                },
            )
        assertEquals("Jumper", book.title)
    }

    @Test
    fun `exth author records become the book authors`() {
        // Re-id the fixture's single EXTH record 503 → 100: the string flows
        // through the SAME decode rule into the author list, and the title
        // falls back to the full-name field ("Pride and Prejudice").
        val bytes = fixture("mobi7_plain.mobi").copyOf()
        val rec0 =
            ((bytes[78].toInt() and 0xFF) shl 24) or ((bytes[79].toInt() and 0xFF) shl 16) or
                ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
        val headerLength = readU32(bytes, rec0 + 0x14)
        val record503 = rec0 + 16 + headerLength + 12
        bytes[record503] = 0
        bytes[record503 + 1] = 0
        bytes[record503 + 2] = 0
        bytes[record503 + 3] = 100
        val book = MobiParser.parse(bytes)
        assertEquals(listOf("Pride and Prejudice"), book.authors)
        assertEquals("Pride and Prejudice", book.title)
    }
}
