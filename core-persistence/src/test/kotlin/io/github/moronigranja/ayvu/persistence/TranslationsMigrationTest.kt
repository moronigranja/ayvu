package io.github.moronigranja.ayvu.persistence

import android.content.Context
import androidx.room.Room
import io.github.moronigranja.ayvu.player.pregen.PregenKey
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v3 → v4 migration (read-in-language display): the `translations` table
 * arrives as a pure add — v3 data (book, progress, bookmark, activity)
 * survives, the new table exists empty, and the store contract works over
 * the migrated database. Raw-DDL fixture style, same as the 1→2 / 1→3
 * migration tests.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationsMigrationTest {
    @Test
    fun `migration to v4 keeps v3 data and the translations table works`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-3-4.db"
        context.deleteDatabase(name)

        // Build the v3 database with the exact DDL Room v3 generated, no
        // room_master_table so Room treats it as a legacy DB (same fixture
        // pattern as RoomPlayerStoreTest's 1→2 and ActivitySecondsDaoTest's
        // 1→3 migration tests).
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
                    "CREATE TABLE progress (bookId TEXT NOT NULL PRIMARY KEY, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, offsetSeconds REAL NOT NULL DEFAULT 0, speed REAL NOT NULL DEFAULT 1.0, updatedAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL("CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                raw.execSQL(
                    "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, offsetSeconds REAL NOT NULL, label TEXT, createdAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks (bookId)")
                raw.execSQL(
                    "CREATE TABLE position_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, passageIndex INTEGER NOT NULL, offsetSeconds REAL NOT NULL, createdAtEpochMillis INTEGER NOT NULL)",
                )
                raw.execSQL("CREATE INDEX index_position_history_bookId ON position_history (bookId)")
                raw.execSQL(
                    "CREATE TABLE activity_seconds (dayKey TEXT NOT NULL, bookId TEXT NOT NULL, kind TEXT NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY(dayKey, bookId, kind))",
                )
                raw.execSQL("CREATE INDEX index_activity_seconds_bookId ON activity_seconds (bookId)")
                raw.execSQL("INSERT INTO books (id, title, authors, importedAtEpochMillis) VALUES ('b1', 'Anna', '', 100)")
                raw.execSQL(
                    "INSERT INTO progress (bookId, chapterIndex, passageIndex, offsetSeconds, speed, updatedAtEpochMillis) VALUES ('b1', 1, 2, 3.5, 1.0, 500)",
                )
                raw.execSQL(
                    "INSERT INTO bookmarks (bookId, chapterIndex, passageIndex, offsetSeconds, label, createdAtEpochMillis) VALUES ('b1', 0, 1, 2.25, 'favorite', 600)",
                )
                raw.execSQL(
                    "INSERT INTO activity_seconds (dayKey, bookId, kind, seconds) VALUES ('2026-09-13', 'b1', 'LISTEN', 90)",
                )
                raw.version = 3
            }

        val migrated =
            Room
                .databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                // v3 data survived the migration.
                assertEquals(
                    "Anna",
                    migrated
                        .bookDao()
                        .all()
                        .single()
                        .title,
                )
                assertEquals(1, migrated.progressDao().get("b1")?.chapterIndex)
                assertEquals(
                    1,
                    migrated
                        .bookmarkDao()
                        .all("b1")
                        .single()
                        .passageIndex,
                )
                assertEquals(
                    90,
                    migrated
                        .activityDao()
                        .observeSince("2026-09-01")
                        .first()
                        .single()
                        .seconds,
                )

                // The new table exists, is empty, and the store contract is live.
                val store = RoomTranslationStore(migrated)
                assertTrue(migrated.translationDao().chapter("b1", 0).isEmpty())
                migrated.translationDao().put(
                    TranslationEntity("b1", 0, 1, "pt-BR", "lfm12b", "Todas as famílias felizes.", 700),
                )
                assertEquals(
                    "Todas as famílias felizes.",
                    store.get("b1", 0, 1, TranslationTarget("pt-BR", PregenKey.LFM_TRANSLATOR)),
                )
                // REPLACE on the natural key: the same target overwrites.
                store.put("b1", 0, 1, TranslationTarget("pt-BR", PregenKey.LFM_TRANSLATOR), "Nova tradução.")
                assertEquals("Nova tradução.", store.get("b1", 0, 1, TranslationTarget("pt-BR", PregenKey.LFM_TRANSLATOR)))
                // A different translator is a different row (decisions #161).
                assertEquals(
                    null,
                    store.get("b1", 0, 1, TranslationTarget("pt-BR", "small100")),
                )
                assertNotNull(store.chapter("b1", 0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
