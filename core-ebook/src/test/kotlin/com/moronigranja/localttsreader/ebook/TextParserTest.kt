package com.moronigranja.localttsreader.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TextParserTest {
    private fun textBytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------------
    // BOM sniffing
    // ------------------------------------------------------------------

    @Test
    fun `UTF-8 with BOM is decoded correctly`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + textBytes("hello")
        val parsed = TextParser.parse(bytes)
        assertEquals("hello", parsed.chapters[0].passages[0].text)
    }

    @Test
    fun `UTF-16LE with BOM is decoded correctly`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(StandardCharsets.UTF_16LE)
        val parsed = TextParser.parse(bytes)
        assertEquals("hello", parsed.chapters[0].passages[0].text)
    }

    @Test
    fun `UTF-16BE with BOM is decoded correctly`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hello".toByteArray(StandardCharsets.UTF_16BE)
        val parsed = TextParser.parse(bytes)
        assertEquals("hello", parsed.chapters[0].passages[0].text)
    }

    @Test
    fun `no BOM is decoded as UTF-8`() {
        val bytes = textBytes("hello")
        val parsed = TextParser.parse(bytes)
        assertEquals("hello", parsed.chapters[0].passages[0].text)
    }

    // ------------------------------------------------------------------
    // Malformed UTF-8
    // ------------------------------------------------------------------

    @Test
    fun `malformed UTF-8 raises EBookParseException`() {
        val bytes = byteArrayOf(0x80.toByte()) + textBytes("hello")
        val ex = assertThrows(EBookParseException::class.java) { TextParser.parse(bytes) }
        assertTrue(ex.message!!.contains("UTF-8"))
    }

    // ------------------------------------------------------------------
    // Plain text
    // ------------------------------------------------------------------

    @Test
    fun `plain text produces a single chapter with stem title`() {
        val source = EBookSource("notes.txt") { ByteArrayInputStream(textBytes("First paragraph.\n\nSecond.")) }
        val parsed = TextParser.parse(source)
        assertEquals(1, parsed.chapters.size)
        assertEquals("notes", parsed.chapters[0].title)
        assertEquals(2, parsed.chapters[0].passages.size)
        assertEquals("First paragraph.", parsed.chapters[0].passages[0].text)
    }

    @Test
    fun `file name is used as fallback title`() {
        val parsed = TextParser.parse(textBytes("MyBook.txt"), "MyBook")
        assertEquals("MyBook", parsed.chapters[0].title)
    }

    @Test
    fun `file name without extension uses Untitled fallback`() {
        val parsed = TextParser.parse(textBytes("MyBook"))
        assertEquals("Untitled", parsed.chapters[0].title)
    }

    // ------------------------------------------------------------------
    // Markdown ATX headings split chapters
    // ------------------------------------------------------------------

    @Test
    fun `ATX headings split chapters and heading text becomes chapter title`() {
        val text = "# Chapter 1\n\nBody paragraph.\n\n## Chapter 2\n\nMore body."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
        assertEquals("Chapter 2", parsed.chapters[1].title)
        assertEquals(1, parsed.chapters[0].passages.size)
        assertEquals(1, parsed.chapters[1].passages.size)
    }

    @Test
    fun `bare hash is not a heading`() {
        val text = "#\n\nBody text."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals("Body text.", parsed.chapters[0].passages[0].text)
    }

    @Test
    fun `glued hash is not a heading`() {
        val text = "#glued\n\nBody text."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(listOf("#glued", "Body text."), parsed.chapters[0].passages.map { it.text })
    }

    @Test
    fun `ATX heading with leading spaces is stripped`() {
        val text = "  # Chapter 1\n\nBody."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
    }

    @Test
    fun `ATX heading with trailing spaces is trimmed`() {
        val text = "# Chapter 1   \n\nBody."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
    }

    // ------------------------------------------------------------------
    // Fenced code blocks
    // ------------------------------------------------------------------

    @Test
    fun `fenced code blocks are content, not chapter boundaries`() {
        val text = "```\ncode line 1\ncode line 2\n```\n\nBody."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(listOf("code line 1\ncode line 2", "Body."), parsed.chapters[0].passages.map { it.text })
    }

    @Test
    fun `atx headings inside fenced code blocks do not split chapters`() {
        val text = "```\n# Not a chapter\n```\n\nBody."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(listOf("# Not a chapter", "Body."), parsed.chapters[0].passages.map { it.text })
    }

    // ------------------------------------------------------------------
    // Blank lines
    // ------------------------------------------------------------------

    @Test
    fun `blank lines separate paragraphs`() {
        val text = "First.\n\n\nSecond."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(2, parsed.chapters[0].passages.size)
        assertEquals("First.", parsed.chapters[0].passages[0].text)
        assertEquals("Second.", parsed.chapters[0].passages[1].text)
    }

    @Test
    fun `empty paragraphs are skipped`() {
        val text = "First.\n\n\n\nSecond."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(2, parsed.chapters[0].passages.size)
    }

    // ------------------------------------------------------------------
    // CRLF and CR normalization
    // ------------------------------------------------------------------

    @Test
    fun `CRLF is normalized to LF`() {
        val text = "First.\r\n\r\nSecond."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(2, parsed.chapters[0].passages.size)
        assertEquals("First.", parsed.chapters[0].passages[0].text)
        assertEquals("Second.", parsed.chapters[0].passages[1].text)
    }

    @Test
    fun `single CR is normalized to LF`() {
        val text = "First.\r\nSecond."
        val parsed = TextParser.parse(textBytes(text))
        assertEquals(1, parsed.chapters.size)
        assertEquals(1, parsed.chapters[0].passages.size)
        assertEquals("First.\nSecond.", parsed.chapters[0].passages[0].text)
    }

    // ------------------------------------------------------------------
    // Parse error on empty file
    // ------------------------------------------------------------------

    @Test
    fun `empty file raises parse error`() {
        val ex =
            assertThrows(EBookParseException::class.java) {
                TextParser.parse(textBytes(""))
            }
        assertTrue(ex.message!!.contains("no readable text"))
    }

    // ------------------------------------------------------------------
    // No chapters
    // ------------------------------------------------------------------

    @Test
    fun `only headings produce no chapters and raise a parse error`() {
        val ex =
            assertThrows(EBookParseException::class.java) {
                TextParser.parse(textBytes("# Heading\n\n# Another"))
            }
        assertTrue(ex.message!!.contains("no readable text"))
    }
}
