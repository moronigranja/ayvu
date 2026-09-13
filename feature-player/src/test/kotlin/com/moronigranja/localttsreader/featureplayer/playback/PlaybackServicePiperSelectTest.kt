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
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.piper.PiperVoices
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D4 selection end-to-end on the service seam (decisions #154 addendum): with
 * `tts_engine = piper-v1` persisted, every synthesis the fill and the loop run
 * carries the voice the Piper instance actually serves — the stored global
 * voice (a Kokoro name) resolves through the decisions #144 availability
 * shape to [PiperVoices.LESSAC] — and playback proceeds through the normal
 * fill/loop machinery (D1 semantics untouched, decisions #155). Piper is not
 * degraded, and its segment-less outcomes (#30b) keep the read-along surface
 * empty rather than fabricated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePiperSelectTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "piper-select-book",
        title = "Piper Select",
        chapters = listOf(
            Chapter(
                0,
                "One",
                (1..30).map { i ->
                    TextPassage("Passage number $i with enough words to span almost sixty characters of speech text.")
                },
            ),
        ),
    )

    /** Piper-shaped engine: records the requested voice, renders 10 s of
     * audio with segments = null (the recorded #30b degradation). */
    private class RecordingEngine : TTSEngine {
        override val spec = EngineSpec("piper-v1", "Piper", EngineTier.PRIMARY, setOf("en", "de"))
        override val packs: List<TtsPack> = emptyList()
        val requests = CopyOnWriteArrayList<SynthesisRequest>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            requests += request
            return SynthesisOutcome.Audio(ByteArray(10 * 24_000 * 2), 24_000, 1, segments = null)
        }
    }

    private class FakePiperRuntime(
        context: Context,
        settings: AppSettings,
        private val engine: TTSEngine,
    ) : PiperRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine
        // The per-book path pairs the engine with the resolved voice — the
        // fake must serve both entry points.
        override fun engineFor(voice: String): TTSEngine? = engine
        override val failureReason: String? = null
    }

    private class FakeKokoroRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = error("kokoro must not be touched under piper-v1")
        override val failureReason: String? = null
    }

    private val onUnusedSystemTts = object : dagger.Lazy<TTSEngine> {
        override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
    }

    private class RecordingOutput : PassageOutput {
        @Volatile
        var playCalls = 0
            private set
        override fun play(pcm: ByteArray, sampleRate: Int, speed: Double) {
            playCalls++
        }
        override fun stop() = Unit
        override val positionSamples: Int get() = 0
        override fun setVolume(multiplier: Float) = Unit
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { RoomLibraryStore(database, scope).add(LibraryEntry(book, importedAtEpochMillis = 1L)) }
    }

    @After
    fun tearDown() {
        database.close()
        PlaybackActive.markStopped()
    }

    private fun await(label: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $label")
    }

    @Test
    fun `piper-v1 selected synthesizes with the resolved voice and no fabricated read-along`() {
        val engine = RecordingEngine()
        val output = RecordingOutput()
        val service = PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val audioManager = PlaybackService::class.java.getDeclaredField("audioManager")
            audioManager.isAccessible = true
            audioManager.set(this, context.getSystemService(Context.AUDIO_SERVICE))
            val session = PlaybackService::class.java.getDeclaredField("session")
            session.isAccessible = true
            session.set(this, MediaSessionCompat(this, "local-tts-reader"))
            this.store = InMemoryPlayerStore()
            this.output = output
            this.libraryStore = RoomLibraryStore(database, scope)
            this.settings = AppSettings(SettingsStore(database.settingsDao()))
            this.runtime = FakeKokoroRuntime(context, this.settings)
            this.pregenCache = PregenCache(context)
            this.selector =
                EngineSelector(
                    this.runtime,
                    FakePiperRuntime(context, this.settings, engine),
                    onUnusedSystemTts,
                    this.settings,
                )
        }
        PlaybackStateHolder.reset()
        runBlocking { service.settings.setTtsEngine(SettingsStore.PIPER_ENGINE) }
        try {
            // The persisted selection + a stored GLOBAL voice that Piper does
            // not expose (the Kokoro default): the availability rule must
            service.openBook(book.id)
            await("openBook builds the queue") { PlaybackStateHolder.state.value.bookId == book.id }
            // The machine phase must be LOADING for the loop to run; playFrom
            // sets it (openBook alone leaves the fresh machine IDLE).
            runBlocking { service.machine!!.playFrom(PlayerPosition(book.id, 0, 0)) }
            await("the piper engine synthesized for the fill") { engine.requests.isNotEmpty() }
            // The loop only starts on a service command tail — seekBy(0.0) is
            // the in-place loop-restart (the D1 scaffold's cold-start trigger).
            service.seekBy(0.0)
            await("audio plays through the normal loop") { output.playCalls > 0 }

            assertTrue(
                "every synthesis names a voice the Piper instance serves " +
                    "(requested: ${engine.requests.map { it.voice }.distinct()})",
                engine.requests.all { it.voice == PiperVoices.LESSAC },
            )
            assertFalse(
                "piper is a PRIMARY engine class — the degraded pill never shows",
                PlaybackStateHolder.state.value.degraded,
            )
            // #30b degradation at the published surface: segment-less outcomes
            // keep the read-along fields empty (no fabricated anchors) while
            // playback runs.
            assertEquals(0.0, PlaybackStateHolder.state.value.passageDurationSeconds, 0.0)
            assertTrue(PlaybackStateHolder.state.value.segments.isEmpty())
        } finally {
            service.stopEverything()
        }
    }
}
