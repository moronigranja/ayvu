package com.moronigranja.localttsreader.featureplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.BookLayout
import com.moronigranja.localttsreader.player.BookProgress
import com.moronigranja.localttsreader.player.CoverageSpan
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerEvent
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerState
import com.moronigranja.localttsreader.player.PlayerStateMachine
import com.moronigranja.localttsreader.player.PlayerStore
import com.moronigranja.localttsreader.player.SleepTimer
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.player.pregen.CoverageEncoder
import com.moronigranja.localttsreader.player.pregen.PregenAudio
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.player.pregen.PregenQueue
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * T4-2 playback: foreground service running the [PlayerStateMachine] against
 * the Kokoro engine and an [AudioTrack] (through [PassageOutput]), with
 * MediaSession, audio focus/ducking, route-switch (becoming-noisy) handling,
 * sleep-timer ticks, and a media notification. The docked reader UI observes
 * [PlaybackStateHolder] and drives this service with Intent actions.
 *
 */
@AndroidEntryPoint
class PlaybackService : Service() {
    @Inject lateinit var store: PlayerStore

    @Inject lateinit var libraryStore: RoomLibraryStore

    @Inject lateinit var runtime: KokoroRuntime

    // C1.5 (decisions #102): the engine seam — Kokoro or the degraded device
    // voice, selected by the persisted tts_engine setting.
    @Inject lateinit var selector: EngineSelector

    @Inject lateinit var settings: AppSettings

    @Inject lateinit var pregenCache: PregenCache

    @Inject lateinit var pregenManager: PregenManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Dedicated player thread (decisions #85): the loop + ticker run on their
     * own single-thread dispatcher so the boundary path (advance → publish →
     * next dispatch) never queues behind the prefill's synthesis on
     * [Dispatchers.Default] — the boundary gap's `pub` stage was measured at
     * 7-45 ms of CPU-contention time with the session/notify already gated.
     * Single-threaded also hardens the machine's single-writer edge (ticker
     * and loop share one thread). */
    private val playerDispatcher =
        Executors
            .newSingleThreadExecutor { r ->
                Thread(r, "AyvuPlayer").apply { priority = Thread.MAX_PRIORITY }
            }.asCoroutineDispatcher()

    /** CR-2 host-test seam: the current machine (set by tests directly). */
    internal var machine: PlayerStateMachine? = null

    /** Active book (internal for host tests that drive commands directly). */
    internal var book: Book? = null

    /** CR-2 host-test seam: the passage output (tests inject a fake).
     * Default: the static track (decisions #84) — MODE_STREAM proved inert
     * on the S22 (PLAYING with a frozen head, #83), so playback stays on
     * the static model with PRE-ARMING: the next passage's track is built
     * while the current one plays, so the boundary swap is a fast handoff
     * instead of a rebuild on the critical path. */
    internal var output: PassageOutput = AudioTrackPassageOutput()
    private var tickerJob: Job? = null
    private var pregenJob: Job? = null
    private var queue: PregenQueue? = null

    /** Torrent-bar coverage refresh state: [coverageDirty] marks every source
     * that changes what audio is on disk (queue rebuild, persist, manual
     * pregen progress); the snapshot recomputes only when dirty or the
     * book|voice|speed key changed. */
    private var coverageDirty = true
    private var coverageKey: String? = null
    private var coverageCache: Pair<List<CoverageSpan>, List<Float>> = emptyList<CoverageSpan>() to emptyList<Float>()
    private var coverageObserverJob: Job? = null

    /** True while the post-STOP cushion fill runs (startPostStopPrefill): it
     * gates the generation notification's cancel in [publish] — publishing
     * after STOP (phase != LOADING) must not drop the notification that the
     * fill is showing. */
    private var postStopFill = false

    /** Generation-notification throttle: in-place refresh at most once per
     * second (the buffering loops poll at ≤50/200 ms, and each notify is an
     * IPC to system_server). */
    private var lastGenerationNotifyAt = 0L

    /** The last passage's rendered audio, keyed identically to the disk tier —
     * a seek that stays in the passage replays it with zero synthesis (decisions
     * #55 layer 1). Cleared on book switch. */
    private var lastAudio: Pair<PregenKey, PregenAudio>? = null

    /** In-flight first-listen persists; seek paths join them so a re-fetch of
     * a just-listened passage always finds the disk entry. Never cancelled —
     * a new first-listen must not drop the previous passage's write. */
    private val pendingPersists = mutableListOf<Job>()

    /** The active play loop coroutine; cancelled directly by [stopEverything]
     * so a stale loop can never advance the machine after a newer command. */
    private var loopJob: Job? = null

    /** Serializes transport commands (seek/chapter/skip/speed): rapid taps
     * queue instead of racing the machine's single-writer state. */
    private val commandLock = kotlinx.coroutines.sync.Mutex()
    private var stopSignal = CompletableDeferred<Unit>()

    /** The graceful STOP's final machine write (CR-2): onDestroy joins it so
     * exactly one authoritative stop persists — never a stale teardown write
     * overwriting the captured playhead. */
    private var finalStopJob: Job? = null

    /** Last live-playhead checkpoint (CR-2): monotonic interval gate. */
    private var lastCheckpointAt = 0L

    /** The in-flight command coroutine (CR-5): every control-plane command
     * (open/play/pause/seek/navigate/...) runs here; a NEWER command's
     * [stopEverything] cancels it so a superseded load can never advance or
     * publish shared state. The long-running synthesis/play loop runs inside
     * its command, so commands stay cancellable. */
    private var commandJob: Job? = null

    /**
     * Monotonic command generation (CR-5/CR-7): bumped by every
     * [stopEverything]. A command captures the generation it was launched
     * with and checks [active] before ANY publish/startForeground/
     * stopForeground side effect — cancellation alone cannot stop a
     * non-cancellable section from reaching its tail.
     */
    @Volatile private var commandGeneration = 0L
    private var segments: List<SegmentAnchor> = emptyList()

    /** CR-2 host-test seam: the current PCM slice's start offset (book-time). */
    internal var baselineOffset = 0.0

    /** The last rendered passage's sample rate (S5, decisions #77): the live
     * playhead and the completion margin must use the actual audio rate, not a
     * kokoro constant — a future 22.05/16 kHz engine would silently miscompute
     * both. Defaults to kokoro's 24 kHz before the first passage renders. */
    private var lastSampleRateHz = DEFAULT_SAMPLE_RATE_HZ
    private var ringHasEntries = false

    // Measurement probes (goals §Measurement): tap-to-audio dispatch baseline
    // + boundary-gap consecutive-play baseline. Debug-gated (probesActive) and
    // log-only — never block, publish, or reorder.
    private var tapAt = 0L
    private var tapAction: String? = null
    private var playAt = 0L
    private var prevFrames = 0

    /** Marker-accurate gap baseline (decisions #81): set when the
     * end-of-buffer marker fires, consumed by the next play dispatch. */
    private var lastMarkerAt = 0L

    /** The notification key of the last re-`notify` (decisions #82): the
     * per-boundary re-notify was an IPC to system_server on the player
     * coroutine, a measurable part of the boundary gap. Skipped when the
     * transport-relevant visible content (book + play/pause action) is
     * unchanged — the passage ordinal in the shade may lag until the next
     * phase/transport change (a fresh notify fires then). */
    private var lastNotifiedKey: String? = null

    /** MediaSession content key (decisions #85): book + phase — the session
     * is updated only when this changes, not at every passage boundary. */
    private var lastSessionKey: String? = null

    // Media-notification cover art, cached per book (files/covers/<bookId>).
    private var coverArtBookId: String? = null
    private var coverArt: Bitmap? = null
    private var resumeOnGain = false
    private var ducking = false

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // Measurement probes (docs/generate-play-goals.md §Measurement)
    // ------------------------------------------------------------------

    /** True on app debug builds: there is no feature-player BuildConfig, so
     * the gate is the app's debuggable runtime flag AND the [gapProbeActive]
     * master toggle (flip it to withdraw every probe without a rebuild). */
    private val probesActive: Boolean
        get() = gapProbeActive && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Single gated emit point for every probe: logs only — never blocks,
     * publishes, or reorders, so the 50 ms poll loop and CR-2/CR-5/CR-7
     * ordering are untouched. Tags are consumed by a dev script. */
    private fun probe(
        tag: String,
        message: String,
    ) {
        if (!probesActive) return
        android.util.Log.d(tag, message)
    }

    /** Arms the tap-to-audio baseline (L1/L2/L3) at transport-command
     * dispatch, captured as early as possible — the first thing after
     * onStartCommand entry. A null action (sticky restart / watchdog replay)
     * is not a user tap and keeps the previous tap armed. */
    private fun probeTap(action: String?) {
        if (action == null) return
        tapAt = clock()
        tapAction = action
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        session =
            MediaSessionCompat(this, "Ayvu").apply {
                setCallback(mediaCallback)
                isActive = true
            }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) return START_NOT_STICKY
        // Measurement probe (goals §Measurement): the tap timestamp is taken
        // at dispatch — before the foreground/when block — and consumed at
        // the first output.play (tap-to-audio, L1/L2/L3).
        if (probesActive) probeTap(intent.action)
        // Every command arrives via startForegroundService: enter the
        // foreground FIRST so an early return (no engine/book/machine) can
        // never trip ForegroundServiceDidNotStartInTimeException (#50).
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent.action) {
            ACTION_OPEN -> openBook(intent.bookId())
            ACTION_PLAY -> startPlayback(intent.bookId(), explicit = false)
            ACTION_PLAY_POSITION -> startPlayback(intent.bookId(), explicit = true, intent = intent)
            ACTION_OPEN_CHAPTER -> openChapter(intent.bookId(), intent.getIntExtra(EXTRA_DIRECTION, 0))
            ACTION_OPEN_POSITION ->
                openPosition(
                    intent.bookId(),
                    intent.getIntExtra(EXTRA_CHAPTER, 0),
                    intent.getIntExtra(EXTRA_PASSAGE, 0),
                )
            ACTION_RESUME -> resumePlayer(intent.bookId())
            ACTION_PAUSE -> pausePlayer(PauseReason.USER)
            ACTION_SKIP_FORWARD -> navigate { it.skipForward() }
            ACTION_SKIP_BACKWARD -> navigate { it.skipBackward() }
            ACTION_SEEK_FORWARD -> seekBy(SEEK_STEP_SECONDS)
            ACTION_SEEK_BACKWARD -> seekBy(-SEEK_STEP_SECONDS)
            ACTION_UNDO -> navigateUndo()
            ACTION_STOP -> stopPlayer()
            ACTION_SLEEP -> cycleSleepTimer()
            ACTION_BOOKMARK -> addBookmarkAtPlayhead()
            ACTION_CHANGE_VOICE -> intent.getStringExtra(EXTRA_VOICE)?.let(::changeVoice)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // Commands

    /**
     * Opens a book for READING without starting playback (decisions #52):
     * stops whatever is playing, loads the book, positions the machine at the
     * resume point (or the start), publishes the text state, and drops the
     * foreground — the transport buttons or the library play button resume
     * audio later. The service stays started to answer commands.
     */
    internal fun openBook(bookId: String?) {
        val id = bookId ?: return
        stopEverything()
        launchCommand { generation ->
            settings.reload()
            val activeBook =
                runCatching { libraryStore.cachedBooks() }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (activeBook == null) return@launchCommand
            book = activeBook
            machine = PlayerStateMachine(store, BookLayout(activeBook))
            lastAudio = null
            queue = buildQueue()
            val position = machine!!.openPosition() ?: machine!!.firstPosition()
            if (position != null) machine!!.present(position)
            refreshBookmarks()
            // Front-load the opening (user request): a book opened while idle
            // warms its first ~45 s of audio, so first play starts without the
            // cold synthesize-then-play gap.
            if (position != null) startPrefill(position)
            // CR-5: a stale load must never publish or drop the foreground.
            if (!active(generation)) return@launchCommand
            PlaybackStateHolder.update { it.copy(failure = null) }
            publish()
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * Turns the reader across a chapter boundary WITHOUT starting playback
     * (decisions #52: open ≠ auto-play — same contract as [openBook]). A
     * forward turn lands on the neighbor's FIRST passage, a backward turn
     * on its LAST — the reader's left-zone turn shows the previous
     * chapter's ending. The machine is rebuilt over the book while the
     * CURRENT chapter is captured first; the neighbor is the next/previous
     * chapter with passages ([BookLayout.nextChapter]/[previousChapter]),
     * so an empty spine slot is skipped and a boundary turn at the book's
     * edge is a no-op. Playback never starts; the reader pages to the
     * presented passage itself.
     */
    internal fun openChapter(
        bookId: String?,
        direction: Int,
    ) {
        val id = bookId ?: return
        val activeBook = book ?: return
        val current =
            machine
                ?.state
                ?.value
                ?.position
                ?.chapterIndex ?: return
        // Resolve the neighbor BEFORE touching the machine/audio: a book-edge
        // turn is a pure no-op and must leave the present machine intact —
        // rebuilding below before this null check would null the position and
        // silently swallow the next turn.
        val layout = BookLayout(activeBook)
        val target = if (direction > 0) layout.nextChapter(current) else layout.previousChapter(current)
        if (target == null) return
        stopEverything()
        launchCommand { generation ->
            settings.reload()
            val reloaded =
                runCatching { libraryStore.cachedBooks() }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (reloaded == null) return@launchCommand
            book = reloaded
            machine = PlayerStateMachine(store, BookLayout(reloaded))
            lastAudio = null
            queue = buildQueue()
            val passage = if (direction < 0) activeBook.chapters[target].passages.lastIndex else 0
            machine!!.present(PlayerPosition(id, target, passage))
            refreshBookmarks()
            startPrefill(PlayerPosition(id, target, passage))
            // CR-5: a stale load must never publish or drop the foreground.
            if (!active(generation)) return@launchCommand
            PlaybackStateHolder.update { it.copy(failure = null) }
            publish()
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * Presents an explicit (chapter, passage) WITHOUT starting playback — the
     * chapter selector and bookmark jumps route here instead of
     * [startPlayback]'s explicit position (open ≠ auto-play, decisions #52).
     * Mirrors [openChapter]: stops current audio, rebuilds the machine over
     * the book, presents the target, publishes the text, drops the foreground.
     * An empty spine slot falls to the nearest playable chapter (forward,
     * then backward); an unreachable target is a no-op.
     */
    internal fun openPosition(
        bookId: String?,
        chapter: Int,
        passage: Int,
    ) {
        val id = bookId ?: return
        stopEverything()
        launchCommand { generation ->
            settings.reload()
            val reloaded =
                runCatching { libraryStore.cachedBooks() }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (reloaded == null) return@launchCommand
            val layout = BookLayout(reloaded)
            val target =
                when {
                    layout.isValid(chapter, passage) -> PlayerPosition(id, chapter, passage)
                    layout.isValid(chapter, 0) -> PlayerPosition(id, chapter, 0)
                    else -> {
                        val c = layout.nextChapter(chapter) ?: layout.previousChapter(chapter)
                        c?.let { PlayerPosition(id, it, 0) }
                    }
                } ?: return@launchCommand
            book = reloaded
            machine = PlayerStateMachine(store, layout)
            lastAudio = null
            queue = buildQueue()
            machine!!.present(target)
            refreshBookmarks()
            startPrefill(target)
            // CR-5: a stale load must never publish or drop the foreground.
            if (!active(generation)) return@launchCommand
            PlaybackStateHolder.update { it.copy(failure = null) }
            publish()
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /** Enriches the UI state with the book's bookmarks for the reader menu. */
    private fun refreshBookmarks() {
        val id = machine?.bookId ?: return
        scope.launch {
            val bookmarks = store.bookmarks(id)
            PlaybackStateHolder.update { it.copy(bookmarks = bookmarks) }
        }
    }

    private fun startPlayback(
        bookId: String?,
        explicit: Boolean,
        intent: Intent? = null,
    ) {
        val id = bookId ?: return
        if (selector.engine() == null) {
            PlaybackStateHolder.update { it.copy(failure = selector.failureReason ?: "engine unavailable") }
            return
        }
        stopEverything()
        requestFocus()
        launchCommand { generation ->
            settings.reload() // V1: settings written by the UI apply at the next play action
            applyPlaybackVolume()
            val activeBook =
                runCatching { libraryStore.cachedBooks() }
                    .getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (activeBook == null) return@launchCommand
            book = activeBook
            machine = PlayerStateMachine(store, BookLayout(activeBook))
            lastAudio = null
            queue = buildQueue()
            refreshBookmarks()
            val position =
                if (explicit) {
                    PlayerPosition(id, intent?.getIntExtra(EXTRA_CHAPTER, 0) ?: 0, intent?.getIntExtra(EXTRA_PASSAGE, 0) ?: 0)
                } else {
                    null
                }
            if (position != null) {
                machine!!.playFrom(position)
            } else if (machine!!.resume() == null) {
                // First play: start at the book's first *playable* passage —
                // segmentation renumbers from 0, but a stale/empty parse must
                // not crash the (0,0) require.
                machine!!.playFrom(
                    machine!!.firstPosition() ?: run {
                        PlaybackStateHolder.update { it.copy(failure = "nothing to play") }
                        return@launchCommand
                    },
                )
            }
            if (machine!!.state.value.phase != PlayerPhase.LOADING) {
                PlaybackStateHolder.update { it.copy(failure = "nothing to play") }
                return@launchCommand
            }
            // Keep a time-buffer filled while the loop runs (startPrefill owns
            // the long-lived synthesis job; the loop no longer cancel-relaunches).
            machine!!
                .state.value.position
                ?.let { startPrefill(it) }
            // CR-5/CR-7: a superseded play must not enter the foreground or
            // start its loop after a newer command won.
            if (!active(generation)) return@launchCommand
            // G2: the session window starts here and ends only when the
            // post-stop fill completes (markStopped in startPostStopPrefill).
            PlaybackActive.markStarted()
            startForeground(NOTIFICATION_ID, buildNotification())
            publish()
            startLoop()
        }
    }

    /**
     * C2: switches the ACTIVE book to a newly selected voice while preserving
     * the playhead (roadmap acceptance: "pause/restart occurs once at the
     * same position; the following passage uses the new voice"). Runs as a
     * tracked command under the A5 single-writer model: the live position is
     * captured BEFORE [stopEverything] supersedes in-flight synthesis, the
     * settings reload picks up the new voice, and the queue/fill rebuild
     * voice-keyed exactly like any other queue build — so a stale generation
     * from the old voice can never publish (CR-5) and the disk cache selects
     * the new voice's entries naturally. The persisted setting is written by
     * the caller (selector surface) BEFORE dispatch; this command only
     * rebuilds. Playing resumes at the captured playhead; a paused session
     * stays paused at the same position (no auto-resume).
     */
    internal fun changeVoice(voice: String) {
        if (voice.isBlank()) return
        // Callers (the reader's voice sheet) persist the new voice BEFORE
        // dispatch and only dispatch on a real change (voice != current), so
        // a `voice == activeVoice()` guard here would always early-return and
        // the rebuild would never run. The service rebuilds whenever a
        // different voice is handed to it, preserving the playhead (A5).
        val active = machine ?: return // nothing open — the setting alone suffices
        val wasPlaying =
            active.state.value.phase == PlayerPhase.PLAYING || active.state.value.phase == PlayerPhase.LOADING
        val position =
            active.state.value.position
                ?.copy(offsetSeconds = liveOffsetSeconds())
        stopEverything()
        launchCommand { generation ->
            settings.reload() // C2: the new voice landed before dispatch
            if (!active(generation)) return@launchCommand
            applyPlaybackVolume()
            if (position == null || machine == null) return@launchCommand
            queue = buildQueue()
            if (wasPlaying) {
                machine!!.playFrom(position)
            } else {
                machine!!.present(position)
            }
            if (!active(generation)) return@launchCommand
            if (wasPlaying) {
                machine!!
                    .state.value.position
                    ?.let { startPrefill(it) }
                PlaybackActive.markStarted()
                // C2: the rebuild went through stopEverything — the focus
                // request survived it (session-scoped, decisions #135), but a
                // prior full LOSS may have left us without the grant; re-ask
                // so this resumed session is a focus participant again.
                requestFocus()
                startForeground(NOTIFICATION_ID, buildNotification())
                publish()
                startLoop()
            } else {
                publish()
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun resumePlayer(bookId: String? = null) {
        val active = machine
        if (active == null) {
            // The service restarted with no machine (STOP's post-stop fill
            // self-stopped it, or the process recycled) while the reader
            // stayed open: there is nothing to resume into. With the
            // reader's book id, rebuild the machine and resume from the
            // persisted playhead — the play button must never be dead.
            val id = bookId ?: return
            startPlayback(id, explicit = false)
            return
        }
        val phase = active.state.value.phase
        if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.LOADING) return
        if (selector.engine() == null) return
        stopEverything()
        requestFocus()
        launchCommand { generation ->
            settings.reload() // V1: voice changes from settings apply on resume
            applyPlaybackVolume()
            if (phase == PlayerPhase.COMPLETED) {
                active.playFrom(PlayerPosition(active.bookId, 0, 0))
            } else if (active.resume() == null) {
                // A fresh book (or one opened without playing yet): start
                // from the first passage instead of doing nothing.
                active.playFrom(active.firstPosition() ?: return@launchCommand)
            }
            if (!active(generation)) return@launchCommand
            queue = buildQueue()
            active.state.value.position
                ?.let { startPrefill(it) }
            // G2: the session window starts here and ends only when the
            // post-stop fill completes (markStopped in startPostStopPrefill).
            PlaybackActive.markStarted()
            startForeground(NOTIFICATION_ID, buildNotification())
            publish()
            startLoop()
        }
    }

    /**
     * Pause: cancels everything in flight (the load/generation loop included)
     * and publishes PAUSED on every surface — but only if no newer command
     * superseded this pause before its publish (CR-5/CR-7).
     */
    internal fun pausePlayer(
        reason: PauseReason,
        resumeOnGain: Boolean = false,
    ) {
        this.resumeOnGain = resumeOnGain
        val active = machine ?: return
        val live = liveOffsetSeconds()
        stopEverything()
        launchCommand { generation ->
            active.pause(live)
            if (active(generation)) publish()
        }
    }

    internal fun navigate(move: suspend (PlayerStateMachine) -> List<PlayerEvent>) {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        val live = liveOffsetSeconds()
        stopEverything(stopFill = false)
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.notePlaybackOffset(live)
                move(active)
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                if (wasPaused) active.pause() // A7: navigation never resumes a paused playhead
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) {
                // D1: the long-lived follow-playhead fill SURVIVED
                // (stopFill=false) — its ensure re-arms from the machine's new
                // position on the next tick; guard only against an absent fill.
                if (pregenJob == null) {
                    active.state.value.position
                        ?.let { startPrefill(it) }
                }
                startLoop()
            }
        }
    }

    /**
     * Rolling seek (decisions #53): converts the playhead to global book-time
     * (chars/15 speech model), applies the delta, clamps to the book, and maps
     * back to a spine position — seeks roll across passage boundaries. The
     * machine's [PlayerStateMachine.seekTo] pushes the position being left for
     * one undo.
     */
    internal fun seekBy(deltaSeconds: Double) {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        val live = liveOffsetSeconds()
        stopEverything(stopFill = false)
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.notePlaybackOffset(live)
                val activeBook = book ?: return@launchCommand
                val position = active.state.value.position ?: return@launchCommand
                val target =
                    BookProgress.positionAt(
                        activeBook,
                        BookProgress.elapsedSeconds(activeBook, position) + deltaSeconds,
                    )
                active.seekTo(target)
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                // A7 (CR-7): seek repositions a paused playhead without resuming.
                if (wasPaused) active.pause()
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) {
                // D1: the long-lived follow-playhead fill SURVIVED
                // (stopFill=false) — its ensure re-arms from the machine's new
                // position on the next tick; guard only against an absent fill.
                if (pregenJob == null) {
                    active.state.value.position
                        ?.let { startPrefill(it) }
                }
                startLoop()
            }
        }
    }

    private fun navigateUndo() {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        stopEverything(stopFill = false)
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.undoSkip()
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                if (wasPaused) active.pause() // A7: undo never resumes a paused playhead
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) {
                // D1: the long-lived follow-playhead fill SURVIVED
                // (stopFill=false) — its ensure re-arms from the machine's new
                // position on the next tick; guard only against an absent fill.
                if (pregenJob == null) {
                    active.state.value.position
                        ?.let { startPrefill(it) }
                }
                startLoop()
            }
        }
    }

    private fun cycleSleepTimer() {
        val active = machine ?: return
        val next =
            when (active.state.value.sleepTimer) {
                SleepTimer.Off -> SleepTimer.EndOfChapter
                SleepTimer.EndOfChapter -> SleepTimer.Duration(clock() + 30 * 60_000L)
                is SleepTimer.Duration -> SleepTimer.Off
            }
        active.setSleepTimer(next)
        publish()
    }

    private fun addBookmarkAtPlayhead() {
        val active = machine ?: return
        val position = active.state.value.position ?: return
        val label = book?.passageText(position.chapterIndex, position.passageIndex)?.take(48)
        scope.launch {
            active.notePlaybackOffset(liveOffsetSeconds())
            active.addBookmark(label = label)
            refreshBookmarks()
            publish()
        }
    }

    private fun stopPlayer() {
        // G2: the STOP command does NOT end the session window — the post-stop
        // fill still synthesizes, and a yielding pregen worker must stay
        // paused until that fill completes (markStopped fires there, after
        // which stopSelf/onDestroy tears the service down).
        // Capture the playhead BEFORE captureAndStop resets the machine — the
        // post-stop fill resumes from where listening stopped.
        val stopPos = machine?.state?.value?.position
        captureAndStop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Keep filling the next buffer after STOP so the next open reads it from
        // disk (no cold gap), then self-stop when full or the budget elapses.
        if (stopPos != null) {
            startPostStopPrefill(stopPos)
        } else {
            stopSelf()
        }
    }

    /**
     * CR-2: captures the live playhead BEFORE any output teardown and hands
     * the single authoritative final write to [finalStopJob]. [stopEverything]
     * releases [output] (zeroing its head), so capturing first is what keeps
     * STOP from rewinding persistence to the buffer's start offset.
     * Returns the captured book-time offset.
     */
    internal fun captureAndStop(): Double {
        val finalOffset = liveOffsetSeconds()
        stopEverything()
        releaseAudioFocus()
        finalStopJob =
            scope.launch {
                machine?.stop(finalOffset)
                PlaybackStateHolder.reset()
            }
        return finalOffset
    }

    // ------------------------------------------------------------------
    // Playback loop

    private suspend fun startLoop() {
        loopJob = coroutineContext[Job]
        tickerJob = scope.launch { withContext(playerDispatcher) { ticker() } }
        withContext(playerDispatcher) {
            runLoop()
        }
    }

    /** The playback loop body, on the dedicated player thread (decisions #85). */
    private suspend fun runLoop() {
        while (true) {
            val active = machine ?: return
            val current = active.state.value
            if (current.phase != PlayerPhase.LOADING) return
            val position = current.position ?: return
            val activeBook = book ?: return
            val text = activeBook.passageText(position.chapterIndex, position.passageIndex) ?: return

            // Fast path (T5), now with two tiers: the in-memory look-ahead queue
            // first, then the offline disk cache (#42) — a passage pre-generated
            // overnight plays without synthesis; a cold/jumped passage falls
            // back to a synchronous synthesize.
            val voice = activeVoice()
            val key = PregenKey(activeBook.id, position.chapterIndex, position.passageIndex, voice, current.speed)
            // Deterministic re-seek (layer 2): in-flight first-listen persists
            // land before any re-fetch, so a played passage is always on disk.
            pendingPersists.forEach { it.join() }
            val fromLast = lastAudio?.takeIf { it.first == key }?.second
            val fromQueue =
                fromLast
                    ?.let { null }
                    ?: queue?.take(position.chapterIndex, position.passageIndex)
            val fromDisk =
                fromQueue
                    ?.let { null }
                    ?: pregenCache.cache.get(key)
            val outcome =
                fromLast
                    ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                    ?: fromQueue
                        ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                    ?: fromDisk
                        ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                    ?: bufferForPlayback(position, voice, current.speed, text)
            val sourceKey =
                when {
                    fromLast != null -> "buffer"
                    fromQueue != null -> "pregen"
                    fromDisk != null -> "disk"
                    else -> "synthesized"
                }
            android.util.Log.d("PlaybackService", "loop: source=$sourceKey")
            val audio =
                outcome as? SynthesisOutcome.Audio ?: run {
                    val reason = (outcome as? SynthesisOutcome.Failed)?.reason ?: "engine/packs unavailable"
                    PlaybackStateHolder.update { it.copy(failure = "synthesis failed: $reason") }
                    return
                }
            // First listen of this passage (no source had it): persist it so
            // an offline run never redoes the work — normal use fills the cache.
            // The write is tracked so seek paths join it (deterministic disk).
            if (fromLast == null && fromQueue == null && fromDisk == null) {
                val toCache = PregenAudio(audio.pcm, audio.sampleRateHz, audio.segments)
                pendingPersists +=
                    scope.launch(Dispatchers.IO) {
                        pregenCache.cache.put(key, toCache)
                        // A just-listened passage is now on disk — repaint the bar.
                        coverageDirty = true
                    }
            }
            // Keep the rendered passage for same-passage seek reuse (layer 1).
            lastAudio = key to PregenAudio(audio.pcm, audio.sampleRateHz, audio.segments)
            segments = audio.segments ?: emptyList()
            baselineOffset = position.offsetSeconds
            lastSampleRateHz = audio.sampleRateHz
            active.onAudioStarted()
            val playStart = if (probesActive) clock() else 0L
            val sliced = sliceForSpeed(audio.pcm, baselineOffset, audio.sampleRateHz, current.speed)
            output.play(sliced, audio.sampleRateHz, current.speed)
            // The generation notification covered the LOADING wait; audio is
            // playing now — drop it immediately. publish() would only fire at the
            // next passage boundary (onAudioStarted does not publish), which is a
            // whole passage too late for "disappears when audio starts".
            cancelGenerationNotification()
            if (probesActive) {
                probe(
                    "AyvuPlay",
                    "out=${clock() - playStart} frames=${sliced.size / 2} passage=${position.chapterIndex}/${position.passageIndex}",
                )
            }
            // Pre-arm (decisions #84): while the current passage plays, stage
            // the NEXT passage's track so the boundary swaps instead of
            // rebuilding (the measured 29-55 ms critical-path cost). Peek the
            // queue, then the disk tier, for the next passage's size/rate; a
            // miss just skips the pre-arm (the boundary falls back to build).
            runCatching {
                val next = nextPosition(position, activeBook) ?: return@runCatching
                val nextPcm =
                    queue
                        ?.peek(next.chapterIndex, next.passageIndex)
                        ?: pregenCache.cache.get(PregenKey(activeBook.id, next.chapterIndex, next.passageIndex, voice, current.speed))
                if (nextPcm != null) output.prearm(nextPcm.pcm.size, nextPcm.sampleRateHz)
            }
            // Measurement probes (goals §Measurement): tap-to-audio at the
            // first frame actually written to AudioTrack, and the boundary
            // gap vs the previous CONSECUTIVE same-loop play. gap-ms is an
            // approximation: play(N+1) dispatch minus (play(N) dispatch plus
            // play(N)'s rendered duration) — the true passage end is not
            // observable without an AudioTrack marker callback.
            if (probesActive) {
                val frames = sliced.size / 2
                val now = clock()
                val markerUsed = lastMarkerAt > 0L
                val gapMs =
                    if (markerUsed) {
                        // Marker-accurate audible gap (decisions #81): audio N's
                        // exact end (marker callback) to this play's dispatch —
                        // no poll quantization. Falls back to [computeGapMs] when
                        // the marker did not fire (unreliable-device concern).
                        val g = now - lastMarkerAt
                        lastMarkerAt = 0L
                        g.takeIf { it >= 0 }
                    } else {
                        computeGapMs(now, playAt, prevFrames, audio.sampleRateHz)
                    }
                if (gapMs != null) {
                    probe(
                        "AyvuGap",
                        "gap-ms=$gapMs m=${if (markerUsed) 1 else 0} passage=${position.chapterIndex}/${position.passageIndex}",
                    )
                }
                playAt = now
                prevFrames = frames
                if (tapAt != 0L) {
                    probe("AyvuTap", "tap-to-audio ms=${now - tapAt} source=$sourceKey action=${tapAction ?: "unknown"}")
                    tapAt = 0L
                }
            }
            // Pre-generation is a background prefill job (startPrefill), not a
            // per-boundary cancel-relaunch: a cancelled synthesis never finishes
            // and every passage change paid a cold restart. The fill job only
            // stops on stopEveryState/rebuild.
            val finished = awaitPlaybackOrStop(sliced.size / 2, audio.sampleRateHz, active)
            android.util.Log.d(
                "PlaybackService",
                "loop: await returned finished=$finished (pos=${output.positionSamples}/${sliced.size / 2})",
            )
            if (!finished) return

            // Boundary stage-attribution (decisions #82): the marker-accurate
            // gap is the sum of the advance+write, the structural publish,
            // and the next dispatch's source resolution. Logged once per
            // boundary so the ~70 ms residual can be pinned instead of
            // guessed at.
            val b0 = if (probesActive) clock() else 0L
            val events = active.onPassageFinished()
            val b1 = if (probesActive) clock() else 0L
            android.util.Log.d("PlaybackService", "loop: onPassageFinished events=$events")
            if (events.isEmpty()) return
            if (events.any { it is PlayerEvent.PlaybackCompleted || it is PlayerEvent.PauseRequested }) {
                publish()
                return
            }
            publish()
            if (probesActive) {
                probe(
                    "AyvuBoundary",
                    "adv=${b1 - b0} pub=${clock() - b1} passage=${position.chapterIndex}/${position.passageIndex}",
                )
            }
        }
    }

    private suspend fun awaitPlaybackOrStop(
        totalFrames: Int,
        sampleRate: Int,
        active: PlayerStateMachine,
    ): Boolean {
        // Marker-based completion (decisions #81): an exact end-of-buffer
        // marker removes the 50 ms poll quantization from the boundary gap —
        // the poll stays as the fallback for devices where static markers
        // never fire. 10 ms margin from the rendered rate (S5).
        val marker = CompletableDeferred<Unit>()
        output.setCompletionMarker(totalFrames) {
            if (probesActive) lastMarkerAt = clock()
            marker.complete(Unit)
        }
        val target = totalFrames - frameMargin(sampleRate)
        var lastSeen = output.positionSamples
        var stallLoggedAt = 0L
        while (true) {
            if (stopSignal.isCompleted) return false
            val pos = output.positionSamples
            if (marker.isCompleted || pos >= target) return true
            // Stall diagnostic (probes only): if the head has not advanced for
            // 5 s, log the output's live state once — the streaming track on
            // the S22 stalled silently (no head movement, no error), and the
            // loop cannot tell why without this.
            if (probesActive && pos == lastSeen) {
                val now = clock()
                if (stallLoggedAt == 0L || now - stallLoggedAt >= 5_000) {
                    stallLoggedAt = now
                    probe("AyvuStall", "pos=$pos target=$target frames=$totalFrames")
                }
            } else {
                lastSeen = pos
                stallLoggedAt = 0L
            }
            // CR-2: throttled live-playhead checkpoint while playing — abrupt
            // process death loses at most one interval, not a whole passage.
            // Runs in the player coroutine, so it cannot race the machine's
            // own passage transitions (single-player-writer edge).
            if (dueCheckpoint(clock())) active.notePlaybackOffset(liveOffsetSeconds())
            // Wake on the marker (immediate end) or the poll tick (fallback).
            select<Unit> {
                marker.onAwait { Unit }
                onTimeout(50) { Unit }
            }
        }
    }

    /** CR-2 checkpoint gate: true at most every [CHECKPOINT_MS] of wall time
     * (persistence cadence — never every UI tick). */
    internal fun dueCheckpoint(nowMs: Long): Boolean {
        if (nowMs - lastCheckpointAt < CHECKPOINT_MS) return false
        lastCheckpointAt = nowMs
        return true
    }

    private suspend fun ticker() {
        while (true) {
            delay(TICK_MS)
            val active = machine ?: return
            val events = active.advance(clock())
            if (events.any { it == PlayerEvent.PauseRequested }) {
                val live = liveOffsetSeconds()
                stopEverything()
                active.pause(live)
                publish()
                return
            }
            if (events.isEmpty() && active.state.value.phase in SETTLED_PHASES) publishDetails()
        }
    }

    private fun publish() {
        val active =
            machine ?: run {
                PlaybackStateHolder.reset()
                return
            }
        val state = active.state.value
        // The reader's per-second progress/read-along surface (StateFlow
        // only) is always kept current — cheap, no IPC.
        PlaybackStateHolder.update { stateCopy(it, active) }
        // MediaSession-boundary gate (decisions #85): the session carries only
        // the book title + play/pause state, NEITHER of which changes at a
        // passage boundary — so per-boundary setMetadata/setPlaybackState is
        // pure IPC churn (a measurable part of the boundary gap, like the
        // notify that #82 already gated). Update the session only when its
        // content actually changes (book or phase).
        val sessionKey = "${book?.id}|${state.phase}"
        if (sessionKey != lastSessionKey) {
            session.setMetadata(
                MediaMetadataCompat
                    .Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book?.title ?: "")
                    // The now-playing/notification "artist" line — brand it Ayvu
                    // (was the stale module/package name, decisions #113).
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Ayvu")
                    .build(),
            )
            session.setPlaybackState(
                PlaybackStateCompat
                    .Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_STOP,
                    ).setState(
                        when (state.phase) {
                            PlayerPhase.PLAYING, PlayerPhase.LOADING -> PlaybackStateCompat.STATE_PLAYING
                            PlayerPhase.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                            PlayerPhase.COMPLETED -> PlaybackStateCompat.STATE_STOPPED
                            PlayerPhase.IDLE -> PlaybackStateCompat.STATE_NONE
                        },
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    ).build(),
            )
            lastSessionKey = sessionKey
        }
        // Boundary-path optimization (decisions #82): re-notifying every
        // passage is an IPC to system_server on the player coroutine — a
        // measurable part of the measured boundary gap. The visible content
        // (book + play/pause action) only changes on book/phase/transport
        // transitions, so skip the notify when those are unchanged.
        val notifyKey = buildNotifyKey(state)
        if (notifyKey != lastNotifiedKey) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
            lastNotifiedKey = notifyKey
        }
        // Generation notification: visible exactly while (phase == LOADING) or
        // the post-stop cushion fill runs. publish() fires on structural
        // transitions — this cancels any lingering entry outside those windows.
        if (state.phase != PlayerPhase.LOADING && !postStopFill) cancelGenerationNotification()
    }

    /** Per-second read-along/progress publish (S3, goals G1/G3): StateFlow
     * ONLY — no MediaSession rebuild, no notification — so the 1 s ticker
     * stops re-`notify`ing notification 42 and resetting the session state
     * every second. Same field computation as [publish] via [stateCopy], so
     * the two paths cannot drift (CR-8/CR-9). */
    internal fun publishDetails() {
        val active = machine ?: return
        PlaybackStateHolder.update { stateCopy(it, active) }
    }

    /** The full [PlaybackUiState] field computation — the single copy block
     * the repo has burned on twice (CR-8/CR-9 collateral drops). Both
     * [publish] (structural snapshot) and [publishDetails] (per-second feed)
     * drive through here so every field stays populated on every path. */
    private fun stateCopy(
        base: PlaybackUiState,
        active: PlayerStateMachine,
    ): PlaybackUiState {
        val state = active.state.value
        val position = state.position
        // Derived once per copy: the smooth char-weighted playhead (book-time
        // elapsed/total at 1.0×) and the coverage snapshot (cached until
        // dirty — a recompute walks the book's chars, fine at 1 Hz).
        val elapsedSeconds = position?.let { p -> book?.let { BookProgress.elapsedSeconds(it, p) } } ?: 0.0
        val totalSeconds = book?.let { BookProgress.totalSeconds(it) } ?: 0.0
        val coverage = coverageSnapshot()
        return base.copy(
            bookId = active.bookId,
            bookTitle = book?.title ?: "",
            authors = book?.authors ?: emptyList(),
            chapterIndex = position?.chapterIndex ?: 0,
            passageIndex = position?.passageIndex ?: 0,
            // Book-wide passage position (item 4): chapter prefix sums + the
            // in-chapter index — the book is resident in memory (CR-9 reads
            // book.chapters for the stitched reader), so no Room query and
            // no cache. Chapters are ordered by index (model contract).
            bookPassageIndex =
                book?.let {
                    it.chapters.filter { ch -> ch.index < (position?.chapterIndex ?: 0) }.sumOf { ch -> ch.passages.size } +
                        (position?.passageIndex ?: 0)
                } ?: 0,
            bookPassageCount = book?.chapters?.sumOf { it.passages.size } ?: 0,
            passageText = position?.let { p -> book?.passageText(p.chapterIndex, p.passageIndex) } ?: "",
            passageDurationSeconds = segments.lastOrNull()?.endSeconds ?: 0.0,
            chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList(),
            chapterPassages =
                position?.let { p ->
                    book
                        ?.chapters
                        ?.firstOrNull { it.index == p.chapterIndex }
                        ?.passages
                        ?.map { it.text }
                } ?: emptyList(),
            segments = segments,
            offsetSeconds = liveOffsetSeconds(),
            readFraction = position?.let { p -> book?.let { BookProgress.fraction(it, p.chapterIndex, p.passageIndex) } } ?: 0f,
            elapsedSeconds = elapsedSeconds,
            playheadFraction = if (totalSeconds > 0.0) (elapsedSeconds / totalSeconds).toFloat().coerceIn(0f, 1f) else 0f,
            coverageSpans = coverage.first,
            chapterMarks = coverage.second,
            timeLeftSeconds =
                position?.let { p ->
                    book?.let { BookProgress.remainingSeconds(it, p.chapterIndex, p.passageIndex, liveOffsetSeconds(), state.speed) }
                } ?: 0.0,
            generatedAheadSeconds = position?.let { queue?.aheadSeconds(it) } ?: 0.0,
            speed = state.speed,
            phase = state.phase,
            degraded = selector.isDegraded,
            sleepTimer = state.sleepTimer,
            canUndo = ringHasEntries,
            failure = state.failure ?: PlaybackStateHolder.state.value.failure,
        )
    }

    /**
     * The torrent-bar snapshot for the current book/voice/speed: run-length
     * coverage spans + chapter ticks. Recomputed only when marked dirty
     * ([coverageDirty]) or the `bookId|voice|speed` key changed — every
     * persist source marks dirty, so the bar tracks the disk tier without
     * walking it per tick. Returns the empty pair when no book/machine is
     * present (nothing publishable yet).
     */
    private fun coverageSnapshot(): Pair<List<CoverageSpan>, List<Float>> {
        val activeBook = book ?: return emptyList<CoverageSpan>() to emptyList<Float>()
        val active = machine ?: return emptyList<CoverageSpan>() to emptyList<Float>()
        // Host-test seam: the suite's direct-constructed services may lack the
        // Hilt-primed cache (publish runs inside command coroutines — an
        // uninitialized field would die silently there). Hilt always injects
        // on device; the guard is a no-op there.
        if (!::pregenCache.isInitialized) return emptyList<CoverageSpan>() to emptyList<Float>()
        val voice = activeVoice()
        val speed = active.state.value.speed
        val key = "${activeBook.id}|$voice|$speed"
        if (!coverageDirty && coverageKey == key) return coverageCache
        val keys = pregenCache.cache.generatedKeys(activeBook.id, voice, speed)
        coverageCache = CoverageEncoder.spans(activeBook, keys) to CoverageEncoder.chapterMarks(activeBook)
        coverageKey = key
        coverageDirty = false
        return coverageCache
    }

    /**
     * Manual pregen progress → coverage refresh: observes the book's unique
     * WorkInfo flow and marks the snapshot dirty + re-publishes on EVERY
     * emission, so the bar fills teal span-by-span even while paused (no
     * ticker runs there). Cancelled by [stopEverything] and re-armed on every
     * queue rebuild ([buildQueue] — the same book/voice/speed lifecycle as
     * the coverage key).
     */
    private fun startCoverageObserver(bookId: String) {
        coverageObserverJob?.cancel()
        coverageObserverJob = null
        // Host-test seam: direct-constructed services never inject
        // [pregenManager], and calling workInfoFlow without WorkManager init
        // throws. Hilt injects it on device before onCreate — the guard is a
        // no-op there; the manual-pregen bar refresh simply doesn't run in
        // host tests (the snapshot still recomputes on every dirty source).
        if (!::pregenManager.isInitialized) return
        coverageObserverJob =
            scope.launch {
                pregenManager.workInfoFlow(bookId).collect {
                    coverageDirty = true
                    publishDetails()
                }
            }
    }

    /** Live playhead in book-time seconds within the passage. */
    private fun liveOffsetSeconds(): Double {
        val active = machine ?: return 0.0
        // The buffer is book-time and sped by setPlaybackRate, so the head
        // position counts book-time frames at ANY speed (decisions #52); the
        // frame→time conversion uses the rendered rate, not a kokoro constant
        // (S5 — swaps to a future engine without miscomputing the playhead).
        return baselineOffset + output.positionSamples / lastSampleRateHz.toDouble()
    }

    // ------------------------------------------------------------------
    // Media session / focus / noisy / notification

    private val mediaCallback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                // Measurement probe (goals §Measurement, L3): media-button/headset
                // resume tap — in-process resume AND the post-death rebuild (arms
                // before resumePlayer's machine==null startPlayback).
                if (probesActive) probeTap(ACTION_RESUME)
                resumePlayer(PlaybackStateHolder.state.value.bookId)
            }

            override fun onPause() = pausePlayer(PauseReason.USER)

            override fun onStop() = stopPlayer()

            override fun onSkipToNext() = navigate { it.skipForward() }

            override fun onSkipToPrevious() = navigate { it.skipBackward() }
        }

    private fun requestFocus() {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    /** Applies the persisted playback gain (settings) times the ducking
     * factor to the output. Called at every play start and on focus
     * gain/loss; [AudioTrackPassageOutput] re-applies the value to each
     * rebuilt passage track, so the gain survives track rebuilds. */
    private fun applyPlaybackVolume() {
        val gain = settings.state.value.playbackGain * if (ducking) DUCK_VOLUME else 1f
        output.setVolume(gain)
    }

    /** Focus loss → pause, but ONLY while actually listening: with focus now
     * session-scoped (decisions #135) an open-but-idle/paused book also holds
     * the request, and pausing a non-playing machine would spurious-publish
     * PAUSED. [PlayerStateMachine.pause] sets PAUSED unconditionally once a
     * position exists — the guard is what keeps a call during idle reading a
     * no-op. */
    private fun pauseForFocus(resumeOnGain: Boolean) {
        val phase = machine?.state?.value?.phase
        if (phase != PlayerPhase.PLAYING && phase != PlayerPhase.LOADING) return
        pausePlayer(PauseReason.FOCUS, resumeOnGain = resumeOnGain)
    }

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (ducking) {
                        ducking = false
                        applyPlaybackVolume()
                    }
                    if (resumeOnGain && machine?.state?.value?.phase == PlayerPhase.PAUSED) {
                        resumeOnGain = false
                        resumePlayer()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    ducking = false
                    // Full loss (another app took over — music, a call): pause
                    // and never auto-resume; the user's next play re-requests.
                    pauseForFocus(resumeOnGain = false)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseForFocus(resumeOnGain = true)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducking = true
                    applyPlaybackVolume()
                }
            }
        }

    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayer(PauseReason.NOISY)
            }
        }

    /** The transport-relevant identity of the media notification's visible
     * content (decisions #82): book + whether the play/pause action shows
     * play or pause. The passage ordinal is deliberately excluded — it
     * changes every boundary and is what forced the per-passage IPC; the
     * shade may show a stale ordinal until the next phase/transport change
     * (a fresh notify fires then, and the ordinal updates). */
    private fun buildNotifyKey(state: PlayerState): String? {
        val phase = state.phase
        return "${book?.id}|${phase == PlayerPhase.PLAYING || phase == PlayerPhase.LOADING}"
    }

    /** The tap target shared by both notifications: open the app. */
    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun buildNotification(): Notification {
        val openApp = openAppIntent()

        fun action(
            intentAction: String,
            icon: Int,
            label: String,
        ) = NotificationCompat.Action(
            icon,
            label,
            PendingIntent.getService(
                this,
                intentAction.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(intentAction).putExtra(EXTRA_BOOK_ID, book?.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val active = machine?.state?.value
        val playing = active?.phase == PlayerPhase.PLAYING || active?.phase == PlayerPhase.LOADING
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(book?.title ?: "Ayvu")
            .setContentText(
                "Chapter ${(active?.position?.chapterIndex ?: 0) + 1} · Passage ${(active?.position?.passageIndex ?: 0) + 1}",
            ).setLargeIcon(coverArt())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_SKIP_BACKWARD, android.R.drawable.ic_media_previous, "Previous"))
            .addAction(
                action(
                    if (playing) ACTION_PAUSE else ACTION_RESUME,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                ),
            ).addAction(action(ACTION_SKIP_FORWARD, android.R.drawable.ic_media_next, "Next"))
            .addAction(action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Stop"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(1))
            .build()
    }

    /** The passage after [position] in spine order, or null at the book's end
     * (decisions #84 — the boundary pre-arm target). */
    private fun nextPosition(
        position: PlayerPosition,
        book: Book,
    ): PlayerPosition? {
        var chapter = position.chapterIndex
        var passage = position.passageIndex + 1
        while (chapter < book.chapters.size) {
            if (passage < book.chapters[chapter].passages.size) {
                return PlayerPosition(book.id, chapter, passage)
            }
            chapter += 1
            passage = 0
        }
        return null
    }

    /** The book's cover bitmap for the media notification, cached per book
     * and downsampled to ≤ ~512 px (album-art only — full res is overkill). */
    private fun coverArt(): Bitmap? {
        val id = book?.id ?: return null
        if (coverArtBookId == id) return coverArt
        coverArtBookId = id
        val file = File(filesDir, "covers/$id")
        coverArt =
            if (file.isFile) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            } else {
                null
            }
        return coverArt
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ayvu playback", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(GENERATION_CHANNEL_ID, "Audio generation", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /**
     * Throttled (≥1 s) in-place refresh of the generation notification:
     * indeterminate while the buffer holds 0 s (engine loading), then
     * determinate against the fill's own D1 horizon ([PREFILL_LOOKAHEAD_SECONDS]).
     * Id 44 — deliberately NOT 43, which the pregen worker already owns: the
     * playback-time and manual notifications must coexist (id 43 would
     * overwrite the manual run's in place).
     */
    private fun showGenerationNotification(
        bookTitle: String,
        aheadSeconds: Double,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastGenerationNotifyAt < GENERATION_NOTIFY_THROTTLE_MS) return
        lastGenerationNotifyAt = now
        val percent = (aheadSeconds / PREFILL_LOOKAHEAD_SECONDS * 100).toInt().coerceIn(0, 100)
        runCatching {
            NotificationManagerCompat.from(this).notify(
                GENERATION_NOTIFICATION_ID,
                NotificationCompat
                    .Builder(this, GENERATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Generating audio")
                    // The indeterminate case (ahead == 0) reads "0%"; the bar's
                    // determinate fill carries it to 100 as the cushion builds.
                    .setContentText("$bookTitle — $percent%")
                    .setUsesChronometer(true)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, percent, aheadSeconds <= 0.0)
                    .setContentIntent(openAppIntent())
                    .build(),
            )
        }
    }

    private fun cancelGenerationNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(GENERATION_NOTIFICATION_ID) }
    }

    private fun buildQueue(): PregenQueue? {
        val active = machine ?: return null
        val activeBook = book ?: return null
        val speed = active.state.value.speed
        // A queue rebuild is a voice/speed/book change — the coverage key
        // changes with it; force a recompute and re-arm the manual-pregen
        // observer on the new book (it cancels its predecessor).
        coverageDirty = true
        startCoverageObserver(activeBook.id)
        return PregenQueue(
            book = activeBook,
            voice = activeVoice(),
            speed = speed,
            synthesize = { text ->
                selector.engine()?.synthesize(SynthesisRequest(text, activeVoice(), speed = speed))
                    ?: SynthesisOutcome.Failed("engine unavailable")
            },
            lookahead = PREFILL_LOOKAHEAD_PASSAGES,
            lookaheadSeconds = PREFILL_LOOKAHEAD_SECONDS,
            onSynthesized = { key, audio ->
                // Persist every pre-generated passage so a later open reads it
                // from disk (`source=disk`) — the "ready next time" tier. A disk
                // failure must never kill the fill; the audio still plays this session.
                runCatching { pregenCache.cache.put(key, audio) }
                    .onFailure { android.util.Log.w("PlaybackService", "pregen persist failed for $key", it) }
                // A passage just landed on disk — the coverage bar must repaint.
                coverageDirty = true
            },
        )
    }

    /**
     * Front-loads and keeps a time-buffer of audio ahead of the playhead. Runs
     * a single long-lived job that re-ensures the queue toward
     * [PREFILL_LOOKAHEAD_SECONDS] so Kokoro (RTF <1) stays ahead of 1x playback
     * and a book opened while idle warms its opening. Cancelled only on stop /
     * queue rebuild (speed/voice/book change) — never at a passage boundary,
     * which used to kill the in-flight synthesis cold (the 24 s gap).
     */
    private fun startPrefill(from: PlayerPosition) {
        // A playhead-following fill supersedes the post-stop cushion: the
        // generation notification is no longer backed by post-stop work.
        postStopFill = false
        startFill(from, followPlayhead = true, deadlineMs = null)
    }

    /**
     * One parameterized fill job (QW4, decisions #78): the single owner of
     * the pre-generation loop, replacing the duplicated
     * startPrefill / bufferForPlayback-ensure / startPostStopPrefill shapes.
     * [followPlayhead] = true re-arms from the live playhead every tick and
     * runs until cancelled (booking open prefill); false pins [from] and
     * stops once [PREFILL_LOOKAHEAD_SECONDS] are queued or [deadlineMs]
     * elapses (post-STOP fill). [onDone] fires on the completion path (the
     * post-stop fill clears the G2 session window and self-stops there).
     */
    private fun startFill(
        from: PlayerPosition,
        followPlayhead: Boolean,
        deadlineMs: Long?,
        onDone: (suspend () -> Unit)? = null,
    ) {
        val q = queue ?: return
        pregenJob?.cancel() // superseded queue (speed/voice/book) — safe to drop
        pregenJob =
            scope.launch {
                // Conditional-yield signal (item 5): the fill occupies the
                // shared engine for its whole session.
                PlaybackActive.markEngineUsed()
                try {
                    val startedAt = System.currentTimeMillis()
                    while (isActive) {
                        if (deadlineMs != null && System.currentTimeMillis() - startedAt >= deadlineMs) break
                        // Re-arm from the live playhead every tick so consumed passages
                        // stay pruned and the fill tracks the moving position; [from]
                        // is the start (a book opened idle front-loads its opening).
                        val playhead = if (followPlayhead) machine?.state?.value?.position ?: from else from
                        // D1: the live playhead is re-checked BETWEEN passages inside
                        // ensure, so a seek during a fill does not synthesize the
                        // stale plan (the plan yields once the playhead overtakes the
                        // next planned key).
                        q.ensure(playhead) { machine?.state?.value?.position }
                        // Post-stop arm: feed the generation notification with
                        // the cushion's fill progress (throttled inside).
                        if (!followPlayhead) {
                            showGenerationNotification(book?.title ?: "Ayvu", q.aheadSeconds(playhead))
                        }
                        if (!followPlayhead && q.aheadSeconds(playhead) >= PREFILL_LOOKAHEAD_SECONDS) {
                            android.util.Log.d("PlaybackService", "postStop: fill done ahead=${q.aheadSeconds(playhead)} self-stopping")
                            break
                        }
                        delay(PREFILL_TICK_MS)
                    }
                } finally {
                    PlaybackActive.markEngineStopped()
                }
                onDone?.invoke()
            }
    }

    /**
     * Buffer-before-start for a cold/jumped passage: the queue only renders
     * passages strictly AFTER [position], so instead of waiting for [position]
     * itself (which would spin to the timeout), wait until the SAME buffer the
     * prefill maintains is queued AHEAD of the playhead, then synthesize the
     * cold passage synchronously — playback starts in steady state instead of
     * stalling at the first boundary. Loop inbound only (suspends the play);
     * bounded by [PLAY_BUFFER_TIMEOUT_MS] for engines slower than real-time
     * (the wait then ends early with whatever buffer exists).
     *
     * RTF hook (item 8, D2): when the persisted tri-state says the engine is
     * realtime (wall ≤ audio over ≥ 10 s of samples), the wait is skipped —
     * the current passage resolves from the synchronous synthesis below fast
     * enough that the look-ahead cushion buys nothing. [PLAY_BUFFER_TIMEOUT_MS]
     * stays the hard cap for the slow/unmeasured path. Degraded (system-TTS)
     * is treated as realtime-by-not-gating: it does NOT benefit from the
     * cushion either, but its engine is outside the Kokoro measurement — the
     * hook is gated on non-degraded so the degraded path keeps today's wait
     * byte-for-byte (the fill still polls aheadSeconds on it).
     */
    private suspend fun bufferForPlayback(
        position: PlayerPosition,
        voice: String,
        speed: Double,
        text: String,
    ): SynthesisOutcome {
        val q = queue
        val realtime = !selector.isDegraded && settings.state.value.realtimeCapable == true
        if (q != null && !stopSignal.isCompleted && !realtime) {
            val startedAt = System.currentTimeMillis()
            android.util.Log.d("PlaybackService", "buffer: waiting for $PREFILL_LOOKAHEAD_SECONDS s ahead")
            while (
                q.aheadSeconds(position) < PREFILL_LOOKAHEAD_SECONDS &&
                System.currentTimeMillis() - startedAt < PLAY_BUFFER_TIMEOUT_MS &&
                !stopSignal.isCompleted
            ) {
                // The long-lived fill job (startFill/followPlayhead) already
                // ensures toward this same target — polling here avoids an
                // extra contended ensure per 50 ms (QW4).
                showGenerationNotification(book?.title ?: "Ayvu", q.aheadSeconds(position))
                delay(50)
            }
            android.util.Log.d(
                "PlaybackService",
                "buffer: ahead=" + q.aheadSeconds(position) + "s after " +
                    (System.currentTimeMillis() - startedAt) + "ms",
            )
        }
        return try {
            // Conditional-yield signal (item 5): a synchronous buffer
            // synthesis holds the engine.
            PlaybackActive.markEngineUsed()
            val startedAt = System.currentTimeMillis()
            val outcome =
                selector.engine()?.synthesize(SynthesisRequest(text, voice, speed = speed))
                    ?: SynthesisOutcome.Failed("engine unavailable")
            // RTF lazy fallback (item 8): real passages measure the same
            // wall/audio pair as Preview; stop accumulating once a verdict
            // exists (realtimeCapable != null).
            if (outcome is SynthesisOutcome.Audio && settings.state.value.realtimeCapable == null) {
                val wallMs = System.currentTimeMillis() - startedAt
                val audioMs = outcome.pcm.size * 1000L / (outcome.sampleRateHz * 2L) // mono 16-bit
                settings.setRtfSample(wallMs, audioMs)
            }
            outcome
        } finally {
            PlaybackActive.markEngineStopped()
        }
    }

    /**
     * Keeps filling the buffer after STOP until the look-ahead target is met,
     * so the next open reads it from disk — no cold gap tomorrow morning.
     * Cancels the playback prefill (already stopped), runs a fresh fill from
     * [from] (the last playhead), and tears the service down once full or the
     * post-stop budget elapses. onSynthesized persists every passage to disk.
     */
    private fun startPostStopPrefill(from: PlayerPosition) {
        android.util.Log.d("PlaybackService", "postStop: fill start from ${from.chapterIndex}/${from.passageIndex}")
        // While this fill runs, publishing (phase != LOADING) must NOT cancel
        // the generation notification the fill is feeding.
        postStopFill = true
        startFill(from, followPlayhead = false, deadlineMs = POST_STOP_MAX_MS) {
            // G2: the session window ends when the fill completes — a yielding
            // pregen worker may resume only once post-stop synthesis is done.
            PlaybackActive.markStopped()
            stopSelf()
        }
    }

    /** The voice the ACTIVE engine serves for the stored global voice
     * (decisions #144 availability shape): engine-exposed ids pass through,
     * anything else falls back to that engine's default — the choke point
     * behind synthesis, coverage keys and the queue's engine-keyed paths.
     * D1 semantics unchanged for Kokoro (passthrough, as before). */
    private fun activeVoice(): String = selector.resolveVoice(settings.state.value.voice)

    /** Releases the session's audio focus — the counterpart of [requestFocus].
     * Only a true stop calls this ([captureAndStop]'s STOP path and [onDestroy]);
     * see the [stopEverything] comment for why in-place commands do not. */
    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        ducking = false
    }

    internal fun stopEverything(stopFill: Boolean = true) {
        // CR-5/CR-7: supersede every in-flight command BEFORE cancelling —
        // the generation check is what stops a stale load from publishing at
        // its (otherwise uncancellable) tail.
        commandGeneration++
        commandJob?.cancel()
        commandJob = null
        loopJob?.cancel()
        loopJob = null
        tickerJob?.cancel()
        tickerJob = null
        // The manual-pregen observer refreshes the coverage bar; it outlives
        // nothing — cancelling here keeps a stale observer off a dead book.
        coverageObserverJob?.cancel()
        coverageObserverJob = null
        postStopFill = false
        cancelGenerationNotification()
        // D1: in-place navigation (seek/navigate/undo) keeps the long-lived
        // follow-playhead fill alive so its in-flight ensure survives the
        // move and re-arms from the new playhead — only queue rebuilds
        // (book/voice/speed change) and true stops cancel it.
        if (stopFill) {
            pregenJob?.cancel()
            pregenJob = null
        }
        stopSignal.complete(Unit)
        stopSignal = CompletableDeferred()
        // Measurement probe baseline (goals §Measurement, GAP1): resume/seek/
        // stop breaks consecutive same-loop plays — the next play is a fresh
        // start, never a gap. The tap arm is deliberately preserved: an
        // open/play tap races its own command's stopEverything and must
        // survive to the first play dispatch.
        playAt = 0L
        prevFrames = 0
        lastMarkerAt = 0L // a cancelled track never fires its marker — no stale gap
        lastNotifiedKey = null // a fresh command re-notifies with current state
        lastSessionKey = null // a fresh command re-publishes the session
        output.stop()
        // Audio focus is SESSION-scoped (decisions #135): [stopEverything] runs
        // on EVERY command — including in-place seek/navigate/undo, whose tails
        // restart the play loop. Abandoning here dropped focus on those paths,
        // so playback continued WITHOUT a focus request: a Teams call or
        // WhatsApp message could neither pause it (no LOSS callback) nor duck
        // it (the system knows nothing about us). Keep the request alive across
        // commands; only a true stop ([captureAndStop]/[onDestroy]) releases it.
        // [requestFocus] re-requests on every play/resume anyway, so re-gaining
        // after another app took the focus is automatic.
    }

    /**
     * CR-5/CR-7: launches a command coroutine under the generation guard.
     * Every control-plane command routes through here (NOT
     * [addBookmarkAtPlayhead]/[refreshBookmarks] — those must not take over
     * command ownership). [active] checks before side effects are what make a
     * superseded command a no-op even when cancellation alone would not reach
     * it in time.
     */
    internal fun launchCommand(block: suspend (generation: Long) -> Unit) {
        val generation = commandGeneration
        commandJob = scope.launch { block(generation) }
    }

    /** CR-5/CR-7: true only for the current command generation. */
    private fun active(generation: Long): Boolean = generation == commandGeneration

    override fun onDestroy() {
        // G2 teardown safety net: the STOP path defers the clear to the
        // post-stop fill's completion, so this is what ends the window when
        // no fill runs (no machine) or the service dies mid-session. A fill
        // that already marked stopped makes this a harmless repeat clear.
        PlaybackActive.markStopped()
        cancelGenerationNotification()
        runBlocking {
            // CR-2: exactly one authoritative final write — join a graceful
            // STOP's write; otherwise write the captured playhead ourselves
            // (captured before teardown).
            teardownWrite()
            stopEverything()
            releaseAudioFocus() // the session ends with the service
            PlaybackStateHolder.reset()
        }
        session.isActive = false
        session.release()
        runCatching { unregisterReceiver(noisyReceiver) }
        scope.cancel()
        playerDispatcher.close() // the dedicated player thread (decisions #85)
        super.onDestroy()
    }

    /**
     * CR-2: the single final machine write. When a graceful STOP's write is in
     * flight ([finalStopJob]), joins it — never double-writing with a stale
     * offset computed from an already-released output. Otherwise captures the
     * live playhead first and writes it.
     */
    internal suspend fun teardownWrite() {
        val pending = finalStopJob
        if (pending != null) {
            pending.join()
        } else {
            val finalOffset = liveOffsetSeconds()
            machine?.stop(finalOffset)
        }
    }

    private fun Intent.bookId(): String? = getStringExtra(EXTRA_BOOK_ID)

    internal enum class PauseReason { USER, FOCUS, NOISY, SLEEP }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42

        /** Playback-time generation progress (step 5 of the coverage-bar
         * redo). 44, NOT 43: PregenWorker owns 43 for its manual-run
         * notification — same id would overwrite it (the two are distinct
         * operations that may run together). */
        private const val GENERATION_CHANNEL_ID = "generation"
        private const val GENERATION_NOTIFICATION_ID = 44

        /** In-place refresh cadence for the generation notification. */
        private const val GENERATION_NOTIFY_THROTTLE_MS = 1_000L
        private const val TICK_MS = 1_000L

        /** CR-2 live-playhead persistence cadence (roadmap A2). */
        internal const val CHECKPOINT_MS = 5_000L
        private const val SEEK_STEP_SECONDS = 30.0

        /** Kokoro's sample rate — the default before a passage renders (S5). */
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        private const val DUCK_VOLUME = 0.2f

        /** 10 ms of completion margin in frames at a rendered rate (S5). */
        private fun frameMargin(sampleRate: Int): Int = sampleRate / 100
        // D1 (decisions #155, roadmap "approximately 30-second audio horizon"):
        // the look-ahead horizon is AUDIO time at the playhead — ensure() queues
        // passages until ~30 s of audio sits ahead, and the tick top-up follows
        // the playhead (survive-seek, decisions #91, keeps that cushion intact
        // across seeks). One constant IS the horizon everywhere: the queue's
        // time bound, the buffer-before-start wait, and this notification's
        // denominator — they must not drift. The B6 headroom trade (a shorter
        // cushion before the cold target's sync synthesis) is accepted: the
        // cold path is not an SLO, and every horizon second is also a seek
        // second the acceptance measures.

        /** Prefill horizon (D1): buffer this many seconds of audio ahead of
         * the playhead — the queue target, the buffer-before-start target,
         * and the generation notification's 100% denominator share it. */
        private const val PREFILL_LOOKAHEAD_SECONDS = 30.0

        /** Prefill: hard passage ceiling (bulwark against tiny-passage books). */
        private const val PREFILL_LOOKAHEAD_PASSAGES = 60

        /** Prefill: re-check cadence; ensure() returns early once the target is met. */
        private const val PREFILL_TICK_MS = 200L

        /** Buffer-before-start: max wall time to wait for the prefill queue to
         * render a cold/jumped passage before falling back to a sync synthesize. */
        private const val PLAY_BUFFER_TIMEOUT_MS = 60_000L

        /** Post-STOP fill: keep filling for at most this long before tearing down. */
        private const val POST_STOP_MAX_MS = 120_000L
        private val SETTLED_PHASES = setOf(PlayerPhase.PLAYING, PlayerPhase.PAUSED, PlayerPhase.LOADING)
        private var clock: () -> Long = System::currentTimeMillis

        /** Measurement-probe master toggle (goals §Measurement): one-line
         * kill switch; the runtime gate additionally requires a debuggable
         * app build (feature-player has no BuildConfig). */
        internal var gapProbeActive = true

        /**
         * Pure GAP1 decision (goals §Measurement): the inter-play gap between
         * two consecutive same-loop plays. [prevPlayAt] is play N's dispatch
         * time (0 = none — resume/seek reset the baseline via stopEverything,
         * so a fresh start is never reported as a gap); [prevFrames] is play
         * N's frame count (sliced PCM size / 2, mono 16-bit). Returns the gap
         * in ms, or null when the pair is not same-loop-consecutive or the
         * clock went backwards. Approximation: play-dispatch to play-dispatch,
         * not a true passage-end measurement.
         */
        internal fun computeGapMs(
            now: Long,
            prevPlayAt: Long,
            prevFrames: Int,
            sampleRate: Int,
        ): Long? {
            if (prevPlayAt <= 0L || prevFrames <= 0 || sampleRate <= 0) return null
            val expectedEnd = prevPlayAt + (prevFrames * 1000L) / sampleRate
            if (now < expectedEnd) return null
            return now - expectedEnd
        }

        const val ACTION_OPEN = "open"
        const val ACTION_PLAY = "play"
        const val ACTION_PLAY_POSITION = "play_position"
        const val ACTION_OPEN_CHAPTER = "open_chapter"
        const val ACTION_OPEN_POSITION = "open_position"
        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE = "pause"
        const val ACTION_SKIP_FORWARD = "skip_forward"
        const val ACTION_SKIP_BACKWARD = "skip_backward"
        const val ACTION_SEEK_FORWARD = "seek_forward"
        const val ACTION_SEEK_BACKWARD = "seek_backward"
        const val ACTION_UNDO = "undo"
        const val ACTION_STOP = "stop"
        const val ACTION_SLEEP = "sleep"
        const val ACTION_BOOKMARK = "bookmark"
        const val ACTION_CHANGE_VOICE = "change_voice"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PASSAGE = "passage"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_VOICE = "voice"

        /**
         * Book-time start slicing: trims the passage PCM to the playhead only.
         * Speed is NOT applied here — [PassageOutput] tempo-changes the buffer
         * via `AudioTrack.setPlaybackRate`, so frames stay book-time and a
         * mid-passage resume skips exactly `offsetSeconds * sampleRate` frames
         * at any speed (decisions #52).
         */
        fun sliceForSpeed(
            pcm: ByteArray,
            offsetSeconds: Double,
            sampleRate: Int,
            speed: Double,
        ): ByteArray {
            if (offsetSeconds <= 0.0) return pcm
            val skipBytes = ((offsetSeconds * sampleRate).toInt() * 2).coerceIn(0, maxOf(pcm.size - 2, 0))
            return pcm.copyOfRange(skipBytes, pcm.size)
        }
    }
}
