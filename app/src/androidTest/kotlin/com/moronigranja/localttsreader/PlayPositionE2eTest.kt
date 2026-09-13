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
 * S3 device verification: ACTION_PLAY_POSITION (the seam the share "Listen
 * here" and the reader gesture both drive) must land the playhead on a
 * mid-book passage, play through, and complete — proving "open book at
 * passage → player starts there" end to end in the real service.
 *
 * Requires staged packs + espeak bundle (build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class PlayPositionE2eTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "position-e2e-book",
        title = "Position E2E",
        chapters = listOf(
            Chapter(
                0,
                "One",
                listOf(
                    TextPassage(
                        "The gate stood open at the far end of the field. " +
                            "Cold light spread across the morning grass and the path. " +
                            "She counted the fence posts along the track to the barn. " +
                            "The wind carried the sound of water from the lower meadow. " +
                            "They found the key beneath the loose stone by the steps. " +
                            "It took the whole hour to walk the edge of the wood.",
                    ),
                ),
            ),
            Chapter(
                1,
                "Two",
                listOf(
                    TextPassage("The bridge crossed the narrow stream behind the house."),
                    TextPassage("Beyond the hill the road turned north toward the gate."),
                    TextPassage("This is the final sentence of the position test book."),
                ),
            ),
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
    fun playsFromAnExplicitPassageAndCompletes() {
        PlaybackStateHolder.reset()
        // Chapter 1 (index 1), passage 0 — NOT the book start.
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY_POSITION)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
                .putExtra(PlaybackService.EXTRA_CHAPTER, 1)
                .putExtra(PlaybackService.EXTRA_PASSAGE, 0),
        )

        var sawTargetPassage = false
        var sawPlaying = false
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) sawPlaying = true
            if (state.phase == PlayerPhase.PLAYING && state.chapterIndex == 1 && state.passageIndex == 0) {
                sawTargetPassage = true
            }
            if (state.phase == PlayerPhase.COMPLETED) {
                assertTrue("playback ran", sawPlaying)
                assertTrue("playhead landed on the requested passage 1/0 (saw PLAYING at 1/0)", sawTargetPassage)
                assertEquals("completes at the book's last passage", 1, state.chapterIndex)
                assertEquals("completes at passage 2", 2, state.passageIndex)
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }
}
