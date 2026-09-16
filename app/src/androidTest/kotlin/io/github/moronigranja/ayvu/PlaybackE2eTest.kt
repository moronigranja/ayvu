package io.github.moronigranja.ayvu

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureplayer.playback.PlaybackService
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.MIGRATION_1_2
import io.github.moronigranja.ayvu.persistence.MIGRATION_2_3
import io.github.moronigranja.ayvu.persistence.MIGRATION_3_4
import io.github.moronigranja.ayvu.persistence.RoomLibraryStore
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end T4-2: the real service + engine + AudioTrack over a two-passage
 * book — machine transitions to COMPLETED, segments arrive, and the resume
 * row lands on the last passage, all in the real app process.
 *
 * Requires staged packs + espeak bundle in the app files dir (build.md), and
 * media volume 0 (`adb shell media volume --stream 3 --set 0`) so the spike
 * runs silently.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "t4-e2e-book",
            title = "E2E Test Book",
            chapters =
                listOf(
                    // Passage 1 is deliberately long (~15 s): the pre-generation queue
                    // (T5) synthesizes passage 2 while it plays — even on a throttled,
                    // locked-screen device a 1-passage head start finishes in time, so
                    // passage 2 MUST come out of the queue, not the synthesizer.
                    Chapter(
                        0,
                        "One",
                        listOf(
                            TextPassage(
                                "The hikers met at dawn by the old stone bridge. " +
                                    "Mist lay over the river and the town was quiet. " +
                                    "They packed the map, the thermos, and the climbing rope. " +
                                    "The trail rose gently through the pines for an hour. " +
                                    "Listen to the wind in the high branches and the birds. " +
                                    "It takes patience to reach the ridge before noon.",
                            ),
                        ),
                    ),
                    Chapter(1, "Two", listOf(TextPassage("And this is the very last passage of the test book. Goodbye."))),
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
            // Pin the ENGINE and voice: this class asserts Kokoro's sentence
            // anchors, and the engine resolves from the persisted setting, so a
            // device left on Piper (`segments = null` by design, #30b) failed it
            // for a reason that had nothing to do with the code under test
            // (S22 2026-09-15). Same idiom as EsVoiceE2eTest's KEY_VOICE write.
            database.settingsDao().put(SettingEntity(SettingsStore.KEY_TTS_ENGINE, SettingsStore.DEFAULT_TTS_ENGINE))
            database.settingsDao().put(SettingEntity(SettingsStore.KEY_VOICE, SettingsStore.DEFAULT_VOICE))
        }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase: this file is the app's live DB — the app's Hilt
        // Room singleton (used by PlaybackService/PregenWorker) keeps a
        // connection to it; unlinking the file mid-process breaks every
        // later test in the same instrumentation run (#42 device pass).
    }

    @Test
    fun playsThroughTheBookAndCompletes() {
        PlaybackStateHolder.reset()
        // Rerun hygiene: a previous run's resume row parks the playhead at the
        // book's ending, so ACTION_PLAY would slice ~0 frames and "complete"
        // instantly. Delete it so every run starts at 0/0 with real audio.
        runBlocking { database.progressDao().delete(book.id) }
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )

        // Wait for the full run: LOADING → PLAYING → … → COMPLETED.
        var sawPlaying = false
        var sawSegments = false
        // The cold open cannot reach PREFILL_LOOKAHEAD_SECONDS (45 s) on an
        // ~18 s fixture book, so the first passage pays the full
        // PLAY_BUFFER_TIMEOUT_MS (60 s) buffer wait before playing; the whole
        // run is 60 s burn + engine open + ~18 s playback. 90 s sat on the
        // edge; 180 s covers the burn plus real-time headroom.
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.LOADING || state.phase == PlayerPhase.PLAYING) sawPlaying = true
            if (state.segments.isNotEmpty()) sawSegments = true
            if (state.phase == PlayerPhase.COMPLETED) {
                assertEquals("book id propagated", book.id, state.bookId)
                // The book's last passage is chapter 1 / passage 0 (chapter 2 has one passage).
                assertEquals("last chapter reached", 1, state.chapterIndex)
                assertEquals("last passage reached", 0, state.passageIndex)
                assertTrue("read-along anchors surfaced somewhere", sawSegments)
                assertTrue("playback actually ran (saw LOADING/PLAYING)", sawPlaying)
                // The resume row lands on the ending (decisions #33).
                val progress = runBlocking { database.progressDao().get(book.id) }
                assertEquals(1, progress?.chapterIndex)
                assertEquals(0, progress?.passageIndex)
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }
}
