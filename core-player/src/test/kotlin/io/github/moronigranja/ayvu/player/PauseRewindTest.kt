package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Resume-after-pause rewind policy (owner request 2026-09-27). */
class PauseRewindTest {
    // 150 chars at 15 chars/s = 10 s per passage; 3 passages/chapter = 30 s.
    private val book =
        Book(
            id = "b1",
            title = "Anna",
            chapters =
                listOf(
                    Chapter(0, "One", (0 until 3).map { TextPassage("c0p$it".padEnd(150, 'x')) }),
                    Chapter(1, "Two", (0 until 3).map { TextPassage("c1p$it".padEnd(150, 'x')) }),
                ),
        )

    @Test
    fun `each duration band maps to its seconds`() {
        assertEquals(0.0, PauseRewind.rewindSeconds(-1L), 0.0)
        assertEquals(3.0, PauseRewind.rewindSeconds(0L), 0.0)
        assertEquals(3.0, PauseRewind.rewindSeconds(PauseRewind.SHORT_PAUSE_MS - 1), 0.0)
        assertEquals(10.0, PauseRewind.rewindSeconds(PauseRewind.SHORT_PAUSE_MS), 0.0)
        assertEquals(10.0, PauseRewind.rewindSeconds(PauseRewind.MEDIUM_PAUSE_MS - 1), 0.0)
        assertEquals(20.0, PauseRewind.rewindSeconds(PauseRewind.MEDIUM_PAUSE_MS), 0.0)
        assertEquals(20.0, PauseRewind.rewindSeconds(PauseRewind.LONG_PAUSE_MS - 1), 0.0)
        assertEquals(30.0, PauseRewind.rewindSeconds(PauseRewind.LONG_PAUSE_MS), 0.0)
        assertEquals(30.0, PauseRewind.rewindSeconds(24 * 3_600_000L), 0.0)
    }

    @Test
    fun `rewind moves back within the chapter`() {
        // Chapter 0 passage 2, 5 s in = book-time 25 s. Rewind 10 s → 15 s =
        // passage 1, 5 s in (strict boundary rolls into the next passage).
        val rewound = PauseRewind.rewind(book, PlayerPosition("b1", 0, 2, 5.0), 10.0)
        assertEquals(0, rewound.chapterIndex)
        assertEquals(1, rewound.passageIndex)
        assertEquals(5.0, rewound.offsetSeconds, 1e-9)
    }

    @Test
    fun `rewind never precedes the chapter's first passage`() {
        // Chapter 1 passage 0, 5 s in = book-time 35 s. A 30 s rewind would
        // reach 5 s (chapter 0) but must clamp to chapter 1's start.
        val rewound = PauseRewind.rewind(book, PlayerPosition("b1", 1, 0, 5.0), 30.0)
        assertEquals(1, rewound.chapterIndex)
        assertEquals(0, rewound.passageIndex)
        assertEquals(0.0, rewound.offsetSeconds, 1e-9)
    }
}
