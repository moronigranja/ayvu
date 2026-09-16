package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * kokoro-hexagon P4: on-device AOT. Compiles each offloadable stage ONCE
 * through the QNN EP with ep.context_enable=1 and writes the per-SoC context
 * binary (the JIT compile OOMs/minutes-long inside the full pipeline — see
 * decisions #8/#11). The stage runner then loads the cached contexts.
 *
 * Usage (staged per build.md):
 *   adb shell am instrument -w -e class \
 *     io.github.moronigranja.ayvu.spiketts.KokoroAotCompileTest \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KokoroAotCompileTest {
    companion object {
        private const val TA_PIN = 1344
        private const val TAG = "KokoroSpike"
    }

    /** Platform codename → directory label, mirroring QnnEp.SOC_MODELS. */
    private fun platform(): String =
        try {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            get.invoke(null, "ro.board.platform") as? String ?: Build.HARDWARE
        } catch (_: Throwable) {
            Build.HARDWARE
        }

    private fun compileStage(
        env: OrtEnvironment,
        stageDir: File,
        outDir: File,
        stage: String,
        log: (String) -> Unit,
    ) {
        val outFile = File(outDir, "$stage.context.bin")
        val options =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                KokoroBenchmarkRunner.QnnEp.install(this)
                // ep.* keys are SESSION config entries (not provider options) —
                // onnxruntime.ai QNN-ExecutionProvider docs; passed as provider
                // options they are silently ignored (measured).
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", outFile.absolutePath)
            }
        val t0 = System.currentTimeMillis()
        val modelFile = if (stage == "vocoder-fp32") File(stageDir, "vocoder.onnx") else File(stageDir, "$stage.onnx")
        val session = env.createSession(modelFile.absolutePath, options)
        val openMs = System.currentTimeMillis() - t0
        // one inference so the graph is fully realized, then release
        val feeds: Map<String, OnnxTensor> =
            when (stage) {
                "prosody" ->
                    mapOf(
                        "/MatMul_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(640 * TA_PIN)), longArrayOf(1, 640, TA_PIN.toLong())),
                        "/Slice_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(128)), longArrayOf(1, 128)),
                    )
                else ->
                    mapOf(
                        "/MatMul_1_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(512 * TA_PIN)), longArrayOf(1, 512, TA_PIN.toLong())),
                        "/If_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(2 * TA_PIN)), longArrayOf(1, (2 * TA_PIN).toLong())),
                        "/If_1_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(2 * TA_PIN)), longArrayOf(1, (2 * TA_PIN).toLong())),
                        "/decoder/generator/noise_res.0/Add_8_output_0" to
                            OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(FloatArray(256 * 20 * TA_PIN)),
                                longArrayOf(1, 256, (20 * TA_PIN).toLong()),
                            ),
                        "/decoder/generator/noise_res.1/Add_8_output_0" to
                            OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(FloatArray(128 * (120 * TA_PIN + 1))),
                                longArrayOf(1, 128, (120 * TA_PIN + 1).toLong()),
                            ),
                        "/Slice_2_output_0" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(128)), longArrayOf(1, 128)),
                    )
            }
        feeds.values.forEach { it.close() }
        session.close()
        val size = if (outFile.isFile) outFile.length() else -1
        log("aot $stage: open ${openMs}ms, context $size bytes -> ${outFile.absolutePath}")
    }

    @Test
    fun compileContextBinaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        KokoroBenchmarkRunner.QnnEp.libDir = context.applicationInfo.nativeLibraryDir
        val env = OrtEnvironment.getEnvironment()
        val stageDir = File(File(context.filesDir, "models"), "stages-1344")
        val outDir = File(File(context.filesDir, "models"), "contexts-${platform()}")
        outDir.mkdirs()
        val log: (String) -> Unit = { line: String -> Log.d(TAG, line) }
        val stages =
            if (File(stageDir, "vocoder-fp32.onnx").isFile) {
                listOf("prosody", "vocoder", "vocoder-fp32")
            } else {
                listOf("prosody", "vocoder")
            }
        for (stage in stages) {
            if (File(outDir, "$stage.context.bin").isFile) {
                log("aot $stage: context exists, skip")
                continue
            }
            try {
                compileStage(env, stageDir, outDir, stage, log)
            } catch (e: Throwable) {
                log("aot $stage FAILED: ${e.message?.take(300)}")
            }
        }
    }
}
