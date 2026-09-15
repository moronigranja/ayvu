package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonemeChunkerTest {
    @Test
    fun `empty input splits to nothing`() {
        assertEquals(emptyList<String>(), PhonemeChunker.split(""))
        assertEquals(emptyList<String>(), PhonemeChunker.split("   "))
    }

    @Test
    fun `short input stays a single batch`() {
        assertEquals(listOf("həlˈoʊ"), PhonemeChunker.split("həlˈoʊ"))
    }

    @Test
    fun `mid-word runs are sliced at the context limit`() {
        val phonemes = "x".repeat(520)
        val batches = PhonemeChunker.split(phonemes, maxLength = 510)
        assertEquals(2, batches.size)
        assertEquals(510, batches[0].length)
        assertEquals(10, batches[1].length)
    }

    @Test
    fun `sentence marks are the preferred cut boundary`() {
        val a = "həˈloʊ! " + "ðɪs".repeat(60) // 8 + 240
        val b = "wˈɜːld".repeat(60) // 360
        val text = a + b
        val batches = PhonemeChunker.split(text, maxLength = 400)
        assertTrue(batches.size >= 2, "long text must split")
        assertTrue(batches.all { it.length <= 400 }, "every batch within the context")
        assertTrue(batches.first().contains("!"), "sentence mark stays with its clause")
        assertEquals(text.replace(" ", ""), batches.joinToString("").replace(" ", ""), "no phonemes lost or reordered")
    }

    @Test
    fun `batches are balanced to avoid a short tail`() {
        // Four equal atoms of 300: packing at 510 cannot merge any pair, so
        // the balanced split keeps the atoms as-is (fewest passes == 4).
        val atom = "a".repeat(300)
        val text = "$atom $atom $atom $atom"
        val batches = PhonemeChunker.split(text, maxLength = 510)
        assertEquals(4, batches.size)
        assertEquals(listOf(300, 300, 300, 300), batches.map { it.length })
    }

    @Test
    fun `packing shrinks the limit to fill batches evenly`() {
        // 200+200+300 with separators: two batches fit at most (602 > 510),
        // so the balanced search shrinks the window limit down to 401 — the
        // smallest limit that still needs only two passes (401|300).
        val text = "${"b".repeat(200)} ${"b".repeat(200)} ${"b".repeat(300)}"
        val batches = PhonemeChunker.split(text, maxLength = 510)
        assertEquals(2, batches.size)
        assertEquals(401, batches[0].length) // 200 + separator + 200
        assertEquals(300, batches[1].length)
        assertEquals(text.replace(" ", ""), batches.joinToString("").replace(" ", ""))
    }

    @Test
    fun `pause after sentence clause and plain words`() {
        assertEquals(0.25, PhonemeChunker.pauseAfter("həlˈoʊ!", 0.25, 0.1))
        assertEquals(0.25, PhonemeChunker.pauseAfter("wˈɜːld…", 0.25, 0.1))
        assertEquals(0.1, PhonemeChunker.pauseAfter("həlˈoʊ,", 0.25, 0.1))
        assertEquals(0.1, PhonemeChunker.pauseAfter("a;", 0.25, 0.1))
        assertEquals(0.0, PhonemeChunker.pauseAfter("word", 0.25, 0.1))
        assertEquals(0.0, PhonemeChunker.pauseAfter("", 0.25, 0.1))
        assertEquals(0.0, PhonemeChunker.pauseAfter("  ", 0.25, 0.1))
    }
}
