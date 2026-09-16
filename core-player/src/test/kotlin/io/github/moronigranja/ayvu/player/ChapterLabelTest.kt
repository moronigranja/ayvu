package io.github.moronigranja.ayvu.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterLabelTest {
    @Test
    fun `titles already carrying an ordinal are not doubled`() {
        assertEquals("1. Millie: The Underlying Problem", chapterMenuLabel(0, "1. Millie: The Underlying Problem"))
        assertEquals("12. The End", chapterMenuLabel(11, "12. The End"))
        assertEquals("IV. Storm", chapterMenuLabel(1, "IV. Storm"))
        assertEquals("Chapter 3", chapterMenuLabel(2, "Chapter 3"))
        assertEquals("PART II", chapterMenuLabel(3, "PART II"))
    }

    @Test
    fun `unlabeled titles gain the dense index prefix`() {
        assertEquals("1. Millie", chapterMenuLabel(0, "Millie"))
        assertEquals("3. Introduction", chapterMenuLabel(2, "Introduction"))
    }

    @Test
    fun `word numerals without chapter marker are left prefixed`() {
        // "Part One" has no digit/roman chapter marker — prefixed like a title.
        assertEquals("1. Part One", chapterMenuLabel(0, "Part One"))
    }
}
