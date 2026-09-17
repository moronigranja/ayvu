package io.github.moronigranja.ayvu.ops

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts the process-lifetime operation runner in the foreground (dataSync):
 * every running operation keeps the process alive while the app is
 * backgrounded. One service for all operations — the runner owns the
 * notifications, this class only forwards start/cancel/timeout.
 */
@AndroidEntryPoint
class OperationService : Service() {
    @Inject lateinit var runner: AndroidOperationRunner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            // Plain startService: no foreground requirement, and the operation
            // ends itself (its own finish tears the service down).
            ACTION_CANCEL -> runner.cancel(intent.getStringExtra(EXTRA_ID).orEmpty())
            // ACTION_START — and any unexpected action, which the platform does
            // not let us stop without: enter the foreground with the
            // operation's own notification, or a stand-in when it is gone.
            else -> startForegroundFor(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundFor(
        intent: Intent?,
        startId: Int,
    ) {
        when (val plan = foregroundPlan(intent?.getStringExtra(EXTRA_ID), runner)) {
            is ForegroundPlan.Operation -> {
                ServiceCompat.startForeground(
                    this,
                    plan.notificationId,
                    plan.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                runner.markForeground(plan.specId)
            }
            ForegroundPlan.StandIn -> {
                // The operation already finished (a sub-second batch) or the
                // action carries none: startForegroundService REQUIRES a
                // startForeground() before the service may stop — stopping first
                // faults the app with ForegroundServiceDidNotStartInTimeException
                // — so satisfy the platform with a stand-in and leave at once.
                ServiceCompat.startForeground(
                    this,
                    IDLE_NOTIFICATION_ID,
                    buildIdleNotification(this, channelId(OperationChannel.DOWNLOADS)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    /** Android 15's six-hour `dataSync` cap: end the operations, drop the
     *  foreground state. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        runner.onServiceTimeout()
        stopSelf()
    }

    companion object {
        const val ACTION_START = "io.github.moronigranja.ayvu.ops.START"
        const val ACTION_CANCEL = "io.github.moronigranja.ayvu.ops.CANCEL"
        const val EXTRA_ID = "operationId"

        fun startIntent(
            context: Context,
            operationId: String,
        ): Intent =
            Intent(context, OperationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, operationId)
    }
}

/** What the service must foreground for a queued START. */
internal sealed interface ForegroundPlan {
    /** The operation is still running: foreground ITS notification (the same
     *  object it posted), so the user sees one continuous operation. */
    data class Operation(
        val notificationId: Int,
        val notification: Notification,
        val specId: String,
    ) : ForegroundPlan

    /** No operation for this start: the service must STILL call
     *  `startForeground` (a stand-in) — `startForegroundService` requires it
     *  before the service may stop — and may then stop itself. */
    data object StandIn : ForegroundPlan
}

/** The START decision, isolated from the `Service` so it is host-testable. */
internal fun foregroundPlan(
    operationId: String?,
    runner: AndroidOperationRunner,
): ForegroundPlan {
    val current = operationId?.let(runner::currentNotification)
    return if (current == null) {
        ForegroundPlan.StandIn
    } else {
        ForegroundPlan.Operation(current.notificationId, current.notification, current.spec.id)
    }
}
