package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.session.MediaSessionCompat
import androidx.room.Room
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.RoomLibraryStore
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.BookLayout
import io.github.moronigranja.ayvu.player.InMemoryPlayerStore
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.player.PlayerStateMachine
import io.github.moronigranja.ayvu.player.PlayerStore
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Resume-after-pause rewind (owner request 2026-09-27): resuming a paused
 * session rewinds by a duration that scales with how long it was paused, never
 * before the current chapter's first passage. Drives the real [PlaybackService]
 * with a single-passage chapter (so the chapter-start clamp is exact).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePauseRewindTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    // One chapter, one passage of 300 chars (= 20 s at 15 chars/s): chapter
    // start = 0 and a 5 s offset is a valid in-passage playhead, so the rewound
    // offset is the arithmetic directly.
    private val book =
        Book(
            id = "pr-book",
            title = "PauseRewind",
            chapters = listOf(Chapter(0, "One", listOf(TextPassage("word ".repeat(60))))),
        )

    private class RecordingEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
        private val engine: TTSEngine,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine

        override val failureReason: String? = null
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
        PlaybackActive.markStopped()
    }

    private fun service(
        store: PlayerStore,
        machine: PlayerStateMachine,
    ): Pair<PlaybackService, AppSettings> {
        val settings = AppSettings(SettingsStore(database.settingsDao()))
        val engine = RecordingEngine()
        return PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val audioManagerField = PlaybackService::class.java.getDeclaredField("audioManager")
            audioManagerField.isAccessible = true
            audioManagerField.set(this, context.getSystemService(Context.AUDIO_SERVICE))
            val sessionField = PlaybackService::class.java.getDeclaredField("session")
            sessionField.isAccessible = true
            sessionField.set(this, MediaSessionCompat(this, "pause-rewind-test"))
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServicePauseRewindTest.book
            this.output = FakeOutput()
            this.settings = settings
            this.runtime = FakeRuntime(context, settings, engine)
            this.libraryStore = RoomLibraryStore(database, scope)
            this.pregenCache = PregenCache(context)
            this.selector =
                EngineSelector(
                    FakeRuntime(context, settings, engine),
                    PiperRuntime(context, settings),
                    TranslateRuntime(context, settings),
                    object : dagger.Lazy<TTSEngine> {
                        override fun get(): TTSEngine = error("system tts unused")
                    },
                    settings,
                    FakeTranslationService(),
                )
        } to settings
    }

    private fun pausedMachine(
        store: PlayerStore,
        offset: Double,
    ): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking {
            machine.playFrom(PlayerPosition(book.id, 0, 0, offset))
            machine.pause()
        }
        return machine
    }

    private fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `a long pause rewinds the overnight amount, clamped to the chapter start`() {
        val store = InMemoryPlayerStore()
        val machine = pausedMachine(store, offset = 5.0)
        val (svc, settings) = service(store, machine)
        runBlocking { settings.setBookPausedAt(book.id, System.currentTimeMillis() - 2 * 3_600_000L) }

        svc.resumePlayer(book.id)
        await("the resume to consume the marker") { runBlocking { settings.bookPausedAt(book.id) } == null }

        val committed = runBlocking { store.readProgress(book.id) }!!
        assertEquals("the chapter's first passage", 0, committed.passageIndex)
        assertEquals("a 2 h pause rewinds 20 s, clamped to the chapter's start", 0.0, committed.offsetSeconds, 1e-9)
        assertNull("the marker is consumed, never re-fired", runBlocking { settings.bookPausedAt(book.id) })
        svc.captureAndStop()
    }

    @Test
    fun `a short pause rewinds a sentence`() {
        val store = InMemoryPlayerStore()
        val machine = pausedMachine(store, offset = 5.0)
        val (svc, settings) = service(store, machine)
        runBlocking { settings.setBookPausedAt(book.id, System.currentTimeMillis() - 2_000L) }

        svc.resumePlayer(book.id)
        await("the resume to consume the marker") { runBlocking { settings.bookPausedAt(book.id) } == null }

        val committed = runBlocking { store.readProgress(book.id) }!!
        assertEquals("5 s − 3 s = 2 s into the passage", 2.0, committed.offsetSeconds, 1e-9)
        svc.captureAndStop()
    }
}
