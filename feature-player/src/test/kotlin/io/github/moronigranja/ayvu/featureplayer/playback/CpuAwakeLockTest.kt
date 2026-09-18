package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * The dry-buffer CPU lock's contract: held while the buffer is being filled,
 * freed on every exit, and an unbalanced pair can neither pin the CPU awake
 * (a battery bug) nor throw on the player thread. Device report 2026-09-17:
 * with the screen off, playback occasionally stopped and buffered — the stall's
 * timers cannot fire once the device sleeps, and with no track playing nothing
 * else holds the CPU awake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CpuAwakeLockTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ShadowPowerManager.clearWakeLocks()
    }

    private fun systemLock() = shadowOf(ShadowPowerManager.getLatestWakeLock())

    /** The shadow's `isHeld` is protected (and the platform's own `isHeld()` is
     *  hidden API), so the held state is read reflectively — the shadow is the
     *  only observable for "the system lock is actually held". */
    private fun systemHeld(): Boolean {
        val method = ShadowPowerManager.ShadowWakeLock::class.java.getDeclaredMethod("isHeld")
        method.isAccessible = true
        return method.invoke(systemLock()) as Boolean
    }

    @Test
    fun `acquire holds a partial lock and release frees it`() {
        val lock = CpuAwakeLock(context)

        lock.acquire()

        assertTrue("held while the buffer is dry", lock.isHeld)
        assertTrue("the system lock is held", systemHeld())
        assertEquals(CpuAwakeLock.WAKE_LOCK_TAG, systemLock().tag)

        lock.release()

        assertFalse(lock.isHeld)
        assertFalse("the system lock is freed", systemHeld())
    }

    @Test
    fun `an unbalanced pair neither over-locks nor throws`() {
        val lock = CpuAwakeLock(context)

        lock.acquire()
        lock.acquire() // a second acquire from a re-entrant path must not re-lock

        assertTrue(lock.isHeld)
        assertEquals("only one real acquire", 1, systemLock().timesHeld)

        lock.release()
        lock.release() // the extra release must not throw on the player thread

        assertFalse(lock.isHeld)
        assertFalse(systemHeld())
    }

    @Test
    fun `the lock is re-acquirable after a release`() {
        val lock = CpuAwakeLock(context)

        lock.acquire()
        lock.release()
        lock.acquire()

        assertTrue(lock.isHeld)
        assertEquals(2, systemLock().timesHeld)
    }
}
