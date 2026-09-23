package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.model.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Chapter geometry of one parsed [Book]: passage counts per chapter in spine
 * order, and passage-level navigation. The machine is bound to one book —
 * playing another book is a new machine over the same [PlayerStore].
 */
class BookLayout(
    book: Book,
) {
    /** The book the layout was built from. */
    val bookId: String = book.id

    private val passageCounts: IntArray =
        IntArray((book.chapters.maxOfOrNull { it.index } ?: -1) + 1)

    init {
        for (chapter in book.chapters) {
            require(chapter.index in passageCounts.indices) { "chapter index ${chapter.index} out of range" }
            passageCounts[chapter.index] = chapter.passages.size
        }
    }

    val chapterCount: Int get() = passageCounts.size

    fun passageCount(chapterIndex: Int): Int = passageCounts[chapterIndex]

    fun isValid(
        chapterIndex: Int,
        passageIndex: Int,
    ): Boolean = chapterIndex in passageCounts.indices && passageIndex in 0 until passageCounts[chapterIndex]

    /** The passage after [chapterIndex]/[passageIndex], or null past the book's end. */
    fun next(
        chapterIndex: Int,
        passageIndex: Int,
    ): Pair<Int, Int>? {
        if (!isValid(chapterIndex, passageIndex)) return null
        if (passageIndex + 1 < passageCounts[chapterIndex]) return chapterIndex to (passageIndex + 1)
        var chapter = chapterIndex + 1
        while (chapter < passageCounts.size && passageCounts[chapter] == 0) chapter++
        return if (chapter < passageCounts.size) chapter to 0 else null
    }

    /** The passage before [chapterIndex]/[passageIndex], or null at the book's start. */
    fun previous(
        chapterIndex: Int,
        passageIndex: Int,
    ): Pair<Int, Int>? {
        if (!isValid(chapterIndex, passageIndex)) return null
        if (passageIndex > 0) return chapterIndex to (passageIndex - 1)
        var chapter = chapterIndex - 1
        while (chapter >= 0 && passageCounts[chapter] == 0) chapter--
        return if (chapter >= 0) chapter to (passageCounts[chapter] - 1) else null
    }

    /** The book's first playable passage, or null when it has no passages at all. */
    fun first(): Pair<Int, Int>? {
        val chapter = passageCounts.indexOfFirst { it > 0 }
        return if (chapter >= 0) chapter to 0 else null
    }

    /** The next chapter with passages, or null past the book's end. */
    fun nextChapter(chapterIndex: Int): Int? {
        var chapter = chapterIndex + 1
        while (chapter < passageCounts.size && passageCounts[chapter] == 0) chapter++
        return chapter.takeIf { it < passageCounts.size }
    }

    /** The previous chapter with passages, or null at the book's start.
     *
     * An out-of-range [chapterIndex] (a target past the end — a stale bookmark
     * or resume row kept after the book was re-imported shorter) has no
     * "previous" to walk from: it clamps to the last chapter with passages
     * instead of indexing out of bounds. Before this, `previousChapter(5)` on a
     * two-chapter book threw an ArrayIndexOutOfBoundsException inside the open
     * command's coroutine — swallowed, so the jump silently did nothing. */
    fun previousChapter(chapterIndex: Int): Int? {
        var chapter = minOf(chapterIndex, passageCounts.size) - 1
        while (chapter >= 0 && passageCounts[chapter] == 0) chapter--
        return chapter.takeIf { it >= 0 }
    }
}

/** Passage text lookup from a [Book] by spine indexes (the player's audio unit). */
fun Book.passageText(
    chapterIndex: Int,
    passageIndex: Int,
): String? =
    chapters
        .firstOrNull { it.index == chapterIndex }
        ?.passages
        ?.getOrNull(passageIndex)
        ?.text

/**
 * The v1 player state machine (decisions #29/#33) — the single writer of
 * [PlayerStore]: transport transitions, sleep timer, speed (API retained —
 * resume pins 1.0× while the selector is removed, decisions #71), and the
 * undo-skip position ring. Pure JVM, fully unit-testable; the Android edge
 * (feature-player) drives it and reacts to [PlayerEvent]s.
 *
 * Ring semantics: a user-directed move **away** from the current position
 * (skip, seek, play-from-elsewhere) pushes the position being left so one
 * undo restores it; natural forward playback never pushes. Every write goes
 * through [PlayerStore.commitProgress] — progress row + ring push are one
 * transaction, so the undo target can never drift from the resume row
 * (roadmap T4 carry-over note 3). Completion pushes the ending, so undo
 * replays it.
 *
 * Positions are in book-time (offset at 1.0×): [setSpeed] never moves the
 * play point and the offset survives speed changes.
 *
 * The machine is not thread-safe per instance; the edge serializes calls
 * (a single player coroutine).
 */
class PlayerStateMachine(
    private val store: PlayerStore,
    private val layout: BookLayout,
    private val ringCapacity: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Book the machine is bound to (layout was built from it). */
    val bookId: String = layout.bookId

    // ------------------------------------------------------------------
    // Transport

    /** Starts playback at the stored resume point; returns it, or null when
     * the book was never played or the stored point no longer maps to the
     * current layout (stale parse from an older import/device cycle — the
     * caller falls back to a fresh start and overwrites the stale row).
     * Ring untouched (nothing is being left). */
    suspend fun resume(): PlayerPosition? {
        val stored = storeOp { store.readProgress(bookId) } ?: return null
        val position = stored.toPosition()
        if (!layout.isValid(position.chapterIndex, position.passageIndex)) return null
        val canUndo = ringHasEntries()
        _state.update {
            it.copy(
                phase = PlayerPhase.LOADING,
                position = position,
                // Stored per-book speed is ignored while the selector is
                // removed (2026-08-28, decisions #71); rows normalize to 1.0
                // on the next write (the machine commits _state.value.speed).
                speed = 1.0,
                sleepTimer = SleepTimer.Off,
                canUndo = canUndo,
                failure = null,
            )
        }
        return position
    }

    /** The stored resume point WITHOUT starting playback (open-book mode):
     * like [resume] but leaves the phase alone and never commits. */
    suspend fun openPosition(): PlayerPosition? {
        val stored = storeOp { store.readProgress(bookId) } ?: return null
        val position = stored.toPosition()
        if (!layout.isValid(position.chapterIndex, position.passageIndex)) return null
        val canUndo = ringHasEntries()
        _state.update { it.copy(position = position, canUndo = canUndo, failure = null) }
        return position
    }

    /** Positions the machine for reading without starting playback: sets the
     * position, no commit, no ring push, phase untouched (open-book mode).
     * Seeds [PlayerState.canUndo] from the ring — the machine owns the ring,
     * so no edge has to read [PlayerStore] to know whether undo is available. */
    suspend fun present(position: PlayerPosition) {
        require(position.bookId == bookId) { "position for ${position.bookId}, machine bound to $bookId" }
        require(layout.isValid(position.chapterIndex, position.passageIndex)) { "position outside layout: $position" }
        val canUndo = ringHasEntries()
        _state.update { it.copy(position = position, canUndo = canUndo, failure = null) }
    }

    /** The book's first playable passage — the fresh-start target when no
     * resume row exists (or the stored one is stale). */
    fun firstPosition(): PlayerPosition? =
        layout.first()?.let { (chapterIndex, passageIndex) ->
            PlayerPosition(bookId, chapterIndex, passageIndex)
        }

    /** Starts playback at [position] — an explicit (possibly accidental) play
     * target: the stored resume point is pushed for one undo. */
    suspend fun playFrom(position: PlayerPosition) {
        require(position.bookId == bookId) { "position for ${position.bookId}, machine bound to $bookId" }
        require(layout.isValid(position.chapterIndex, position.passageIndex)) { "position outside layout: $position" }
        val stored = storeOp { store.readProgress(bookId) }
        val ringPush = stored?.takeUnless { it.samePointer(position) }?.toPosition()
        val canUndo = ringPush != null || ringHasEntries()
        _state.update { it.copy(phase = PlayerPhase.LOADING, position = position, canUndo = canUndo, failure = null) }
        storeOp { store.commitProgress(position.toProgress(_state.value.speed), ringPush) }
    }

    /**
     * Reports where the playhead physically is (book-time seconds) and
     * commits it — the edge calls this throttled during playback and before
     * [pause]/[stop], so the resume row tracks the live position (single
     * writer, decisions #33). Ring untouched (natural progress).
     */
    suspend fun notePlaybackOffset(offsetSeconds: Double) {
        val position = _state.value.position ?: return
        val updated = position.copy(offsetSeconds = offsetSeconds)
        _state.update { it.copy(position = updated) }
        storeOp { store.commitProgress(updated.toProgress(_state.value.speed), null) }
    }

    /** Pauses at the playhead and writes — phase PAUSED. [offsetSeconds]
     * defaults to the machine's committed offset when the edge reports none. */
    suspend fun pause(offsetSeconds: Double? = null) {
        val position = _state.value.position ?: return
        val final = offsetSeconds?.let { position.copy(offsetSeconds = it) } ?: position
        storeOp { store.commitProgress(final.toProgress(_state.value.speed), null) }
        _state.update { it.copy(position = final, phase = PlayerPhase.PAUSED) }
    }

    /** The edge started producing audio for the current position. */
    fun onAudioStarted() {
        _state.update { it.copy(phase = PlayerPhase.PLAYING) }
    }

    /** Detaches the player: final write at the playhead, phase IDLE. */
    suspend fun stop(offsetSeconds: Double? = null) {
        _state.value.position?.let { position ->
            val final = offsetSeconds?.let { position.copy(offsetSeconds = it) } ?: position
            _state.update { it.copy(position = final) }
            storeOp { store.commitProgress(final.toProgress(_state.value.speed), null) }
        }
        _state.update { it.copy(phase = PlayerPhase.IDLE) }
    }

    // ------------------------------------------------------------------
    // Playback progress

    /**
     * The edge finished the current passage's audio. Advances to the next
     * passage (no ring push — natural progress), pauses at a chapter end when
     * the sleep timer is [SleepTimer.EndOfChapter], or completes the book
     * (pushing the ending for one undo).
     */
    suspend fun onPassageFinished(): List<PlayerEvent> {
        val current = _state.value.position ?: return emptyList()
        if (_state.value.phase != PlayerPhase.PLAYING) return emptyList()

        if (_state.value.sleepTimer == SleepTimer.EndOfChapter) {
            val next = layout.next(current.chapterIndex, current.passageIndex)
            if (next != null && next.first != current.chapterIndex) {
                // Chapter boundary with end-of-chapter set: stop before the
                // new chapter, resume row at the chapter's last passage.
                val pauseAt = current.copy(offsetSeconds = 0.0)
                storeOp { store.commitProgress(pauseAt.toProgress(_state.value.speed), null) }
                _state.update { it.copy(position = pauseAt, phase = PlayerPhase.PAUSED, sleepTimer = SleepTimer.Off) }
                return listOf(PlayerEvent.PauseRequested)
            }
        }

        val next = layout.next(current.chapterIndex, current.passageIndex)
        if (next == null) {
            // Book end: keep the resume row at the ending's start for undo.
            val ending = current.copy(offsetSeconds = 0.0)
            storeOp { store.commitProgress(ending.toProgress(_state.value.speed), ending) }
            _state.update { it.copy(position = ending, phase = PlayerPhase.COMPLETED, canUndo = true) }
            return listOf(PlayerEvent.PlaybackCompleted)
        }

        val (chapterIndex, passageIndex) = next
        val advanced = PlayerPosition(bookId, chapterIndex, passageIndex)
        storeOp { store.commitProgress(advanced.toProgress(_state.value.speed), null) }
        // The next passage is not being heard yet: the edge must load it.
        _state.update { it.copy(position = advanced, phase = PlayerPhase.LOADING) }
        return listOf(PlayerEvent.PassageAdvanced(chapterIndex, passageIndex))
    }

    // ------------------------------------------------------------------
    // Navigation (user-directed: every move pushes what is being left)

    /** Next passage (or next chapter's first); pushes the current position. */
    suspend fun skipForward(): List<PlayerEvent> = moveBy { chapter, passage -> layout.next(chapter, passage) }

    /** Previous passage; pushes the current position. */
    suspend fun skipBackward(): List<PlayerEvent> = moveBy { chapter, passage -> layout.previous(chapter, passage) }

    /** Jumps to the next (direction > 0) or previous chapter's first passage;
     * the position being left is pushed as one ring entry — the "tail" of
     * intermediate passages collapses into it — for a single undo. */
    suspend fun skipChapter(direction: Int): List<PlayerEvent> {
        val current = _state.value.position ?: return emptyList()
        val targetChapter =
            if (direction > 0) {
                layout.nextChapter(current.chapterIndex)
            } else {
                layout.previousChapter(current.chapterIndex)
            }
        if (targetChapter == null) return emptyList()
        val position = PlayerPosition(bookId, targetChapter, 0)
        commitMove(position, ringPush = current)
        return listOf(PlayerEvent.PassageAdvanced(position.chapterIndex, position.passageIndex))
    }

    /** Explicit jump (reader "tap a passage", S3 "Listen from here");
     * pushes what is being left. */
    suspend fun seekTo(position: PlayerPosition) {
        require(position.bookId == bookId) { "position for ${position.bookId}" }
        require(layout.isValid(position.chapterIndex, position.passageIndex)) { "position outside layout: $position" }
        val current = _state.value.position ?: return
        if (current.samePointer(position)) return
        commitMove(position, ringPush = current)
    }

    /** Pops the ring and returns to the pushed position (one-shot undo). */
    suspend fun undoSkip(): PlayerPosition? {
        val popped = storeOp { store.popRing(bookId) } ?: return null
        require(layout.isValid(popped.chapterIndex, popped.passageIndex)) { "ring entry outside layout: $popped" }
        commitMove(popped, ringPush = null)
        // The pop may have emptied the ring — re-read it (the machine owns it).
        val canUndo = ringHasEntries()
        _state.update { it.copy(canUndo = canUndo) }
        return popped
    }

    private suspend fun moveBy(target: (chapter: Int, passage: Int) -> Pair<Int, Int>?): List<PlayerEvent> {
        val current = _state.value.position ?: return emptyList()
        val moved = target(current.chapterIndex, current.passageIndex) ?: return emptyList()
        val position = PlayerPosition(bookId, moved.first, moved.second)
        commitMove(position, ringPush = current)
        return listOf(PlayerEvent.PassageAdvanced(position.chapterIndex, position.passageIndex))
    }

    private suspend fun commitMove(
        position: PlayerPosition,
        ringPush: PlayerPosition?,
    ) {
        storeOp { store.commitProgress(position.toProgress(_state.value.speed), ringPush) }
        // A push makes undo available; a pop (ringPush == null via [undoSkip])
        // is re-read by the caller. Readings stay exact without a query per move.
        _state.update {
            it.copy(
                position = position,
                phase = PlayerPhase.LOADING,
                canUndo = ringPush != null || it.canUndo,
                failure = null,
            )
        }
    }

    /** True when the book's undo ring holds an entry. The machine is the ring's
     * single writer (and the only reader that matters) — the Android edge reads
     * [PlayerState.canUndo] instead. */
    private suspend fun ringHasEntries(): Boolean = storeOp { store.readRing(bookId) }.orEmpty().isNotEmpty()

    // ------------------------------------------------------------------
    // Speed / sleep timer / bookmarks

    /** Per-book speed (presets UI, decisions #29). Clamped; preserves the
     * play point — the offset is book-time, so it never moves with speed. */
    suspend fun setSpeed(speed: Double) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.value.position?.let { position ->
            storeOp { store.commitProgress(position.toProgress(clamped), null) }
        }
        _state.update { it.copy(speed = clamped) }
    }

    fun setSleepTimer(timer: SleepTimer) {
        _state.update { it.copy(sleepTimer = timer) }
    }

    /**
     * The reader menu's single sleep button, cycled in place (decisions #29):
     * Off → EndOfChapter → Duration([SLEEP_STEP_MILLIS] from now) → Off.
     *
     * The policy lives with the state it changes. It used to live in the
     * Android edge ([io.github.moronigranja.ayvu.featureplayer.playback.PlaybackService]),
     * which kept the 30-minute product rule in service glue and left the
     * machine exposing only a dumb setter.
     */
    fun cycleSleepTimer(nowEpochMillis: Long = clock()) {
        val next =
            when (_state.value.sleepTimer) {
                SleepTimer.Off -> SleepTimer.EndOfChapter
                SleepTimer.EndOfChapter -> SleepTimer.Duration(nowEpochMillis + SLEEP_STEP_MILLIS)
                is SleepTimer.Duration -> SleepTimer.Off
            }
        _state.update { it.copy(sleepTimer = next) }
    }

    /**
     * Wall-clock tick for countdown sleep: fires once at [SleepTimer.Duration]
     * expiry (pauses + writes), clears the timer. End-of-chapter is handled by
     * [onPassageFinished]. Returns the events the edge must honor.
     */
    suspend fun advance(nowEpochMillis: Long = clock()): List<PlayerEvent> {
        val timer = _state.value.sleepTimer
        if (timer !is SleepTimer.Duration) return emptyList()
        if (nowEpochMillis < timer.endsAtEpochMillis) return emptyList()
        // Expired: clear the timer, pause (and write) when actively playing.
        val wasPlaying = _state.value.phase == PlayerPhase.PLAYING
        _state.update { it.copy(sleepTimer = SleepTimer.Off) }
        if (wasPlaying) {
            _state.value.position?.let { position ->
                storeOp { store.commitProgress(position.toProgress(_state.value.speed), null) }
            }
            _state.update { it.copy(phase = PlayerPhase.PAUSED) }
        }
        return if (wasPlaying) listOf(PlayerEvent.PauseRequested) else emptyList()
    }

    /** Bookmarks the machine's current position (long-press add). */
    suspend fun addBookmark(label: String? = null): Bookmark? {
        val position = _state.value.position ?: return null
        val bookmark =
            Bookmark(
                bookId = bookId,
                chapterIndex = position.chapterIndex,
                passageIndex = position.passageIndex,
                offsetSeconds = position.offsetSeconds,
                label = label,
                createdAtEpochMillis = clock(),
            )
        return storeOp { store.addBookmark(bookmark) }
    }

    suspend fun removeBookmark(bookmarkId: Long) = storeOp { store.removeBookmark(bookmarkId) }

    suspend fun bookmarks(): List<Bookmark> = storeOp { store.bookmarks(bookId) } ?: emptyList()

    // ------------------------------------------------------------------

    /**
     * The single write point's error envelope: a persistence failure becomes a
     * typed [PlayerState.failure]; a CANCELLATION never does. The Android edge
     * cancels a superseded command — and the play loop itself — the moment a
     * newer command arrives, and the loop's throttled playhead checkpoint is
     * suspended in exactly this call, so a superseded write resumes with a
     * CancellationException. Turning that into `failure` published the raw job
     * message ("StandaloneCoroutine was cancelled") through the UI state
     * holder and replaced the reader's book body with the error state
     * (owner report 2026-09-22). Cancellation must propagate; the store's own
     * failures stay typed.
     */
    private inline fun <T> storeOp(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(failure = e.message ?: "store failure") }
            null
        }

    private fun PlayerProgress.toPosition(): PlayerPosition = PlayerPosition(bookId, chapterIndex, passageIndex, offsetSeconds)

    private fun PlayerPosition.toProgress(speed: Double): PlayerProgress =
        PlayerProgress(bookId, chapterIndex, passageIndex, offsetSeconds, speed, clock())

    private fun PlayerProgress.samePointer(other: PlayerPosition): Boolean =
        chapterIndex == other.chapterIndex && passageIndex == other.passageIndex

    private fun PlayerPosition.samePointer(other: PlayerPosition): Boolean =
        chapterIndex == other.chapterIndex && passageIndex == other.passageIndex

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 3.0

        /** Countdown length the sleep cycle arms (30 min, decisions #29). */
        const val SLEEP_STEP_MILLIS = 30 * 60_000L
    }
}
