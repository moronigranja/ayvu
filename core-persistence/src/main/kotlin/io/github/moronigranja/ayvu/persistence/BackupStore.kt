package io.github.moronigranja.ayvu.persistence

import androidx.room.withTransaction
import io.github.moronigranja.ayvu.backup.BackupBook
import io.github.moronigranja.ayvu.backup.BackupBookmark
import io.github.moronigranja.ayvu.backup.BackupCodec
import io.github.moronigranja.ayvu.backup.BackupHistory
import io.github.moronigranja.ayvu.backup.BackupPassage
import io.github.moronigranja.ayvu.backup.BackupProgress
import io.github.moronigranja.ayvu.backup.BackupSnapshot
import io.github.moronigranja.ayvu.backup.BackupTranslation

/**
 * E1: the Room ↔ backup-archive boundary. [snapshot] is one consistent read
 * of every table mapped to the codec DTOs; [merge] applies a read snapshot
 * transactionally with the signed-off precedence (decisions #109):
 *
 * - progress: local wins (a local resume row is never overwritten);
 * - settings: restored keys overwrite local, absent keys keep local;
 * - bookmarks/history: idempotent append (a second restore adds no rows);
 * - passages: only written for books whose cache is missing (existing cached
 *   parses are never clobbered, never re-parsed);
 * - book files: opt-in copy, written back verbatim, only for a stem that matches a
 *   restored book id.
 *
 * [bookFileStore] is nullable so pure-JVM tests can exercise the merge
 * without the sidecar tier.
 */
class BackupStore(
    private val database: LibraryDatabase,
    private val appVersion: String,
    private val bookFileStore: BookFileStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun snapshot(includeBooks: Boolean): BackupSnapshot =
        database.withTransaction {
            BackupSnapshot(
                version = BackupCodec.BACKUP_VERSION,
                appVersion = appVersion,
                exportedAtEpochMillis = now(),
                settings = database.settingsDao().all().associate { it.key to it.value },
                library = database.bookDao().all().map { it.toBackupBook() },
                passages = database.passageDao().all().map { it.toBackupPassage() },
                progress = database.progressDao().all().map { it.toBackupProgress() },
                bookmarks = database.bookmarkDao().all().map { it.toBackupBookmark() },
                positionHistory = database.historyDao().all().map { it.toBackupHistory() },
                translations = database.translationDao().all().map { it.toBackupTranslation() },
                bookFiles = if (includeBooks) bookFileStore?.all().orEmpty() else emptyMap(),
            )
        }

    /**
     * Applies [snapshot] in one transaction, in dependency order (passages
     * reference books). Returns the applied counts; identical books/bookmarks/
     * history rows are never duplicated, so a second merge of the same
     * archive reports all-unchanged.
     */
    suspend fun merge(snapshot: BackupSnapshot): BackupMergeResult =
        database.withTransaction {
            // 1. Books — upsert by content-hash id (added vs unchanged by prior row).
            var booksAdded = 0
            var booksUnchanged = 0
            snapshot.library.forEach { book ->
                if (database.bookDao().byId(book.id) == null) booksAdded += 1 else booksUnchanged += 1
                database.bookDao().upsert(book.toEntity())
            }

            // 2. Passages — only for books with no local cache (never clobber, never re-parse).
            snapshot.passages.groupBy { it.bookId }.forEach { (bookId, group) ->
                if (database.passageDao().forBook(bookId).isEmpty()) {
                    database.passageDao().upsertAll(group.map { it.toEntity() })
                }
            }

            // 3. Progress — local wins.
            var progressRestored = 0
            snapshot.progress.forEach { progress ->
                if (database.progressDao().get(progress.bookId) == null) {
                    database.progressDao().upsert(progress.toEntity())
                    progressRestored += 1
                }
            }

            // 4. Bookmarks — idempotent natural-key inserts ("" label = null sentinel).
            var bookmarksAdded = 0
            snapshot.bookmarks.groupBy { it.bookId }.forEach { (bookId, restored) ->
                val existing =
                    database
                        .bookmarkDao()
                        .all(bookId)
                        .map { it.toNaturalKey() }
                        .toMutableSet()
                restored.forEach { bookmark ->
                    if (existing.add(bookmark.toNaturalKey())) {
                        database.bookmarkDao().insert(bookmark.toEntity())
                        bookmarksAdded += 1
                    }
                }
            }

            // 5. History — idempotent append (a re-restore adds no duplicate rows;
            // a ring entry is one user move, so a natural key is unique), then
            // prune each book to the shared ring cap for genuinely new entries.
            var historyAppended = 0
            snapshot.positionHistory.groupBy { it.bookId }.forEach { (bookId, restored) ->
                val existing =
                    database
                        .historyDao()
                        .all(bookId)
                        .map { it.toNaturalKey() }
                        .toMutableSet()
                restored.forEach { entry ->
                    if (existing.add(entry.toNaturalKey())) {
                        database.historyDao().insert(entry.toEntity())
                        historyAppended += 1
                    }
                }
            }
            snapshot.positionHistory.map { it.bookId }.distinct().forEach { bookId ->
                database.historyDao().prune(bookId, RoomPlayerStore.RING_CAPACITY)
            }

            // 6. Settings — restored keys overwrite local; absent keys keep local.
            database.settingsDao().putAll(snapshot.settings.map { (key, value) -> SettingEntity(key, value) })

            // 6b. Translations — restored rows overwrite local on the natural
            // (bookId, chapter, passage, lang, translator) key: [put] is REPLACE
            // on that PK, so the settings precedence falls out of the DAO.
            snapshot.translations.forEach { database.translationDao().put(it.toEntity()) }

            // 7. Book files — opt-in sidecar copy, restricted to books this archive
            // actually restores: a name whose stem is not a restored id is dropped
            // (the codec already refuses a traversal name and the store refuses one
            // that resolves outside `files/books`; this is the layer that keeps a
            // sidecar and a library row in step).
            bookFileStore?.let { store ->
                val restoredIds = snapshot.library.map { it.id }.toSet()
                snapshot.bookFiles.forEach { (name, bytes) ->
                    if (name.substringBeforeLast('.') in restoredIds) store.save(name, bytes)
                }
            }

            BackupMergeResult(
                booksAdded = booksAdded,
                booksUnchanged = booksUnchanged,
                progressRestored = progressRestored,
                bookmarksAdded = bookmarksAdded,
                historyAppended = historyAppended,
            )
        }

    // ------------------------------------------------------------------
    // Snapshot mappings
    // ------------------------------------------------------------------

    private fun BookEntity.toBackupBook() =
        BackupBook(
            id = id,
            title = title,
            authors = decodeAuthors(authors),
            importedAtEpochMillis = importedAtEpochMillis,
        )

    private fun PassageEntity.toBackupPassage() =
        BackupPassage(
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            passageIndex = passageIndex,
            text = text,
        )

    private fun ProgressEntity.toBackupProgress() =
        BackupProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            speed = speed,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun BookmarkEntity.toBackupBookmark() =
        BackupBookmark(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            // The DTO label is non-null; the entity's null means "no label" — the
            // "" sentinel inverts the merge mapping (decisions #109).
            label = label ?: "",
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun PositionHistoryEntity.toBackupHistory() =
        BackupHistory(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun TranslationEntity.toBackupTranslation() =
        BackupTranslation(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            lang = lang,
            translator = translator,
            text = text,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    // ------------------------------------------------------------------
    // Merge mappings
    // ------------------------------------------------------------------

    private fun BackupBook.toEntity() =
        BookEntity(
            id = id,
            title = title,
            authors = encodeAuthors(authors),
            importedAtEpochMillis = importedAtEpochMillis,
        )

    private fun BackupPassage.toEntity() =
        PassageEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            passageIndex = passageIndex,
            text = text,
        )

    private fun BackupProgress.toEntity() =
        ProgressEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            speed = speed,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun BackupBookmark.toEntity() =
        BookmarkEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            label = label.ifEmpty { null },
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun BackupHistory.toEntity() =
        PositionHistoryEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun BackupTranslation.toEntity() =
        TranslationEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            lang = lang,
            translator = translator,
            text = text,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    /** E1: the bookmark identity — same book+position+label+time = same row. */
    private fun BookmarkEntity.toNaturalKey() =
        BookmarkNaturalKey(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            label = label ?: "",
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun BackupBookmark.toNaturalKey() =
        BookmarkNaturalKey(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            label = label,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private data class BookmarkNaturalKey(
        val bookId: String,
        val chapterIndex: Int,
        val passageIndex: Int,
        val offsetSeconds: Double,
        val label: String,
        val createdAtEpochMillis: Long,
    )

    /** E1: the history identity — same book+position+time = same ring entry. */
    private fun PositionHistoryEntity.toNaturalKey() =
        HistoryNaturalKey(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun BackupHistory.toNaturalKey() =
        HistoryNaturalKey(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            offsetSeconds = offsetSeconds,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private data class HistoryNaturalKey(
        val bookId: String,
        val chapterIndex: Int,
        val passageIndex: Int,
        val offsetSeconds: Double,
        val createdAtEpochMillis: Long,
    )
}

/** E1: what [BackupStore.merge] applied — the restore summary source. */
data class BackupMergeResult(
    val booksAdded: Int,
    val booksUnchanged: Int,
    val progressRestored: Int,
    val bookmarksAdded: Int,
    val historyAppended: Int,
)
