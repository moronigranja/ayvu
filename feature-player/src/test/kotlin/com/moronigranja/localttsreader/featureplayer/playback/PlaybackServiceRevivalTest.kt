package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import java.lang.reflect.Field
import java.lang.reflect.Method
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

/**
 * The self-stop/revival seam (6eaa2c0 + PR-0 QW2): the service's post-stop
 * fill completes and self-stops it while the reader stays open. The reader's
 * Play button then sends ACTION_RESUME with the reader's book id, and the
 * fresh (machine-less) service must rebuild the machine and resume from the
 * persisted playhead the STOP wrote — never dead-end, never restart at
 * passage 0.
 *
 * [PlaybackServicePublishGuardTest] pins the id-bearing notification/MediaSession
 * surfaces; these tests pin the dispatch + rebuild behavior itself:
 * the ACTION_RESUME path through [PlaybackService.onStartCommand], the
 * resumed-position contract, and the no-id dead-end safety guard.
 *
 * Same harness as [PlaybackServiceA57Test]: the real [PlaybackService]
 * constructed directly (Hilt fields assigned by hand) with a fake [TTSEngine]
 * and the real [PlayerStateMachine] over [InMemoryPlayerStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceRevivalTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "revival-book",
        title = "Revival",
        chapters = listOf(
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
    private val onUnusedSystemTts = object : dagger.Lazy<TTSEngine> {
        override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
    }

    private class FakeOutput : PassageOutput {
        override fun play(pcm: ByteArray, sampleRate: Int, speed: Double) = Unit
        override fun stop() = Unit
        override val positionSamples: Int = 0
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
        fun prime(name: String, value: Any) {
            val field: Field = PlaybackService::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(service, value)
        }
        prime("audioManager", context.getSystemService(Context.AUDIO_SERVICE))
        prime("session", MediaSessionCompat(service, "local-tts-reader"))
        service.store = store
        service.machine = null // the fresh instance after a self-stop / process death
        service.book = null
        service.output = FakeOutput()
        service.libraryStore = RoomLibraryStore(database, scope)
        service.settings = AppSettings(SettingsStore(database.settingsDao()))
        service.runtime = FakeRuntime(context, service.settings, engine)
        service.selector = EngineSelector(service.runtime, PiperRuntime(context, service.settings), onUnusedSystemTts, service.settings)
        return service
    }

    /** Polls until the condition holds (async command + prefill coroutines),
     * failing with [message] after the budget. */
    private fun await(message: String, budgetMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    // ------------------------------------------------------------------
    // Revival — ACTION_RESUME with the reader's book id
    // ------------------------------------------------------------------

    /** The device-verified revival (6eaa2c0): play → STOP → post-stop fill
     * self-stops the service → reader Play (ACTION_RESUME + book id) on the
     * fresh machine-less instance rebuilds the machine and resumes from the
     * persisted playhead. Pre-fix, resumePlayer() returned silently — a dead
     * play button; a naive rebuild without machine.resume() would restart at
     * passage 0. */
    @Test
    fun `resume with the reader's book id rebuilds the machine at the persisted playhead`() {
        val store = InMemoryPlayerStore()
        // A listening session that reached passage 1: the playhead the STOP
        // wrote before the service self-stopped.
        val stopped = PlayerStateMachine(store, BookLayout(book))
        runBlocking { stopped.playFrom(PlayerPosition(book.id, 0, 1)) }

        val engine = FakeEngine()
        val service = createdService(store, engine)
        PlaybackStateHolder.reset()
        assertNull("the restart left no machine", service.machine)

        service.onStartCommand(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_RESUME)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
            0,
            1,
        )

        await("the rebuild command must reconstruct the machine") { service.machine != null }
        await("resume must synthesize (prefill for the resumed passage)", condition = { engine.synthesized.isNotEmpty() })
        val rebuilt = service.machine!!
        assertEquals("resumed into the persisted playhead, not passage 0", 1, rebuilt.state.value.position?.passageIndex)
        assertEquals(PlayerPhase.LOADING, rebuilt.state.value.phase)
        await("the rebuilt session is published") {
            PlaybackStateHolder.state.value.let { it.bookId == book.id && it.phase == PlayerPhase.LOADING }
        }
        service.stopEverything() // cancel the loop/prefill jobs
        PlaybackStateHolder.reset()
    }

    // ------------------------------------------------------------------
    // Guard — a machine-less resume without a book id is a safe no-op
    // ------------------------------------------------------------------

    /** A machine-less ACTION_RESUME with no id (extras lost across process
     * death, or a caller that predates the id contract) must dead-end
     * silently: no crash, no rebuild from nothing, no published state. The
     * G2 session window also stays closed — nothing started. */
    @Test
    fun `a machine-less resume without a book id dead-ends safely`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine()
        val service = createdService(store, engine)
        PlaybackStateHolder.reset()

        service.onStartCommand(
            Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME),
            0,
            2,
        )

        Thread.sleep(300) // let any (wrong) rebuild attempt surface
        assertNull("no machine may be built without a book id", service.machine)
        assertNull("no book may be published", PlaybackStateHolder.state.value.bookId)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
        assertTrue("nothing may be synthesized", engine.synthesized.isEmpty())
        PlaybackStateHolder.reset()
    }
}
