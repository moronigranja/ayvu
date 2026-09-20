package io.github.moronigranja.ayvu.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** First notification id of the operation range. 41 is the service's stand-in
 *  (see [buildIdleNotification]), 42/43/44 are taken by playback,
 *  pre-generation and playback generation respectively; 45+ is allocated per
 *  operation at runtime (never persisted). */
const val NOTIFICATION_ID_BASE = 45

/** The stand-in's id (above the app's other fixed ids, below the range). */
const val IDLE_NOTIFICATION_ID = 41

/** First id of the terminal range — a distinct id so the foreground-service
 *  teardown of the operation notification cannot race the terminal post. */
const val TERMINAL_NOTIFICATION_ID_BASE = 200

private const val CHANNEL_ID_DOWNLOADS = "ayvu-downloads"
private const val CHANNEL_ID_IMPORT = "ayvu-import"
private const val CHANNEL_ID_EXPORT = "ayvu-export"

/** Creates the operation channels if absent (`createNotificationChannel` is
 *  idempotent). */
fun ensureChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID_DOWNLOADS, "Ayvu downloads", NotificationManager.IMPORTANCE_LOW),
    )
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID_IMPORT, "Book import", NotificationManager.IMPORTANCE_LOW),
    )
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID_EXPORT, "Ayvu exports", NotificationManager.IMPORTANCE_LOW),
    )
}

/** The notification channel id for [channel]. */
fun channelId(channel: OperationChannel): String =
    when (channel) {
        OperationChannel.DOWNLOADS -> CHANNEL_ID_DOWNLOADS
        OperationChannel.IMPORT -> CHANNEL_ID_IMPORT
        OperationChannel.EXPORT -> CHANNEL_ID_EXPORT
    }

/** The Stop action's intent: routes back into [OperationService] with
 *  [OperationService.ACTION_CANCEL] for [operationId]. */
fun cancelIntent(
    context: Context,
    notificationId: Int,
    operationId: String,
): PendingIntent =
    PendingIntent.getService(
        context,
        notificationId,
        Intent(context, OperationService::class.java)
            .setAction(OperationService.ACTION_CANCEL)
            .putExtra(OperationService.EXTRA_ID, operationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/** The ongoing progress notification: title, content text, determinate or
 *  indeterminate bar, and the [cancelLabel] Stop action. */
fun buildOperationNotification(
    context: Context,
    channelId: String,
    title: String,
    text: String,
    percent: Int?,
    notificationId: Int,
    cancelLabel: String,
    cancelIntent: PendingIntent,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(100, percent ?: 0, percent == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(0, cancelLabel, cancelIntent)
        .build()

/**
 * The stand-in the service foregrounds when its queued START arrives after its
 * operation already finished (a sub-second import), or for an action that
 * carries no operation: `startForegroundService` REQUIRES a `startForeground`
 * before the service may stop, and stopping first faults the app with
 * `ForegroundServiceDidNotStartInTimeException`. It is removed in the same
 * callback.
 */
fun buildIdleNotification(
    context: Context,
    channelId: String,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Ayvu")
        .setOnlyAlertOnce(true)
        .build()

/** The one-shot terminal notification for a failed operation. */
fun buildTerminalNotification(
    context: Context,
    channelId: String,
    title: String,
    text: String,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .build()
