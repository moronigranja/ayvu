package com.moronigranja.localttsreader.persistence

import androidx.room.withTransaction
import com.moronigranja.localttsreader.player.Bookmark
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerProgress
import com.moronigranja.localttsreader.player.PlayerStore

/**
 * Room-backed [PlayerStore] (T4-1, decisions #33): the single transactional
 * write point for the player state machine. [commitProgress] upserts the
 * progress row and pushes the ring entry (then prunes to the cap) in ONE
 * transaction — the resume row and the undo ring cannot drift apart
 * (roadmap T4 carry-over note 3). Pop consumes the newest entry.
 */
class RoomPlayerStore(
    private val database: LibraryDatabase,
    private val ringCapacity: Int = RING_CAPACITY,
) : PlayerStore {
    override suspend fun readProgress(bookId: String): PlayerProgress? = database.progressDao().get(bookId)?.toPlayerProgress()

    override suspend fun commitProgress(
        progress: PlayerProgress,
        ringPush: PlayerPosition?,
    ) {
        database.withTransaction {
            database.progressDao().upsert(progress.toEntity())
            ringPush?.let { pushed ->
                database.historyDao().insert(pushed.toEntity())
                database.historyDao().prune(pushed.bookId, ringCapacity)
            }
        }
    }

    override suspend fun readRing(bookId: String): List<PlayerPosition> = database.historyDao().all(bookId).map { it.toPlayerPosition() }

    override suspend fun popRing(bookId: String): PlayerPosition? =
        database.withTransaction {
            val top = database.historyDao().newest(bookId) ?: return@withTransaction null
            database.historyDao().delete(top.id)
            top.toPlayerPosition()
        }

    override suspend fun addBookmark(bookmark: Bookmark): Bookmark {
        val id = database.bookmarkDao().insert(bookmark.toEntity())
        return bookmark.copy(id = id)
    }

    override suspend fun removeBookmark(bookmarkId: Long) = database.bookmarkDao().delete(bookmarkId)

    override suspend fun bookmarks(bookId: String): List<Bookmark> = database.bookmarkDao().all(bookId).map { it.toBookmark() }

    private fun PlayerProgress.toEntity() =
        ProgressEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            speed = speed,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun ProgressEntity.toPlayerProgress() =
        PlayerProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            speed = speed,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun PlayerPosition.toEntity() =
        PositionHistoryEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            createdAtEpochMillis = System.currentTimeMillis(),
        )

    private fun PositionHistoryEntity.toPlayerPosition() =
        PlayerPosition(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
        )

    private fun Bookmark.toEntity() =
        BookmarkEntity(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            label = label,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun BookmarkEntity.toBookmark() =
        Bookmark(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            label = label,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    companion object {
        /** The per-book undo-ring cap (decisions #29) — shared with the backup
         * merge's prune step so the two can never drift (E1). */
        const val RING_CAPACITY = 10
    }
}
