package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D2 2-engine parallel pre-generation measurement (roadmap D2 additions).
 * Runs `PregenParallelRunner` as an instrumented test — locked/screen-off, the
 * realistic pregen condition, `am instrument` keeps the process exempt from
 * the freezer. Logs through `KokoroSpike`, writes
 * `kokoro_pregen_parallel.json` to the external files dir.
 *
 * Usage (device staged per build.md):
 *   adb shell am instrument -w \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 *     -e class io.github.moronigranja.ayvu.spiketts.PregenParallelBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class PregenParallelBenchmarkTest {
    @Test
    fun measureParallelPregenOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ok = PregenParallelRunner(context).run { Log.d("KokoroSpike", it) }
        assertTrue("parallel pregen benchmark failed", ok)
    }
}
