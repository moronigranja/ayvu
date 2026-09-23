package io.github.moronigranja.ayvu

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureplayer.playback.PlaybackService
import io.github.moronigranja.ayvu.featureshare.OpenTarget
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.MIGRATION_1_2
import io.github.moronigranja.ayvu.persistence.MIGRATION_2_3
import io.github.moronigranja.ayvu.persistence.MIGRATION_3_4
import io.github.moronigranja.ayvu.persistence.RoomLibraryStore
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bug 1 device verification (owner report 2026-09-22): a configuration change
 * (rotation) recreates MainActivity while the task's own intent still carries
 * the share/identify OpenTarget extras. Before the fix, [MainActivity.onCreate]
 * re-consumed those extras unconditionally, re-arming the reader's start
 * target, so every rotation re-dispatched ACTION_PLAY_POSITION and restarted
 * audio at the share target with no press. The fix consumes the target on a
 * fresh launch only (and the reader clears it after the one-shot dispatch).
 *
 * Requires staged packs + espeak bundle (build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class ReaderRotationReplayE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "rotation-replay-e2e-book",
            title = "Rotation Replay E2E",
            chapters =
                (0 until 3).map { chapter ->
                    Chapter(
                        chapter,
                        "Chapter ${chapter + 1}",
                        (0 until 4).map { passage ->
                            TextPassage(
                                "The gate stood open at the far end of the field. " +
                                    "Cold light spread across the morning grass and the path. " +
                                    "She counted the fence posts along the track to the barn. " +
                                    "The wind carried the sound of water from the lower meadow. " +
                                    "They found the key beneath the loose stone by the steps. " +
                                    "C${chapter + 1}P${passage + 1}.",
                            )
                        },
                    )
                },
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
            RoomLibraryStore(database, scope).add(LibraryEntry(book, importedAtEpochMillis = 1L))
        }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase (A8): this is the app's live DB — the app's Hilt
        // Room singleton (PlaybackService/PregenWorker) holds a connection;
        // unlinking it under the running app wiped production data.
    }

    /** Polls until the condition holds (async service commands), failing with
     * [message] after the budget. */
    private fun await(
        message: String,
        budgetMs: Long = 60_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        assertTrue(message, condition())
    }

    @Test
    fun rotatingTheReaderDoesNotReplayTheShareTarget() {
        PlaybackStateHolder.reset()
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(OpenTarget.EXTRA_BOOK_ID, book.id)
                .putExtra(OpenTarget.EXTRA_CHAPTER, 1)
                .putExtra(OpenTarget.EXTRA_PASSAGE, 1)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // The share target plays on entry (the reported trigger).
            await("the share target plays") {
                PlaybackStateHolder.state.value.let {
                    it.bookId == book.id && it.chapterIndex == 1 && it.passageIndex == 1 &&
                        (it.phase == PlayerPhase.LOADING || it.phase == PlayerPhase.PLAYING)
                }
            }
            // Pause so the recreation's assertion is exact (no advancing playhead).
            context.startForegroundService(
                Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_PAUSE),
            )
            await("paused") { PlaybackStateHolder.state.value.phase == PlayerPhase.PAUSED }
            val before = PlaybackStateHolder.state.value

            scenario.recreate()
            Thread.sleep(3_000) // let any wrongly re-dispatched command land

            val after = PlaybackStateHolder.state.value
            assertEquals("the reader stays on the book", book.id, after.bookId)
            assertEquals("rotation must not restart the share target", PlayerPhase.PAUSED, after.phase)
            assertEquals("the playhead must not move", before.chapterIndex, after.chapterIndex)
            assertEquals("the playhead must not move", before.passageIndex, after.passageIndex)
            assertNull("no failure may be published", after.failure)
        }
    }
}
