package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.model.Book

/**
 * Reader-orientation math (decisions #50 gap pass): where the playhead is
 * in the book (fraction), listening time elapsed/remaining, and mapping a
 * book-time playhead back to a spine position.
 *
 * The fraction is passage-granular like the resume row (completed passages
 * incl. the current one over the book's total). Time uses the same
 * chars-per-second estimate as [io.github.moronigranja.ayvu.player.pregen.PregenSpaceEstimator]
 * — informational, deliberately not a measured per-voice rate; positions are
 * book-time (decision #29), so elapsed is speed-independent and remaining is
 * the 1.0× remainder divided by [speed].
 */
object BookProgress {
    const val DEFAULT_CHARS_PER_SECOND = 15.0

    /** [0..1]: completed passages (incl. current) over the book's total. */
    fun fraction(
        book: Book,
        chapterIndex: Int,
        passageIndex: Int,
    ): Float {
        val total = book.chapters.sumOf { it.passages.size }
        if (total == 0) return 0f
        var before = 0
        for (chapter in book.chapters) {
            if (chapter.index < chapterIndex) {
                before += chapter.passages.size
            } else if (chapter.index == chapterIndex) {
                before += passageIndex.coerceIn(0, chapter.passages.size)
                break
            }
        }
        return ((before + 1).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Listening time left in the book at [speed], from the playhead. */
    fun remainingSeconds(
        book: Book,
        chapterIndex: Int,
        passageIndex: Int,
        offsetSeconds: Double,
        speed: Double,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): Double {
        if (book.chapters.isEmpty()) return 0.0
        val rate = speed.coerceAtLeast(0.01) * charsPerSecond
        var remaining = 0.0
        var seenCurrent = false
        for (chapter in book.chapters) {
            for ((index, passage) in chapter.passages.withIndex()) {
                val sectionSeconds = passage.text.length / rate
                if (chapter.index == chapterIndex && index == passageIndex) {
                    // Offset is book-time at 1.0×; the passage's 1.0× duration
                    // minus the playhead, scaled to the current speed.
                    remaining += ((passage.text.length / charsPerSecond) - offsetSeconds).coerceAtLeast(0.0) / speed.coerceAtLeast(0.01)
                    seenCurrent = true
                } else if (seenCurrent) {
                    remaining += sectionSeconds
                }
            }
        }
        return remaining
    }

    /** Playhead position in book-time seconds at 1.0×: every preceding
     * passage's estimated duration plus the playhead offset into the current
     * one (clamped to the passage). Speed-independent by construction. */
    fun elapsedSeconds(
        book: Book,
        chapterIndex: Int,
        passageIndex: Int,
        offsetSeconds: Double,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): Double = elapsedSeconds(book, PlayerPosition(book.id, chapterIndex, passageIndex, offsetSeconds), charsPerSecond)

    /** Playhead position in book-time seconds at 1.0× (see the indexed form). */
    fun elapsedSeconds(
        book: Book,
        position: PlayerPosition,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): Double {
        var before = 0.0
        var currentDuration = 0.0
        outer@ for (chapter in book.chapters) {
            for ((index, passage) in chapter.passages.withIndex()) {
                if (chapter.index == position.chapterIndex && index == position.passageIndex) {
                    currentDuration = passage.text.length / charsPerSecond
                    break@outer
                }
                before += passage.text.length / charsPerSecond
            }
        }
        return before + position.offsetSeconds.coerceIn(0.0, currentDuration)
    }

    /** The book's total estimated speaking time at 1.0× (chars/cps). */
    fun totalSeconds(
        book: Book,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): Double = book.chapters.sumOf { chapter -> chapter.passages.sumOf { it.text.length } } / charsPerSecond

    /**
     * Maps a book-time playhead at 1.0× back to a spine position by walking
     * passage durations (chars/cps), clamped to the book's bounds. The
     * returned offset is book-time seconds, unchanged by speed.
     */
    fun positionAt(
        book: Book,
        seconds: Double,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): PlayerPosition {
        var remaining = seconds.coerceIn(0.0, totalSeconds(book, charsPerSecond))
        for (chapter in book.chapters) {
            for ((index, passage) in chapter.passages.withIndex()) {
                val duration = passage.text.length / charsPerSecond
                // Strict: an exact boundary rolls into the NEXT passage (start),
                // so 10.0 s lands at passage 2, not passage 1's end.
                if (remaining < duration) {
                    return PlayerPosition(book.id, chapter.index, index, remaining.coerceIn(0.0, duration))
                }
                remaining -= duration
            }
        }
        // Exact book end (or empty book): last passage, full duration.
        val lastChapter = book.chapters.lastOrNull() ?: return PlayerPosition(book.id, 0, 0, 0.0)
        val lastPassage =
            lastChapter.passages.lastOrNull()
                ?: return PlayerPosition(book.id, lastChapter.index, 0, 0.0)
        return PlayerPosition(
            book.id,
            lastChapter.index,
            lastChapter.passages.size - 1,
            lastPassage.text.length / charsPerSecond,
        )
    }
}
