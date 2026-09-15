package com.moronigranja.localttsreader.player

// T4 player domain — the pure-JVM logic half of the docked read-along player
// (decisions #29/#31/#33): transport state, the single transactional write
// point (progress + position ring), sleep timer, speed, bookmarks. Android
// edges (MediaSession, audio output, Compose) live in feature-player.

/**
 * A play/read pointer: passage-granular (decisions #13) plus an in-passage
 * offset in **book-time seconds** — the offset a passage occupies at 1.0×
 * speed, so changing speed never moves the play point (free-rider acceptance,
 * decisions #29). The carousel is chapter/passage indexes into a parsed Book.
 */
data class PlayerPosition(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double = 0.0,
)

/** The persisted resume row: [PlayerPosition] + per-book speed — the single
 * source of truth the reader and the player both write through (decisions
 * #29 "read/listen progress single-source"). */
data class PlayerProgress(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val speed: Double,
    val updatedAtEpochMillis: Long,
)

/** A user bookmark on a passage (long-press add; reader menu, decisions #29). */
data class Bookmark(
    val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double = 0.0,
    val label: String? = null,
    val createdAtEpochMillis: Long,
)

/** Sleep timer modes (decisions #29: sleep timer incl. end-of-chapter). */
sealed interface SleepTimer {
    data object Off : SleepTimer

    /** Pause when the current chapter's last passage finishes. */
    data object EndOfChapter : SleepTimer

    /** Pause when the wall clock passes [endsAtEpochMillis]. */
    data class Duration(
        val endsAtEpochMillis: Long,
    ) : SleepTimer
}

/** Transport phase of the machine. LOADING = position committed, audio not
 * yet producing (the edge synthesizes; pre-gen fills it). */
enum class PlayerPhase { IDLE, LOADING, PLAYING, PAUSED, COMPLETED }

/** Observable machine state. */
data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val position: PlayerPosition? = null,
    val speed: Double = 1.0,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    /** True when an undo target exists (the ring has entries). The machine
     * owns the ring, so it owns this flag — the Android edge reads it instead
     * of re-reading [PlayerStore] behind the machine's back. */
    val canUndo: Boolean = false,
    /** Last typed failure, cleared on the next successful op. */
    val failure: String? = null,
)

/** Events the machine emits for the edge to act on (synthesis, audio, UI). */
sealed interface PlayerEvent {
    /** Sleep timer fired or end-of-chapter reached: the edge must pause audio. */
    data object PauseRequested : PlayerEvent

    /** Playback crossed into the next passage: the edge starts it. */
    data class PassageAdvanced(
        val chapterIndex: Int,
        val passageIndex: Int,
    ) : PlayerEvent

    /** The book's last passage finished. */
    data object PlaybackCompleted : PlayerEvent
}
