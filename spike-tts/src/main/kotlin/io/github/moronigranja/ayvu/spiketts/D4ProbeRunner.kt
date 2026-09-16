package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * D4 small-tier probe (roadmap D4): Piper (rhasspy/piper-voices
 * en_US-lessac-medium, direct-ORT VITS — NOT sherpa) and Supertonic 3
 * (Supertone/supertonic-3 @ 3cadd1ee, flow-matching, 4 graphs) measured on
 * the HiBreak against the Kokoro 3.01 RTF baseline (bugs.md B6 re-measure,
 * #93/#97). The verdict legs of the 2026-08-31 closer-look probe
 * ([OnnxProbeRunner]) proved open/run-finite on fabricated passes; this one
 * runs the REAL pipelines end-to-end and produces playable WAVs.
 *
 * All model-dependent inputs are host-prepared into `files/d4_inputs.json`
 * (the D3 host-precomputed-corpus pattern, decisions #93) so the device
 * numbers compare inference only: Piper phoneme ids (host espeak-ng 1.52 →
 * the voice's phoneme_id_map), Supertonic text_ids/mask (reference SDK
 * UnicodeProcessor, lang "na"), style vectors (M1.json), and the latent
 * shape (dp-deterministic). RTF keys on the ACTUAL produced audio length —
 * the Supertonic dp duration is proportional, not seconds: the vocoder
 * emits ~1.7× the dp seconds at latent_len frames.
 *
 * Session options follow the #93 MOSS lesson (memory patterns + CPU arena
 * off, 6 intra-op threads) so memory numbers are truthful on a 3.9 GB device.
 * Results flush after every leg (lmkd can kill mid-run).
 */
class D4ProbeRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "D4Probe"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun run(
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val merged =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("inputs", JSONObject(context.filesDir.resolve("d4_inputs.json").readText()))
        val outFile = File(outDir, "d4_probe_results.json")

        fun flush() {
            File(outDir, "d4_probe_results.json.tmp").writeText(merged.toString(2))
            outFile.delete()
            File(outDir, "d4_probe_results.json.tmp").renameTo(outFile)
        }

        val inputs = merged.getJSONObject("inputs")
        val models = File(context.filesDir, "models")

        try {
            merged.put(
                "piper",
                probePiper(inputs.getJSONObject("piper"), File(models, "piper/en_US-lessac-medium.onnx"), outDir) {
                    log("[piper] $it")
                },
            )
        } catch (t: Throwable) {
            merged.put("piper", JSONObject().put("unavailable", t.message ?: t.toString()))
        }
        flush()

        try {
            merged.put(
                "supertonic3",
                probeSupertonic(inputs.getJSONObject("supertonic"), File(models, "supertonic/onnx"), outDir) {
                    log("[supertonic3] $it")
                },
            )
        } catch (t: Throwable) {
            merged.put("supertonic3", JSONObject().put("unavailable", t.message ?: t.toString()))
        }
        flush()

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        merged.put("total_pss_kb", mem.totalPss).put("vm_hwm_kb", readVmHwm())
        flush()
        log("d4_probe_results.json written to $outDir")
        return merged
    }

    // ---- Piper: single VITS graph, ids -> audio @ 22050 ----

    private fun probePiper(
        input: JSONObject,
        model: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        check(model.isFile) { "piper model missing at $model" }
        val (session, openMs) = open(model, log)
        session.use {
            val ids = input.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val scales =
                floatArrayOf(
                    input.getDouble("noise_scale").toFloat(),
                    input.getDouble("length_scale").toFloat(),
                    input.getDouble("noise_w").toFloat(),
                )
            val sampleRate = input.getInt("sample_rate").toLong()

            fun runOnce(): FloatArray =
                OnnxTensor
                    .createTensor(
                        env,
                        LongBuffer.wrap(ids),
                        longArrayOf(1, ids.size.toLong()),
                    ).use { t ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3)).use { s ->
                            OnnxTensor
                                .createTensor(
                                    env,
                                    LongBuffer.wrap(longArrayOf(ids.size.toLong())),
                                    longArrayOf(1),
                                ).use { l ->
                                    session.run(mapOf("input" to t, "scales" to s, "input_lengths" to l)).use { out ->
                                        flatten(out[0].value)
                                    }
                                }
                        }
                    }

            runOnce() // warmup
            val runs = JSONArray()
            var wav = FloatArray(0)
            repeat(3) {
                val t0 = System.nanoTime()
                wav = runOnce()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                runs.put(statLine(ms, wav.size, sampleRate))
                log("run ${it + 1}: $ms ms for ${wav.size.toDouble() / sampleRate} s")
            }
            Wav.write(File(outDir, "d4_piper.wav"), wav, sampleRate.toInt())
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            return legResult(openMs, runs, wav, sampleRate, mem)
        }
    }

    // ---- Supertonic 3: dp -> text_encoder -> 8-step flow ODE -> vocoder @ 44100 ----

    private fun probeSupertonic(
        input: JSONObject,
        onnxDir: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val graphs =
            mapOf(
                "dp" to "duration_predictor.onnx",
                "text_encoder" to "text_encoder.onnx",
                "vector_estimator" to "vector_estimator.onnx",
                "vocoder" to "vocoder.onnx",
            ).mapValues { File(onnxDir, it.value) }
        graphs.values.forEach { check(it.isFile) { "supertonic graph missing at $it" } }

        val sessions = LinkedHashMap<String, Pair<OrtSession, Long>>()
        try {
            for ((name, file) in graphs) sessions[name] = open(file, log)
            val dp = sessions.getValue("dp").first
            val textEncoder = sessions.getValue("text_encoder").first
            val vectorEst = sessions.getValue("vector_estimator").first
            val vocoder = sessions.getValue("vocoder").first

            val textIds = flatLongs(input.getJSONArray("text_ids"))
            val textMask = flatFloats(input.getJSONArray("text_mask"))
            val styleTtl = flatFloats(input.getJSONArray("style_ttl"))
            val styleDp = flatFloats(input.getJSONArray("style_dp"))
            val latentMask = flatFloats(input.getJSONArray("latent_mask"))
            val latentDim = input.getLong("latent_dim")
            val latentLen = input.getLong("latent_len")
            val sampleRate = input.getInt("sample_rate").toLong()
            val textLen = textIds.size.toLong()
            val steps = input.getInt("steps")

            val textIdsT = OnnxTensor.createTensor(env, LongBuffer.wrap(textIds), longArrayOf(1, textLen))
            val textMaskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textLen))
            val styleTtlT = OnnxTensor.createTensor(env, FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256))
            val styleDpT = OnnxTensor.createTensor(env, FloatBuffer.wrap(styleDp), longArrayOf(1, 8, 16))
            val latentMaskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLen))
            val totalStepT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1))

            fun noisyLatent(): OnnxTensor {
                val data = FloatArray((latentDim * latentLen).toInt()) { Math.random().toFloat() * 2f - 1f }
                // latent_mask is [1,1,L]: broadcast over the latent-dim axis,
                // i.e. flat row-major [144][L] multiplies by mask[i % L].
                for (i in data.indices) data[i] *= latentMask[i % latentMask.size]
                return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, latentDim, latentLen))
            }

            fun runFullPipeline(): FloatArray {
                dp.run(mapOf("text_ids" to textIdsT, "style_dp" to styleDpT, "text_mask" to textMaskT)).use { dpOut ->
                    dpOut[0].close()
                }
                textEncoder
                    .run(
                        mapOf("text_ids" to textIdsT, "style_ttl" to styleTtlT, "text_mask" to textMaskT),
                    ).use { encOut ->
                        @Suppress("UNCHECKED_CAST")
                        val emb = encOut[0].value as Array<Array<FloatArray>>
                        val embFlat = emb.flatMap { a -> a.flatMap { it.toList() }.map { it } }.toFloatArray()
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(embFlat), longArrayOf(1, 256, textLen)).use { textEmbT ->
                            var latent = noisyLatent()
                            for (step in 0 until steps) {
                                OnnxTensor
                                    .createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
                                    .use { curStepT ->
                                        vectorEst
                                            .run(
                                                mapOf(
                                                    "noisy_latent" to latent,
                                                    "text_emb" to textEmbT,
                                                    "style_ttl" to styleTtlT,
                                                    "latent_mask" to latentMaskT,
                                                    "text_mask" to textMaskT,
                                                    "current_step" to curStepT,
                                                    "total_step" to totalStepT,
                                                ),
                                            ).use { estOut ->
                                                latent.close()
                                                @Suppress("UNCHECKED_CAST")
                                                val denoised = estOut[0].value as Array<Array<FloatArray>>
                                                val flat = denoised.flatMap { a -> a.flatMap { it.toList() } }.toFloatArray()
                                                latent =
                                                    OnnxTensor.createTensor(
                                                        env,
                                                        FloatBuffer.wrap(flat),
                                                        longArrayOf(1, latentDim, latentLen),
                                                    )
                                            }
                                    }
                            }
                            vocoder.run(mapOf("latent" to latent)).use { vocOut ->
                                latent.close()
                                return flatten(vocOut[0].value)
                            }
                        }
                    }
            }

            runFullPipeline() // warmup
            val runs = JSONArray()
            var wav = FloatArray(0)
            repeat(3) {
                val t0 = System.nanoTime()
                wav = runFullPipeline()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                runs.put(statLine(ms, wav.size, sampleRate))
                log("run ${it + 1}: $ms ms for ${wav.size.toDouble() / sampleRate} s")
            }
            Wav.write(File(outDir, "d4_supertonic.wav"), wav, sampleRate.toInt())
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            val openMs = JSONObject()
            for ((name, pair) in sessions) openMs.put(name, pair.second)
            return legResult(openMs, runs, wav, sampleRate, mem).put("steps", steps)
        } finally {
            sessions.values.forEach { it.first.close() }
        }
    }

    // ---- helpers ----

    /** Flattens a [T], [1,T] or [1,T,1] float output to samples. */
    private fun flatten(value: Any): FloatArray =
        when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val out = ArrayList<Float>(1 shl 16)
                for (e in value) out.addAll(flatten(e!!).toList())
                out.toFloatArray()
            }
            else -> error("unexpected output tensor type: ${value::class.java}")
        }

    private fun statLine(
        ms: Double,
        samples: Int,
        sampleRate: Long,
    ): JSONObject {
        val audioS = samples.toDouble() / sampleRate
        return JSONObject()
            .put("synth_ms", Math.round(ms * 10) / 10.0)
            .put("audio_s", Math.round(audioS * 100) / 100.0)
            .put("rtf", Math.round(ms / 1000.0 / audioS * 1000) / 1000.0)
    }

    private fun legResult(
        openMs: Any,
        runs: JSONArray,
        wav: FloatArray,
        sampleRate: Long,
        mem: Debug.MemoryInfo,
    ): JSONObject =
        JSONObject()
            .put("open_ms", openMs)
            .put("runs", runs)
            .put("best_rtf", (0 until runs.length()).minOf { runs.getJSONObject(it).getDouble("rtf") })
            .put("sample_rate", sampleRate)
            .put("peak", Math.round(wav.max()))
            .put("finite", wav.all { it.isFinite() })
            .put("total_pss_kb", mem.totalPss)
            .put("vm_hwm_kb", readVmHwm())

    private fun open(
        graph: File,
        log: (String) -> Unit,
    ): Pair<OrtSession, Long> {
        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(6)
        // MOSS lesson (decisions #93): truthful weak-device memory numbers.
        opts.setMemoryPatternOptimization(false)
        opts.setCPUArenaAllocator(false)
        val session = env.createSession(graph.absolutePath, opts)
        val ms = System.currentTimeMillis() - t0
        log("opened ${graph.name} in $ms ms")
        return session to ms
    }

    private fun flatLongs(a: JSONArray): LongArray {
        val outer = a.getJSONArray(0)
        return LongArray(outer.length()) { outer.getLong(it) }
    }

    private fun flatFloats(a: JSONArray): FloatArray {
        // Recursively flattens nested [1][d1][d2] (or [1][1][d2]) JSON arrays
        // to flat row-major floats — style_ttl is [1, 50, 256] = 12800 values.
        val out = ArrayList<Float>(a.length() * 4)

        fun add(node: Any) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) add(node.get(i))
                is Number -> out.add(node.toFloat())
                else -> error("non-numeric in float array: $node")
            }
        }
        add(a)
        return out.toFloatArray()
    }

    private fun readVmHwm(): Long =
        try {
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("VmHWM") }
                .split(Regex("\\s+"))[1]
                .toLong()
        } catch (_: Throwable) {
            -1L
        }
}
