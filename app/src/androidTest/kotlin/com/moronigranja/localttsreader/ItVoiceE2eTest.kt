package com.moronigranja.localttsreader

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.MIGRATION_1_2
import com.moronigranja.localttsreader.persistence.MIGRATION_2_3
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * it-IT spot test on-device (user request, 2026-08-27): select the Italian
 * voice if_sara, play an Italian passage through the real engine, complete
 * the book. The per-passage `voice=if_sara` logcat lines are the direct
 * evidence that the Italian voice reached the synthesizer.
 *
 * Requires staged packs + espeak bundle (build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class ItVoiceE2eTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "it-e2e-book",
        title = "Libro in Italiano",
        chapters = listOf(
            Chapter(
                0,
                "Il Primo Capitolo",
                listOf(
                    TextPassage(
                        "Trentatré trentini entrarono a Trento, tutti e trenta trotterellando. " +
                            "Il fiume scorre veloce tra le pietre del vecchio ponte. " +
                            "La casa aveva una grande finestra sulla valle. " +
                            "Ogni mattina il pescatore tornava prima del tramonto. " +
                            "I bambini giocavano nel giardino della scuola durante la mattina. " +
                            "Il vento forte scuoteva le foglie degli alberi alti.",
                    ),
                ),
            ),
            Chapter(1, "La Fine", listOf(TextPassage("Questa è l'ultima frase del libro di prova."))),
        ),
    )

    @Before
    fun setUp() = runBlocking {
        database = Room.databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        store = RoomLibraryStore(database, scope)
        store.add(LibraryEntry(book, importedAtEpochMillis = 1L))
        database.settingsDao().put(
            SettingEntity(SettingsStore.KEY_VOICE, "if_sara"),
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
    fun itVoicePlaysTheBookThrough() {
        PlaybackStateHolder.reset()
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )

        var sawPlaying = false
        val deadline = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) sawPlaying = true
            if (state.phase == PlayerPhase.COMPLETED) {
                assertTrue("playback ran", sawPlaying)
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }
}