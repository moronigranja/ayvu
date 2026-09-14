package com.moronigranja.localttsreader.featureplayer.playback

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.model.CachedPassage
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.ProgressEntity
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.pregen.PregenBudget
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Worker-level regression: the manual whole-book path (no budget) must
 * actually synthesize instead of silently succeeding, a finite listening-time
 * budget must bound how much NEW audio is produced (and anchor the run at the
 * reading position), and engine failure terminals must fail the job with a
 * typed error rather than collapse into an indistinguishable success.
 *
 * Runs the real [PregenWorker] (Robolectric: in-memory Room store/settings,
 * real [PregenCache] under the test app's filesDir, a faked [TTSEngine]
 * through the [KokoroRuntime] override seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PregenWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var settings: AppSettings
    private lateinit var cache: PregenCache
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "t42-worker-test-book",
            title = "Worker Test",
            chapters =
                listOf(
                    Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1"))),
                    Chapter(1, "Two", listOf(TextPassage("p2"), TextPassage("p3"), TextPassage("p4"))),
                ),
        )

    /** Fake engine: records every request; outcome scriptable per test. */
    private class FakeEngine(
        var outcome: (String) -> SynthesisOutcome = {
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
        },
        /** Per-text hook (e.g. to engage [PlaybackActive] mid-run). */
        var onText: (String) -> Unit = {},
    ) : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        val synthesized = mutableListOf<String>()

        suspend fun synthesizeText(text: String): SynthesisOutcome {
            synthesized += text
            onText(text)
            return outcome(text)
        }

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome = synthesizeText(request.text)
    }

    /** KokoroRuntime is open solely so host tests inject a fake engine. */
    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
        private val engine: TTSEngine?,
        override val failureReason: String? = null,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine
    }

    private fun worker(
        input: Data,
        engine: TTSEngine?,
    ): PregenWorker {
        val runtime = FakeRuntime(context, settings, engine)
        // C1.5: the worker consumes the engine through the selector seam
        // (kokoro default — the system engine is never realized in tests).
        val selector =
            EngineSelector(
                runtime,
                PiperRuntime(context, settings),
                TranslateRuntime(context, settings),
                object : dagger.Lazy<TTSEngine> {
                    override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
                },
                settings,
            )
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = PregenWorker(appContext, workerParameters, selector, store, settings, database.progressDao(), cache)
            }
        return TestListenableWorkerBuilder<PregenWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(input)
            .build()
    }

    private fun manualInput(vararg ids: String) =
        workDataOf(
            PregenWorker.KEY_MODE to PregenWorker.MODE_MANUAL,
            PregenWorker.KEY_BOOK_IDS to ids,
        )

    private fun toCached(book: Book): CachedBook =
        CachedBook(
            id = book.id,
            title = book.title,
            passages =
                book.chapters.flatMap { ch ->
                    ch.passages.mapIndexed { i, p -> CachedPassage(ch.index, ch.title, i, p.text) }
                },
        )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomLibraryStore(database, scope)
        settings = AppSettings(SettingsStore(database.settingsDao()))
        cache = PregenCache(context)
        runBlocking { store.add(LibraryEntry(book, importedAtEpochMillis = 1L)) }
    }

    @After
    fun tearDown() {
        database.close()
        PlaybackActive.markStopped() // the G2 tests drive the session flag
        PlaybackActive.markEngineStopped()
    }

    @Test
    fun `whole-book manual input without a budget synthesizes and caches`() =
        runBlocking {
            val engine = FakeEngine()
            val result = worker(manualInput(book.id), engine).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), engine.synthesized)
            assertTrue(
                "first passage written to the tier",
                cache.cache.contains(PregenKey(book.id, 0, 0, "af_heart", 1.0)),
            )
            assertTrue(
                "whole book reached the tier",
                book.chapters.all { ch ->
                    ch.passages.indices.all { p ->
                        cache.cache.contains(PregenKey(book.id, ch.index, p, "af_heart", 1.0))
                    }
                },
            )
        }

    @Test
    fun `a finite listening budget bounds how much is synthesized`() =
        runBlocking {
            val engine = FakeEngine()
            val w = worker(manualInput(book.id), engine)
            val result =
                w.runBooks(
                    books = listOf(toCached(book)),
                    // Each fake passage is 1000 bytes ≈ 20.8 ms; two exceed 30 ms.
                    budget = PregenBudget(maxSeconds = 0.03),
                    voice = "af_heart",
                    speed = 1.0,
                )
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(
                "stops once the listening budget is met, before the whole book",
                listOf("p0", "p1"),
                engine.synthesized,
            )
        }

    @Test
    fun `an unbounded budget runs the whole book`() =
        runBlocking {
            val engine = FakeEngine()
            val w = worker(manualInput(book.id), engine)
            val result =
                w.runBooks(
                    books = listOf(toCached(book)),
                    budget = PregenBudget(),
                    voice = "af_heart",
                    speed = 1.0,
                )
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("unbounded runs until the book is done", 5, engine.synthesized.size)
        }

    @Test
    fun `a bounded budget anchors the run at the persisted reading position`() =
        runBlocking {
            // Resume row at chapter 0 / passage 1: the "next N minutes" run must
            // synthesize from there (p1 onward), not re-walk the already-read p0.
            database.progressDao().upsert(
                ProgressEntity(book.id, chapterIndex = 0, passageIndex = 1, updatedAtEpochMillis = 1),
            )
            val engine = FakeEngine()
            val w = worker(manualInput(book.id), engine)
            val result =
                w.runBooks(
                    books = listOf(toCached(book)),
                    // Far beyond this 5-passage book: the budget only marks the run
                    // bounded (anchored), it never caps it here.
                    budget = PregenBudget(maxSeconds = 1_000.0),
                    voice = "af_heart",
                    speed = 1.0,
                )
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(
                "synthesizes from the reading position forward, skipping the already-read p0",
                listOf("p1", "p2", "p3", "p4"),
                engine.synthesized,
            )
        }

    // ------------------------------------------------------------------
    // Conditional yield (item 5) — manual runs pause only while playback
    // HOLDS the shared engine ([PlaybackActive.engineInUse]); a cache-fed
    // session leaves it free and the run proceeds.
    // ------------------------------------------------------------------

    /** With the engine held by playback (a cold seek's fill or buffer
     * synthesis), the run WAITS before its first book instead of aborting —
     * the old G2 blanket session yield (break + run over) is superseded. */
    @Test
    fun `manual pregen waits while playback holds the engine and resumes after release`() =
        runBlocking {
            val engine = FakeEngine()
            val w = worker(manualInput(book.id), engine)
            PlaybackActive.markEngineUsed()
            val released = CompletableDeferred<Unit>()
            val holder =
                scope.launch {
                    delay(300) // hold the engine briefly, then free it
                    PlaybackActive.markEngineStopped()
                    released.complete(Unit)
                }
            try {
                val result =
                    w.runBooks(
                        books = listOf(toCached(book)),
                        budget = PregenBudget(), // unbounded — only the engine can hold it
                        voice = "af_heart",
                        speed = 1.0,
                    )
                assertTrue("the run must wait for the engine, not abort", released.isCompleted)
                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals("once the engine frees, the whole book runs", 5, engine.synthesized.size)
            } finally {
                PlaybackActive.markEngineStopped()
                holder.cancel()
            }
        }

    /** Playback grabbing the engine MID-run yields at the next passage
     * boundary (Yielded is a safely bounded terminal, CR-1); the run then
     * waits and RESUMES with the next book — it never competes with playback
     * for the shared engine, and it never aborts the run. */
    @Test
    fun `manual pregen yields at a boundary while playback holds the engine, then resumes`() =
        runBlocking {
            val engine =
                FakeEngine(onText = { text ->
                    if (text == "p0") PlaybackActive.markEngineUsed() // playback grabs the engine mid-first-passage
                })
            val book2 = Book(id = "t42-worker-book2", title = "Two", chapters = listOf(Chapter(0, "A", listOf(TextPassage("q0")))))
            val w = worker(manualInput(book.id), engine)
            val released = CompletableDeferred<Unit>()
            val holder =
                scope.launch {
                    delay(300) // release before the worker's next 1 s check
                    PlaybackActive.markEngineStopped()
                    released.complete(Unit)
                }
            try {
                val result =
                    w.runBooks(
                        books = listOf(toCached(book), toCached(book2)),
                        budget = PregenBudget(),
                        voice = "af_heart",
                        speed = 1.0,
                    )
                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals(
                    "p0 runs, p1 yields to playback, the next book resumes after release",
                    listOf("p0", "q0"),
                    engine.synthesized,
                )
            } finally {
                PlaybackActive.markEngineStopped()
                holder.cancel()
            }
        }

    /** The in-place notification refresh is throttled to ~1 s: a progress
     * event inside the window must NOT re-notify; one past the window
     * replaces the [PregenWorker.NOTIFICATION_ID] entry in place (the same
     * ID, so the notification list never grows). */
    @Test
    fun `notification refresh is throttled to about a second`() =
        runBlocking {
            val w = worker(manualInput(book.id), FakeEngine())
            val manager = context.getSystemService(NotificationManager::class.java) as NotificationManager

            // percent = processed*100/totalPassages — synthesize+cache both count.
            fun progress(pct: Int) =
                com.moronigranja.localttsreader.player.pregen.PregenProgress(
                    chaptersDone = 1,
                    passagesSynthesized = pct,
                    passagesCached = pct,
                    failures = 0,
                    totalChapters = 2,
                    totalPassages = 200,
                )
            var now = 1_000L
            w.refreshNotification("T", progress(10), clock = { now })
            val first =
                shadowOf(manager)
                    .activeNotifications
                    .single()
                    .notification.extras
                    .getString(android.app.Notification.EXTRA_TEXT)
            now += 500 // inside the 1 s window
            w.refreshNotification("T", progress(20), clock = { now })
            assertEquals(
                "an in-window refresh must not re-notify",
                first,
                shadowOf(manager)
                    .activeNotifications
                    .single()
                    .notification.extras
                    .getString(android.app.Notification.EXTRA_TEXT),
            )
            now += 1_000 // past the window
            w.refreshNotification("T", progress(30), clock = { now })
            val notifications = shadowOf(manager).activeNotifications
            assertEquals("same ID replaces in place — the list never grows", 1, notifications.size)
            assertTrue(
                "the replaced notification carries the newer percent",
                notifications
                    .single()
                    .notification.extras
                    .getString(android.app.Notification.EXTRA_TEXT)!!
                    .contains("(30%)"),
            )
        }

    /** #136: a bounded run's percent is slice-relative — 15 min of a 30-min
     * ask reads 50%, not ~0% of a 5,000-passage book. The notification is the
     * surface the user watches, so it must carry the slice percent. */
    @Test
    fun `bounded run notification percent is slice-relative`() =
        runBlocking {
            val w = worker(manualInput(book.id), FakeEngine())
            val manager = context.getSystemService(NotificationManager::class.java) as NotificationManager
            w.refreshNotification(
                "T",
                com.moronigranja.localttsreader.player.pregen.PregenProgress(
                    chaptersDone = 1,
                    totalChapters = 2,
                    totalPassages = 5_000,
                    synthesizedSeconds = 15.0 * 60.0,
                    budgetSeconds = 30.0 * 60.0,
                ),
                clock = { 1_000L },
            )
            val text =
                shadowOf(manager)
                    .activeNotifications
                    .single()
                    .notification.extras
                    .getString(android.app.Notification.EXTRA_TEXT)
            assertTrue("the body reads the slice percent, not the book denominator: $text", text!!.contains("(50%)"))
        }

    @Test
    fun `Unavailable settles as a failed job with a typed error`() =
        runBlocking {
            val engine = FakeEngine(outcome = { SynthesisOutcome.Unavailable })
            val result = worker(manualInput(book.id), engine).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            val output = (result as ListenableWorker.Result.Failure).outputData
            val error = output.getString(PregenWorker.KEY_ERROR)
            assertTrue("error names the cause: $error", error != null && "unavailable" in error.lowercase())
        }

    @Test
    fun `synthesis meltdown settles as a failed job`() =
        runBlocking {
            val engine = FakeEngine(outcome = { SynthesisOutcome.Failed("boom") })
            val result = worker(manualInput(book.id), engine).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            val output = (result as ListenableWorker.Result.Failure).outputData
            val error = output.getString(PregenWorker.KEY_ERROR)
            assertTrue("error names the meltdown: $error", error != null && "repeated synthesis failures" in error)
            assertEquals("no audio survived the meltdown", 0, output.getInt(PregenWorker.KEY_PROGRESS_SYNTHESIZED, 1))
        }

    @Test
    fun `missing engine fails before any work`() =
        runBlocking {
            val result = worker(manualInput(book.id), null).doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun `requested books absent from the library fail instead of a false success`() =
        runBlocking {
            val engine = FakeEngine()
            val result = worker(manualInput("not-in-the-library"), engine).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            val output = (result as ListenableWorker.Result.Failure).outputData
            val error = output.getString(PregenWorker.KEY_ERROR)
            assertTrue("error names the missing books: $error", error != null && "library" in error.lowercase())
            assertTrue("nothing was synthesized", engine.synthesized.isEmpty())
        }
}
