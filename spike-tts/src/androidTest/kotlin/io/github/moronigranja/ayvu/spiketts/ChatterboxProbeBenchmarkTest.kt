package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D5 Chatterbox probe on-device (roadmap D5): runs the q4 multilingual export
 * end-to-end on the S22 through ORT-android over the host-prepared
 * `files/d5_chat_inputs.json` (the on-device BPE is a recorded D5 gap),
 * recording open/RTF/stage/PSS numbers per thread leg and writing playable
 * WAVs plus the greedy-ref parity (token ids + audio vs the host reference —
 * the #86 stub gate). Staging (build.md "D5 Chatterbox staging"):
 * files/models/chatterbox/ graphs, files/chat_voice_24k.f32,
 * files/d5_chat_inputs.json, files/chat_ref_meta.json, files/chat_ref_audio.f32.
 */
@RunWith(AndroidJUnit4::class)
class ChatterboxProbeBenchmarkTest {
    @Test
    fun probeChatterboxOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(context.filesDir, "models/chatterbox")
        assertTrue("chatterbox graphs not staged at $dir", dir.isDirectory)
        assertTrue("d5_chat_inputs.json not staged", File(context.filesDir, "d5_chat_inputs.json").isFile)
        assertTrue("voice pcm not staged", File(context.filesDir, "chat_voice_24k.f32").isFile)
        assertTrue("ref meta not staged", File(context.filesDir, "chat_ref_meta.json").isFile)
        val merged = ChatterboxProbeRunner(context).run(outDir) { Log.d(ChatterboxProbeRunner.TAG, it) }
        val legs = merged.getJSONArray("legs")
        assertTrue("results must carry legs", legs.length() > 0)
        val leg = legs.getJSONObject(0)
        assertTrue("first leg must complete", !leg.has("error"))
        val runs = leg.getJSONArray("runs")
        assertTrue("runs must exist", runs.length() > 0)
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            assertTrue("run ${r.optString("id")} must be finite", r.optBoolean("finite", false))
        }
        Log.d(ChatterboxProbeRunner.TAG, "d5 ok: ref rtf=${runs.getJSONObject(0).optDouble("rtf", -1.0)}")
    }
}
