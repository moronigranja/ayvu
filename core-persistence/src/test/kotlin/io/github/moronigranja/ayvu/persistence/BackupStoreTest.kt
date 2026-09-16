package io.github.moronigranja.ayvu.persistence

import androidx.room.Room
import io.github.moronigranja.ayvu.backup.BackupCodec
import io.github.moronigranja.ayvu.backup.BackupHistory
import io.github.moronigranja.ayvu.backup.BackupReadResult
import io.github.moronigranja.ayvu.backup.BackupSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * E1 snapshot/merge contract (decisions #109), headless: a populated export
 * restores onto a fresh in-memory DB losslessly (through the codec), a second
 * merge adds no duplicate rows, progress is local-wins, settings
 * restore-wins-with-absent-kept, existing cached parses are never clobbered,
 * and book files round-trip through the sidecar tier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupStoreTest {
    private lateinit var database: LibraryDatabase
    private lateinit var bookFiles: File

    @Before
    fun setUp() {
        database = freshDatabase()
        bookFiles = createTempDirectory("backup-store-test").toFile()
    }

    @After
    fun tearDown() {
        database.close()
        bookFiles.deleteRecursively()
    }

    private fun freshDatabase(): LibraryDatabase =
        Room
            .inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                LibraryDatabase::class.java,
            ).allowMainThreadQueries()
            .build()

    private fun store(db: LibraryDatabase = database) =
        BackupStore(
            database = db,
            appVersion = "0.1.0",
            bookFileStore = BookFileStore(bookFiles),
            now = { FIXED_NOW },
        )

    /** A b1+b2 populated source DB: cached parses, progress, bookmarks (one
     * with a label, one without), undo history, settings, one book file. */
    private suspend fun populate(db: LibraryDatabase) {
        db.bookDao().upsert(BookEntity("b1", "Anna Karenina", encodeAuthors(listOf("Leo Tolstoy")), 1_000L))
        db.bookDao().upsert(BookEntity("b2", "Dom Casmurro", encodeAuthors(listOf("Machado de Assis")), 2_000L))
        db.passageDao().upsertAll(
            listOf(
                PassageEntity("b1", 0, "Happy Families", 0, "All happy families are alike."),
                PassageEntity("b1", 0, "Happy Families", 1, "Each unhappy one in its own way."),
                PassageEntity("b2", 1, null, 0, "Chapter-less passage."),
            ),
        )
        db.progressDao().upsert(ProgressEntity("b1", 0, 1, 3.5, 1.0, 3_000L))
        db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = "b1",
                chapterIndex = 0,
                passageIndex = 0,
                offsetSeconds = 2.25,
                label = "favorite line",
                createdAtEpochMillis = 1_500L,
            ),
        )
        db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = "b2",
                chapterIndex = 1,
                passageIndex = 0,
                offsetSeconds = 0.0,
                label = null,
                createdAtEpochMillis = 2_500L,
            ),
        )
        db.historyDao().insert(
            PositionHistoryEntity(bookId = "b1", chapterIndex = 0, passageIndex = 0, offsetSeconds = 0.0, createdAtEpochMillis = 1_400L),
        )
        db.historyDao().insert(
            PositionHistoryEntity(bookId = "b1", chapterIndex = 0, passageIndex = 1, offsetSeconds = 3.5, createdAtEpochMillis = 2_000L),
        )
        db.settingsDao().put(SettingEntity("voice", "af_heart"))
        db.settingsDao().put(SettingEntity("theme_mode", "dark"))
        db.translationDao().put(
            TranslationEntity("b1", 0, 0, "pt-BR", "lfm12b", "Todas as famílias felizes são iguais.", 3_100L),
        )
        BookFileStore(bookFiles).save("b1.epub", EPUB_BYTES)
    }

    /** Snapshot → codec → read, checked as Ok. */
    private suspend fun roundTrip(
        db: LibraryDatabase = database,
        includeBooks: Boolean = false,
    ): BackupSnapshot {
        val read = BackupCodec.read(BackupCodec.write(store(db).snapshot(includeBooks)))
        check(read is BackupReadResult.Ok) { "read failed: $read" }
        return read.snapshot
    }

    // ------------------------------------------------------------------
    // Lossless fresh-install restore
    // ------------------------------------------------------------------

    @Test
    fun `exported snapshot restores losslessly onto a fresh database`() =
        runTest {
            populate(database)

            val exported = store().snapshot(includeBooks = true)
            val read = BackupCodec.read(BackupCodec.write(exported))
            check(read is BackupReadResult.Ok) { "read failed: $read" }

            val fresh = freshDatabase()
            try {
                val merged = store(fresh).merge(read.snapshot)

                assertEquals(
                    BackupMergeResult(booksAdded = 2, booksUnchanged = 0, progressRestored = 1, bookmarksAdded = 2, historyAppended = 2),
                    merged,
                )
                // A fresh install: the restored database re-exports the same snapshot.
                assertEquals(exported, store(fresh).snapshot(includeBooks = true))
            } finally {
                fresh.close()
            }
        }

    @Test
    fun `empty library round-trips as a no-op restore`() =
        runTest {
            val exported = store().snapshot(includeBooks = false)
            val read = BackupCodec.read(BackupCodec.write(exported))
            check(read is BackupReadResult.Ok)

            val merged = store().merge(read.snapshot)

            assertEquals(BackupMergeResult(0, 0, 0, 0, 0), merged)
            assertTrue(database.bookDao().all().isEmpty())
        }

    // ------------------------------------------------------------------
    // Idempotent re-restore
    // ------------------------------------------------------------------

    @Test
    fun `second merge of the same snapshot adds no duplicate rows`() =
        runTest {
            populate(database)
            val snapshot = roundTrip()

            val fresh = freshDatabase()
            try {
                val first = store(fresh).merge(snapshot)
                val second = store(fresh).merge(snapshot)

                assertEquals(2, first.booksAdded)
                assertEquals(0, second.booksAdded)
                assertEquals(2, second.booksUnchanged) // every book already present
                assertEquals(0, second.progressRestored)
                assertEquals(0, second.bookmarksAdded)
                assertEquals(2, fresh.bookDao().all().size)
                assertEquals(2, fresh.bookmarkDao().all().size)
                // History is append-then-pruned to the ring cap: the second merge
                // must not grow the ring nor change its content.
                assertEquals(2, fresh.historyDao().all().size)
                // The no-arg `all()` is ASC (bookId, id): the two restored entries.
                val tuples = fresh.historyDao().all().map { Triple(it.bookId, it.chapterIndex, it.passageIndex) }
                assertEquals(listOf(Triple("b1", 0, 0), Triple("b1", 0, 1)), tuples)
            } finally {
                fresh.close()
            }
        }

    @Test
    fun `history ring stays capped when the archive carries more entries than the cap`() =
        runTest {
            database.bookDao().upsert(BookEntity("b1", "B", encodeAuthors(emptyList()), 1_000L))
            val snapshot =
                BackupSnapshot(
                    version = BackupCodec.BACKUP_VERSION,
                    appVersion = "0.1.0",
                    exportedAtEpochMillis = FIXED_NOW,
                    settings = emptyMap(),
                    library = emptyList(),
                    passages = emptyList(),
                    progress = emptyList(),
                    bookmarks = emptyList(),
                    positionHistory =
                        (1..30L).map { i ->
                            BackupHistory("b1", 0, 0, 0.0, i * 100)
                        },
                    bookFiles = emptyMap(),
                )

            store().merge(snapshot)

            assertEquals(RoomPlayerStore.RING_CAPACITY, database.historyDao().all().size)
            // The newest entries survive (rowid order == insert order).
            assertEquals(30, database.historyDao().all().maxOf { it.createdAtEpochMillis / 100 })
        }

    // ------------------------------------------------------------------
    // Merge precedence
    // ------------------------------------------------------------------

    @Test
    fun `local progress wins over a restored progress row`() =
        runTest {
            populate(database)
            val snapshot = roundTrip()

            // Target: same book, but the local reader is already at chapter 5.
            val fresh = freshDatabase()
            try {
                fresh.bookDao().upsert(BookEntity("b1", "Anna Karenina", encodeAuthors(listOf("Leo Tolstoy")), 1_000L))
                fresh.passageDao().upsertAll(listOf(PassageEntity("b1", 0, "Happy Families", 0, "local cached text")))
                fresh.progressDao().upsert(ProgressEntity("b1", 5, 2, 9.0, 1.25, 9_999L))

                store(fresh).merge(snapshot)

                val local = fresh.progressDao().get("b1")!!
                assertEquals(5, local.chapterIndex)
                assertEquals(9.0, local.offsetSeconds, 0.0)
                // The restored bookmarks still land (merge independently of progress);
                // the archive's b2 book row was also merged, so both bookmarks return.
                assertEquals(2, fresh.bookmarkDao().all().size)
                assertEquals(1, fresh.bookmarkDao().all().count { it.label == "favorite line" })
            } finally {
                fresh.close()
            }
        }

    @Test
    fun `restored settings overwrite local and absent keys keep local values`() =
        runTest {
            populate(database)
            val snapshot = roundTrip()

            // Local rows: one key the archive also has (voice), one it does not.
            database.settingsDao().put(SettingEntity("voice", "local_voice"))
            database.settingsDao().put(SettingEntity("local_only", "kept"))

            store().merge(snapshot)

            val rows = database.settingsDao().all().associate { it.key to it.value }
            assertEquals("af_heart", rows["voice"]) // restored overwrites local
            assertEquals("dark", rows["theme_mode"])
            assertEquals("kept", rows["local_only"]) // absent from archive keeps local
        }

    // ------------------------------------------------------------------
    // Passages never clobbered
    // ------------------------------------------------------------------

    @Test
    fun `restored translations overwrite local on the natural key`() =
        runTest {
            populate(database)
            val snapshot = roundTrip()

            // Local rows: the same (book, chapter, passage, lang, translator)
            // key holds a different text; a different translator's row is
            // distinct (the natural key includes the translator, decisions #161).
            database.translationDao().put(
                TranslationEntity("b1", 0, 0, "pt-BR", "lfm12b", "LOCAL TEXT", 4_000L),
            )
            database.translationDao().put(
                TranslationEntity("b1", 0, 0, "pt-BR", "small100", "RETIRED ENGINE TEXT", 4_100L),
            )

            store().merge(snapshot)

            val rows = database.translationDao().all().associate { Triple(it.bookId, it.passageIndex, it.translator) to it.text }
            // Restored rows overwrite local on the same key…
            assertEquals("Todas as famílias felizes são iguais.", rows[Triple("b1", 0, "lfm12b")])
            // …while a key the archive does not carry keeps its local row.
            assertEquals("RETIRED ENGINE TEXT", rows[Triple("b1", 0, "small100")])
        }

    @Test
    fun `existing book cache is not rewritten by a merge`() =
        runTest {
            populate(database)
            val snapshot = roundTrip()

            // The book exists locally with its own cached parse.
            val fresh = freshDatabase()
            try {
                fresh.bookDao().upsert(BookEntity("b1", "Anna Karenina", encodeAuthors(listOf("Leo Tolstoy")), 1_000L))
                fresh.passageDao().upsertAll(listOf(PassageEntity("b1", 0, "Happy Families", 0, "LOCAL CACHE TEXT")))

                store(fresh).merge(snapshot)

                assertEquals(
                    "LOCAL CACHE TEXT",
                    fresh
                        .passageDao()
                        .forBook("b1")
                        .single()
                        .text,
                )
            } finally {
                fresh.close()
            }
        }

    // ------------------------------------------------------------------
    // Book files
    // ------------------------------------------------------------------

    @Test
    fun `book files round-trip through snapshot and merge`() =
        runTest {
            populate(database)
            val storeFiles = createTempDirectory("backup-store-files").toFile()
            try {
                val withoutFiles = store().snapshot(includeBooks = false)
                assertTrue(withoutFiles.bookFiles.isEmpty())

                val withFiles = store().snapshot(includeBooks = true)
                assertEquals(EPUB_BYTES.toList(), withFiles.bookFiles["b1.epub"]!!.toList())

                // The sidecar from the source ends up back on disk after a merge.
                val fresh = freshDatabase()
                try {
                    val targetFiles = BookFileStore(storeFiles)
                    BackupStore(fresh, "0.1.0", targetFiles, now = { FIXED_NOW }).merge(withFiles)
                    assertEquals(EPUB_BYTES.toList(), targetFiles.all()["b1.epub"]!!.toList())
                } finally {
                    fresh.close()
                }
            } finally {
                storeFiles.deleteRecursively()
            }
        }

    @Test
    fun `deleteForBook removes every sidecar of the book`() {
        val target = BookFileStore(bookFiles)
        target.save("b1.epub", EPUB_BYTES)
        target.save("b1.txt", EPUB_BYTES)
        target.save("b2.epub", EPUB_BYTES)

        target.deleteForBook("b1")

        val remaining = target.all()
        assertEquals(setOf("b2.epub"), remaining.keys)
        assertTrue(remaining["b2.epub"]!!.contentEquals(EPUB_BYTES))
    }

    companion object {
        private const val FIXED_NOW = 1_752_000_000_000L
        private val EPUB_BYTES = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)
    }
}
