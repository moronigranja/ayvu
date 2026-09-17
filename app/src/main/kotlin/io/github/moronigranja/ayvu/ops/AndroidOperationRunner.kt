package io.github.moronigranja.ayvu.ops

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** A running operation's preview state, handed to [OperationService] so it can
 *  enter the foreground with the operation's own notification. */
data class OperationNotification(
    val notificationId: Int,
    val notification: Notification,
    val spec: OperationSpec,
)

/**
 * The Android [OperationRunner]: one process-lifetime host for every covered
 * long operation. Bodies run on the app-scope [CoroutineScope] (the same
 * process-lifetime scope the container already provides), the process is held
 * alive by [OperationService]'s foreground `dataSync` state, and Stop reaches
 * the body as coroutine cancellation.
 *
 * One notification per operation (progress + Stop), throttled to
 * [NOTIFY_THROTTLE_MS] with immediate re-posts when the content text changes.
 * A supersede ([run] with a running id) cancels the running operation first, so
 * a repeated download restarts cleanly instead of stacking.
 */
@Singleton
class AndroidOperationRunner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appScope: CoroutineScope,
    ) : OperationRunner {
        /** Injectable clock (the PregenWorker host-test convention). */
        internal var clock: () -> Long = System::currentTimeMillis

        private val lock = Any()
        private val running = LinkedHashMap<String, Running>()
        private val nextNotificationId = AtomicInteger(NOTIFICATION_ID_BASE)
        private val nextTerminalId = AtomicInteger(TERMINAL_NOTIFICATION_ID_BASE)
        private var foregroundId: String? = null

        private class Running(
            val spec: OperationSpec,
            val notificationId: Int,
            var notification: Notification? = null,
            var job: Job? = null,
            /** True once [OperationService] called `startForeground` for this
             *  operation — until then the platform's start requirement for the
             *  service is still outstanding (see [finish]). */
            var foregrounded: Boolean = false,
            var text: String = "",
            var percent: Int? = null,
            var lastNotifyAt: Long = 0L,
        )

        override fun run(
            spec: OperationSpec,
            block: suspend (OperationReporter) -> Unit,
        ) {
            cancel(spec.id)
            ensureChannels(context)
            val entry =
                Running(
                    spec = spec,
                    notificationId = nextNotificationId.getAndIncrement(),
                    lastNotifyAt = clock(),
                )
            synchronized(lock) { running[spec.id] = entry }
            post(entry)
            ContextCompat.startForegroundService(context, OperationService.startIntent(context, spec.id))
            // LAZY so the Stop path can never observe a started body with no job
            // to cancel: the job is registered before it begins.
            val job =
                appScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        block(reporterFor(spec.id))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        postTerminal(spec, t.message)
                    }
                }
            // Cleanup hangs off the JOB, not a `finally` in the body: a body
            // cancelled before its first dispatch never runs, and the entry (and
            // its notification) must still go.
            job.invokeOnCompletion { finish(spec.id, entry) }
            synchronized(lock) { if (running[spec.id] === entry) entry.job = job }
            job.start()
        }

        override fun cancel(id: String): Boolean {
            val entry = synchronized(lock) { running[id] } ?: return false
            entry.job?.cancel()
            return true
        }

        /** The notification for [id] (null when it is not running), for
         *  [OperationService]'s `startForeground`. */
        fun currentNotification(id: String): OperationNotification? =
            synchronized(lock) {
                running[id]?.let { entry ->
                    entry.notification?.let { OperationNotification(entry.notificationId, it, entry.spec) }
                }
            }

        /** Records that [id] currently holds the foreground notification. */
        fun markForeground(id: String) {
            synchronized(lock) {
                running[id]?.let {
                    it.foregrounded = true
                    foregroundId = id
                }
            }
        }

        /** Android 15's `dataSync` six-hour cap: end every running operation. */
        fun onServiceTimeout() {
            val jobs = synchronized(lock) { running.values.mapNotNull { it.job } }
            jobs.forEach { it.cancel() }
        }

        private fun reporterFor(id: String): OperationReporter =
            OperationReporter { text, percent ->
                val entry = synchronized(lock) { running[id] } ?: return@OperationReporter
                synchronized(lock) {
                    val changed = text != entry.text
                    entry.text = text
                    entry.percent = percent
                    val now = clock()
                    if (changed || now - entry.lastNotifyAt >= NOTIFY_THROTTLE_MS) {
                        entry.lastNotifyAt = now
                        post(entry)
                    }
                }
            }

        /** Cancels the operation notification, removes the entry, and hands the
         *  foreground slot to the next queued operation (or tears the service
         *  down). Entry-identity guarded, so a superseded operation's `finally`
         *  cannot remove its successor. */
        private fun finish(
            id: String,
            entry: Running,
        ) {
            val (nextId, heldForeground) =
                synchronized(lock) {
                    // A superseded operation's finally reaches here with its entry
                    // already replaced: the notification must still go (its id is
                    // private to that entry), but the map and foreground slot
                    // belong to the successor.
                    if (running[id] !== entry) {
                        NotificationManagerCompat.from(context).cancel(entry.notificationId)
                        return
                    }
                    running.remove(id)
                    NotificationManagerCompat.from(context).cancel(entry.notificationId)
                    val next = running.keys.firstOrNull()
                    val wasForeground = foregroundId == id
                    if (next == null) {
                        foregroundId = null
                        null to wasForeground
                    } else {
                        next to wasForeground
                    }
                }
            when {
                // An operation that finished before the service's queued START
                // callback ran leaves a pending foreground requirement: stopping
                // the service now would fault the app
                // (ForegroundServiceDidNotStartInTimeException). That callback
                // satisfies the requirement and stops the service itself.
                nextId == null && entry.foregrounded ->
                    context.stopService(Intent(context, OperationService::class.java))
                // Re-promote only when this operation held (or nobody held — an
                // unprocessed start) the foreground; a later operation's service
                // start already carries its own notification.
                nextId != null && (heldForeground || foregroundId == null) ->
                    context.startService(OperationService.startIntent(context, nextId))
            }
        }

        private fun post(entry: Running) {
            val notification =
                buildOperationNotification(
                    context = context,
                    channelId = channelId(entry.spec.channel),
                    title = entry.spec.title,
                    text = entry.text,
                    percent = entry.percent,
                    notificationId = entry.notificationId,
                    cancelLabel = entry.spec.cancelLabel,
                    cancelIntent = cancelIntent(context, entry.notificationId, entry.spec.id),
                )
            entry.notification = notification
            NotificationManagerCompat.from(context).notify(entry.notificationId, notification)
        }

        private fun postTerminal(
            spec: OperationSpec,
            message: String?,
        ) {
            val terminalId = nextTerminalId.getAndIncrement()
            NotificationManagerCompat.from(context).notify(
                terminalId,
                buildTerminalNotification(
                    context = context,
                    channelId = channelId(spec.channel),
                    title = spec.title,
                    text = "Stopped: ${message ?: "failed"}",
                ),
            )
        }

        private companion object {
            const val NOTIFY_THROTTLE_MS = 1_000L
        }
    }
