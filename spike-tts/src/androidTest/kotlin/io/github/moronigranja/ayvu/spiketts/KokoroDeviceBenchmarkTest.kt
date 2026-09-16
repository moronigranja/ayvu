package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kokoro on-device measurement as an instrumented test: runs on a locked or
 * screen-off device (the realistic player condition) — no Activity, no
 * keyguard interaction, `am instrument` keeps the process exempt from the
 * the freezer. Reports through logcat (`KokoroSpike`) and writes
 * per-provider kokoro_results_<label>.json + WAVs to the external files dir.
 *
 * Usage (device staged per build.md, screen may be off/locked):
 *   adb shell am instrument -w \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KokoroDeviceBenchmarkTest {
    @Test
    fun measureKokoroOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ok = KokoroBenchmarkRunner(context).run { Log.d("KokoroSpike", it) }
        assertTrue("Kokoro benchmark failed", ok)
    }
}
