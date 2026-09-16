package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Audio-focus session contract (decisions #135): playback must stay a focus
 * participant across EVERY command — an in-place seek/navigate that restarts
 * the loop must not drop the focus request, or external audio (Teams calls,
 * WhatsApp messages) can neither pause nor duck the book (the device-reported
 * bug). And focus loss only pauses while actually listening.
 *
 * Same harness as [PlaybackServiceA57Test]/[PlaybackServicePublishGuardTest]:
 * the real service constructed directly, Hilt fields assigned by hand, and
 * the shadow AudioManager recording the focus request/abandon calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceFocusTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book =
        Book(
            id = "focus-book",
            title = "Focus",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        // ~240 chars ≈ 16 s per passage at the chars/15 model;
                        // five passages = 80 s, so the 30 s seek stays mid-book
                        // on a PLAYING machine (a book-end seek would complete
                        // the machine before the focus-loss assertion runs).
                        List(5) {
                            TextPassage("The gate stood open beside the barn door, cold light across the field. ".repeat(6))
                        },
                    ),
                ),
        )

    private class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
        private val engine: TTSEngine?,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine

        override val failureReason: String? = null
    }

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
        PlaybackActive.markStopped()
        PlaybackStateHolder.reset()
    }

    private fun playingMachine(
        store: PlayerStore,
        pauseAt: Double? = null,
    ): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking {
            machine.playFrom(PlayerPosition(book.id, 0, 0)) // phase LOADING
            pauseAt?.let { machine.pause(it) }
        }
        return machine
    }

    /** A57-style service: constructed directly with base context attached and
     * the session/audioManager lateinits primed (Hilt's transformed onCreate
     * cannot run outside a Hilt application; same pattern as the sibling
     * service tests). */
    private fun createdService(
        store: PlayerStore,
        machine: PlayerStateMachine?,
    ): PlaybackService =
        PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val audioManagerField = PlaybackService::class.java.getDeclaredField("audioManager")
            audioManagerField.isAccessible = true
            audioManagerField.set(this, context.getSystemService(Context.AUDIO_SERVICE))
            val sessionField = PlaybackService::class.java.getDeclaredField("session")
            sessionField.isAccessible = true
            sessionField.set(this, MediaSessionCompat(this, "focus-test"))
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServiceFocusTest.book
            this.output = FakeOutput()
            this.libraryStore = RoomLibraryStore(database, scope)
            this.settings = AppSettings(SettingsStore(database.settingsDao()))
            this.runtime = FakeRuntime(context, this.settings, FakeEngine())
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

    private fun focusListener(service: PlaybackService): AudioManager.OnAudioFocusChangeListener? {
        val field = PlaybackService::class.java.getDeclaredField("focusListener")
        field.isAccessible = true
        return field.get(service) as AudioManager.OnAudioFocusChangeListener?
    }

    private fun audioManager(): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun mediaCallback(service: PlaybackService): MediaSessionCompat.Callback {
        val field = PlaybackService::class.java.getDeclaredField("mediaCallback")
        field.isAccessible = true
        return field.get(service) as MediaSessionCompat.Callback
    }

    // ------------------------------------------------------------------
    // The session contract (decisions #135)
    // ------------------------------------------------------------------

    /** The device-reported bug: playback went on at full volume over Teams
     * calls / WhatsApp messages. Root cause: [PlaybackService.stopEverything]
     * abandoned audio focus on EVERY command, and the in-place seek/navigate
     * tails restart the loop WITHOUT re-requesting — so after one seek the app
     * played with NO focus request and the system never delivered the loss
     * callback that would pause it. The fix keeps the request alive for the
     * session; the seek must leave it registered. */
    @Test
    fun `an in-place seek while playing keeps the audio-focus request registered`() {
        val store = InMemoryPlayerStore()
        val service = createdService(store, playingMachine(store, pauseAt = 0.0))
        PlaybackStateHolder.reset()

        // Enter a real play session (the transport play → requestFocus).
        mediaCallback(service).onPlay()
        Thread.sleep(400)
        val playRequest = Shadows.shadowOf(audioManager()).lastAudioFocusRequest
        assertNotNull("play requested audio focus", playRequest)
        assertNull(
            "nothing abandoned the request during the play command",
            Shadows.shadowOf(audioManager()).lastAbandonedAudioFocusRequest,
        )

        // In-place seek: pre-fix this abandoned focus (stopEverything), so the
        // restarted loop played without a request. Post-fix it survives.
        service.seekBy(30.0)
        Thread.sleep(400)
        assertNull(
            "an in-place seek must not abandon the session's audio-focus request",
            Shadows.shadowOf(audioManager()).lastAbandonedAudioFocusRequest,
        )

        // The payoff: an external audio source (Teams call, WhatsApp message)
        // still gets our listener — the book pauses.
        focusListener(service)?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        Thread.sleep(400)
        assertEquals(
            PlayerPhase.PAUSED,
            service.machine
                ?.state
                ?.value
                ?.phase,
        )
        assertEquals(PlayerPhase.PAUSED, PlaybackStateHolder.state.value.phase)

        service.stopEverything()
    }

    /** Focus loss while the machine is open but NOT listening (the reader is
     * open, book presented, nothing playing) is a no-op — the session-scoped
     * request is held while idle, but [PlayerStateMachine.pause] would
     * otherwise spurious-publish PAUSED from an IDLE state. */
    @Test
    fun `focus loss while idle leaves the machine untouched`() {
        val store = InMemoryPlayerStore()
        val machine = PlayerStateMachine(store, BookLayout(book))
        // open-book mode: IDLE, no playback
        runBlocking { machine.present(PlayerPosition(book.id, 0, 0)) }
        val service = createdService(store, machine)
        PlaybackStateHolder.reset()

        focusListener(service)?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        Thread.sleep(300)

        assertEquals("an idle machine is not paused by focus loss", PlayerPhase.IDLE, machine.state.value.phase)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }
}
