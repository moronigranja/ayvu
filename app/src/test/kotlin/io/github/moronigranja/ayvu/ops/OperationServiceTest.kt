package io.github.moronigranja.ayvu.ops

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The START decision the service executes. `startForegroundService` REQUIRES a
 * `startForeground()` for every start before the service may stop — stopping
 * first faults the app with `ForegroundServiceDidNotStartInTimeException`
 * (device-observed: a sub-second book import finished before the queued START
 * callback ran, and the runner's stopService in that window killed the
 * process). So a START whose operation is gone must still produce a foreground
 * call (the stand-in), never a bare stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationServiceTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val runner = AndroidOperationRunner(context, scope)

    private val spec =
        OperationSpec(
            id = "book-import",
            channel = OperationChannel.IMPORT,
            title = "Ayvu — importing books",
        )

    @Test
    fun `a running operation plans to foreground its own notification`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { latch.await() }

        val plan = foregroundPlan(spec.id, runner)

        assertTrue(plan is ForegroundPlan.Operation)
        val operation = plan as ForegroundPlan.Operation
        assertEquals(NOTIFICATION_ID_BASE, operation.notificationId)
        assertEquals(spec.id, operation.specId)
        assertEquals(spec.title, operation.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(1, operation.notification.actions.size)
    }

    @Test
    fun `a START whose operation is gone plans the stand-in, never a bare stop`() {
        assertEquals(ForegroundPlan.StandIn, foregroundPlan(spec.id, runner))
        assertEquals(ForegroundPlan.StandIn, foregroundPlan(null, runner))
    }

    @Test
    fun `a finished operation still plans the stand-in for its late START`() {
        runner.run(spec) { }
        scheduler.advanceUntilIdle()

        assertEquals(ForegroundPlan.StandIn, foregroundPlan(spec.id, runner))
        assertNull(
            "the runner must not stop the service while the start requirement is outstanding",
            shadowOf(application).nextStoppedService,
        )
    }

    @Test
    fun `the operation notification carries the Stop action that cancels it`() {
        val latch = CompletableDeferred<Unit>()
        runner.run(spec) { latch.await() }
        val notification = runner.currentNotification(spec.id)!!.notification

        val action = notification.actions.single()
        assertEquals("Stop", action.title.toString())
        val saved = shadowOf(action.actionIntent).savedIntent
        assertEquals(OperationService.ACTION_CANCEL, saved.action)
        assertEquals(spec.id, saved.getStringExtra(OperationService.EXTRA_ID))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // The cancel path the service's ACTION_CANCEL branch drives.
        assertTrue(runner.cancel(spec.id))
        scheduler.advanceUntilIdle()
        assertNull(runner.currentNotification(spec.id))
        assertTrue(
            shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .allNotifications
                .isEmpty(),
        )
    }
}
