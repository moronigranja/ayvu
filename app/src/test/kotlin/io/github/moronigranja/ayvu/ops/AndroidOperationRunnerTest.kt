package io.github.moronigranja.ayvu.ops

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * A2: the runner owns one notification per operation (progress + Stop),
 * supersedes by id, cancels the body on Stop, and posts terminal notifications
 * from a disjoint id range. Robolectric's NotificationManager shadow is the
 * assertion surface (ids via [android.app.NotificationManager] lookups).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOperationRunnerTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val notificationManager: NotificationManager =
        application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val now = AtomicLong(0L)
    private val runner = AndroidOperationRunner(context, scope).also { it.clock = { now.get() } }

    private val spec =
        OperationSpec(
            id = "pack-download:translate-lfm12b",
            channel = OperationChannel.DOWNLOADS,
            title = "Ayvu — Translate pack",
        )

    @Test
    fun `run posts one ongoing notification with the spec channel title and Stop action`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { latch.await() }

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val notification = posted.single()
        assertEquals("ayvu-downloads", notification.channelId)
        assertEquals(spec.title, notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions.size)
        val action = notification.actions.single()
        assertEquals("Stop", action.title.toString())
        val saved = shadowOf(action.actionIntent).savedIntent
        assertEquals(OperationService.ACTION_CANCEL, saved.action)
        assertEquals(spec.id, saved.getStringExtra(OperationService.EXTRA_ID))
    }

    @Test
    fun `run posts an export operation on the export channel`() {
        val exportSpec =
            OperationSpec(
                id = "export:book-1",
                channel = OperationChannel.EXPORT,
                title = "Ayvu — exporting Book",
            )
        val latch = CompletableDeferred<Unit>()
        runner.run(exportSpec) { latch.await() }

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertEquals("ayvu-export", notification.channelId)
        assertEquals(exportSpec.title, notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `run requests the foreground service for the operation`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { latch.await() }

        val started = shadowOf(application).nextStartedService
        assertNotNull("startForegroundService must be requested", started)
        assertEquals(OperationService.ACTION_START, started.action)
        assertEquals(spec.id, started.getStringExtra(OperationService.EXTRA_ID))
    }

    @Test
    fun `report updates the same notification to a determinate bar`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { vault ->
            vault.report("half way", 42)
            latch.await()
        }
        scheduler.runCurrent()

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals("an update must reuse the operation's notification", 1, posted.size)
        val notification = posted.single()
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, true))
        assertEquals("half way", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `the first report is indeterminate`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { vault ->
            vault.report("starting", null)
            latch.await()
        }
        scheduler.runCurrent()

        val notification = shadowOf(notificationManager).allNotifications.single()
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false))
    }

    @Test
    fun `cancel cancels the body and its notification`() {
        val started = CompletableDeferred<Unit>()
        val latch = CompletableDeferred<Unit>()
        var cancelled = false
        runner.run(spec) {
            started.complete(Unit)
            try {
                latch.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        scheduler.runCurrent()
        assertTrue("the body must have started", started.isCompleted)

        assertTrue(runner.cancel(spec.id))
        scheduler.advanceUntilIdle()

        assertTrue("Stop reaches the body as cancellation", cancelled)
        assertTrue(
            "the operation notification is gone",
            shadowOf(notificationManager).allNotifications.isEmpty(),
        )
    }

    @Test
    fun `cancel of an unknown id reports false`() {
        assertFalse(runner.cancel("nobody"))
    }

    @Test
    fun `running the same id supersedes the previous operation`() {
        val firstLatch = CompletableDeferred<Unit>()
        val secondLatch = CompletableDeferred<Unit>()
        var firstCancelled = false
        runner.run(spec) {
            try {
                firstLatch.await()
            } catch (e: CancellationException) {
                firstCancelled = true
                throw e
            }
        }
        scheduler.runCurrent()
        runner.run(spec) { secondLatch.await() }
        scheduler.runCurrent()

        assertTrue(firstCancelled)
        assertEquals(
            "one notification per id, and it belongs to the new operation",
            1,
            shadowOf(notificationManager).allNotifications.size,
        )
    }

    @Test
    fun `normal completion cancels the notification and stops the service`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { latch.await() }
        scheduler.runCurrent()
        // The service's START callback (the platform requirement) runs first.
        runner.markForeground(spec.id)

        latch.complete(Unit)
        scheduler.advanceUntilIdle()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
        assertEquals(
            "the last operation tears the service down",
            OperationService::class.java.name,
            shadowOf(application).nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `a throwing body posts a terminal notification from the terminal range`() {
        runner.run(spec) { error("boom") }
        scheduler.advanceUntilIdle()

        val terminal = shadowOf(notificationManager).getNotification(null, TERMINAL_NOTIFICATION_ID_BASE)
        assertNotNull("terminal notifications start at $TERMINAL_NOTIFICATION_ID_BASE", terminal)
        assertEquals("Stopped: boom", terminal!!.extras.getString(Notification.EXTRA_TEXT))
        assertNull(
            "the operation notification is cancelled, not reused",
            shadowOf(notificationManager).getNotification(null, NOTIFICATION_ID_BASE),
        )
        assertTrue(terminal.extras.getString(Notification.EXTRA_TITLE) == spec.title)
        assertTrue(terminal.flags and NotificationCompat.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `currentNotification exposes the posted notification for startForeground`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { vault ->
            vault.report("78%")
            latch.await()
        }
        scheduler.runCurrent()

        val current = runner.currentNotification(spec.id)
        assertNotNull(current)
        assertEquals(NOTIFICATION_ID_BASE, current!!.notificationId)
        assertEquals(spec, current.spec)
        assertEquals("78%", current.notification.extras.getString(Notification.EXTRA_TEXT))
        assertNull(runner.currentNotification("other"))
    }
}
