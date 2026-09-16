package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 2026-08-31 closer-look probe (landscape.md §HF trending sweep): verifies the
 * two EVALUATE candidates actually open and run on ORT-android before any
 * engine work — the same gate the host-side ORT 1.23.2 (identical version to
 * the Android pin) already passed per graph.
 *
 * Legs (fabricated passes, matching the host-verified shapes exactly; the
 * corpus/quality gate is a later D4/D5 leg, not this probe):
 *  - chatterbox-q4 (BricksDisplay/chatterbox-multilingual-ONNX-q4, 790 MB):
 *    speech_encoder (0.5 s zero audio) -> embed_tokens -> language_model
 *    prefill (30-layer GroupQueryAttention + MatMulNBits, zero-length KV) ->
 *    conditional_decoder. The vocoder step uses fabricated tokens and is
 *    EXPECTED to fail (0-size f0 slice — a token-stream artifact, host-verified
 *    identical); it is recorded for host/device parity. The working
 *    AR-generated-token vocoder path is verified host-side only (q4 vocoder
 *    decodes 1.6 s audio finite, peak 1.36).
 *  - audio8 0.1B INT8 (Audio8/audio8-TTS-0.1B-ONNX-INT8, 431 MB online set):
 *    slow_ar one recurrent step (zero states) -> fast_ar one step ->
 *    codec decoder over a fabricated [1,10,8] codes slice. All-finite on host
 *    (slow 30 ms, fast 4 ms, codec 277 ms/8 frames @ 1.23.2 CPU).
 *
 * Results JSON: per-session open_ms, per-run ms, output shapes +
 * finite/nan/inf/peak/rms, and process memory (PSS/VmHWM) with all sessions
 * resident. Written after every completed leg (lmkd on a 3.97 GB device can
 * kill mid-run — the D3 runner's survive-the-kill flush pattern).
 */
class OnnxProbeRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "OnnxProbe"

        private val CHATTERBOX = "chatterbox-q4"
        private val AUDIO8 = "audio8"

        private val CBC_GRAPHS =
            mapOf(
                "speech_encoder" to "onnx/speech_encoder.onnx",
                "embed_tokens" to "onnx/embed_tokens.onnx",
                "language_model" to "onnx/language_model.onnx",
                "conditional_decoder" to "onnx/conditional_decoder.onnx",
            )
        private val A8_GRAPHS =
            mapOf(
                "slow_ar" to "slow_ar_int8.onnx",
                "fast_ar" to "fast_ar_int8.onnx",
                "codec" to "codec_decoder_fp16.onnx",
            )
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
        val outFile = File(outDir, "onnx_probe_results.json")

        fun flush() {
            File(outDir, "onnx_probe_results.json.tmp").writeText(merged.toString(2))
            outFile.delete()
            File(outDir, "onnx_probe_results.json.tmp").renameTo(outFile)
        }

        val root = File(context.filesDir, "models")
        val cbcRoot = File(root, CHATTERBOX)
        val a8Root = File(root, AUDIO8)

        // ---- chatterbox-q4 leg ----
        try {
            merged.put(CHATTERBOX, probeChatterbox(cbcRoot) { log("[chatterbox-q4] $it") })
        } catch (t: Throwable) {
            merged.put(CHATTERBOX, JSONObject().put("unavailable", t.message ?: t.toString()))
        }
        flush()

        // ---- audio8 leg ----
        try {
            merged.put(AUDIO8, probeAudio8(a8Root) { log("[audio8] $it") })
        } catch (t: Throwable) {
            merged.put(AUDIO8, JSONObject().put("unavailable", t.message ?: t.toString()))
        }
        flush()

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        merged
            .put("vm_hwm_kb", readVmHwm())
            .put("total_pss_kb", mem.totalPss)
        flush()
        log("onnx_probe_results.json written to $outDir")
        return merged
    }

    private fun open(
        graph: File,
        log: (String) -> Unit,
    ): Pair<OrtSession, Long> {
        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(6)
        // MOSS lesson (decisions #93): ORT-android memory-pattern optimization
        // plus the CPU arena allocator can push lmkd-kill RSS on weak-RAM
        // devices; disable both for a truthful weaker-device memory number.
        opts.setMemoryPatternOptimization(false)
        opts.setCPUArenaAllocator(false)
        val session = env.createSession(graph.absolutePath, opts)
        return session to (System.currentTimeMillis() - t0)
    }

    private fun probeChatterbox(
        root: File,
        log: (String) -> Unit,
    ): JSONObject {
        check(root.isDirectory) { "chatterbox-q4 model dir missing at $root" }
        val out = JSONObject()
        val opened = LinkedHashMap<String, OrtSession>()
        try {
            for ((name, rel) in CBC_GRAPHS) {
                val (s, ms) = open(File(root, rel), log)
                opened[name] = s
                out.put("open_${name}_ms", ms)
                log("open $name: $ms ms")
            }
            val enc = opened.getValue("speech_encoder")
            val t0 = System.currentTimeMillis()
            val encOuts =
                enc.run(
                    mapOf(
                        "audio_values" to f32T(12000, 1L, 12000L),
                    ),
                )
            out.put("enc_run_ms", System.currentTimeMillis() - t0)
            out.put("enc_audio_features", tensorStat(encOuts[0] as OnnxTensor))
            out.put("enc_audio_tokens", tensorStat(encOuts[1] as OnnxTensor))
            out.put("enc_speaker_embeddings", tensorStat(encOuts[2] as OnnxTensor))
            out.put("enc_speaker_features", tensorStat(encOuts[3] as OnnxTensor))
            log("speech_encoder run ok")

            val emb = opened.getValue("embed_tokens")
            val t1 = System.currentTimeMillis()
            val inputIds = LongArray(4)
            val pos = longArrayOf(-1, 0, 1, 2)
            val embOut =
                emb.run(
                    mapOf(
                        "input_ids" to i64T(inputIds, 1L, 4L),
                        "position_ids" to i64T(pos, 1L, 4L),
                        "exaggeration" to f32T(1, 1L),
                    ),
                )
            out.put("embed_run_ms", System.currentTimeMillis() - t1)
            out.put("embed_inputs_embeds", tensorStat(embOut[0] as OnnxTensor))
            log("embed_tokens run ok")

            // llm prefill: fabricated [1,37,1024] inputs_embeds (values don't
            // matter for an open/run/finite gate; host python fed the real
            // concat of cond_emb [1,33,1024] + text embeds [1,4,1024]).
            val llm = opened.getValue("language_model")
            val seq = 33 + 4
            val feeds = LinkedHashMap<String, OnnxTensor>()
            feeds["inputs_embeds"] = f32T2(0.0f, 1L, seq.toLong(), 1024L)
            feeds["attention_mask"] = i64T(LongArray(seq) { 1 }, 1L, seq.toLong())
            for (l in 0 until 30) {
                feeds["past_key_values.$l.key"] = f32T2(0.0f, 1L, 16L, 0L, 64L)
                feeds["past_key_values.$l.value"] = f32T2(0.0f, 1L, 16L, 0L, 64L)
            }
            val t2 = System.currentTimeMillis()
            val llmOuts = llm.run(feeds)
            out.put("llm_run_ms", System.currentTimeMillis() - t2)
            out.put("llm_logits", tensorStat(llmOuts[0] as OnnxTensor))
            out.put("llm_present0_key_shape", JSONArray((llmOuts[1] as OnnxTensor).info.shape.toList()))
            feeds.values.forEach { it.close() }
            log("language_model run ok")

            // conditional_decoder: fabricated-input run that host-side fails
            // with a 0-size f0 slice (token-stream artifact, not a graph
            // break); recorded for host/device parity.
            val voc = opened.getValue("conditional_decoder")
            val t3 = System.currentTimeMillis()
            try {
                val vocOuts =
                    voc.run(
                        mapOf(
                            "speech_tokens" to i64T(LongArray(13), 1L, 13L),
                            "speaker_embeddings" to f32T2(0.0f, 1L, 192L),
                            "speaker_features" to f32T2(0.0f, 1L, 25L, 80L),
                        ),
                    )
                out.put("voc_run_ms", System.currentTimeMillis() - t3)
                out.put("voc_waveform", tensorStat(vocOuts[0] as OnnxTensor))
                log("conditional_decoder run ok")
            } catch (e: Throwable) {
                out.put("voc_error", e.message ?: e.toString())
                out.put("voc_run_ms", System.currentTimeMillis() - t3)
                log("conditional_decoder FAILED: ${e.message}")
            }
            return out
        } finally {
            opened.values.forEach { it.close() }
        }
    }

    private fun probeAudio8(
        root: File,
        log: (String) -> Unit,
    ): JSONObject {
        check(root.isDirectory) { "audio8 model dir missing at $root" }
        val out = JSONObject()
        val opened = LinkedHashMap<String, OrtSession>()
        try {
            for ((name, rel) in A8_GRAPHS) {
                val (s, ms) = open(File(root, rel), log)
                opened[name] = s
                out.put("open_${name}_ms", ms)
                log("open $name: $ms ms")
            }
            val slow = opened.getValue("slow_ar")
            val t0 = System.currentTimeMillis()
            val slowOuts =
                slow.run(
                    mapOf(
                        "codes" to i64T(LongArray(11), 1L, 11L, 1L),
                        "position" to i64T(LongArray(1), 1L),
                        "cache_keys" to f32T2(0.0f, 24L, 1L, 2L, 2048L, 64L),
                        "cache_values" to f32T2(0.0f, 24L, 1L, 2L, 2048L, 64L),
                        "conv_states" to f32T2(0.0f, 24L, 1L, 896L, 4L),
                        "ssm_states" to f32T2(0.0f, 24L, 1L, 24L, 32L, 64L),
                    ),
                )
            out.put("slow_run_ms", System.currentTimeMillis() - t0)
            out.put("slow_logits", tensorStat(slowOuts[0] as OnnxTensor))
            out.put("slow_hidden", tensorStat(slowOuts[1] as OnnxTensor))
            log("slow_ar run ok")

            val fast = opened.getValue("fast_ar")
            val t1 = System.currentTimeMillis()
            val fastOuts =
                fast.run(
                    mapOf(
                        "slow_hidden" to f32T2(0.0f, 1L, 1L, 512L),
                        "token_id" to i64T(LongArray(1), 1L, 1L),
                        "use_slow_hidden" to boolT(true, 1L),
                        "input_pos" to i64T(LongArray(1), 1L),
                        "cache_key_0" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_value_0" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_key_1" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_value_1" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_key_2" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_value_2" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_key_3" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                        "cache_value_3" to f32T2(0.0f, 1L, 2L, 10L, 64L),
                    ),
                )
            out.put("fast_run_ms", System.currentTimeMillis() - t1)
            out.put("fast_logits", tensorStat(fastOuts[0] as OnnxTensor))
            log("fast_ar run ok")

            val codec = opened.getValue("codec")
            val t2 = System.currentTimeMillis()
            val codecOuts =
                codec.run(
                    mapOf(
                        "codes" to i64T(LongArray(80) { 0 }, 1L, 10L, 8L),
                    ),
                )
            out.put("codec_run_ms", System.currentTimeMillis() - t2)
            out.put("codec_audio", tensorStat(codecOuts[0] as OnnxTensor))
            log("codec run ok")
            return out
        } finally {
            opened.values.forEach { it.close() }
        }
    }

    // ---- tensor helpers (repo pattern: direct native-order buffers + OnnxTensor.createTensor) ----

    private fun f32T(
        count: Int,
        vararg dims: Long,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return OnnxTensor.createTensor(env, buf, longArrayOf(*dims))
    }

    private fun f32T2(
        fill: Float,
        vararg dims: Long,
    ): OnnxTensor {
        val n = dims.fold(1L) { a, b -> a * b }.toInt()
        val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        while (buf.hasRemaining()) buf.put(fill)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(*dims))
    }

    private fun i64T(
        data: LongArray,
        vararg dims: Long,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(*dims))
    }

    private fun boolT(
        value: Boolean,
        vararg dims: Long,
    ): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(1)
        bb.put(if (value) 1 else 0)
        bb.rewind()
        return OnnxTensor.createTensor(env, bb, longArrayOf(*dims), OnnxJavaType.BOOL)
    }

    /** Float stats for a float output tensor; ints just report shape/type. */
    private fun tensorStat(t: OnnxTensor): JSONObject {
        val info = t.info
        val stat = JSONObject().put("shape", JSONArray(info.shape.toList()))
        if (info.type != OnnxJavaType.FLOAT) {
            return stat.put("type", info.type.toString())
        }
        val arr = t.floatBuffer
        val n = arr.remaining()
        var nan = 0
        var inf = 0
        var peak = 0.0f
        var sumSq = 0.0
        var v: Float
        repeat(n) {
            v = arr.get()
            if (v.isNaN()) {
                nan++
            } else if (v.isInfinite()) {
                inf++
            } else {
                peak = maxOf(peak, kotlin.math.abs(v))
                sumSq += v.toDouble() * v
            }
        }
        return stat
            .put("type", "float")
            .put("count", n)
            .put("nan", nan)
            .put("inf", inf)
            .put("finite", nan == 0 && inf == 0)
            .put("peak_abs", peak)
            .put("rms", Math.sqrt(sumSq / n))
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }
}
