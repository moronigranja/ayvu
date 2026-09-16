package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D4 small-tier probe on-device (roadmap D4): runs the REAL Piper and
 * Supertonic 3 pipelines end-to-end on the HiBreak through ORT-android
 * (pinned 1.23.2) over the host-prepared `files/d4_inputs.json`, recording
 * open/RTF/PSS/VmHWM per leg and writing playable WAVs. Verdict legs for
 * the keep/drop/defer call against the Kokoro 3.01 B6 baseline (#93/#97).
 * Staging (build.md "D4 small-tier staging"): files/models/piper/
 * en_US-lessac-medium.onnx, the files/models/supertonic/onnx graphs, and
 * files/d4_inputs.json.
 */
@RunWith(AndroidJUnit4::class)
class D4ProbeBenchmarkTest {
    @Test
    fun probeSmallTierOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val piper = File(context.filesDir, "models/piper/en_US-lessac-medium.onnx")
        val st = File(context.filesDir, "models/supertonic/onnx")
        val inputs = File(context.filesDir, "d4_inputs.json")
        assertTrue("piper model not staged at $piper", piper.isFile)
        assertTrue("supertonic graphs not staged at $st", st.isDirectory)
        assertTrue("d4_inputs.json not staged at $inputs", inputs.isFile)
        val merged = D4ProbeRunner(context).run(outDir) { Log.d(D4ProbeRunner.TAG, it) }
        assertTrue("results must carry both legs", merged.has("piper") && merged.has("supertonic3"))
        val piperRes = merged.getJSONObject("piper")
        val stRes = merged.getJSONObject("supertonic3")
        assertTrue("piper leg must complete", !piperRes.has("unavailable") && piperRes.getBoolean("finite"))
        assertTrue("supertonic leg must complete", !stRes.has("unavailable") && stRes.getBoolean("finite"))
        Log.d(
            D4ProbeRunner.TAG,
            "d4 ok: piper rtf=${piperRes.getDouble("best_rtf")} " +
                "supertonic rtf=${stRes.getDouble("best_rtf")}",
        )
    }
}
