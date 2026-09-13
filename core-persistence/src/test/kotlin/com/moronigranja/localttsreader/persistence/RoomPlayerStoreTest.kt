package com.moronigranja.localttsreader.persistence

import android.content.Context
import androidx.room.Room
import com.moronigranja.localttsreader.player.Bookmark
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerProgress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T4-1 persistence: the Room [PlayerStore] (single transactional write point,
 * cap ring) and the 1→2 migration (progress offset/speed backfill + new
 * tables). Runs against real SQLite under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPlayerStoreTest {

    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomPlayerStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomPlayerStore(database, ringCapacity = 3)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun progress(
        chapter: Int,
        passage: Int,
        offset: Double = 0.0,
        speed: Double = 1.0,
    ) = PlayerProgress("b1", chapter, passage, offset, speed, 1_000L)

    private fun position(chapter: Int, passage: Int, offset: Double = 0.0) =
        PlayerPosition("b1", chapter, passage, offset)

    // ------------------------------------------------------------------

    @Test
    fun `progress round-trips with offset and speed`() = runTest {
        store.commitProgress(progress(0, 2, offset = 4.5, speed = 1.5), null)
        assertEquals(progress(0, 2, 4.5, 1.5), store.readProgress("b1"))
    }

    @Test
    fun `commit pushes the ring newest-first and writes the row atomically`() = runTest {
        store.commitProgress(progress(0, 0), position(0, 0))
        store.commitProgress(progress(0, 1), position(0, 1))
        store.commitProgress(progress(0, 2), position(0, 2))

        assertEquals(listOf(position(0, 2), position(0, 1), position(0, 0)), store.readRing("b1"))
        assertEquals(0, store.readProgress("b1")!!.chapterIndex)
        assertEquals(2, store.readProgress("b1")!!.passageIndex)
    }

    @Test
    fun `no ring push means the ring is untouched`() = runTest {
        store.commitProgress(progress(0, 2), null)
        assertTrue(store.readRing("b1").isEmpty())
    }

    @Test
    fun `ring is capped per book`() = runTest {
        for (i in 0..4) store.commitProgress(progress(0, i), position(0, i))
        assertEquals(listOf(position(0, 4), position(0, 3), position(0, 2)), store.readRing("b1"))
    }

    @Test
    fun `rings are per book`() = runTest {
        store.commitProgress(progress(0, 0), PlayerPosition("b1", 0, 0))
        store.commitProgress(progress(0, 0), PlayerPosition("b2", 0, 0))
        assertEquals(1, store.readRing("b1").size)
        assertEquals(1, store.readRing("b2").size)
    }

    @Test
    fun `pop consumes the newest entry`() = runTest {
        store.commitProgress(progress(0, 0), position(0, 0))
        store.commitProgress(progress(0, 1), position(0, 1))
        assertEquals(position(0, 1), store.popRing("b1"))
        assertEquals(listOf(position(0, 0)), store.readRing("b1"))
        assertEquals(position(0, 0), store.popRing("b1"))
        assertNull(store.popRing("b1"))
    }

    @Test
    fun `bookmarks round-trip newest-first and delete`() = runTest {
        val first = store.addBookmark(Bookmark(bookId = "b1", chapterIndex = 0, passageIndex = 0, label = "a", createdAtEpochMillis = 10))
        val second = store.addBookmark(Bookmark(bookId = "b1", chapterIndex = 0, passageIndex = 1, label = "b", createdAtEpochMillis = 20))
        assertTrue("store-assigned ids", first.id != 0L && second.id != first.id)
        assertEquals(listOf(second, first), store.bookmarks("b1"))

        store.removeBookmark(first.id)
        assertEquals(listOf(second), store.bookmarks("b1"))
    }

    // ------------------------------------------------------------------
    // Migration 1 → 2

    @Test
    fun `migration backfills progress defaults and creates the player tables`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-1-2.db"
        context.deleteDatabase(name)

        // Build the v1 database with the exact DDL Room v1 generated (foreign
        // key + index included — Room validates full TableInfo after a
        // migration), no room_master_table so Room treats it as a legacy DB.
        // Keep this SQL in sync with the schema as it was before the player
        // slice (pre-T4-1 entities).
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(name), null,
        ).use { raw ->
            raw.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, authors TEXT NOT NULL, importedAtEpochMillis INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE passages (bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, chapterTitle TEXT, text TEXT NOT NULL, PRIMARY KEY(bookId, chapterIndex, passageIndex), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)")
            raw.execSQL("CREATE INDEX index_passages_bookId ON passages (bookId)")
            raw.execSQL("CREATE TABLE progress (bookId TEXT NOT NULL PRIMARY KEY, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
            raw.execSQL("CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            raw.execSQL("INSERT INTO books (id, title, authors, importedAtEpochMillis) VALUES ('b1', 'Anna', '', 100)")
            raw.execSQL("INSERT INTO progress (bookId, chapterIndex, passageIndex, updatedAtEpochMillis) VALUES ('b1', 1, 2, 500)")
            raw.version = 1
        }

        val migrated = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val row = migrated.progressDao().get("b1")
            assertEquals(1, row?.chapterIndex)
            assertEquals(2, row?.passageIndex)
            assertEquals(0.0, row!!.offsetSeconds, 0.0)
            assertEquals(1.0, row.speed, 0.0)

            // New tables are live and usable end to end.
            val store = RoomPlayerStore(migrated)
            store.commitProgress(
                PlayerProgress("b1", 1, 2, 3.25, 1.5, 600),
                PlayerPosition("b1", 1, 2, 3.25),
            )
            assertEquals(3.25, store.readProgress("b1")?.offsetSeconds)
            assertEquals(1.5, store.readProgress("b1")?.speed)
            assertEquals(1, store.readRing("b1").size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
