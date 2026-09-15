package com.moronigranja.localttsreader.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D5 Pocket TTS probe on-device (roadmap D5, decisions #149): runs the Kyutai
 * Pocket TTS int8 export end-to-end on the HiBreak through ORT-android over
 * the host-prepared `files/d5_inputs.json`, recording open/RTF/stage/PSS
 * numbers per thread leg and writing playable WAVs plus the temperature-0
 * parity latents (compared against the host reference — the #86 stub gate).
 * Staging (build.md "D5 Pocket TTS staging"): files/models/pocket/ graphs,
 * files/voice_24k.f32, files/d5_inputs.json, files/pocket_ref_meta.json,
 * files/pocket_ref_latents.f32, files/pocket_ref_audio.f32.
 */
@RunWith(AndroidJUnit4::class)
class PocketProbeBenchmarkTest {
    @Test
    fun probePocketOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(context.filesDir, "models/pocket/english_2026-04")
        assertTrue("pocket graphs not staged at $dir", dir.isDirectory)
        assertTrue("d5_inputs.json not staged", File(context.filesDir, "d5_inputs.json").isFile)
        assertTrue("voice pcm not staged", File(context.filesDir, "voice_24k.f32").isFile)
        val merged = PocketProbeRunner(context).run(outDir) { Log.d(PocketProbeRunner.TAG, it) }
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
        Log.d(PocketProbeRunner.TAG, "d5 ok: ref rtf=${runs.getJSONObject(0).optDouble("rtf", -1.0)}")
    }
}
