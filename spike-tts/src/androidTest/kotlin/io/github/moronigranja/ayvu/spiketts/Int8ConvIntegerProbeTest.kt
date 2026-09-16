package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * int8 re-run (decisions #86 follow-up): two legs, labeled by the
 * instrumentation arg `ort_version` so the pin A/B (1.23.2 vs 1.29.0)
 * writes self-identifying results:
 * 1. minimal ConvInteger graph — the exact #86 open blocker;
 * 2. the Kokoro int8 candidate via the D3 precision axis (oracle-gated
 *    against the pinned fp32) — staged as `files/models/kokoro-model-int8`.
 */
@RunWith(AndroidJUnit4::class)
class Int8ConvIntegerProbeTest {
    @Test
    fun probeInt8OnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val conv = Int8OpsProbe(context).run(args) { Log.d("Int8Probe", it) }
        assertTrue("convinteger probe must record a verdict", conv.has("convinteger") || conv.has("unavailable"))
        if (File(context.filesDir, "models/kokoro-model-int8").isFile) {
            val ok =
                KokoroBenchmarkRunner(context).runPrecision(
                    KokoroBenchmarkRunner.ModelPrecision.INT8,
                ) { Log.d("Int8Probe", it) }
            Log.d("Int8Probe", "kokoro int8 leg completed: $ok")
        } else {
            Log.d("Int8Probe", "kokoro-model-int8 not staged — skipping the E2E leg")
        }
    }
}
