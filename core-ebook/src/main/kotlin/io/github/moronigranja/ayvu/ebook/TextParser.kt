package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * TXT/Markdown parser (C7). Plain text is a single chapter titled from the file name;
 * Markdown splits chapters on ATX headings (`#`…`######`, CommonMark rules), heading
 * text becoming the chapter title and also the fallback book title. Passages are
 * blank-line-separated paragraphs; internal line breaks are preserved (segmentation
 * already handles multi-line passages). Empty chapters are never emitted — the same
 * convention as the MOBI NCX path.
 *
 * Encoding: UTF-8 by default with BOM sniffing for UTF-8/UTF-16LE/UTF-16BE. Anything
 * malformed (or a legacy single-byte charset) raises [EBookParseException], which the
 * import flow surfaces as a typed failure instead of silently mangling the text.
 */
object TextParser : EBookParser {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** CommonMark ATX closing/opening: ≤3 leading spaces, 1–6 hashes, space before text (a
     *  bare `#…` line is a heading with no title; `#glued` is not a heading at all). */
    private val ATX_HEADING = Regex("""^\s{0,3}#{1,6}(?:\s+(.*))?$""")

    /** Simplified fenced-code delimiter: any ```` ``` ```` or `~~~` line (≤3 leading spaces)
     *  toggles fence state, inside which headings are content, not chapters. */
    private val FENCE = Regex("""^\s{0,3}(?:```|~~~)(.*)?$""")

    override fun parse(source: EBookSource): Book {
        val bytes = source.readCapped()
        val fallback =
            source.fileName
                .substringBeforeLast('.')
                .substringAfterLast('/')
                .ifBlank { "Untitled" }
        return parse(bytes, fallback)
    }

    /** Parse raw text bytes; [fallbackTitle] is the file-derived title (and the title of
     *  any text that precedes the first heading). */
    fun parse(
        bytes: ByteArray,
        fallbackTitle: String = "Untitled",
    ): Book {
        val chapters = buildChapters(decode(bytes), fallbackTitle)
        if (chapters.isEmpty()) throw EBookParseException("no readable text in file")
        return Book(id = Bytes.sha256Hex(bytes), title = fallbackTitle, chapters = chapters)
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    private fun decode(bytes: ByteArray): String =
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                decodeStrict(bytes, 3, StandardCharsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                decodeStrict(bytes, 2, StandardCharsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                decodeStrict(bytes, 2, StandardCharsets.UTF_16BE)
            else -> decodeStrict(bytes, 0, StandardCharsets.UTF_8)
        }

    private fun decodeStrict(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
    ): String =
        try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (e: CharacterCodingException) {
            throw EBookParseException("file is not valid ${charset.name()}", e)
        }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    private fun buildChapters(
        text: String,
        fallbackTitle: String,
    ): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val passages = mutableListOf<TextPassage>()
        var paragraph = StringBuilder()
        var hasContent = false
        var pendingTitle: String? = fallbackTitle
        var nextChapterIndex = 0
        var inFence = false

        fun flushParagraph() {
            if (hasContent) {
                passages += TextPassage(paragraph.toString().trim())
                paragraph = StringBuilder()
                hasContent = false
            }
        }

        fun flushChapter() {
            if (passages.isNotEmpty()) {
                chapters += Chapter(nextChapterIndex++, pendingTitle, passages.toList())
            }
            passages.clear()
        }

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        for (line in normalized.lineSequence()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) {
                flushParagraph()
                continue
            }
            if (FENCE.matchEntire(trimmed) != null) {
                inFence = !inFence
                flushParagraph()
                continue
            }
            if (!inFence) {
                val heading = ATX_HEADING.matchEntire(trimmed)
                if (heading != null) {
                    flushParagraph()
                    flushChapter()
                    pendingTitle = heading.groupValues[1].trim().ifEmpty { null }
                    continue
                }
            }
            paragraph.append(trimmed).append('\n')
            hasContent = true
        }
        flushParagraph()
        flushChapter()
        return chapters
    }
}
