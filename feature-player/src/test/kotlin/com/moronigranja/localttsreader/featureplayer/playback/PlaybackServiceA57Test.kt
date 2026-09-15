package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.session.MediaSessionCompat
import androidx.room.Room
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.BookLayout
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerStateMachine
import com.moronigranja.localttsreader.player.PlayerStore
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
import kotlinx.coroutines.runBlocking
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
import kotlin.math.abs

/**
 * CR-5/CR-7 service-edge regression (roadmap A5+A7): the single-writer
 * command model — a superseded command can never publish state or restart the
 * play loop after a newer command won; pause during first-audio generation
 * (LOADING) settles every surface to PAUSED; navigation/seek never resume a
 * paused playhead.
 *
 * Drives the real [PlaybackService] (constructed directly; Hilt fields
 * assigned by hand) with a fake [TTSEngine] (synthesis calls are counted) and
 * the real [PlayerStateMachine] over [InMemoryPlayerStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceA57Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Three short passages: a 30 s seek crosses several passages at the
     * chars/15 speech model. */
    private val book =
        Book(
            id = "a57-book",
            title = "A57",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        listOf(
                            TextPassage("The gate stood open beside the barn door."),
                            TextPassage("Cold light spread across the morning field."),
                            TextPassage("She counted the fence posts along the track."),
                        ),
                    ),
                ),
        )

    private open class FakeEngine(
        var outcome: (String) -> SynthesisOutcome = {
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
        },
    ) : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        val synthesized = mutableListOf<String>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            synthesized += request.text
            return outcome(request.text)
        }
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
        private val engine: TTSEngine?,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine

        override val failureReason: String? = null
    }

    /** Degraded path unused in kokoro-default tests: the selector's system
     * engine is never realized when ttsEngine stays "kokoro-82m". */
    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
        }

    private class FakeOutput : PassageOutput {
        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) = Unit

        override fun stop() = Unit

        override val positionSamples: Int = 0

        override fun setVolume(multiplier: Float) = Unit
    }

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking { RoomLibraryStore(database, scope).add(LibraryEntry(book, importedAtEpochMillis = 1L)) }
    }

    @After
    fun tearDown() {
        database.close()
        PlaybackActive.markStopped() // the G2 session-window test drives the global flag
    }

    private fun playingMachine(
        store: PlayerStore,
        pauseAt: Double? = null,
    ): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking {
            machine.playFrom(PlayerPosition(book.id, 0, 0))
            pauseAt?.let { machine.pause(it) }
        }
        return machine
    }

    private fun service(
        store: PlayerStore,
        machine: PlayerStateMachine,
        engine: FakeEngine = FakeEngine(),
    ): PlaybackService =
        PlaybackService().apply {
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServiceA57Test.book
            this.output = FakeOutput()
            this.libraryStore = RoomLibraryStore(database, scope)
            this.settings = AppSettings(SettingsStore(database.settingsDao()))
            this.runtime = FakeRuntime(context, this.settings, engine)
            this.selector =
                EngineSelector(
                    this.runtime,
                    PiperRuntime(context, this.settings),
                    TranslateRuntime(context, this.settings),
                    onUnusedSystemTts,
                    this.settings,
                )
        }

    // ------------------------------------------------------------------
    // A7 — pause during first-audio generation settles every surface
    // ------------------------------------------------------------------

    /** CR-7: PAUSE while LOADING (synthesis in flight) must cancel the job and
     * publish PAUSED — the device-observed failure left MediaSession PLAYING
     * and the notification on "Pause". */
    @Test
    fun `pause during first-audio generation publishes PAUSED and never resumes`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store) // phase LOADING, position set
        val engine = FakeEngine()
        val service = service(store, machine, engine)
        PlaybackStateHolder.reset()

        service.pausePlayer(PlaybackService.PauseReason.USER)

        Thread.sleep(200) // let the pause command publish
        assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
        assertEquals(PlayerPhase.PAUSED, PlaybackStateHolder.state.value.phase)
        Thread.sleep(150)
        assertEquals(
            "no superseded publish may overwrite PAUSED",
            PlayerPhase.PAUSED,
            PlaybackStateHolder.state.value.phase,
        )
        assertTrue("nothing was synthesized after the pause", engine.synthesized.isEmpty())
    }

    /** CR-7: a stale publish-loop from a superseded command is dead — nothing
     * can republish state after stopEverything (the CR-5 unpublish class). */
    @Test
    fun `a superseded command loop is cancelled and stays dead`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store)
        val service = service(store, machine)
        PlaybackStateHolder.reset()

        // Simulates the device failure mode: an in-flight generation command
        // that keeps publishing PLAYING while the user paused. The first
        // action is a delay so the cancel deterministically lands before any
        // publish (no publish-before-cancel race on a fast machine).
        service.launchCommand {
            delay(1_000)
            while (true) {
                PlaybackStateHolder.update { it.copy(phase = PlayerPhase.PLAYING) }
                delay(5)
            }
        }
        service.stopEverything() // a newer command's first act
        Thread.sleep(100)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
        Thread.sleep(150)
        assertEquals("cancelled loop must stay dead", PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }

    // ------------------------------------------------------------------
    // A7 — navigation never resumes a paused playhead
    // ------------------------------------------------------------------

    /** CR-7: a ±30 s seek while genuinely paused repositions the playhead and
     * must NEVER start audio (the device evidence showed re-synthesis). */
    @Test
    fun `seek while paused repositions without resuming playback`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store, pauseAt = 2.0) // PAUSED at (0,0)+2s
        val engine = FakeEngine()
        val service = service(store, machine, engine)
        val before = runBlocking { store.readProgress(book.id) }!!

        service.seekBy(30.0)

        Thread.sleep(300) // let the seek command settle
        val after = runBlocking { store.readProgress(book.id) }!!
        assertTrue(
            "playhead moved",
            after.chapterIndex != before.chapterIndex ||
                after.passageIndex != before.passageIndex ||
                abs(after.offsetSeconds - before.offsetSeconds) > 1e-9,
        )
        assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
        assertEquals("paused seek must not synthesize", 0, engine.synthesized.size)
    }

    /** A7: skip-navigation while paused also repositions without resuming —
     * the same wasPaused preservation drives [PlaybackService.navigate]. */
    @Test
    fun `skip forward while paused repositions without resuming playback`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store, pauseAt = 0.0) // PAUSED at (0,0)
        val engine = FakeEngine()
        val service = service(store, machine, engine)

        service.navigate { it.skipForward() }

        Thread.sleep(300)
        val row = runBlocking { store.readProgress(book.id) }!!
        assertEquals("moved to the next passage", 1, row.passageIndex)
        assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
        assertTrue("paused skip must not synthesize", engine.synthesized.isEmpty())
    }

    // ------------------------------------------------------------------
    // A5 — superseded commands never win
    // ------------------------------------------------------------------

    /** CR-5: an OPEN superseded by a newer command's stopEverything must never
     * complete its load or publish. (Had the load finished before the cancel,
     * that is a genuine success, not a race.) */
    @Test
    fun `a superseded open never completes or publishes`() {
        val store = InMemoryPlayerStore()
        val service =
            PlaybackService().apply {
                this.store = store
                this.output = FakeOutput()
                this.libraryStore = RoomLibraryStore(database, scope)
                this.settings = AppSettings(SettingsStore(database.settingsDao()))
            }
        PlaybackStateHolder.reset()

        service.openBook(book.id)
        service.stopEverything() // exactly what a newer command does first (CR-5)
        Thread.sleep(300)

        // The contract: a superseded load never PUBLISHES (a mid-flight load
        // may already have touched the machine — the observable guarantee is
        // that its publish/foreground side effects were skipped).
        assertNull(
            "superseded load must not have published the book",
            PlaybackStateHolder.state.value.bookId,
        )
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }

    /** The launcher itself: commands are tracked so stopEverything can cancel
     * them (regression: open/openChapter/play launched UNTRACKED coroutines —
     * a later stopEverything had no handle on them; the unused declared load
     * job was removed in PR-0). */
    @Test
    fun `launched commands are cancelled by stopEverything`() {
        val store = InMemoryPlayerStore()
        val service = service(store, playingMachine(store))
        PlaybackStateHolder.reset()

        // A command that keeps publishing until cancelled — pre-fix this
        // coroutine had no handle at all. First action is a delay so the
        // cancel deterministically lands before any publish.
        service.launchCommand {
            delay(1_000)
            while (true) {
                PlaybackStateHolder.update { it.copy(phase = PlayerPhase.PLAYING) }
                delay(5)
            }
        }
        service.stopEverything()
        Thread.sleep(100)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }

    // ------------------------------------------------------------------
    // Chapter-boundary turns — the reader's side zones
    // ------------------------------------------------------------------

    /** The reader's left-zone tap at a chapter's first page must land on the
     * PREVIOUS chapter's LAST passage — its ending — the reverse of the
     * forward turn's landing on the neighbor's FIRST passage (was: passage 0
     * on both sides). Still IDLE (decisions #52: open ≠ auto-play), skipping
     * empty spine slots. */
    @Test
    fun `backward openChapter lands on the previous chapter's last passage`() {
        val turnBook =
            Book(
                id = "open-chapter-book",
                title = "Open Chapter",
                chapters =
                    listOf(
                        Chapter(
                            0,
                            "One",
                            listOf(
                                TextPassage("First chapter first passage."),
                                TextPassage("First chapter last passage."),
                            ),
                        ),
                        Chapter(1, "Empty", emptyList()), // skipped by BookLayout
                        Chapter(
                            2,
                            "Three",
                            listOf(
                                TextPassage("Third chapter first passage."),
                                TextPassage("Third chapter last passage."),
                            ),
                        ),
                    ),
            )
        runBlocking { RoomLibraryStore(database, scope).add(LibraryEntry(turnBook, importedAtEpochMillis = 1L)) }
        val store = InMemoryPlayerStore()
        val machine = PlayerStateMachine(store, BookLayout(turnBook))
        runBlocking { machine.present(PlayerPosition(turnBook.id, 2, 0)) }
        val service =
            PlaybackService().apply {
                this.store = store
                this.machine = machine
                this.book = turnBook
                this.output = FakeOutput()
                this.libraryStore = RoomLibraryStore(database, scope)
                this.settings = AppSettings(SettingsStore(database.settingsDao()))
                this.runtime = FakeRuntime(context, this.settings, null)
                this.selector =
                    EngineSelector(
                        this.runtime,
                        PiperRuntime(context, this.settings),
                        TranslateRuntime(context, this.settings),
                        onUnusedSystemTts,
                        this.settings,
                    )
            }
        PlaybackStateHolder.reset()

        service.openChapter(turnBook.id, -1)

        Thread.sleep(300)
        assertEquals("backward lands on the previous chapter's LAST passage", 1, PlaybackStateHolder.state.value.passageIndex)
        assertEquals(0, PlaybackStateHolder.state.value.chapterIndex)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)

        // Forward returns to the neighbor's opening passage, skipping the
        // empty spine slot — the unchanged half of the contract.
        service.openChapter(turnBook.id, +1)
        Thread.sleep(300)
        assertEquals(2, PlaybackStateHolder.state.value.chapterIndex)
        assertEquals("forward lands on the neighbor's FIRST passage", 0, PlaybackStateHolder.state.value.passageIndex)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }

    // ------------------------------------------------------------------
    // G2 — the session admission window (STOP → post-stop fill completion)
    // ------------------------------------------------------------------

    /** Engine that blocks every synthesis on a gate: the harness decides
     * exactly when the post-stop fill may complete, so the window-end timing
     * is deterministic (no real-time races). Each passage renders 45 s of
     * audio, so one synthesis satisfies the post-stop look-ahead target. */
    private class GatedSessionEngine : FakeEngine() {
        val release = CompletableDeferred<Unit>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            synthesized += request.text
            release.await()
            return SynthesisOutcome.Audio(
                ByteArray((45.0 * 24_000 * 2).toInt()),
                24_000,
                1,
                listOf(SegmentAnchor(0.0, 45.0)),
            )
        }
    }

    /** PublishGuard-style priming: Hilt's generated onCreate cannot run under
     * plain Robolectric, so a service whose openBook/publish paths run needs
     * base context + the session/audioManager lateinits assigned by hand. */
    private fun attachServiceContext(service: PlaybackService) {
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
    }

    private fun setAudioManager(service: PlaybackService) {
        val field = PlaybackService::class.java.getDeclaredField("audioManager")
        field.isAccessible = true
        field.set(service, context.getSystemService(Context.AUDIO_SERVICE))
    }

    private fun setSession(service: PlaybackService) {
        val field = PlaybackService::class.java.getDeclaredField("session")
        field.isAccessible = true
        field.set(service, MediaSessionCompat(service, "local-tts-reader"))
    }

    private fun mediaCallback(service: PlaybackService): MediaSessionCompat.Callback {
        val field = PlaybackService::class.java.getDeclaredField("mediaCallback")
        field.isAccessible = true
        return field.get(service) as MediaSessionCompat.Callback
    }

    private fun await(
        label: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $label")
    }

    /** G2 (addendum edge #1): the session admission window spans STOP → the
     * post-stop fill's completion. markStopped moved OUT of stopPlayer (a
     * yielding pregen worker would resume mid-fill and recreate the engine
     * contention the yield exists to prevent) and now fires when the fill
     * finishes. The gated engine makes the window-end deterministic. */
    @Test
    fun `session window stays engaged through STOP until the post-stop fill completes`() {
        val engine = GatedSessionEngine()
        val service =
            PlaybackService().apply {
                attachServiceContext(this)
                setAudioManager(this)
                setSession(this)
                this.store = InMemoryPlayerStore()
                this.output = FakeOutput()
                this.libraryStore = RoomLibraryStore(database, scope)
                this.settings = AppSettings(SettingsStore(database.settingsDao()))
                this.runtime = FakeRuntime(context, this.settings, engine)
                this.pregenCache = PregenCache(context)
                this.selector =
                    EngineSelector(
                        this.runtime,
                        PiperRuntime(context, this.settings),
                        TranslateRuntime(context, this.settings),
                        onUnusedSystemTts,
                        this.settings,
                    )
            }
        PlaybackStateHolder.reset()
        PlaybackActive.markStarted() // the start/resume command paths mark this
        try {
            // The post-stop fill needs the service's queue, which only the
            // playback command paths build — openBook (a real command) builds
            // it without starting audio.
            service.openBook(book.id)
            // openBook's publish is the happens-before edge: observing the
            // holder bookId means the command finished (machine + queue built).
            await("openBook command completes") { PlaybackStateHolder.state.value.bookId == book.id }

            // STOP through the media-session surface — the production
            // stopPlayer path (the pre-G2 code cleared PlaybackActive here).
            mediaCallback(service).onStop()

            assertTrue("the session window survives the STOP command", PlaybackActive.isActive)

            // The post-stop fill is in flight but gated — the window stays
            // open. Release it: the window must close only at fill completion.
            engine.release.complete(Unit)
            await("fill completion closes the session window") { !PlaybackActive.isActive }
            assertTrue(
                "the post-stop fill synthesized while the window was still open",
                engine.synthesized.any { it == "Cold light spread across the morning field." },
            )
        } finally {
            PlaybackActive.markStopped()
        }
    }
}
