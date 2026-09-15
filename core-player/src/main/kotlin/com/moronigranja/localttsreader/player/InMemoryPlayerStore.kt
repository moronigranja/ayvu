package com.moronigranja.localttsreader.player

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * [PlayerStore] for tests and host-only tooling — same per-book cap-ring
 * semantics as the Room implementation (decisions #33), no SQLite.
 */
class InMemoryPlayerStore(
    private val ringCapacity: Int = 10,
) : PlayerStore {
    private val mutex = Mutex()
    private val progress = ConcurrentHashMap<String, PlayerProgress>()
    private val rings = ConcurrentHashMap<String, ArrayDeque<PlayerPosition>>()
    private val bookmarks = ConcurrentHashMap<String, MutableList<Bookmark>>()
    private var nextBookmarkId = 1L

    override suspend fun readProgress(bookId: String): PlayerProgress? =
        mutex.withLock {
            progress[bookId]
        }

    override suspend fun commitProgress(
        progressRow: PlayerProgress,
        ringPush: PlayerPosition?,
    ) {
        mutex.withLock {
            progress[progressRow.bookId] = progressRow
            ringPush?.let { pushed ->
                val ring = rings.getOrPut(pushed.bookId) { ArrayDeque() }
                ring.addFirst(pushed)
                while (ring.size > ringCapacity) ring.removeLast()
            }
        }
    }

    override suspend fun readRing(bookId: String): List<PlayerPosition> =
        mutex.withLock {
            rings[bookId]?.toList() ?: emptyList()
        }

    override suspend fun popRing(bookId: String): PlayerPosition? =
        mutex.withLock {
            rings[bookId]?.removeFirstOrNull()
        }

    override suspend fun addBookmark(bookmark: Bookmark): Bookmark =
        mutex.withLock {
            val resolved = bookmark.copy(id = if (bookmark.id == 0L) nextBookmarkId++ else bookmark.id)
            bookmarks.getOrPut(resolved.bookId) { mutableListOf() }.add(0, resolved)
            resolved
        }

    override suspend fun removeBookmark(bookmarkId: Long) =
        mutex.withLock {
            for (list in bookmarks.values) {
                if (list.removeAll { it.id == bookmarkId }) return
            }
        }

    override suspend fun bookmarks(bookId: String): List<Bookmark> =
        mutex.withLock {
            bookmarks[bookId]?.toList() ?: emptyList()
        }
}
