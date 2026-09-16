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
import com.moronigranja.localttsreader.player.BookProgress
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.player.pregen.PregenQueue
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
 * D1 seek-horizon service contracts (decisions #155) on top of the survive-seek
 * fill (decisions #91) — the two halves the ±30 s seek acceptance rests on:
 *
 *  - A seek that lands INSIDE the horizon resolves from the cushion with ZERO
 *    synchronous synthesis at seek time: the target passage was queued by the
 *    fill before the seek, the loop takes it, and the engine is never asked
 *    for the target's text after the seek lands. The 79.6 s (S22) / 107.0 s
 *    (HiBreak) measured cost was exactly this sync synthesis on a cold target.
 *
 *  - A seek on a DEAD fill restarts it (the #78/#91 guarded restart — the fill
 *    owner exists on every loop-restart command), so the loop's
 *    bufferForPlayback wait terminates on real fill progress instead of the
 *    60 s dead-owner timeout (`buffer: waiting ... ahead=0.0s after 60041ms`).
 *
 * Determinism: the fake renders book-model audio (chars/15 s per passage), so
 * audio-time moves 1:1 with the book-time the seek math uses — the 30 s
 * horizon is 30 s of book-time and a +30 s seek lands inside the cushion's
 * crossing passage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceSeekHorizonTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 40 passages; the fake renders each at the book model's own duration, so
     * the 30 s horizon holds ~6 passages and a +30 s seek lands inside it with
     * ~34 passages of spine left to refill from. */
    private val book =
        Book(
            id = "d1-horizon-book",
            title = "D1 Horizon",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        (1..40).map { i ->
                            TextPassage("Passage number $i with enough words to span almost sixty characters of speech text.")
                        },
                    ),
                ),
        )

    /** Engine whose audio duration matches the book model (chars/15 s), so
     * book-time == audio-time; records (text, wall time) per synthesize call
     * so a seek window can be checked for synchronous synthesis. */
    private class FakeEngine(
        @Volatile var healthy: Boolean = true,
    ) : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        /** Written from the fill job AND the play loop concurrently. */
        val synthesized = CopyOnWriteArrayList<String>()
        val synthAt = CopyOnWriteArrayList<Long>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            synthesized += request.text
            synthAt += System.currentTimeMillis()
            if (!healthy) return SynthesisOutcome.Failed("gated")
            val seconds = request.text.length / BookProgress.DEFAULT_CHARS_PER_SECOND
            return SynthesisOutcome.Audio(
                ByteArray((seconds * 24_000 * 2).toInt()),
                24_000,
                1,
                listOf(SegmentAnchor(0.0, seconds)),
            )
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

    private fun field(name: String) = PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }

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

    private fun service(
        engine: FakeEngine,
        output: RecordingOutput,
    ): PlaybackService =
        PlaybackService().apply {
            attachServiceContext(this)
            setAudioManager(this)
            setSession(this)
            this.store = InMemoryPlayerStore()
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
                    FakeTranslationService(),
                )
        }

    @Test
    fun `a seek inside the horizon resolves from the cushion without synchronous synthesis`() {
        val engine = FakeEngine()
        val output = RecordingOutput()
        val service = service(engine, output)
        PlaybackStateHolder.reset()
        try {
            // openBook's front-load fill builds the D1 horizon ahead of the
            // opening (the fake synthesizes instantly); the loop itself only
            // starts on a service command tail, so the SEEK below is the first
            // play — exactly the acceptance shape: a seek landing inside the
            // cushion resolves without ever synthesizing the target.
            service.openBook(book.id)
            await("openBook builds the queue") { PlaybackStateHolder.state.value.bookId == book.id }
            val queue = field("queue").get(service) as PregenQueue
            await("the fill builds the 30 s horizon ahead of the opening") {
                val pos =
                    service.machine!!
                        .state.value.position!!
                queue.aheadSeconds(pos) >= 30.0
            }

            // The +30 s target from the current (offset-0) playhead.
            val target = BookProgress.positionAt(book, 30.0)
            val targetText = book.passageText(target.chapterIndex, target.passageIndex)!!
            assertTrue(
                "the +30 s target is inside the cushion (the seek lands on a cached path)",
                queue.peek(target.chapterIndex, target.passageIndex) != null,
            )

            val playsBefore = output.playCalls
            val seekAt = System.currentTimeMillis()
            service.seekBy(30.0)
            await("the seeked-to passage plays") { output.playCalls > playsBefore }

            val synced =
                (0 until engine.synthesized.size).any {
                    engine.synthesized[it] == targetText && engine.synthAt[it] >= seekAt
                }
            assertTrue(
                "zero synchronous synthesis at seek time (the target resolved from the pregen queue)",
                !synced,
            )
        } finally {
            service.stopEverything() // stop the loop/fill/ticker before the test JVM settles
        }
    }

    @Test
    fun `a seek restarts a dead fill and playback proceeds without the dead-owner wait`() {
        val engine = FakeEngine(healthy = false) // the openBook fill must queue nothing
        val output = RecordingOutput()
        val service = service(engine, output)
        PlaybackStateHolder.reset()
        try {
            service.openBook(book.id)
            await("openBook builds the queue") { PlaybackStateHolder.state.value.bookId == book.id }

            // A true stop cancels the fill (pregenJob == null) — the state a
            // seek can inherit after any teardown between commands.
            service.stopEverything()
            assertTrue("precondition: the fill is dead before the seek", field("pregenJob").get(service) == null)

            engine.healthy = true
            service.seekBy(30.0)

            // The #78/#91 guarded restart: the loop-restart command brings the
            // fill owner back; bufferForPlayback then polls a LIVE fill and the
            // wait terminates on real progress (no 60 s dead-owner timeout).
            await("the seek restarted the dead fill") { field("pregenJob").get(service) != null }
            val job = field("pregenJob").get(service) as kotlinx.coroutines.Job
            assertTrue("the restarted fill is not cancelled", !job.isCancelled)
            await("playback proceeds within one budget (no dead-owner wait)") { output.playCalls > 0 }
        } finally {
            service.stopEverything() // stop the loop/fill/ticker before the test JVM settles
        }
    }
}
