package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2026-08-31 closer-look probe on-device (landscape.md §HF trending sweep):
 * opens + runs the chatterbox-q4 and Audio8 0.1B ONNX graphs through
 * ORT-android (the pinned 1.23.2) on the HiBreak, recording per-session
 * open/run timing, output finiteness, and process memory. Feeds the same
 * fabricated shapes the host-side 1.23.2 gate verified, so a host/device
 * divergence (the Kitten/MOSS ARM NaN class) is the thing under test.
 *
 * Staging (see build.md §"ONNX closer-look staging" once added): models live
 * at files/models/chatterbox-q4 (onnx subfolder) and
 * files/models/audio8/{slow_ar_int8.onnx,fast_ar_int8.onnx,
 * codec_decoder_fp16.onnx,.data}. The conditional_decoder is run with
 * fabricated inputs that the host gate already proved to FAIL (0-size f0
 * slice — a token-stream artifact, not a graph break); the device result is
 * recorded as `voc_error` for host/device parity, and the working AR-driven
 * vocoder path is verified host-side only for now.
 */
@RunWith(AndroidJUnit4::class)
class OnnxProbeBenchmarkTest {
    @Test
    fun probeCandidatesOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val cbc = File(context.filesDir, "models/chatterbox-q4")
        val a8 = File(context.filesDir, "models/audio8")
        assertTrue("chatterbox-q4 not staged at $cbc", cbc.isDirectory)
        assertTrue("audio8 not staged at $a8", a8.isDirectory)
        val merged = OnnxProbeRunner(context).run(outDir) { Log.d(OnnxProbeRunner.TAG, it) }
        assertTrue("results must carry both legs", merged.has("chatterbox-q4") && merged.has("audio8"))
        val a8res = merged.getJSONObject("audio8")
        assertTrue(
            "audio8 leg must complete",
            !a8res.has("unavailable") && a8res.optLong("open_slow_ar_ms", -1) > 0,
        )
        Log.d(
            OnnxProbeRunner.TAG,
            "probe ok: cb=${merged.optJSONObject("chatterbox-q4")?.has("unavailable") == false} " +
                "a8=${!a8res.has("unavailable")}",
        )
    }
}
