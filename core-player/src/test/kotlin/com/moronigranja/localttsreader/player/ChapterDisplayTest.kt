package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reader's block projection (read-in-language display): original-only
 * projects one Original block per passage with the legacy `passage.length + 2`
 * offsets; interleaved gives every translated passage a second block right
 * after its original with `firstBlockOfPassage` pointing at the ORIGINAL;
 * translated-only keeps one block per passage (the translation) with
 * `firstBlockOfPassage` pointing at IT; Pending contributes an empty-text
 * block; and join()/offsets() stay mutually consistent — join length equals
 * the last offset plus its block length in every mode.
 */
class ChapterDisplayTest {
    private val passages = listOf("alpha", "beta", "gamma")

    @Test
    fun `original-only projects one Original block per passage with legacy offsets`() {
        val blocks = ChapterDisplay.project(passages, emptyMap(), DisplayMode.INTERLEAVED)

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it.kind == DisplayKind.Original })
        assertEquals(listOf(0, 1, 2), blocks.map { it.passageIndex })
        // Identical to the old computePassageOffsets accumulation.
        val expected = intArrayOf(0, 5 + 2, 4 + 2 + 5 + 2)
        assertArrayEquals(expected, ChapterDisplay.offsets(blocks))
        assertEquals(ChapterDisplay.join(blocks), passages.joinToString("\n\n"))
    }

    @Test
    fun `interleaved places the translation immediately after its original and anchors on the original`() {
        val blocks =
            ChapterDisplay.project(
                passages,
                mapOf(1 to TranslationState.Ready("BETA-traduzido"), 2 to TranslationState.Ready("GAMMA-traduzido")),
                DisplayMode.INTERLEAVED,
            )

        assertEquals(5, blocks.size, "2 passages translate -> 3 originals + 2 translations")
        assertEquals(listOf(0, 1, 1, 2, 2), blocks.map { it.passageIndex })
        assertEquals(
            listOf(
                DisplayKind.Original,
                DisplayKind.Original,
                DisplayKind.Translation,
                DisplayKind.Original,
                DisplayKind.Translation,
            ),
            blocks.map {
                it.kind
            },
        )
        assertEquals("BETA-traduzido", blocks[2].text)
        // firstBlockOfPassage names the ORIGINAL block of each passage.
        assertArrayEquals(intArrayOf(0, 1, 3), ChapterDisplay.firstBlockOfPassage(blocks, passages.size))
        // The translation text is part of the displayed text (not a gap).
        assertTrue("BETA-traduzido" in ChapterDisplay.join(blocks))
    }

    @Test
    fun `translated-only keeps one block per passage anchored on the translation`() {
        val blocks =
            ChapterDisplay.project(
                passages,
                mapOf(0 to TranslationState.Ready("ALPHA-traduzido"), 1 to TranslationState.Ready("BETA-traduzido")),
                DisplayMode.TRANSLATED_ONLY,
            )

        assertEquals(3, blocks.size, "one block per passage")
        assertEquals(listOf(0, 1, 2), blocks.map { it.passageIndex })
        assertEquals(listOf(DisplayKind.Translation, DisplayKind.Translation, DisplayKind.Original), blocks.map { it.kind })
        // firstBlockOfPassage anchors on the TRANSLATION block.
        assertArrayEquals(intArrayOf(0, 1, 2), ChapterDisplay.firstBlockOfPassage(blocks, passages.size))
        assertTrue("ALPHA-traduzido" in ChapterDisplay.join(blocks))
        assertTrue("alpha" !in ChapterDisplay.join(blocks), "translated passages render no original text")
        assertTrue("gamma" in ChapterDisplay.join(blocks), "an uncovered passage still renders its original")
    }

    @Test
    fun `a pending passage contributes a Pending block with empty text`() {
        val blocks =
            ChapterDisplay.project(
                passages,
                mapOf(1 to TranslationState.Pending),
                DisplayMode.INTERLEAVED,
            )

        assertEquals(DisplayKind.Pending, blocks[2].kind)
        assertEquals("", blocks[2].text, "the projection's Pending block carries no text")
        assertEquals(1, blocks[2].passageIndex)
        // …but the DISPLAYED text renders the loading dots (no blank gap).
        assertTrue("⋯" in ChapterDisplay.join(blocks))
    }

    @Test
    fun `join length equals the last offset plus the last block's rendered length in every case`() {
        val cases =
            listOf(
                ChapterDisplay.project(passages, emptyMap(), DisplayMode.INTERLEAVED),
                ChapterDisplay.project(passages, mapOf(0 to TranslationState.Ready("x")), DisplayMode.INTERLEAVED),
                ChapterDisplay.project(
                    passages,
                    mapOf(0 to TranslationState.Pending, 1 to TranslationState.Unavailable),
                    DisplayMode.INTERLEAVED,
                ),
                ChapterDisplay.project(passages, mapOf(2 to TranslationState.Ready("y")), DisplayMode.TRANSLATED_ONLY),
            )
        for (blocks in cases) {
            val offsets = ChapterDisplay.offsets(blocks)
            val last = blocks.size - 1
            assertEquals(
                ChapterDisplay.join(blocks).length,
                offsets[last] + ChapterDisplay.renderedLength(blocks[last]),
                "offsets and join agree (${blocks.map { it.kind }})",
            )
        }
    }

    @Test
    fun `firstBlockOfPassage indices are always blocks of that passage`() {
        val modes = listOf(DisplayMode.INTERLEAVED, DisplayMode.TRANSLATED_ONLY)
        val maps =
            listOf(
                emptyMap<Int, TranslationState>(),
                mapOf(0 to TranslationState.Ready("a"), 1 to TranslationState.Pending, 2 to TranslationState.Unavailable),
            )
        for (mode in modes) {
            for (translations in maps) {
                val blocks = ChapterDisplay.project(passages, translations, mode)
                val first = ChapterDisplay.firstBlockOfPassage(blocks, passages.size)
                for (i in passages.indices) {
                    assertTrue(first[i] in blocks.indices, "first[i] is a valid block index ($mode)")
                    assertEquals(i, blocks[first[i]].passageIndex, "first block of passage $i is a block of passage $i ($mode)")
                }
            }
        }
    }

    @Test
    fun `empty chapter projects to no blocks`() {
        val blocks = ChapterDisplay.project(emptyList(), emptyMap(), DisplayMode.INTERLEAVED)
        assertTrue(blocks.isEmpty())
        assertEquals("", ChapterDisplay.join(blocks))
        assertArrayEquals(intArrayOf(), ChapterDisplay.offsets(blocks))
    }
}
