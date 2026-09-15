package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Reader-orientation math (decisions #50 gap pass): fraction of the book
 * completed and remaining listening time at the current speed, both computed
 * over the segmented [Book] the player is bound to.
 */
class BookProgressTest {
    // 3 passages: 150 + 300 + 450 chars — 60 s of speech at 15 chars/s.
    private val book =
        Book(
            id = "b1",
            title = "T",
            chapters =
                listOf(
                    Chapter(0, "One", listOf(TextPassage("a".repeat(150)), TextPassage("b".repeat(300)))),
                    Chapter(1, "Two", listOf(TextPassage("c".repeat(450)))),
                ),
        )

    @Test
    fun `fraction counts completed passages including the current one`() {
        assertEquals(1f / 3, BookProgress.fraction(book, 0, 0), 1e-6f)
        assertEquals(2f / 3, BookProgress.fraction(book, 0, 1), 1e-6f)
        assertEquals(1f, BookProgress.fraction(book, 1, 0), 1e-6f)
    }

    @Test
    fun `fraction clamps a stale passage index into the chapter`() {
        assertEquals(1f, BookProgress.fraction(book, 0, 99), 1e-6f)
    }

    @Test
    fun `remaining time from the book start is the whole book at 1x`() {
        assertEquals(60.0, BookProgress.remainingSeconds(book, 0, 0, 0.0, 1.0), 1e-6)
    }

    @Test
    fun `remaining time honors the current passage offset`() {
        // 60 s total; 10 s into the first passage → 50 s left.
        assertEquals(50.0, BookProgress.remainingSeconds(book, 0, 0, 10.0, 1.0), 1e-6)
    }

    @Test
    fun `remaining time scales with speed`() {
        // 450-char last passage = 30 s at 1x, 15 s at 2x.
        assertEquals(30.0, BookProgress.remainingSeconds(book, 1, 0, 0.0, 1.0), 1e-6)
        assertEquals(15.0, BookProgress.remainingSeconds(book, 1, 0, 0.0, 2.0), 1e-6)
    }

    @Test
    fun `empty book is zero everywhere`() {
        val empty = Book("e", "E", chapters = emptyList())
        assertEquals(0f, BookProgress.fraction(empty, 0, 0))
        assertEquals(0.0, BookProgress.remainingSeconds(empty, 0, 0, 0.0, 1.0))
    }

    // ------------------------------------------------------------------
    // Elapsed / positionAt (book-time at 1.0× — prerequisite for ±30s seeks)

    @Test
    fun `elapsed seconds accumulate preceding passages plus the offset`() {
        assertEquals(0.0, BookProgress.elapsedSeconds(book, 0, 0, 0.0), 1e-6)
        assertEquals(10.0, BookProgress.elapsedSeconds(book, 0, 1, 0.0), 1e-6)
        assertEquals(30.0, BookProgress.elapsedSeconds(book, 1, 0, 0.0), 1e-6)
        assertEquals(15.0, BookProgress.elapsedSeconds(book, 0, 1, 5.0), 1e-6)
    }

    @Test
    fun `elapsed clamps the offset into the current passage`() {
        // 10 s into a 10 s passage is the passage end, never more.
        assertEquals(10.0, BookProgress.elapsedSeconds(book, 0, 0, 99.0), 1e-6)
        assertEquals(0.0, BookProgress.elapsedSeconds(book, 0, 0, -5.0), 1e-6)
    }

    @Test
    fun `total seconds is the sum of all passage durations`() {
        assertEquals(60.0, BookProgress.totalSeconds(book), 1e-6)
    }

    @Test
    fun `positionAt walks durations and lands on the playhead`() {
        assertEquals(PlayerPosition("b1", 0, 0, 0.0), BookProgress.positionAt(book, 0.0))
        assertEquals(PlayerPosition("b1", 0, 1, 0.0), BookProgress.positionAt(book, 10.0))
        assertEquals(PlayerPosition("b1", 1, 0, 0.0), BookProgress.positionAt(book, 30.0))
        assertEquals(PlayerPosition("b1", 0, 1, 19.9), BookProgress.positionAt(book, 29.9))
    }

    @Test
    fun `positionAt clamps to the book bounds`() {
        assertEquals(PlayerPosition("b1", 0, 0, 0.0), BookProgress.positionAt(book, -5.0))
        // Past the end: last passage, offset = its full duration.
        assertEquals(PlayerPosition("b1", 1, 0, 30.0), BookProgress.positionAt(book, 999.0))
    }

    @Test
    fun `elapsed and positionAt round-trip across the book`() {
        for (seconds in listOf(0.0, 7.5, 10.0, 24.0, 42.0, 60.0)) {
            val position = BookProgress.positionAt(book, seconds)
            assertEquals(seconds, BookProgress.elapsedSeconds(book, position), 1e-6)
        }
    }
}
