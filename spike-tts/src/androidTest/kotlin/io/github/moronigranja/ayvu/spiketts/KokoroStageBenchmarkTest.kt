package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * kokoro-hexagon P4 device half: the T_a=1344-pinned stage pipeline, each
 * offloadable stage on the CPU control and the QNN HTP EP, per-stage outputs
 * compared. Staged per build.md under files/models/stages-1344 (+kokoro_config).
 *
 * Usage (S22/SM8450 control; screen may be off/locked):
 *   adb shell am instrument -w -e class \
 *     io.github.moronigranja.ayvu.spiketts.KokoroStageBenchmarkTest \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KokoroStageBenchmarkTest {
    @Test
    fun measureStagePipelineOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ok = KokoroStageRunner(context).run { line: String -> Log.d("KokoroSpike", line) }
        assertTrue("stage pipeline benchmark failed", ok)
    }
}
