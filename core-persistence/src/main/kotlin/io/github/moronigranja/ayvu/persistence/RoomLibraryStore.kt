package io.github.moronigranja.ayvu.persistence

import androidx.room.withTransaction
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Room-backed [LibraryStore] (P1/P2 swap target): [books] streams library rows
 * in import order; [add] persists the book and its full parsed passages **in one
 * transaction** — that passages cache is what the launch-time index rebuild
 * consumes, so a relaunch never re-parses (P2).
 *
 * Book ids are content hashes (decisions #11), so [add] is naturally
 * idempotent: [androidx.room.Upsert] replaces rows on the same id.
 *
 * Note: [books] entries carry an empty `chapters` list on purpose — the list UI
 * needs title/authors only; the full parse is reachable via [cachedBooks].
 */
class RoomLibraryStore(
    private val database: LibraryDatabase,
    private val scope: CoroutineScope,
) : LibraryStore {
    override val books: StateFlow<List<LibraryEntry>> =
        database
            .bookDao()
            .observeAll()
            .map { rows -> rows.map { it.toLibraryEntry() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** CR-3/A3: durable membership — the re-import duplicate gate (Room is truth). */
    override suspend fun contains(bookId: String): Boolean = database.bookDao().byId(bookId) != null

    override suspend fun add(entry: LibraryEntry) {
        database.withTransaction {
            // Replace, not append: the passages cache must mirror this entry's
            // parse exactly. Same-id re-adds with a differing (empty) chapter
            // list would otherwise leave stale rows behind.
            database.passageDao().deleteByBook(entry.book.id)
            database.bookDao().upsert(entry.bookEntity())
            database.passageDao().upsertAll(entry.book.cachedPassages())
        }
    }

    override suspend fun delete(bookId: String) {
        // One transaction: passages cascade from the book row, but progress,
        // bookmarks, the undo ring, the per-day activity rows, the stored
        // translations and the per-book settings rows have no FK — delete
        // them explicitly so the resume surface never points at a removed
        // book (decisions #50), the stats card never counts a deleted book
        // (Phase H, decisions #109), and the per-book voice/translate/display
        // settings + translated text never orphan behind it (cleanup pass G).
        database.withTransaction {
            database.progressDao().delete(bookId)
            database.bookmarkDao().deleteByBook(bookId)
            database.historyDao().deleteByBook(bookId)
            database.activityDao().deleteByBook(bookId)
            database.translationDao().deleteByBook(bookId)
            database.passageDao().deleteByBook(bookId)
            database.settingsDao().deleteAll(
                listOf(
                    SettingsStore.bookVoiceKey(bookId),
                    SettingsStore.bookTranslateKey(bookId),
                    SettingsStore.bookDisplayKey(bookId),
                ),
            )
            database.bookDao().delete(bookId)
        }
    }

    /** Every book's cached parse, in import order — the rebuild's input (P2). */
    override suspend fun cachedBooks(): List<CachedBook> {
        val books = database.bookDao().all()
        val passagesByBook = database.passageDao().all().groupBy { it.bookId }
        return books.map { book ->
            CachedBook(
                id = book.id,
                title = book.title,
                authors = decodeAuthors(book.authors),
                passages = passagesByBook[book.id].orEmpty().map { it.toCachedPassage() },
            )
        }
    }
}
