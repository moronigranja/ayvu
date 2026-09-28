package io.github.moronigranja.ayvu.persistence

import androidx.room.Room
import io.github.moronigranja.ayvu.backup.BackupCodec
import io.github.moronigranja.ayvu.backup.BackupReadResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The per-book voice override (decisions #144, roadmap Phase K item 5):
 * one `book.voice.<bookId>` row in the EXISTING generic settings table —
 * no Room migration. The key round-trips (set/clear), an explicit override
 * beats the global default while changing only that book, and it rides the
 * existing backup archive untouched (snapshot dumps every settings row raw;
 * the merge's restored-keys-overwrite precedence applies unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookVoiceOverrideTest {
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        database = freshDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun freshDatabase(): LibraryDatabase =
        Room
            .inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                LibraryDatabase::class.java,
            ).allowMainThreadQueries()
            .build()

    @Test
    fun `set and clear round-trip through the generic table`() =
        runTest {
            val store = SettingsStore(database.settingsDao())

            store.setBookVoice("b1", "de_DE-thorsten-high")
            assertEquals("de_DE-thorsten-high", store.bookVoice("b1"))
            assertEquals(
                "the row lives in the same generic settings table",
                mapOf("b1" to "de_DE-thorsten-high"),
                store.bookVoices(),
            )
            assertEquals(
                "the stored row is the prefix + book id",
                mapOf(SettingsStore.bookVoiceKey("b1") to "de_DE-thorsten-high"),
                database
                    .settingsDao()
                    .all()
                    .filter { it.key.startsWith(SettingsStore.KEY_BOOK_VOICE_PREFIX) }
                    .associate { it.key to it.value },
            )

            store.setBookVoice("b1", null)
            assertNull("clear deletes the row — absent key = no override", store.bookVoice("b1"))
            assertEquals(emptyMap<String, String>(), store.bookVoices())
        }

    @Test
    fun `an override changes only that book and never the global default`() =
        runTest {
            val store = SettingsStore(database.settingsDao())
            store.setVoice("af_heart")
            store.setBookVoice("b1", "de_DE-thorsten-high")
            store.setBookVoice("b2", "en_US-lessac-medium")

            assertEquals("override wins for b1", "de_DE-thorsten-high", store.bookVoice("b1"))
            assertEquals("b2 keeps its own override", "en_US-lessac-medium", store.bookVoice("b2"))
            assertEquals("a third book has no override", null, store.bookVoice("b3"))
            assertEquals("the global default is untouched", "af_heart", store.voice())

            store.setVoice("af_bella")
            assertEquals(
                "changing the global default neither clears nor rewrites overrides",
                "de_DE-thorsten-high",
                store.bookVoice("b1"),
            )
        }

    @Test
    fun `blank values are no override`() =
        runTest {
            val store = SettingsStore(database.settingsDao())
            database.settingsDao().put(SettingEntity(SettingsStore.bookVoiceKey("b1"), "  "))

            assertNull(store.bookVoice("b1"))
            assertEquals(emptyMap<String, String>(), store.bookVoices())
        }

    @Test
    fun `reading, engine and pausedAt keys round-trip, reject malformed, and mirror`() =
        runTest {
            val store = SettingsStore(database.settingsDao())
            val settings = AppSettings(store)

            store.setBookReading("b1", 3, 7)
            store.setBookEngine("b1", "piper-v1")
            store.setBookPausedAt("b1", 1_700_000_000_000L)
            assertEquals(3 to 7, store.bookReading("b1"))
            assertEquals("piper-v1", store.bookEngine("b1"))
            assertEquals(1_700_000_000_000L, store.bookPausedAt("b1"))

            // Per-book isolation.
            assertNull(store.bookReading("b2"))
            assertNull(store.bookEngine("b2"))
            assertNull(store.bookPausedAt("b2"))

            // Blank / malformed values are treated as absent.
            database.settingsDao().put(SettingEntity(SettingsStore.bookReadingKey("b3"), "not-a-pair"))
            database.settingsDao().put(SettingEntity(SettingsStore.bookEngineKey("b3"), "  "))
            database.settingsDao().put(SettingEntity(SettingsStore.bookPausedAtKey("b3"), "soon"))
            assertNull(store.bookReading("b3"))
            assertNull(store.bookEngine("b3"))
            assertNull(store.bookPausedAt("b3"))
            assertEquals(mapOf("b1" to (3 to 7)), store.bookReadings())
            assertEquals(mapOf("b1" to "piper-v1"), store.bookEngines())
            assertEquals(mapOf("b1" to 1_700_000_000_000L), store.bookPausedAts())

            // Mirror + cold reload.
            settings.reload()
            assertEquals(3 to 7, settings.bookReading("b1"))
            assertEquals("piper-v1", settings.bookEngine("b1"))
            assertEquals(1_700_000_000_000L, settings.bookPausedAt("b1"))

            settings.setBookReading("b1", null, null)
            settings.setBookEngine("b1", null)
            settings.setBookPausedAt("b1", null)
            assertNull(settings.bookReading("b1"))
            assertNull(settings.bookEngine("b1"))
            assertNull(settings.bookPausedAt("b1"))
            assertNull(store.bookReading("b1"))
            assertNull(store.bookEngine("b1"))
            assertNull(store.bookPausedAt("b1"))
        }

    @Test
    fun `the override rides the backup archive and reattaches on restore`() =
        runTest {
            // Source device: a global voice and one book's override.
            val source = SettingsStore(database.settingsDao())
            source.setVoice("af_heart")
            source.setBookVoice("b1", "de_DE-thorsten-high")

            val archive =
                BackupCodec.write(
                    BackupStore(
                        database = database,
                        appVersion = "0.1.0",
                        bookFileStore = null,
                        now = { 1_000L },
                    ).snapshot(includeBooks = false),
                )

            // Target device: a fresh database with a different global voice.
            database.close()
            database = freshDatabase()
            val target = SettingsStore(database.settingsDao())
            target.setVoice("af_bella")

            val read = BackupCodec.read(archive)
            check(read is BackupReadResult.Ok) { "archive read failed: $read" }
            BackupStore(
                database = database,
                appVersion = "0.1.0",
                bookFileStore = null,
                now = { 2_000L },
            ).merge(read.snapshot)

            assertEquals("restored keys overwrite local (global voice)", "af_heart", target.voice())
            assertEquals(
                "the override reattaches to the content-hash book id",
                "de_DE-thorsten-high",
                target.bookVoice("b1"),
            )
        }
}
