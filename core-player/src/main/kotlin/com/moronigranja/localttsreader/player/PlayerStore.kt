package com.moronigranja.localttsreader.player

/**
 * The player's persistence contract (decisions #33): progress + position
 * ring + bookmarks. Implemented by Room in core-persistence
 * ([RoomPlayerStore]) and in-memory for tests ([InMemoryPlayerStore]); the
 * state machine is the only writer.
 *
 * Every commit is atomic: [commitProgress] writes the progress row AND, when
 * [ringPush] is present, pushes it onto the per-book cap ring in one
 * transaction — the resume row and the undo ring can never drift apart
 * (roadmap T4 carry-over note 3).
 */
interface PlayerStore {
    /** The book's resume row, or null when never played. */
    suspend fun readProgress(bookId: String): PlayerProgress?

    /**
     * Atomically upserts [progress] and, when [ringPush] is non-null, pushes
     * it onto the book's ring (capped at the implementation's capacity,
     * newest first). A null [ringPush] means "plain position write" — natural
     * forward advance never pollutes the undo ring.
     */
    suspend fun commitProgress(
        progress: PlayerProgress,
        ringPush: PlayerPosition?,
    )

    /** The book's ring entries, newest first. */
    suspend fun readRing(bookId: String): List<PlayerPosition>

    /** Removes and returns the newest ring entry — the one-shot undo target. */
    suspend fun popRing(bookId: String): PlayerPosition?

    /** Persists [bookmark] and returns it with its store-assigned id. */
    suspend fun addBookmark(bookmark: Bookmark): Bookmark

    suspend fun removeBookmark(bookmarkId: Long)

    /** The book's bookmarks, newest first. */
    suspend fun bookmarks(bookId: String): List<Bookmark>
}
