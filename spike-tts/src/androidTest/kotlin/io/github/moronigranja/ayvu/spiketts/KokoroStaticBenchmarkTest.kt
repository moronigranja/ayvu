package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * kokoro-hexagon P3 only: the static-shape re-export (`kokoro-model-static`,
 * input_ids pinned [1,512]) on the CPU control and the QNN HTP EP, oracle-gated
 * against the pinned fp32 dynamic reference. Skips the full provider/precision
 * sweep (KokoroDeviceBenchmarkTest) — those verdicts are already recorded.
 *
 * Usage (Fold/SM8850 primary; staged per build.md + kokoro-model-static):
 *   adb shell am instrument -w -e class \
 *     io.github.moronigranja.ayvu.spiketts.KokoroStaticBenchmarkTest \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KokoroStaticBenchmarkTest {
    @Test
    fun measureStaticKokoroOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runner = KokoroBenchmarkRunner(context)
        val log: (String) -> Unit = { line: String -> Log.d("KokoroSpike", line) }
        val cpuOk = runner.runStatic(KokoroBenchmarkRunner.OrtProvider.CPU, log)
        assertTrue("static CPU control failed", cpuOk)
        // The QNN leg may be unavailable (graph rejected → candidate-unavailable);
        // that is a valid P3 measurement, not a test failure.
        runner.runStatic(KokoroBenchmarkRunner.OrtProvider.QNN_HTP, log)
    }
}
