package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.os.PowerManager

/**
 * The player's `PARTIAL_WAKE_LOCK`, held only while the buffer is DRY
 * ([PlaybackService.bufferForPlayback]).
 *
 * Why it is needed at all: the one thing that normally keeps the CPU awake
 * through playback is the audio HAL's own wake lock, and it exists only while a
 * track is actually PLAYING. A dry buffer is precisely the state where the
 * previous passage's track has ended and the next one is not in the queue yet —
 * so at that moment nothing holds the CPU awake, and the recovery machinery is
 * all thread-park timers (the 50 ms cushion poll, the fill's 200 ms top-up, the
 * 1 s ticker). A device that deep-sleeps there stops firing those timers, which
 * turns a short stall into a long stop-and-buffer (device report 2026-09-17:
 * "listening with the screen off it occasionally stops and buffers").
 *
 * Deliberately idempotent: [acquire] on an already-held lock and [release] with
 * no lock held are both no-ops, so an unbalanced pair can never pin the CPU
 * awake (a battery bug) nor throw (a crash on the player thread). Held and
 * released on the player thread only — no synchronization needed.
 */
internal class CpuAwakeLock(
    context: Context,
) {
    private val lock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

    private var held = false

    /** True while this player holds the CPU awake. */
    val isHeld: Boolean
        get() = held

    fun acquire() {
        if (held) return
        lock.acquire()
        held = true
    }

    fun release() {
        if (!held) return
        lock.release()
        held = false
    }

    companion object {
        /** The wake-lock tag: shows up in `dumpsys power` as the app's holder. */
        const val WAKE_LOCK_TAG = "ayvu:playback-fill"
    }
}
