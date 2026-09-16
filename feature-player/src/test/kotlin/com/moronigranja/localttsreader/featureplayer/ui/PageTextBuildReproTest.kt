package com.moronigranja.localttsreader.featureplayer.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.moronigranja.localttsreader.player.ChapterDisplay
import com.moronigranja.localttsreader.player.DisplayKind
import com.moronigranja.localttsreader.player.DisplayMode
import com.moronigranja.localttsreader.player.TranslationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduction for the device crash
 * `StringIndexOutOfBoundsException: begin 336, end 382, length 378` inside
 * Compose's paragraph-style extraction when the reader's page text carries
 * per-block ParagraphStyle spans. Builds the page text EXACTLY the way
 * ReaderScreen does (chunked blocks + applyBlockStyles + active span) and
 * measures it — a style range beyond the text length fails here first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageTextBuildReproTest {
    @Test
    fun `page text with paragraph styles never exceeds its own length`() {
        // Realistic shapes: a long original, a long translation, a pending block.
        val original1 = ("The Svalbard seed vault was a pleasant surprise and could be a real boon for terraforming. ".repeat(4)).trimEnd()
        val translated1 =
            "O armazém de sementes de Svalbard foi uma surpresa agradável e poderia ser um verdadeiro benefício "
                .repeat(
                    8,
                ).trimEnd()
        val original2 = "But the most exciting item was a variant of a SURGE drive that could be used on large ships. ".repeat(3).trimEnd()
        val passages = listOf(original1, original2)
        val blocks =
            ChapterDisplay.project(
                passages,
                mapOf(0 to TranslationState.Ready(translated1), 1 to TranslationState.Pending),
                DisplayMode.INTERLEAVED,
            )
        val chapterText = ChapterDisplay.join(blocks)
        val offsets = ChapterDisplay.offsets(blocks)
        assertEquals(
            "chunked build must reproduce the joined text",
            chapterText.length,
            offsets.last() + ChapterDisplay.renderedLength(blocks.last()),
        )

        for (window in listOf(0 to chapterText.length, 100 to 400, chapterText.length - 40 to chapterText.length)) {
            val startChar = window.first.coerceIn(0, chapterText.length)
            val endChar = window.second.coerceIn(startChar, chapterText.length)
            if (startChar >= endChar) continue
            val pageText =
                buildAnnotatedString {
                    for (i in blocks.indices) {
                        val block = blocks[i]
                        val bs = offsets.getOrNull(i) ?: continue
                        val be = offsets.getOrNull(i + 1) ?: chapterText.length
                        if (be <= startChar || bs >= endChar) continue
                        val from = maxOf(bs, startChar)
                        val to = minOf(be, endChar)
                        if (block.kind == DisplayKind.Pending && from == bs && to == be) {
                            append("⋯ ".repeat(3).trimEnd().padEnd(ChapterDisplay.renderedLength(block)))
                            append(chapterText.substring(bs + ChapterDisplay.renderedLength(block), be))
                        } else {
                            append(chapterText.substring(from, to))
                        }
                    }
                    applyReaderBlockStyles(blocks, offsets, startChar = startChar, endChar = endChar)
                    // an active sentence span at the last translation sentence
                    val span = offsets.getOrNull(2) ?: return@buildAnnotatedString
                    val hiFrom = maxOf(span, startChar) - startChar
                    val hiTo = minOf(span + 40, endChar) - startChar
                    if (hiFrom < hiTo) addStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), hiFrom, hiTo)
                }
            // Every style range must lie within the text
            for (s in pageText.spanStyles) {
                if (s.end > pageText.text.length) {
                    fail("window($startChar,$endChar) span [${s.start},${s.end}) > len ${pageText.text.length}")
                }
            }
            for (s in pageText.paragraphStyles) {
                assertTrue("paragraph span end ${s.end} > length ${pageText.text.length}", s.end <= pageText.text.length)
            }
            // The crash was Compose's ParagraphStyle expansion past the text;
            // with the indent gone only character styles remain — assert the
            // bounds invariant that the paragraph styles violated.
            assertEquals("no paragraph styles on the page text", 0, pageText.paragraphStyles.size)
        }
    }

    /** The same application rule as ReaderScreen's private helper, re-created
     * over the (internal) style helper below — keeps this test a faithful
     * repro while the helper itself stays exercised by the device build. */
    private fun AnnotatedString.Builder.applyReaderBlockStyles(
        blocks: List<com.moronigranja.localttsreader.player.DisplayBlock>,
        offsets: IntArray,
        startChar: Int,
        endChar: Int,
    ) {
        applyBlockStyles(
            blocks = blocks,
            offsets = offsets,
            muted = androidx.compose.ui.graphics.Color.Gray,
            startOffset = startChar,
            rangeStart = startChar,
            rangeEnd = endChar,
        )
    }
}
