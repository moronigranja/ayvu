package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerPhase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The explicit-play-target crash guard (owner report 2026-09-22): an
 * ACTION_PLAY_POSITION carrying a (chapter, passage) the bound book's layout
 * no longer holds — a stale share/bookmark target after a re-parse — used to
 * reach [PlayerStateMachine.playFrom]'s `require` inside the command
 * coroutine and FATAL the service
 * (`IllegalArgumentException: position outside layout`). The guard validates
 * the requested extras against the layout and degrades to the non-explicit
 * chain: the stored resume row, then the book's first passage.
 *
 * Same harness as [PlaybackServiceRevivalTest]: the real [PlaybackService]
 * constructed directly (Hilt fields assigned by hand) with a fake [TTSEngine]
 * and the real [PlayerStateMachine] over [InMemoryPlayerStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePlayTargetGuardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "guard-book",
            title = "Guard",
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

    private class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        val synthesized = mutableListOf<String>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            synchronized(synthesized) { synthesized += request.text }
            return SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
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
        PlaybackActive.markStopped() // the G2 session-window flag is global
    }

    /** The Hilt plugin bytecode-transforms [PlaybackService.onCreate] to run
     * Dagger injection, which cannot execute under plain Robolectric — so the
     * lifecycle lateinits are primed directly (same as the publish-guard
     * harness) for the paths that need Context. */
    private fun createdService(
        store: PlayerStore,
        engine: FakeEngine,
    ): PlaybackService {
        val attach: Method = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        val service = PlaybackService()
        attach.invoke(service, context)

        fun prime(
            name: String,
            value: Any,
        ) {
            val field: Field = PlaybackService::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(service, value)
        }
        prime("audioManager", context.getSystemService(Context.AUDIO_SERVICE))
        prime("session", MediaSessionCompat(service, "Ayvu"))
        service.store = store
        service.machine = null // the fresh instance after a self-stop / process death
        service.book = null
        service.output = FakeOutput()
        service.libraryStore = RoomLibraryStore(database, scope)
        service.settings = AppSettings(SettingsStore(database.settingsDao()))
        service.runtime = FakeRuntime(context, service.settings, engine)
        service.selector =
            EngineSelector(
                service.runtime,
                PiperRuntime(context, service.settings),
                TranslateRuntime(context, service.settings),
                onUnusedSystemTts,
                service.settings,
                FakeTranslationService(),
            )
        return service
    }

    /** Polls until the condition holds (async command + prefill coroutines),
     * failing with [message] after the budget. */
    private fun await(
        message: String,
        budgetMs: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun playPositionIntent(
        chapter: Int,
        passage: Int,
    ) = Intent(context, PlaybackService::class.java)
        .setAction(PlaybackService.ACTION_PLAY_POSITION)
        .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
        .putExtra(PlaybackService.EXTRA_CHAPTER, chapter)
        .putExtra(PlaybackService.EXTRA_PASSAGE, passage)

    // ------------------------------------------------------------------
    // Out-of-layout target — degrade, never crash
    // ------------------------------------------------------------------

    /** A stale target (chapter 7 does not exist) must not reach playFrom's
     * require: the command survives and resumes at the stored row (0, 1).
     * Pre-fix the IllegalArgumentException kills the command coroutine, so no
     * LOADING machine is ever seen. */
    @Test
    fun `an out-of-layout play target resumes at the stored row instead of crashing`() {
        val store = InMemoryPlayerStore()
        // A listening session that reached passage 1: the resume row the guard falls back to.
        val stopped = PlayerStateMachine(store, BookLayout(book))
        runBlocking { stopped.playFrom(PlayerPosition(book.id, 0, 1)) }

        val engine = FakeEngine()
        val service = createdService(store, engine)
        PlaybackStateHolder.reset()

        service.onStartCommand(playPositionIntent(chapter = 7, passage = 7), 0, 1)

        await("the guard command must not die") {
            service.machine != null && service.machine!!
                .state.value.phase == PlayerPhase.LOADING
        }
        assertEquals(
            "resumed at the stored row, not the stale target",
            PlayerPosition(book.id, 0, 1),
            service.machine!!
                .state.value.position,
        )
        assertNull("no failure may be published", PlaybackStateHolder.state.value.failure)
        service.stopEverything()
        PlaybackStateHolder.reset()
    }

    /** With no stored row the guard falls to the book's first passage (0, 0). */
    @Test
    fun `an out-of-layout play target without a stored row starts at the first passage`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine()
        val service = createdService(store, engine)
        PlaybackStateHolder.reset()

        service.onStartCommand(playPositionIntent(chapter = 7, passage = 7), 0, 2)

        await("the guard command must not die") {
            service.machine != null && service.machine!!
                .state.value.phase == PlayerPhase.LOADING
        }
        assertEquals(
            "started at the book's first passage",
            PlayerPosition(book.id, 0, 0),
            service.machine!!
                .state.value.position,
        )
        assertNull("no failure may be published", PlaybackStateHolder.state.value.failure)
        service.stopEverything()
        PlaybackStateHolder.reset()
    }

    // ------------------------------------------------------------------
    // In-layout target — unchanged behaviour
    // ------------------------------------------------------------------

    /** A valid explicit target still plays exactly the requested passage. */
    @Test
    fun `an in-layout play target still plays the requested passage`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine()
        val service = createdService(store, engine)
        PlaybackStateHolder.reset()

        service.onStartCommand(playPositionIntent(chapter = 0, passage = 2), 0, 3)

        await("the explicit play must land on the requested passage") {
            service.machine != null && service.machine!!
                .state.value.phase == PlayerPhase.LOADING
        }
        assertEquals(
            "played the requested passage",
            PlayerPosition(book.id, 0, 2),
            service.machine!!
                .state.value.position,
        )
        service.stopEverything()
        PlaybackStateHolder.reset()
    }
}
