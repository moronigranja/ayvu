package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase J NMT probe on-device (roadmap Phase J): runs the per-pair OPUS-MT
 * baselines and the single many-to-many M2M-100-418M, fp32 AND dynamic-int8,
 * across the four spike pairs (it->es, en->pt-br, en->it, es->en) through
 * ORT-android (pinned 1.29.0) over the host-prepared `files/translate_inputs.json`
 * (tools/gen_nmt_inputs.py, FLORES-101). Asserts every staged `pair x model x
 * precision` leg completes: >0 tokens, finite logits, eos reached on sentence
 * items, per-leg open/decoder-ms/PSS/VmHWM recorded to translate_results.json.
 * Produced token ids are decoded + chr-F-scored host-side (the spike's quality
 * gate; en->pt-br also gets an owner blind read, decisions #101).
 *
 * Staging (build.md "NMT spike staging"): files/models/<model>/onnx{,-int8}/
 * graphs + files/translate_inputs.json.
 */
@RunWith(AndroidJUnit4::class)
class TranslateProbeBenchmarkTest {
    @Test
    fun probeNmtOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val inputs = File(context.filesDir, "translate_inputs.json")
        assertTrue("translate_inputs.json not staged at $inputs", inputs.isFile)
        val models = File(context.filesDir, "models")
        assertTrue("models root not staged at $models", models.isDirectory)

        val pairs = org.json.JSONObject(inputs.readText()).getJSONObject("pairs")
        val merged = TranslateProbeRunner(context).run(outDir) { Log.d(TranslateProbeRunner.TAG, it) }

        var legs = 0
        for (pair in pairs.keys()) {
            val pairResults = merged.getJSONObject(pair)
            for (modelId in pairs.getJSONObject(pair).getJSONObject("models").keys()) {
                for (precision in arrayOf("fp32", "int8")) {
                    val legKey = "$pair/$modelId/$precision"
                    val leg =
                        pairResults.optJSONObject("$modelId/$precision")
                            ?: throw AssertionError("$legKey: leg missing from results")
                    if (leg.has("unavailable")) {
                        // Measured absence (e.g. M2M-100 fp32: 4.75 GB of graphs,
                        // lmkd-killed on the S22 — decisions #114). The reason is
                        // the record; absence is not a test failure when the
                        // graphs are genuinely not staged.
                        val dir =
                            File(
                                context.filesDir,
                                "models/$modelId/" +
                                    if (precision == "fp32") "onnx" else "onnx-$precision",
                            )
                        assertTrue(
                            "$legKey: failed with graphs staged: ${leg.optString("unavailable")}",
                            !dir.isDirectory,
                        )
                        Log.w(TranslateProbeRunner.TAG, "$legKey unavailable: ${leg.optString("unavailable")}")
                        continue
                    }
                    assertTrue("$legKey: no tokens", leg.getLong("tokens") > 0)
                    assertTrue("$pair/$modelId/$precision: non-finite logits", leg.getBoolean("finite"))
                    val items = leg.getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        assertTrue(
                            "$pair/$modelId/$precision item $i: 0 tokens",
                            item.getLong("tokens") > 0,
                        )
                        if (item.getString("kind") == "sent") {
                            assertTrue(
                                "$pair/$modelId/$precision item $i: EOS not reached",
                                item.getBoolean("eos_hit"),
                            )
                        }
                    }
                    legs++
                }
            }
        }
        Log.d(TranslateProbeRunner.TAG, "translate ok: $legs legs completed")
        assertTrue("no legs completed", legs > 0)
    }
}
