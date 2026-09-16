package io.github.moronigranja.ayvu.persistence

import android.content.Context
import androidx.room.Room
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.LibraryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase H persistence (decisions #109): the v2→v3 migration (existing raw-DDL
 * style, v1 data must survive), the add-upsert accumulate, the one-minute
 * active-day rule behind the streak, and the book-removal drop.
 */
@RunWith(RobolectricTestRunner::class)
class ActivitySecondsDaoTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun accumulate(
        dayKey: String,
        bookId: String = "b1",
        kind: String = "LISTEN",
        seconds: Long,
    ) = database.activityDao().accumulate(dayKey, bookId, kind, seconds)

    // ------------------------------------------------------------------
    // Migration 1 → 3

    @Test
    fun `migration to v3 keeps v1 data and the stats table works`() =
        runTest {
            val name = "migration-1-3.db"
            context.deleteDatabase(name)

            // Build the v1 database with the exact DDL Room v1 generated, no
            // room_master_table so Room treats it as a legacy DB (same fixture as
            // RoomPlayerStoreTest's 1→2 migration test).
            android.database.sqlite.SQLiteDatabase
                .openOrCreateDatabase(
                    context.getDatabasePath(name),
                    null,
                ).use { raw ->
                    raw.execSQL(
                        "CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, authors TEXT NOT NULL, importedAtEpochMillis INTEGER NOT NULL)",
                    )
                    raw.execSQL(
                        "CREATE TABLE passages (bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, chapterTitle TEXT, text TEXT NOT NULL, PRIMARY KEY(bookId, chapterIndex, passageIndex), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)",
                    )
                    raw.execSQL("CREATE INDEX index_passages_bookId ON passages (bookId)")
                    raw.execSQL(
                        "CREATE TABLE progress (bookId TEXT NOT NULL PRIMARY KEY, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)",
                    )
                    raw.execSQL("CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    raw.execSQL("INSERT INTO books (id, title, authors, importedAtEpochMillis) VALUES ('b1', 'Anna', '', 100)")
                    raw.execSQL("INSERT INTO progress (bookId, chapterIndex, passageIndex, updatedAtEpochMillis) VALUES ('b1', 1, 2, 500)")
                    raw.version = 1
                }

            val migrated =
                Room
                    .databaseBuilder(context, LibraryDatabase::class.java, name)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
            try {
                // v1 data survived the chained 1→2→3 walk.
                assertEquals(
                    "Anna",
                    migrated
                        .bookDao()
                        .all()
                        .single()
                        .title,
                )
                assertEquals(1, migrated.progressDao().get("b1")?.chapterIndex)

                // The new table is live end to end.
                migrated.activityDao().accumulate("2026-09-13", "b1", "LISTEN", 600)
                assertEquals(
                    listOf(ActivitySecondsEntity("2026-09-13", "b1", "LISTEN", 600)),
                    migrated.activityDao().observeSince("2026-09-13").first(),
                )
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }

    // ------------------------------------------------------------------
    // accumulate: add on conflict

    @Test
    fun `accumulate adds seconds on conflict instead of replacing`() =
        runTest {
            accumulate("2026-09-13", seconds = 90)
            accumulate("2026-09-13", seconds = 30)

            assertEquals(
                listOf(ActivitySecondsEntity("2026-09-13", "b1", "LISTEN", 120)),
                database.activityDao().observeSince("2026-09-13").first(),
            )
        }

    @Test
    fun `kinds and days key separately`() =
        runTest {
            accumulate("2026-09-13", seconds = 60)
            accumulate("2026-09-13", kind = "READ", seconds = 60)
            accumulate("2026-09-12", seconds = 60)
            assertEquals(
                3,
                database
                    .activityDao()
                    .observeSince("2026-09-01")
                    .first()
                    .size,
            )
            // observeSince is inclusive of the boundary day key: the 09-12 row
            // and both 09-13 rows qualify.
            assertEquals(
                3,
                database
                    .activityDao()
                    .observeSince("2026-09-12")
                    .first()
                    .size,
            )
        }

    // ------------------------------------------------------------------
    // active days (the streak's ≥1 combined minute rule)

    @Test
    fun `a day needs a full combined minute to be active`() =
        runTest {
            accumulate("2026-09-13", seconds = 45)
            accumulate("2026-09-13", kind = "READ", seconds = 14) // 59 combined: not active
            accumulate("2026-09-12", seconds = 60) // exactly one minute: active
            accumulate("2026-09-11", seconds = 30) // alone: not active

            assertEquals(listOf("2026-09-12"), database.activityDao().observeActiveDays().first())
        }

    // ------------------------------------------------------------------
    // book removal

    @Test
    fun `RoomLibraryStore delete drops the book's activity rows only`() =
        runTest {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            val store = RoomLibraryStore(database, scope)
            store.add(LibraryEntry(Book("b1", "Anna", emptyList()), 100))
            store.add(LibraryEntry(Book("b2", "Elsewhere", emptyList()), 100))
            accumulate("2026-09-13", bookId = "b1", seconds = 60)
            accumulate("2026-09-13", bookId = "b2", seconds = 60)

            store.delete("b1")

            val remaining = database.activityDao().observeSince("2026-09-13").first()
            assertTrue("only b2's rows survive: $remaining", remaining.all { it.bookId == "b2" })
        }
}
