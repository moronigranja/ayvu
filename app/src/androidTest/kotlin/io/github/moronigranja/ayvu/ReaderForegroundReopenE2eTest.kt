package io.github.moronigranja.ayvu

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
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
 * Bug (owner report 2026-10-11): the reader can strand on "Opening book…"
 * after a screen off/on. Its only open trigger re-fires when the service-
 * published `state.bookId` CHANGES; an open whose publish was DROPPED leaves
 * the holder unchanged, so nothing re-dispatched on the foreground return and
 * the bounded `scheduleOpenRetry` (4 s ×2) gave up.
 * [io.github.moronigranja.ayvu.featureplayer.ui.ReaderScreen] now re-dispatches
 * on [Lifecycle.Event.ON_START] whenever the holder does not carry this
 * reader's book.
 *
 * The test reproduces the exact stranded state — a dropped publish with the
 * holder unchanged — by DISABLING the [PlaybackService] component (the test
 * runs in the app's own process, so it may change its own component state):
 * the open dispatch at mount (and both retries) become silent no-ops that
 * publish nothing and set no failure, so the holder stays at its process
 * default and the drift effect's key never changes. After the retry window is
 * exhausted the service is re-enabled and the activity resumed: only the
 * ON_START re-dispatch can recover it. NEUTERING the ON_START effect makes this
 * test FAIL on the S22 (the reader stays on the loading state).
 *
 * Requires staged packs + espeak bundle (build.md), media volume 0.
 */
@RunWith(AndroidJUnit4::class)
class ReaderForegroundReopenE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val serviceComponent by lazy { ComponentName(context, PlaybackService::class.java) }

    private val book =
        Book(
            id = "foreground-reopen-e2e-book",
            title = "Foreground Reopen E2E",
            chapters =
                (0 until 2).map { chapter ->
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
        setServiceEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase (A8): this is the app's live DB — the app's Hilt
        // Room singleton (PlaybackService/PregenWorker) holds a connection;
        // unlinking it under the running app wiped production data.
    }

    /** Enables/disables the playback service component in-process.
     * DONT_KILL_APP keeps this test's process (and the instrumentation) alive. */
    private fun setServiceEnabled(state: Int) {
        context.packageManager.setComponentEnabledSetting(
            serviceComponent,
            state,
            PackageManager.DONT_KILL_APP,
        )
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

    /** The reader is on the book's text — the book is published AND the
     * chapter's passages are present (the reader's "Loading book…" branch
     * renders exactly while `chapterPassages` is empty). */
    private fun readerShowsText(): Boolean = PlaybackStateHolder.state.value.let { it.bookId == book.id && it.chapterPassages.isNotEmpty() }

    @Test
    fun returningToTheForegroundReopensAfterADroppedPublish() {
        PlaybackStateHolder.reset()
        context.stopService(Intent(context, PlaybackService::class.java))
        // The open dispatch (mount + both 4 s retries) publishes NOTHING while
        // the component is disabled, and sets no failure — the exact stranded
        // state the report describes.
        setServiceEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        try {
            val intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(OpenTarget.EXTRA_BOOK_ID, book.id)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // Outlast the bounded retry window (4 s ×2) so no scheduled
                // retry can recover the open once the service is re-enabled.
                Thread.sleep(11_000)
                assertTrue(
                    "the dropped-publish open must not have landed",
                    !readerShowsText(),
                )

                // The stopped state a screen-off leaves, then re-enable the
                // service (the retry window is spent) and return to the
                // foreground.
                scenario.moveToState(Lifecycle.State.CREATED)
                setServiceEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                scenario.moveToState(Lifecycle.State.RESUMED)

                await("the reader re-opens on the foreground return", budgetMs = 15_000) { readerShowsText() }
                assertNull("no failure may be published", PlaybackStateHolder.state.value.failure)
                assertEquals("the reader stays on the book", book.id, PlaybackStateHolder.state.value.bookId)
            }
        } finally {
            setServiceEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        }
    }
}
