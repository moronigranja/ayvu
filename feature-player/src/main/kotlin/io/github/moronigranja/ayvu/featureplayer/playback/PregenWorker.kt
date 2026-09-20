package io.github.moronigranja.ayvu.featureplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.ProgressDao
import io.github.moronigranja.ayvu.player.pregen.OfflinePregen
import io.github.moronigranja.ayvu.player.pregen.PregenBudget
import io.github.moronigranja.ayvu.player.pregen.PregenKey
import io.github.moronigranja.ayvu.player.pregen.PregenProgress
import io.github.moronigranja.ayvu.player.pregen.PregenTerminal
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import kotlinx.coroutines.delay

/**
 * Offline pre-generation worker (decisions #42): runs the tested
 * [OfflinePregen] core over the library store's cached parses into the
 * shared [PregenCache], so the work is pure scheduling over a tested cache.
 *
 * Single-mode manual worker: one book (`KEY_BOOK_IDS`), unbounded time — the
 * run ends when the book is fully cached, the tier saturates, or the user
 * cancels. A bounded `KEY_BUDGET_MINUTES` (listening minutes of NEW audio)
 * anchors the run to the book's persisted reading position (the next N
 * minutes ahead of the listener, decisions #132/#133); an unbounded run
 * covers the spine from its start. The run
 * YIELDS at passage boundaries while playback HOLDS the shared engine
 * ([PlaybackActive.engineInUse], item 5 — the G2 blanket session yield is
 * superseded): a cache-fed session leaves the engine free, so a manual run
 * advances; a cold seek's fill or buffer synthesis pauses
 * it at the next boundary. After an engine-touch burst it waits and
 * resumes, never aborting the run.
 *
 * Runs as a foreground worker (dataSync) — synthesis is minutes-to-hours, so
 * the process must not be reaped; the notification carries progress and the
 * library row observes [androidx.work.WorkInfo] progress. The engine's
 * synthesis is cancellable per batch ([PregenWorker]'s cancellation stops the
 * run at the next passage boundary).
 *
 * CR-1: only safely bounded terminals ([PregenTerminal.Completed],
 * [PregenTerminal.BudgetExhausted], [PregenTerminal.CacheSaturated] and the
 * playback yield) settle as success. Engine failure terminals
 * ([PregenTerminal.Unavailable], [PregenTerminal.FailureCap]) and a run whose
 * requested books are absent from the library fail the job with a typed error.
 */
@HiltWorker
class PregenWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val selector: EngineSelector,
        private val libraryStore: LibraryStore,
        private val settings: AppSettings,
        private val progressDao: ProgressDao,
        private val pregenCache: PregenCache,
    ) : CoroutineWorker(appContext, params) {
        private var lastNotifyAt = 0L
        private var totalSynthesized = 0
        private var lastBookTitle: String? = null

        override suspend fun doWork(): Result {
            settings.reload()
            val engine =
                selector.engine()
                    ?: return Result.failure(workDataOf(KEY_ERROR to (selector.failureReason ?: "engine unavailable")))

            // The run takes the library row's chosen listening-time budget
            // (KEY_BUDGET_MINUTES, whole listening minutes); absent → whole book
            // (the pre-budget default). The budget is listening time of NEW audio —
            // the worker hands it to OfflinePregen, which measures synthesized
            // PCM seconds and skips cached passages without counting them.
            val budget =
                inputData
                    .getLong(KEY_BUDGET_MINUTES, -1L)
                    .takeIf { it > 0 }
                    ?.let { PregenBudget(maxSeconds = it * 60.0) } ?: MANUAL_BUDGET
            val voice = inputData.getString(KEY_VOICE) ?: settings.state.value.voice
            val speed = inputData.getDouble(KEY_SPEED, 1.0)

            val wantedIds = inputData.getStringArray(KEY_BOOK_IDS)?.toSet()
            val books = libraryStore.cachedBooks().filter { wantedIds == null || it.id in wantedIds }
            if (books.isEmpty()) {
                // CR-1 class: a run for requested-but-absent books must not settle
                // as a false "offline audio ready" success. The manual scheduler
                // only sends library IDs, so an empty filtered set means the book
                // was deleted while the run sat queued — fail with a typed error.
                // A whole-library run (no IDs sent) over an empty library has no
                // work by definition and still succeeds.
                if (wantedIds != null) {
                    return Result.failure(workDataOf(KEY_ERROR to "No requested books are in the library"))
                }
                return Result.success()
            }

            return runBooks(
                books = books,
                budget = budget,
                voice = voice,
                speed = speed,
                notify = { title, progress -> refreshNotification(title, progress, System::currentTimeMillis) },
            ).also { result ->
                // Terminal notification (item 5): the FGS stops with the worker,
                // so a finished run must leave a trace of its own. Cancellation
                // is deliberate — silent, no nag.
                if (!isStopped) postTerminal(result)
            }
        }

        /** In-place foreground-notification refresh (item 5): re-binding the FGS
         * every passage (the old chapter-boundary setForeground) re-bound the
         * service every second for the whole run — instead the ALREADY-FOREGROUND
         * notification is updated via [NotificationManager.notify], throttled to
         * ~1 s so a fast run does not spam binder traffic. [clock] injectable so
         * host tests can pin the cadence. */
        internal suspend fun refreshNotification(
            bookTitle: String,
            progress: PregenProgress,
            clock: () -> Long,
        ) {
            val percent = progress.percent
            setProgress(
                workDataOf(
                    KEY_PROGRESS_PERCENT to percent,
                    KEY_PROGRESS_CHAPTER to progress.chaptersDone,
                    KEY_PROGRESS_TOTAL_CHAPTERS to progress.totalChapters,
                    KEY_PROGRESS_BOOK to bookTitle,
                ),
            )
            if (clock() - lastNotifyAt >= NOTIFY_THROTTLE_MS) {
                lastNotifyAt = clock()
                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(
                    NOTIFICATION_ID,
                    pregenNotification(
                        bookTitle = bookTitle,
                        chapter = progress.chaptersDone,
                        totalChapters = progress.totalChapters,
                        percent = percent,
                    ),
                )
            }
        }

        /** Posts the non-ongoing, auto-cancel terminal notification (item 5):
         * success terminals say the offline audio is ready, failure terminals
         * carry the typed [KEY_ERROR] text. Reuses [NOTIFICATION_ID], replacing
         * the vanishing FGS entry in place. */
        private fun postTerminal(result: Result) {
            ensureChannel()
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val base =
                NotificationCompat
                    .Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true)
            val terminal =
                when (result) {
                    is Result.Success ->
                        base
                            .setContentTitle("Ayvu — offline audio ready")
                            .setContentText(
                                "Offline audio ready — $totalSynthesized passages cached (${lastBookTitle ?: "Ayvu"})",
                            ).build()
                    is Result.Failure ->
                        base
                            .setContentTitle("Ayvu — pre-generation stopped")
                            .setContentText(result.outputData.getString(KEY_ERROR) ?: "pre-generation stopped")
                            .build()
                    is Result.Retry -> return // the worker never returns Retry; nothing to trace
                    else -> return // defensive: unknown subclass, nothing to trace
                }
            // Device-observed (2026-09-08): a synchronous post here is cancelled —
            // WorkManager's SystemFgDispatcher removes the foreground notification
            // (same id 43) immediately after doWork returns, racing the terminal.
            // Defer past that teardown so the "ready/stopped" notification lands.
            Handler(Looper.getMainLooper()).postDelayed(
                { manager.notify(NOTIFICATION_ID, terminal) },
                TERMINAL_POST_DELAY_MS,
            )
        }

        /**
         * The book loop over [OfflinePregen], separated from [doWork]'s engine
         * open so host tests drive it against a faked engine. A bounded
         * [PregenBudget.maxSeconds] anchors each book at its persisted reading
         * position; an unbounded budget walks the whole spine.
         */
        internal suspend fun runBooks(
            books: List<CachedBook>,
            budget: PregenBudget,
            voice: String,
            speed: Double,
            notify: suspend (String, PregenProgress) -> Unit = { _, _ -> },
        ): Result {
            var bookIndex = 0
            for (book in books) {
                // Conditional yield (item 5): the run continues while playback
                // is fully cache-fed (engineInUse false) and waits at the book
                // boundary when playback needs the shared engine.
                while (PlaybackActive.engineInUse) {
                    delay(1000)
                }
                // Read-in-language (decisions #114): the per-book resolution —
                // resolve() wraps the engine in the TranslatingEngine when the
                // book has a target and the pack is ready, and its keys carry the
                // target's `x<lang>` segment (translated + original audio never
                // collide on disk), falling back to the plain engine otherwise.
                val (engineForBook, _) = selector.resolve(book.id)
                if (engineForBook == null) {
                    return Result.failure(
                        workDataOf(KEY_ERROR to (selector.failureReason ?: "engine unavailable")),
                    )
                }
                val translateLang = selector.translateLangInUse(book.id)
                val runner =
                    OfflinePregen(
                        cache = pregenCache.cache,
                        synthesize = { text, chapterIndex, passageIndex ->
                            // The render's passage identity — the read-in-language
                            // key dimension (same artifact as the reader display).
                            engineForBook.synthesize(
                                SynthesisRequest(
                                    text,
                                    voice,
                                    speed,
                                    chapterIndex = chapterIndex,
                                    passageIndex = passageIndex,
                                ),
                            )
                        },
                        // The language plus which translator renders it (the
                        // ACTIVE engine, decisions #182): the `t<translator>`
                        // segment — never the other option's audio.
                        target = translateLang?.let { TranslationTarget(it, selector.translateEngineId) },
                        // G2: yield to an engaged playback session (manual runs too).
                        shouldContinue = { !PlaybackActive.engineInUse },
                    )
                bookIndex++
                if (bookIndex == 1) {
                    setForeground(
                        ForegroundInfo(
                            NOTIFICATION_ID,
                            pregenNotification(book.title, 0, 0, 0),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        ),
                    )
                }
                val fullBook = book.toBook()
                // Bounded ("next N minutes") runs resume from the book's current
                // reading position so they pre-generate the audio ahead of the
                // listener, not the book's opening chapters. Whole-book runs
                // (null maxSeconds) still cover the spine from the start — the
                // per-passage cache skip keeps every run resume-friendly.
                val startAt =
                    if (budget.maxSeconds != null) {
                        progressDao.get(book.id)?.let { progress ->
                            // progress.chapterIndex is the player's chapter.index; the
                            // planner walks dense list positions, so resolve its slot.
                            val chapter = fullBook.chapters.indexOfFirst { it.index == progress.chapterIndex }
                            chapter.takeIf { it >= 0 }?.let { it to progress.passageIndex }
                        }
                    } else {
                        null
                    }
                val result =
                    runner.run(
                        book = fullBook,
                        // The KEY voice: an explicitly stored target-language
                        // voice names the translated audio under its own key
                        // (the synthesize lambda keeps the request voice).
                        voice = selector.renderVoice(book.id),
                        speed = speed,
                        budget = budget,
                        startAt = startAt,
                    ) { notify(book.title, it) }
                // CR-1: failure terminals must not settle as success. Engine
                // conditions (missing packs, a synthesis meltdown) are global —
                // they will hit every remaining book the same way, so the job
                // stops with a typed error instead of reporting completed work.
                when (result.terminal) {
                    PregenTerminal.Completed,
                    PregenTerminal.BudgetExhausted,
                    PregenTerminal.CacheSaturated,
                    PregenTerminal.Yielded,
                    -> {
                        // The terminal notification's count/title come from the
                        // last successfully-finished book (item 5).
                        totalSynthesized += result.passagesSynthesized
                        lastBookTitle = book.title
                    }
                    PregenTerminal.Unavailable,
                    PregenTerminal.FailureCap,
                    -> return Result.failure(
                        workDataOf(
                            KEY_ERROR to runErrorMessage(book.title, result, result.terminal ?: PregenTerminal.Completed),
                            KEY_PROGRESS_SYNTHESIZED to result.passagesSynthesized,
                            KEY_PROGRESS_CACHED to result.passagesCached,
                            KEY_PROGRESS_FAILURES to result.failures,
                        ),
                    )
                    // run() sets a terminal on every return path; a missing one
                    // is a contract violation — never a silent success.
                    null -> return Result.failure(
                        workDataOf(KEY_ERROR to "Pre-generation stopped without a terminal reason ($book.title)"),
                    )
                }
            }
            return Result.success()
        }

        private fun runErrorMessage(
            bookTitle: String,
            progress: PregenProgress,
            terminal: PregenTerminal,
        ): String {
            val reason =
                when (terminal) {
                    PregenTerminal.Unavailable ->
                        "Pre-generation stopped: the synthesis engine is unavailable (packs missing?)"
                    PregenTerminal.FailureCap ->
                        "Pre-generation stopped after ${progress.failures} repeated synthesis failures"
                    else -> "Pre-generation stopped ($terminal)"
                }
            return "$reason — $bookTitle (" +
                "${progress.passagesSynthesized} synthesized, ${progress.passagesCached} cached, ${progress.failures} failed)"
        }

        private fun pregenNotification(
            bookTitle: String,
            chapter: Int,
            totalChapters: Int,
            percent: Int,
        ): Notification {
            ensureChannel()
            return buildPregenNotification(applicationContext, bookTitle, chapter, totalChapters, percent)
        }

        private fun ensureChannel() {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pre-generation", NotificationManager.IMPORTANCE_LOW),
            )
        }

        companion object {
            const val MODE_MANUAL = "manual"

            // KEY_MODE is kept as an input key for callers (PregenManager, the
            // E2E suite) — the worker itself is single-mode and ignores it.
            const val KEY_MODE = "mode"
            const val KEY_BOOK_IDS = "bookIds"
            const val KEY_VOICE = "voice"
            const val KEY_SPEED = "speed"
            const val KEY_BUDGET_MINUTES = "budgetMinutes"
            const val KEY_ERROR = "error"
            const val KEY_PROGRESS_SYNTHESIZED = "progressSynthesized"
            const val KEY_PROGRESS_CACHED = "progressCached"
            const val KEY_PROGRESS_FAILURES = "progressFailures"
            const val KEY_PROGRESS_PERCENT = "progressPercent"
            const val KEY_PROGRESS_CHAPTER = "progressChapter"
            const val KEY_PROGRESS_TOTAL_CHAPTERS = "progressTotalChapters"
            const val KEY_PROGRESS_BOOK = "progressBook"
            const val OVERNIGHT_NAME = "offline-pregen-overnight"
            const val NOTIFICATION_ID = 43

            /** Every manual run carries this tag, so the notification's Stop
             *  action can cancel all of them (`PregenManager.cancelAllRunning`). */
            const val TAG_PREGEN = "ayvu-pregen"

            /** The pre-generation notification's Stop action. The foreground
             *  notification is not dismissible, so this action IS the cancel path. */
            const val ACTION_CANCEL_PREGEN = "io.github.moronigranja.ayvu.action.CANCEL_PREGEN"

            /**
             * The pre-generation notification (id 43): progress plus the Stop
             * action that ends the run — the same builder serves the progress
             * refresh and the foreground entry.
             */
            internal fun buildPregenNotification(
                context: Context,
                bookTitle: String,
                chapter: Int,
                totalChapters: Int,
                percent: Int,
            ): Notification {
                val body =
                    when {
                        totalChapters == 0 -> "Pre-generating $bookTitle…"
                        percent >= 100 -> "$bookTitle — offline audio ready"
                        else -> "$bookTitle — chapter ${chapter + 1}/$totalChapters ($percent%)"
                    }
                return NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Ayvu — pre-generating")
                    .setContentText(body)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(100, percent, percent <= 0)
                    .addAction(
                        0,
                        "Stop",
                        PendingIntent.getBroadcast(
                            context,
                            0,
                            Intent(context, PregenCancelReceiver::class.java).setAction(ACTION_CANCEL_PREGEN),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).build()
            }

            /** Throttle for the in-place notification refresh (item 5). */
            internal const val NOTIFY_THROTTLE_MS = 1_000L

            /** Grace period after which the terminal notification is posted — past
             * WorkManager's FGS-notification teardown (device-observed race). */
            private const val TERMINAL_POST_DELAY_MS = 500L
            private const val CHANNEL_ID = "pregen"

            /**
             * Manual: whole book by default, or bounded by a KEY_BUDGET_MINUTES
             * input (the library's pre-generate overlay) of listening time; a run
             * always ends when the tier saturates at its byte cap or the user
             * cancels.
             */
            val MANUAL_BUDGET = PregenBudget()

            fun workName(bookId: String) = "offline-pregen-$bookId"
        }
    }
