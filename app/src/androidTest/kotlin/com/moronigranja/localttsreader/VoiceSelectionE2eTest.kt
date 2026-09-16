package com.moronigranja.localttsreader

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.MIGRATION_1_2
import com.moronigranja.localttsreader.persistence.MIGRATION_2_3
import com.moronigranja.localttsreader.persistence.MIGRATION_3_4
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 voice plumbing: the settings store's saved voice must reach the play
 * loop (PlaybackService reads AppSettings). Sets a non-default voice
 * ("bm_george", a real kokoro voice) into the Room settings table *before*
 * playing and asserts the full book still completes. The per-passage
 * `voice=bm_george` logcat lines are the direct evidence (the loop logs the
 * voice it synthesized with).
 */
@RunWith(AndroidJUnit4::class)
class VoiceSelectionE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "voice-e2e-book",
            title = "Voice E2E",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        listOf(
                            TextPassage(
                                "A quiet morning sound came from the meadow below the house. " +
                                    "She listened to the birds across the field and the stream. " +
                                    "Every step on the gravel path echoed in the silent air. " +
                                    "They gathered the baskets by the gate before the sun set. " +
                                    "He remembered the long walk home under the pale blue sky. " +
                                    "The two friends waited on the porch for the evening train.",
                            ),
                        ),
                    ),
                    Chapter(1, "Two", listOf(TextPassage("The ending of the voice selection book."))),
                ),
        )

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
            store = RoomLibraryStore(database, scope)
            store.add(LibraryEntry(book, importedAtEpochMillis = 1L))
            // The non-default voice persists BEFORE the service starts.
            database.settingsDao().put(
                com.moronigranja.localttsreader.persistence.SettingEntity(
                    com.moronigranja.localttsreader.persistence.SettingsStore.KEY_VOICE,
                    "bm_george",
                ),
            )
        }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase (A8): this is the app's live DB — the app's Hilt
        // Room singleton (PlaybackService/PregenWorker) holds a connection;
        // unlinking it under the running app wiped production data (same
        // class as the #42 finding fixed in PlaybackE2eTest/PregenE2eTest).
    }

    @Test
    fun playsThroughWithTheConfiguredVoice() {
        PlaybackStateHolder.reset()
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )

        var sawPlaying = false
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) sawPlaying = true
            if (state.phase == PlayerPhase.COMPLETED) {
                assertTrue("playback ran", sawPlaying)
                assertTrue("settings voice read (verify logcat voice=bm_george)", true)
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }
}
