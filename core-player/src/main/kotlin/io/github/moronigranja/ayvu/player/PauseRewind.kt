package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.model.Book

/**
 * Resume-after-pause rewind policy (owner request 2026-09-27): after a pause,
 * resuming rewinds by a duration that scales with how long the audio was
 * paused — a short pause ≈ a sentence, an overnight pause ≈ half a minute —
 * never before the first passage of the current chapter. Replaces the old
 * "lands a bit earlier" behaviour (docs/ideas.md).
 *
 * Pure JVM, constants only: the curve is the whole policy, so tuning it never
 * touches the service.
 */
object PauseRewind {
    const val SHORT_PAUSE_MS = 60_000L
    const val MEDIUM_PAUSE_MS = 3_600_000L
    const val LONG_PAUSE_MS = 6 * 3_600_000L
    const val SHORT_SECONDS = 3.0
    const val MEDIUM_SECONDS = 10.0
    const val LONG_SECONDS = 20.0
    const val OVERNIGHT_SECONDS = 30.0

    /** The rewind for a pause that lasted [pausedMillis] (negative → none). */
    fun rewindSeconds(pausedMillis: Long): Double =
        when {
            pausedMillis < 0L -> 0.0
            pausedMillis < SHORT_PAUSE_MS -> SHORT_SECONDS
            pausedMillis < MEDIUM_PAUSE_MS -> MEDIUM_SECONDS
            pausedMillis < LONG_PAUSE_MS -> LONG_SECONDS
            else -> OVERNIGHT_SECONDS
        }

    /** [position] shifted back by [seconds] in book time, never before the
     * first passage of [position]'s own chapter. */
    fun rewind(
        book: Book,
        position: PlayerPosition,
        seconds: Double,
    ): PlayerPosition {
        val chapterStart =
            BookProgress.elapsedSeconds(book, PlayerPosition(book.id, position.chapterIndex, 0, 0.0))
        val current = BookProgress.elapsedSeconds(book, position)
        return BookProgress.positionAt(book, (current - seconds).coerceAtLeast(chapterStart))
    }
}
