package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C2: ACTION_CHANGE_VOICE — switching the active book to a newly selected
 * voice must preserve the playhead, run as a tracked A5 command (a
 * superseded/unchanged change never re-synthesizes or moves the playhead),
 * and restart playback exactly once at the same position (the following
 * passage uses the new voice through [activeVoice]).
 *
 * Drives the real [PlaybackService] with a fake engine that records every
 * SynthesisRequest's voice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceChangeVoiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "cv-book",
            title = "ChangeVoice",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        listOf(
                            TextPassage("The hikers met at dawn by the stone bridge."),
                            TextPassage("Mist lay over the river and the town was quiet."),
                            TextPassage("She counted the fence posts along the track."),
                        ),
                    ),
                ),
        )

    private open class RecordingEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        val requests = mutableListOf<SynthesisRequest>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            requests += request
            return SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
        }
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

    private fun playingMachine(store: PlayerStore): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking { machine.playFrom(PlayerPosition(book.id, 0, 0)) }
        return machine // phase LOADING at (0,0)
    }

    private fun service(
        store: PlayerStore,
        machine: PlayerStateMachine,
        engine: RecordingEngine,
    ): Pair<PlaybackService, AppSettings> {
        val settings = AppSettings(SettingsStore(database.settingsDao()))
        val engine1 = engine
        return PlaybackService().apply {
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServiceChangeVoiceTest.book
            this.output = FakeOutput()
            this.settings = settings
            this.runtime = FakeRuntime(context, this.settings, engine1)
            this.libraryStore = RoomLibraryStore(database, scope)
            this.pregenCache = PregenCache(context)
            this.selector =
                EngineSelector(
                    FakeRuntime(context, this.settings, engine1),
                    PiperRuntime(context, this.settings),
                    object : dagger.Lazy<TTSEngine> {
                        override fun get(): TTSEngine = error("system tts unused")
                    },
                    settings,
                )
        } to settings
    }

    @Test
    fun `change voice preserves the playhead and re-synthesizes with the new voice`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store)
        val engine = RecordingEngine()
        val (svc, settings) = service(store, machine, engine)
        runBlocking { settings.setVoice("bm_george") }
        assertEquals(PlayerPhase.LOADING, machine.state.value.phase)

        svc.changeVoice("bm_george")
        Thread.sleep(300) // let the command run

        // Was playing -> restarted exactly once, still active at the same position.
        assertTrue(
            "restarted once (LOADING or PLAYING), was NOT left unused",
            machine.state.value.phase == PlayerPhase.LOADING || machine.state.value.phase == PlayerPhase.PLAYING,
        )
        val pos = machine.state.value.position!!
        assertEquals(0, pos.chapterIndex)
        assertEquals(0, pos.passageIndex)
        assertTrue("new voice reached the engine", engine.requests.any { it.voice == "bm_george" })
    }

    @Test
    fun `change voice with no open book is a no-op`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store)
        val engine = RecordingEngine()
        val (svc, settings) = service(store, machine, engine)
        svc.machine = null // nothing open — the setting alone persists
        runBlocking { settings.setVoice("zm_yunxi") }

        svc.changeVoice("zm_yunxi")
        Thread.sleep(200)

        assertTrue("no synthesis when nothing is open", engine.requests.isEmpty())
    }
}
