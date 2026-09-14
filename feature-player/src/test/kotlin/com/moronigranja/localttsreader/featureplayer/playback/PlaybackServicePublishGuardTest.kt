package com.moronigranja.localttsreader.featureplayer.playback

import android.app.Notification
import android.app.NotificationManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * PR-0 (QW1/QW2) publish + action-surface guard.
 *
 * QW1: [PlaybackService.publish]'s `it.copy(...)` block has burned the repo
 * three times on field drops while editing other fields — `segments` +
 * `offsetSeconds` (3e01cd3/CR-8), `chapterPassages` (26a3272/CR-9), and
 * `chapters` (3bc2057 — the CR-9 fix REPLACED the chapters line with the
 * chapterPassages block). The guard test asserts the full historically
 * collateral-dropped field set after a real publish against a positioned
 * machine + book, so any future copy-block edit that drops a field fails here.
 *
 * QW2: the media-notification action intents must carry EXTRA_BOOK_ID and
 * MediaSession onPlay must resume with the holder's bookId — the post-process-
 * death restart path (`resumePlayer(bookId)` with machine == null) was
 * previously a dead-end because neither surface knew the current book.
 *
 * Same harness as [PlaybackServiceA57Test]: the real [PlaybackService]
 * constructed directly (Hilt fields assigned by hand) with a fake [TTSEngine]
 * and the real [PlayerStateMachine] over [InMemoryPlayerStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServicePublishGuardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Two sentence-spanning anchors: a 2.5 s playhead indexes sentence 0, a
     * 3.0 s playhead sentence 1 — the read-along advance the publish must
     * surface. */
    private val segmentAnchors =
        listOf(
            SegmentAnchor(0.0, 2.5),
            SegmentAnchor(2.5, 5.0),
        )

    private val passageTexts =
        listOf(
            "The gate stood open beside the barn door.",
            "Cold light spread across the morning field.",
            "She counted the fence posts along the track.",
        )

    private val book =
        Book(
            id = "guard-book",
            title = "Guard",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "One",
                        passageTexts.map { TextPassage(it) },
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
    }

    private fun playingMachine(store: PlayerStore): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking { machine.playFrom(PlayerPosition(book.id, 0, 0)) } // phase LOADING, position (0,0) persisted
        return machine
    }

    /** Attaches a directly-constructed service to the app context. The
     * Robolectric lifecycle path would drive Hilt's generated onCreate
     * (demands a @HiltAndroidApp Application — unavailable here), so the plain
     * lifecycle is driven by hand for the paths that need Context. */
    private fun attachServiceContext(service: PlaybackService) {
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
    }

    /** The Hilt plugin bytecode-transforms [PlaybackService.onCreate] to run
     * Dagger injection, which cannot execute under plain Robolectric — so the
     * lifecycle lateinits are primed directly instead. */
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

    /** A57-style service seeded like the harness: constructed directly, base
     * context attached, and the session/audioManager lateinits primed (the
     * Hilt-transformed onCreate cannot run outside a Hilt application), so a
     * publish completes its MediaSession/notification side effects instead of
     * dying on the uninitialized `session` lateinit. */
    private fun createdService(
        store: PlayerStore,
        machine: PlayerStateMachine?,
        engine: FakeEngine = FakeEngine(),
    ): PlaybackService =
        PlaybackService().apply {
            attachServiceContext(this)
            setAudioManager(this)
            setSession(this)
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServicePublishGuardTest.book
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

    /** `segments` has no test seam (the loop is its only writer, and a
     * loop-driven publish on a short book is 60 s-bound by buffer-before-start
     * spinning for the look-ahead target), so the guard injects the exact
     * inputs the loop would have set before the command publish runs. */
    private fun setSegments(
        service: PlaybackService,
        segments: List<SegmentAnchor>,
    ) {
        val field = PlaybackService::class.java.getDeclaredField("segments")
        field.isAccessible = true
        field.set(service, segments)
    }

    private fun buildNotification(service: PlaybackService): Notification {
        val method = PlaybackService::class.java.getDeclaredMethod("buildNotification")
        method.isAccessible = true
        return method.invoke(service) as Notification
    }

    private fun mediaCallback(service: PlaybackService): MediaSessionCompat.Callback {
        val field = PlaybackService::class.java.getDeclaredField("mediaCallback")
        field.isAccessible = true
        return field.get(service) as MediaSessionCompat.Callback
    }

    // ------------------------------------------------------------------
    // QW1 — the publish field-set guard (CR-8/CR-9 regression class)
    // ------------------------------------------------------------------

    /** The guarded contract: a publish against a positioned machine + book
     * reaches the holder with EVERY historically collateral-dropped field
     * populated. Each assertion fails under the era that dropped it:
     * chapters — 3bc2057-era publish; chapterPassages — 26a3272-era;
     * segments/offsetSeconds — 3e01cd3-era. */
    @Test
    fun `publish populates the full historically-dropped field set`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store)
        val service = createdService(store, machine)
        PlaybackStateHolder.reset()
        // The inputs publish() must surface: the anchors the loop would have
        // rendered and a nonzero live playhead (baseline 3.0 s + 0 output
        // samples — the CR-2 seam feeds the live head the same way).
        setSegments(service, segmentAnchors)
        service.baselineOffset = 3.0

        service.pausePlayer(PlaybackService.PauseReason.USER) // command that publishes

        Thread.sleep(300) // let the pause command publish
        val state = PlaybackStateHolder.state.value
        assertEquals("bookId (QW2 in-process source)", book.id, state.bookId)
        assertEquals(
            "chapters restored between passageDurationSeconds and chapterPassages (QW1)",
            listOf("One"),
            state.chapters,
        )
        assertEquals(
            "chapterPassages (CR-9 field)",
            passageTexts,
            state.chapterPassages,
        )
        assertEquals("segments (CR-8 field)", segmentAnchors, state.segments)
        assertEquals("offsetSeconds (CR-8 field) reflects the live playhead", 3.0, state.offsetSeconds, 1e-9)
        assertEquals("passageText", passageTexts[0], state.passageText)
        assertTrue("passageText non-empty", state.passageText.isNotEmpty())
        assertEquals("activeSentenceIndex advanced into the second anchor", 1, state.activeSentenceIndex)
        assertEquals("chapterIndex surfaces the position", 0, state.chapterIndex)
        assertEquals("passageIndex surfaces the position", 0, state.passageIndex)
        assertEquals(PlayerPhase.PAUSED, state.phase)
    }

    // ------------------------------------------------------------------
    // QW2 — book id in the notification / MediaSession surfaces
    // ------------------------------------------------------------------

    /** Every media action's PendingIntent Intent carries EXTRA_BOOK_ID, so a
     * killed-process notification tap can rebuild the machine (pre-fix the
     * intents had no id and ACTION_RESUME dead-ended at `val id = bookId ?:
     * return`). */
    @Test
    fun `notification action intents carry the book id`() {
        val service =
            PlaybackService().apply {
                attachServiceContext(this)
                setSession(this)
                this.book = this@PlaybackServicePublishGuardTest.book
            }
        val notification = buildNotification(service)

        val actions = notification.actions.map { Shadows.shadowOf(it.actionIntent).savedIntent }
        assertEquals(
            "previous / play-resume / next / stop",
            setOf(
                PlaybackService.ACTION_SKIP_BACKWARD,
                PlaybackService.ACTION_RESUME,
                PlaybackService.ACTION_SKIP_FORWARD,
                PlaybackService.ACTION_STOP,
            ),
            actions.map { it.action }.toSet(),
        )

        actions.forEach { intent ->
            assertEquals(
                "every action intent carries EXTRA_BOOK_ID (action=${intent.action} extras=${intent.extras})",
                book.id,
                intent.getStringExtra(PlaybackService.EXTRA_BOOK_ID),
            )
        }
    }

    /** MediaSession onPlay must resume with the HOLDER's bookId — the
     * in-process source of the current book. With machine == null (the
     * post-process-death condition) the id is what lets resumePlayer rebuild;
     * pre-fix onPlay called resumePlayer() with no id and the rebuild
     * dead-ended, leaving the machine null. */
    @Test
    fun `media session play rebuilds the machine from the holder book id after death`() {
        val store = InMemoryPlayerStore()
        val engine = FakeEngine()
        PlaybackStateHolder.reset()
        PlaybackStateHolder.update { it.copy(bookId = book.id) } // what a live session's holder holds
        val service = createdService(store, machine = null, engine = engine)

        mediaCallback(service).onPlay()

        Thread.sleep(400) // let the rebuild command reload the book + publish
        assertNotNull("machine rebuilt — resumePlayer got the holder's book id (QW2)", service.machine)
        assertEquals(book.id, service.book?.id)
        assertEquals(book.id, PlaybackStateHolder.state.value.bookId)
        service.stopEverything() // cancel the in-flight buffer/prefill jobs
        PlaybackStateHolder.reset()
    }

    // ------------------------------------------------------------------
    // S3 — publishDetails (per-second path) vs publish (structural snapshot)
    // ------------------------------------------------------------------

    /** The per-second ticker path must feed the read-along/progress state
     * (G1/G3: segments + live playhead, from which the reader derives
     * activeSentenceIndex) WITHOUT the MediaSession rebuild + notification
     * re-`notify` that the structural snapshot pays for — and it must keep
     * the full field parity (CR-8/CR-9 guard). */
    @Test
    fun `publishDetails feeds per-second state without re-notifying`() {
        val store = InMemoryPlayerStore()
        val machine = playingMachine(store)
        val service = createdService(store, machine)
        PlaybackStateHolder.reset()
        setSegments(service, segmentAnchors)
        service.baselineOffset = 2.6 // inside anchor 1 -> read-along index 1

        service.publishDetails()

        val state = PlaybackStateHolder.state.value
        assertEquals("bookId", book.id, state.bookId)
        assertEquals(
            "details path keeps full field parity (CR-8/CR-9 guard)",
            listOf("One"),
            state.chapters,
        )
        assertEquals("G3 read-along feed: segments advance per second", segmentAnchors, state.segments)
        assertEquals("G1/G3 live playhead per second", 2.6, state.offsetSeconds, 1e-9)
        assertEquals("read-along index tracks the moving playhead", 1, state.activeSentenceIndex)
        assertEquals(PlayerPhase.LOADING, state.phase)
        val notifications =
            Shadows
                .shadowOf(
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
                ).allNotifications
        assertTrue("per-second path must not re-notify (S3)", notifications.isEmpty())

        // The structural path still publishes MediaSession + notification
        // (one notify per structural change — the S3 contract).
        setSegments(service, segmentAnchors)
        service.baselineOffset = 3.0
        service.pausePlayer(PlaybackService.PauseReason.USER) // command that publishes
        Thread.sleep(300)
        val notified =
            Shadows
                .shadowOf(
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
                ).allNotifications
        assertEquals("structural publish notifies once", 1, notified.size)
        PlaybackStateHolder.reset()
    }
}
