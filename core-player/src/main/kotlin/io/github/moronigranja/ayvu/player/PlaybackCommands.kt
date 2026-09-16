package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.player.pregen.PregenSpaceEstimate
import kotlinx.coroutines.flow.Flow

/**
 * The player command surface (decisions #53; A6): transport commands the
 * reader and library surfaces send. The contract moved to core-player so
 * features depend on it, never on the PlaybackService implementation; the
 * app binds the intent-dispatching implementation at the composition root.
 */
interface PlayerCommands {
    /** Starts (or resumes) audio for [bookId] at its resume point. */
    fun play(bookId: String)

    /** Starts audio at an explicit passage (share "Listen here", reader gesture). */
    fun playAt(
        bookId: String,
        chapterIndex: Int,
        passageIndex: Int,
    )

    /** Rebuilds the active book on a NEW voice while preserving the playhead
     * (C2): supersedes in-flight synthesis through the A5 single-writer
     * command model — one pause/restart at the same position, the following
     * passage rendered with the new voice. No-op when the voice is unchanged
     * or no book is open. */
    fun changeVoice(voice: String)

    fun resume()

    fun pause()

    fun stop()

    fun seekForward()

    fun seekBackward()
}

/** One manual pre-generation job's observable state (the library row's
 * progress surface — A6 contract over WorkManager's WorkInfo). */
data class PregenJobState(
    val percent: Int = 0,
    val running: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
)

/** Offline pre-generation scheduling contract (A6): the WorkManager surface
 * behind [PregenManager], hidden from features behind this core contract. */
interface PregenScheduler {
    /**
     * Starts a manual run for one book. A non-null [budgetMinutes] bounds the
     * run to that much listening time and anchors it at the book's current
     * reading position (the next N minutes); null = the whole book from its
     * spine start.
     */
    fun pregenerate(
        bookId: String,
        budgetMinutes: Long? = null,
    )

    /** Cancels the book's queued manual run. */
    fun cancel(bookId: String)

    /** The book's manual job state; emits on WorkManager updates. */
    fun observe(bookId: String): Flow<PregenJobState>
}

/** Storage-transparency façade for the pre-generated audio tier (A6): sizes,
 * estimates and reclamation behind one contract (implemented by the player's
 * [io.github.moronigranja.ayvu.featureplayer.playback.PregenStorage] in
 * the app wiring). */
interface OfflineStorage {
    /** Bytes on disk per book (pcm + sidecar); books without audio are absent. */
    fun usageByBook(): Map<String, Long>

    /** Estimates for every cached book in one pass. */
    suspend fun estimateAll(): Map<String, PregenSpaceEstimate>

    /** Reclaims one book's audio: cancel queued work, then delete the subtree. */
    fun deleteBook(bookId: String)
}
