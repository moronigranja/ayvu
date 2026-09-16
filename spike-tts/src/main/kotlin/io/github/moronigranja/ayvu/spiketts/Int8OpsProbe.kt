package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * ConvInteger availability probe (decisions #86 follow-up): the D3 int8 leg
 * failed to OPEN on the CPU EP with ORT `not_implemented` — "Could not find
 * an implementation for ConvInteger(10)". This runs a minimal single-node
 * ConvInteger graph so the blocker can be re-tested per runtime version
 * independently of model availability: pass the runtime version as an
 * instrumentation arg (`-e ort_version x.y.z`) so results are self-labeling
 * under the pin A/B (1.23.2 vs 1.29.0).
 */
class Int8OpsProbe(
    private val context: Context,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun run(
        args: Bundle?,
        log: (String) -> Unit,
    ): JSONObject {
        val model = File(context.filesDir, "models/convinteger_test.onnx")
        val result = JSONObject().put("ort_version", args?.getString("ort_version") ?: "unknown")
        if (!model.isFile) {
            result.put("unavailable", "convinteger_test.onnx not staged")
            return result
        }
        try {
            env.createSession(model.absolutePath, OrtSession.SessionOptions()).use { s ->
                val x = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
                val w = ByteBuffer.wrap(byteArrayOf(1, 0, 0, 1))
                OnnxTensor.createTensor(env, x, longArrayOf(1, 1, 4, 4), OnnxJavaType.INT8).use { xt ->
                    OnnxTensor.createTensor(env, w, longArrayOf(1, 1, 2, 2), OnnxJavaType.INT8).use { wt ->
                        s.run(mapOf("X" to xt, "W" to wt)).use { out ->
                            result.put("convinteger", "implemented").put("output", out[0].value.toString())
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            result.put("convinteger", "failed").put("error", t.message ?: t.toString())
        }
        log(result.toString(2))
        File(context.getExternalFilesDir(null) ?: context.filesDir, "convinteger_probe_results.json")
            .writeText(result.toString(2))
        return result
    }
}
