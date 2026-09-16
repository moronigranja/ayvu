package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.player.Bookmark
import com.moronigranja.localttsreader.player.ChapterDisplay
import com.moronigranja.localttsreader.player.DisplayKind
import com.moronigranja.localttsreader.player.DisplayMode
import com.moronigranja.localttsreader.player.TranslationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-bearing invariant of the reader's language display (E/F): the
 * projection is ORIGINAL-KEYED in every display mode — a bookmark's
 * (chapter, passage) resolves to the same original passage index in all
 * three layouts, so a bookmark taken with one language stays openable with
 * another (and with the original-only layout, which has no translation map
 * at all). `firstBlockOfPassage[i]` is a block whose `passageIndex == i`
 * for every passage in every mode.
 */
class BookmarkProjectionInvariantTest {
    private val passages = listOf("alpha", "beta", "gamma", "delta")
    private val translations =
        mapOf(
            0 to TranslationState.Ready("ALPHA-pt"),
            2 to TranslationState.Pending,
            3 to TranslationState.Unavailable,
        )
    private val bookmark =
        Bookmark(
            bookId = "b1",
            chapterIndex = 2,
            passageIndex = 2,
            offsetSeconds = 0.0,
            label = null,
            id = 0L,
            createdAtEpochMillis = 0L,
        )

    private val modes = listOf(DisplayMode.INTERLEAVED, DisplayMode.TRANSLATED_ONLY)
    private val noTranslations = emptyMap<Int, TranslationState>()

    @Test
    fun `every mode keeps every passage dense and original-keyed`() {
        val cases =
            listOf(
                "original-only" to (noTranslations to DisplayMode.INTERLEAVED),
                "interleaved" to (translations to DisplayMode.INTERLEAVED),
                "translated-only" to (translations to DisplayMode.TRANSLATED_ONLY),
            )
        for ((label, pair) in cases) {
            val (map, mode) = pair
            val blocks = ChapterDisplay.project(passages, map, mode)
            val first = ChapterDisplay.firstBlockOfPassage(blocks, passages.size)
            for (i in passages.indices) {
                assertTrue(
                    "firstBlockOfPassage[$i] is a real block index ($label)",
                    first[i] in blocks.indices,
                )
                assertEquals(
                    "the first block of passage $i is a block of passage $i — the bookmark's key ($label)",
                    i,
                    blocks[first[i]].passageIndex,
                )
            }
            // The bookmark's own passage: the same original index in every mode.
            assertEquals(
                "the bookmark resolves its passage in $label",
                bookmark.passageIndex,
                blocks[first[bookmark.passageIndex]].passageIndex,
            )
        }
    }

    @Test
    fun `interleaved keeps the original passages visible next to their translations`() {
        val blocks = ChapterDisplay.project(passages, translations, DisplayMode.INTERLEAVED)
        // Passage 2's Pending block renders a placeholder — never a gap.
        val first = ChapterDisplay.firstBlockOfPassage(blocks, passages.size)
        assertTrue(blocks[first[2]].kind == DisplayKind.Original)
        assertEquals(DisplayKind.Pending, blocks[first[2] + 1].kind)
        // And the Original text is still displayed for every passage.
        for ((index, text) in passages.withIndex()) {
            assertTrue("passage $index renders its original in interleaved mode", text in ChapterDisplay.join(blocks))
        }
    }
}