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
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerStore
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fill-restart regression (QW4 one-fill-job, decisions #78): the fill job
 * (pregenJob) is cancelled by stopEverything inside seekBy / navigate /
 * navigateUndo, and those commands restart the play loop WITHOUT restarting
 * the fill — the three loop-restart commands QW4 left without a prefill.
 * bufferForPlayback only POLLS (the loop-side q.ensure was removed by QW4),
 * so with a dead fill NOTHING synthesizes toward the cushion: every cold
 * passage pays the full PLAY_BUFFER_TIMEOUT_MS (60 s) at zero ahead, then
 * synthesizes on demand — the device-observed
 * `buffer: waiting for 45.0 s ahead` → `ahead=0.0s after 60041ms` →
 * `loop: source=synthesized` loop that repeats across whole chapters.
 *
 * Contract under test: after a seek on a playing machine the fill is alive
 * again and the cushion builds — playback proceeds within ONE budget (the
 * 60 s buffer wait exits early, at the ≥30 s D1 horizon) when synthesis is
 * available.
 * Pre-fix the fill never restarts, ahead stays 0, and playback does not
 * reach audio within the observation window (it only would after the 60 s
 * timeout).
 *
 * Determinism: the engine is gated to FAIL synthesis while openBook's
 * front-loading fill runs, so the queue is provably empty when the seek
 * executes — the seek-target passage can never be masked by a stale queue
 * hit. The gate flips to healthy right before the seek; only the RESTARTED
 * fill can then build the cushion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceFillRestartTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 30 passages × ~60 chars (≈4 s each at chars/15): a +30 s seek lands
     * mid-book (~passage 4-5) with ~25 passages after it, so the restarted
     * fill has enough spine left to build the 30 s horizon (the fake engine
     * renders 10 s per passage; 3 synthesizes reach the buffer target). */
    private val book =
        Book(
            id = "fill-restart-book",
            title = "Fill Restart",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        (1..30).map { i ->
                            TextPassage("Passage number $i with enough words to span almost sixty characters of speech text.")
                        },
                    ),
                ),
        )

    /** Gated engine: while [healthy] is false every synthesis FAILS, so a
     * fill synthesizes nothing (the queue stays empty and the pre-seek state
     * is deterministic). 10 s of audio per passage once healthy: 3
     * synthesizes reach the 30 s look-ahead horizon. */
    private class FakeEngine(
        @Volatile var healthy: Boolean = false,
    ) : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        /** Written from the fill job AND the play loop concurrently. */
        val synthesized = CopyOnWriteArrayList<String>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            synthesized += request.text
            if (!healthy) return SynthesisOutcome.Failed("gated")
            return SynthesisOutcome.Audio(ByteArray((10.0 * 24_000 * 2).toInt()), 24_000, 1, listOf(SegmentAnchor(0.0, 10.0)))
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

    /** Degraded path unused in kokoro-default tests (ttsEngine stays kokoro-82m). */
    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
        }

    /** Counts dispatches; the head never advances (awaitPlaybackOrStop parks). */
    private class RecordingOutput : PassageOutput {
        @Volatile
        var playCalls = 0
            private set

        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) {
            playCalls++
        }

        override fun stop() = Unit

        override val positionSamples: Int get() = 0

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
        PlaybackActive.markStopped()
    }

    /** PublishGuard-style priming: Hilt's generated onCreate cannot run under
     * plain Robolectric, so a service whose publish paths run needs base
     * context + the session/audioManager lateinits assigned by hand. */
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

    @Test
    fun `seek restarts the fill so the cushion builds within one budget`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine(healthy = false) // the openBook fill must queue nothing
        val output = RecordingOutput()
        val service =
            PlaybackService().apply {
                attachServiceContext(this)
                setAudioManager(this)
                setSession(this)
                this.store = store
                this.output = output
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
        try {
            // openBook is the real command that builds the service's queue —
            // the private field the fill and the loop share (seekBy retains
            // it, so entries survive seeks and the fill tops them up). While
            // the engine is gated, its front-loading fill synthesizes nothing,
            // so the queue is EMPTY when the seek executes.
            service.openBook(book.id)
            await("openBook builds the queue") { PlaybackStateHolder.state.value.bookId == book.id }
            runBlocking { service.machine!!.playFrom(PlayerPosition(book.id, 0, 0)) } // phase LOADING

            // Restore synthesis; ONLY the restarted fill can build the cushion
            // now (the stale queue is empty by construction).
            engine.healthy = true
            val baseline = engine.synthesized.size

            // The seek command: stopEverything cancels the fill and restarts
            // the loop. The fix restarts the fill from the seek target; the
            // regression leaves pregenJob dead, aheadSeconds stuck at 0, and
            // the loop stalled in the 60 s buffer wait.
            service.seekBy(30.0)

            await("playback proceeds within one budget: the restarted fill rebuilt the cushion") {
                output.playCalls > 0
            }
            assertTrue(
                "the restarted fill synthesized ≥3 passages ahead of the seek target " +
                    "(3 × 10 s = ≥30 s of cushion at the D1 horizon; the loop adds only the sync target passage)",
                engine.synthesized.size - baseline >= 3,
            )
        } finally {
            service.stopEverything() // stop the loop/fill/ticker before the test JVM settles
        }
    }

    @Test
    fun `a seek keeps the fill job alive - in-flight ensure survives (D1)`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine(healthy = true)
        val output = RecordingOutput()
        val service =
            PlaybackService().apply {
                attachServiceContext(this)
                setAudioManager(this)
                setSession(this)
                this.store = store
                this.output = output
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
        val pregenJobField = PlaybackService::class.java.getDeclaredField("pregenJob")
        pregenJobField.isAccessible = true
        try {
            service.openBook(book.id)
            await("openBook builds the queue") { PlaybackStateHolder.state.value.bookId == book.id }
            runBlocking { service.machine!!.playFrom(PlayerPosition(book.id, 0, 0)) } // phase LOADING

            val before: kotlinx.coroutines.Job? = pregenJobField.get(service) as kotlinx.coroutines.Job?
            assertTrue("the openBook fill is running before the seek", before != null)

            service.seekBy(30.0)

            val after: kotlinx.coroutines.Job? = pregenJobField.get(service) as kotlinx.coroutines.Job?
            assertTrue(
                "seekBy must NOT cancel/restart the fill — D1 survive-seek",
                after === before,
            )
            assertTrue("the surviving fill is not cancelled", after != null && !after.isCancelled)
        } finally {
            service.stopEverything() // stop the loop/fill/ticker before the test JVM settles
        }
    }
}
