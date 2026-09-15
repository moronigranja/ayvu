package com.moronigranja.localttsreader.spiketts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Debug
import com.moronigranja.localttsreader.tts.kokoro.KokoroVocabulary
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceBank
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs

/**
 * kokoro-hexagon P4 device half: the T_a=1344-pinned stage pipeline, each stage
 * run on the CPU control and on the QNN HTP EP, outputs compared per stage.
 *
 * Chain (per kokoro-v1.0-stages-1344 artifacts):
 *   text_encoder (CPU; LSTM) -> duration[512], d[1,640,512], t_en[1,512,512]
 *   host alignment (Kotlin gather, bit-exact to common.host_alignment)
 *     -> en[1,640,T_a], asr[1,512,T_a], EOS-replicate-padded to 1344
 *   prosody (QNN ctx) en_pad + style_s -> F0[1,2688], N[1,2688]
 *   noise (CPU only; Random ops) F0 + style_timbre -> x_source_0/1 at pin
 *     (x_source_1 = x_pre = 120*T_a+1; the dims are the model's, verified vs
 *     the onnx graph)
 *   vocoder fp16 (CPU; AOT compile OOMs on v69, decision #12) asr_pad + F0 + N
 *     + x_sources + style_timbre -> x_pre[1,128,161281] + anchor
 *   tail (CPU; ConvTranspose iSTFT) x_pre -> waveform[806400]
 *
 * Offload verdict per stage: QNN leg runs AND (max_abs_diff > 0 or faster
 * than CPU). diff == 0 at CPU-like time = silent CPU fallback; unavailable =
 * fusion check rejected the graph.
 */
class KokoroStageRunner(
    private val context: Context,
) {
    init {

        KokoroBenchmarkRunner.QnnEp.libDir = context.applicationInfo.nativeLibraryDir
    }

    companion object {
        const val TA_PIN = 1344
        const val WINDOW = 512
        const val TEXT_ENCODER = "text_encoder"
        const val PROSODY = "prosody"
        const val NOISE = "noise"
        const val VOCODER = "vocoder"
        const val TAIL = "tail"
        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val stageDir: File = File(File(context.filesDir, "models"), "stages-1344")
    private val voicesFile: File = File(File(context.filesDir, "models"), "kokoro-voices")
    private val corpusFile: File = File(context.filesDir, "corpus.tsv")

    private fun stageOptions(qnn: Boolean): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (qnn) KokoroBenchmarkRunner.QnnEp.install(this)
        }

    private fun open(
        name: String,
        qnn: Boolean,
    ): OrtSession = env.createSession(File(stageDir, "$name.onnx").absolutePath, stageOptions(qnn))

    /**
     * Opens a precompiled QNN AOT context (the EPContext wrapper written by
     * KokoroAotCompileTest) instead of JIT-compiling the ONNX. Returns null
     * when no context exists for the stage. Loaded without ep.context_enable /
     * ep.context_file_path — the wrapper embeds the context path; opening it
     * reloads the cached HTP binary, which is the AOT payoff (JIT opens were
     * minutes-to-OOM; a context reload is milliseconds).
     */
    private fun openContext(stage: String): OrtSession? {
        val ctx = File(File(context.filesDir, "models"), "contexts-${platform()}/$stage.context.bin")
        if (!ctx.isFile) return null
        val options =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                KokoroBenchmarkRunner.QnnEp.install(this)
            }
        return env.createSession(ctx.absolutePath, options)
    }

    /** Platform codename → directory label, mirroring QnnEp.SOC_MODELS. */
    private fun platform(): String =
        try {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            get.invoke(null, "ro.board.platform") as? String ?: Build.HARDWARE
        } catch (_: Throwable) {
            Build.HARDWARE
        }

    /** FloatArray for fp32 outputs, LongArray for int64 outputs (duration). */
    private fun runStage(
        session: OrtSession,
        feeds: Map<String, OnnxTensor>,
    ): List<Any> {
        val names = session.outputNames.toList()
        try {
            session.run(feeds).use { result ->
                return names.map { name ->
                    val t = result.get(name).orElseThrow() as OnnxTensor
                    if (t.info.type == OnnxJavaType.INT64) {
                        val b = t.longBuffer
                        LongArray(b.remaining()).also { arr -> b.get(arr) }
                    } else {
                        val b = t.floatBuffer
                        FloatArray(b.remaining()).also { arr -> b.get(arr) }
                    }
                }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    /**
     * Host-side length regulation, bit-exact to common.host_alignment: the
     * one-hot matmul reduces to a column gather, and gather + zero products is
     * exact in fp32. Durations cover all 512 tokens (the family's pad-tail
     * silence is rendered by the graph, as in the static monolith).
     */
    private fun align(
        d: FloatArray, // 640 * 512
        tEn: FloatArray, // 512 * 512
        dur: LongArray,
    ): Pair<FloatArray, FloatArray> {
        val ta = dur.sum().toInt()
        val idx = IntArray(ta)
        var p = 0
        for (t in dur.indices) {
            repeat(dur[t].toInt()) { idx[p++] = t }
        }
        val en = FloatArray(640 * ta)
        val asr = FloatArray(512 * ta)
        for (c in 0 until 640) {
            for (j in 0 until ta) en[c * ta + j] = d[c * WINDOW + idx[j]]
        }
        for (c in 0 until 512) {
            for (j in 0 until ta) asr[c * ta + j] = tEn[c * WINDOW + idx[j]]
        }
        return en to asr
    }

    /** EOS-replicate the last column of a [C, T] layout up to [C, TA_PIN]. */
    private fun padReplicate(
        a: FloatArray,
        channels: Int,
        ta: Int,
    ): FloatArray {
        if (ta >= TA_PIN) return a
        val out = FloatArray(channels * TA_PIN)
        for (c in 0 until channels) {
            val last = a[c * ta + ta - 1]
            for (j in 0 until ta) out[c * TA_PIN + j] = a[c * ta + j]
            for (j in ta until TA_PIN) out[c * TA_PIN + j] = last
        }
        return out
    }

    private fun diff(
        a: FloatArray,
        b: FloatArray,
    ): Pair<Float, Float> {
        val n = minOf(a.size, b.size)
        var peak = 0.0f
        var total = 0.0
        for (i in 0 until n) {
            val d = abs(a[i] - b[i])
            if (d > peak) peak = d
            total += d
        }
        return peak to (if (n > 0) (total / n).toFloat() else 0f)
    }

    private fun tensor(
        env: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    /**
     * Runs the pinned chain for one passage window, each stage on CPU and on
     * QNN (CPU-only stages once), logging per-stage ms and HTP-vs-CPU diffs.
     */
    fun run(log: (String) -> Unit): Boolean =
        try {
            val vocab = KokoroVocabulary.parse(File(stageDir, "kokoro_config.json").readText())
            val voices = KokoroVoiceBank.load(voicesFile)
            val corpus =
                corpusFile.readLines().mapNotNull { l ->
                    val p = l.split('\t')
                    if (p.size == 3) p else null
                }
            log("device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
            log("stages dir: $stageDir; pin T_a=$TA_PIN")

            val results = JSONObject()
            val passagesJson = JSONArray()
            var allOk = true

            for ((text, language, phonemes) in corpus) {
                val voice = VOICES[language] ?: continue
                val styleRow =
                    voices.styleFor(voice, WINDOW)
                        ?: throw IllegalStateException("unknown voice $voice")
                val styleS = styleRow.copyOfRange(128, 256)
                val styleTimbre = styleRow.copyOfRange(0, 128)
                // The engine's chunker caps at 510 phonemes; take the first
                // window of each passage to bound the measurement.
                val ids = phonemes.mapNotNull { vocab[it] }.take(510).toIntArray()
                if (ids.isEmpty()) continue
                val idsPadded = LongArray(WINDOW)
                for (i in ids.indices) idsPadded[i + 1] = ids[i].toLong() // BOS at 0
                // window = [0] + ids + [0] + 0-pad: BOS at index 0, EOS right
                // after the tokens; trailing slots stay 0 (pad tokens).

                val passageJson = JSONObject().put("language", language).put("phonemes", ids.size)
                val stagesJson = JSONArray()
                var dOut: FloatArray? = null
                var tEnOut: FloatArray? = null
                var durOut: LongArray? = null
                var en: FloatArray? = null
                var asr: FloatArray? = null
                var taReal = 0

                // ── text_encoder (CPU) ──
                runCatching {
                    open(TEXT_ENCODER, qnn = false).use { s ->
                        val feeds =
                            mapOf(
                                "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(idsPadded), longArrayOf(1, WINDOW.toLong())),
                                "style" to tensor(env, styleRow, longArrayOf(1, 256)),
                                "speed" to tensor(env, floatArrayOf(1.0f), longArrayOf(1)),
                            )
                        val t0 = System.currentTimeMillis()
                        val outs = runStage(s, feeds)
                        val ms = System.currentTimeMillis() - t0
                        durOut = outs[0] as LongArray
                        dOut = outs[1] as FloatArray
                        tEnOut = outs[2] as FloatArray
                        stagesJson.put(
                            JSONObject()
                                .put("stage", TEXT_ENCODER)
                                .put("cpu_ms", ms)
                                .put(
                                    "outputs",
                                    JSONArray()
                                        .put(
                                            (outs[0] as LongArray).size,
                                        ).put((outs[1] as FloatArray).size)
                                        .put((outs[2] as FloatArray).size),
                                ),
                        )
                        log("text_encoder cpu ${ms}ms")
                    }
                }.onFailure {
                    log("text_encoder FAILED: $it")
                    allOk = false
                }

                // ── host alignment + EOS-replicate pad ──
                val (enReal, asrReal) = align(dOut!!, tEnOut!!, durOut!!)
                taReal = durOut!!.sum().toInt()
                en = padReplicate(enReal, 640, taReal)
                asr = padReplicate(asrReal, 512, taReal)
                log("alignment: T_a=$taReal -> pin $TA_PIN (host, gather+replicate)")
                stagesJson.put(
                    JSONObject()
                        .put("stage", "alignment")
                        .put("cpu_ms", 0)
                        .put("outputs", JSONArray().put(en.size).put(asr.size)),
                )

                // Free the encoder's raw outputs before the memory-heavy stages
                // (the pinned family materializes ~600 MB of intermediates).
                System.gc()

                // ── prosody (CPU vs QNN) ──
                var f0Cpu: FloatArray? = null
                var nCpu: FloatArray? = null
                runCatching {
                    open(PROSODY, qnn = false).use { s ->
                        val t0 = System.currentTimeMillis()
                        val outs =
                            runStage(
                                s,
                                mapOf(
                                    "/MatMul_output_0" to tensor(env, en!!, longArrayOf(1, 640, TA_PIN.toLong())),
                                    "/Slice_output_0" to tensor(env, styleS, longArrayOf(1, 128)),
                                ),
                            )
                        val ms = System.currentTimeMillis() - t0
                        f0Cpu = outs[0] as FloatArray
                        nCpu = outs[1] as FloatArray
                        stagesJson.put(JSONObject().put("stage", PROSODY).put("cpu_ms", ms))
                        log("prosody cpu ${ms}ms")
                    }
                    // Prefer the precompiled AOT context (ms reload) over a JIT
                    // compile (minutes-to-OOM). Time createSession so the AOT
                    // open cost is measured on the same session we run with.
                    val qnnT0 = System.currentTimeMillis()
                    val qnnCtx = openContext(PROSODY)
                    val qnnOpenMs = if (qnnCtx != null) (System.currentTimeMillis() - qnnT0) else -1
                    (qnnCtx ?: open(PROSODY, qnn = true)).use { s ->
                        val t0 = System.currentTimeMillis()
                        val outs =
                            runStage(
                                s,
                                mapOf(
                                    "/MatMul_output_0" to tensor(env, en!!, longArrayOf(1, 640, TA_PIN.toLong())),
                                    "/Slice_output_0" to tensor(env, styleS, longArrayOf(1, 128)),
                                ),
                            )
                        val ms = System.currentTimeMillis() - t0
                        val (peak, mean) = diff(f0Cpu!!, outs[0] as FloatArray)
                        stagesJson.put(
                            JSONObject()
                                .put("stage", PROSODY)
                                .put("qnn_ms", ms)
                                .put("qnn_open_ms", qnnOpenMs ?: -1)
                                .put("qnn_ctx", qnnCtx != null)
                                .put("max_abs_diff", peak.toDouble())
                                .put("mean_abs_diff", mean.toDouble()),
                        )
                        log(
                            "prosody qnn ${if (qnnCtx != null) "ctx" else "jit"} open=${qnnOpenMs ?: -1}ms run=${ms}ms diff peak=$peak mean=$mean",
                        )
                    }
                }.onFailure { log("prosody leg issue: $it") }

                // ── noise (CPU only; Random ops are HTP-rejected) ──
                var xs0: FloatArray? = null
                var xs1: FloatArray? = null
                runCatching {
                    open(NOISE, qnn = false).use { s ->
                        val t0 = System.currentTimeMillis()
                        val outs =
                            runStage(
                                s,
                                mapOf(
                                    "/If_output_0" to tensor(env, f0Cpu!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                    "/Slice_2_output_0" to tensor(env, styleTimbre, longArrayOf(1, 128)),
                                ),
                            )
                        val ms = System.currentTimeMillis() - t0
                        xs0 = outs[0] as FloatArray
                        xs1 = outs[1] as FloatArray
                        stagesJson.put(JSONObject().put("stage", NOISE).put("cpu_ms", ms))
                        log("noise cpu ${ms}ms")
                    }
                }.onFailure {
                    log("noise FAILED: $it")
                    allOk = false
                }

                // ── vocoder fp16 (CPU vs QNN) ──
                var xPreCpu: FloatArray? = null
                runCatching {
                    open(VOCODER, qnn = false).use { s ->
                        val t0 = System.currentTimeMillis()
                        val outs =
                            runStage(
                                s,
                                mapOf(
                                    "/MatMul_1_output_0" to tensor(env, asr!!, longArrayOf(1, 512, TA_PIN.toLong())),
                                    "/If_output_0" to tensor(env, f0Cpu!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                    "/If_1_output_0" to tensor(env, nCpu!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                    "/decoder/generator/noise_res.0/Add_8_output_0" to
                                        tensor(env, xs0!!, longArrayOf(1, 256, (TA_PIN * 20).toLong())),
                                    "/decoder/generator/noise_res.1/Add_8_output_0" to
                                        tensor(env, xs1!!, longArrayOf(1, 128, (TA_PIN * 120 + 1).toLong())),
                                    "/Slice_2_output_0" to tensor(env, styleTimbre, longArrayOf(1, 128)),
                                ),
                            )
                        val ms = System.currentTimeMillis() - t0
                        xPreCpu = outs[0] as FloatArray
                        stagesJson.put(JSONObject().put("stage", VOCODER).put("cpu_ms", ms))
                        log("vocoder cpu ${ms}ms")
                    }
                    // No AOT context for the vocoder (on-device compile OOMs on
                    // v69, decision #12); a JIT open would OOM the whole run.
                    val vocCtx = openContext(VOCODER)
                    if (vocCtx == null) {
                        log("vocoder qnn SKIP: no AOT context (compile OOMs on v69); staying CPU")
                    } else {
                        vocCtx.use { s ->
                            val t0 = System.currentTimeMillis()
                            val outs =
                                runStage(
                                    s,
                                    mapOf(
                                        "/MatMul_1_output_0" to tensor(env, asr!!, longArrayOf(1, 512, TA_PIN.toLong())),
                                        "/If_output_0" to tensor(env, f0Cpu!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                        "/If_1_output_0" to tensor(env, nCpu!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                        "/decoder/generator/noise_res.0/Add_8_output_0" to
                                            tensor(env, xs0!!, longArrayOf(1, 256, (TA_PIN * 20).toLong())),
                                        "/decoder/generator/noise_res.1/Add_8_output_0" to
                                            tensor(env, xs1!!, longArrayOf(1, 128, (TA_PIN * 120 + 1).toLong())),
                                        "/Slice_2_output_0" to tensor(env, styleTimbre, longArrayOf(1, 128)),
                                    ),
                                )
                            val ms = System.currentTimeMillis() - t0
                            val (peak, mean) = diff(xPreCpu!!, outs[0] as FloatArray)
                            stagesJson.put(
                                JSONObject()
                                    .put("stage", VOCODER)
                                    .put("qnn_ms", ms)
                                    .put("max_abs_diff", peak.toDouble())
                                    .put("mean_abs_diff", mean.toDouble()),
                            )
                            log("vocoder qnn ctx ${ms}ms diff peak=$peak mean=$mean")
                        }
                    }
                }.onFailure { log("vocoder leg issue: $it") }

                en = null
                asr = null
                System.gc()

                // ── tail (CPU) ──
                runCatching {
                    open(TAIL, qnn = false).use { s ->
                        val t0 = System.currentTimeMillis()
                        val outs =
                            runStage(
                                s,
                                mapOf(
                                    "/decoder/generator/LeakyRelu_2_output_0" to
                                        tensor(env, xPreCpu!!, longArrayOf(1, 128, (TA_PIN * 120 + 1).toLong())),
                                ),
                            )
                        val ms = System.currentTimeMillis() - t0
                        val wave = outs[0] as FloatArray
                        stagesJson.put(
                            JSONObject()
                                .put("stage", TAIL)
                                .put("cpu_ms", ms)
                                .put("samples", wave.size),
                        )
                        log("tail cpu ${ms}ms; waveform ${wave.size} samples (${wave.size / 24000.0}s)")
                    }
                }.onFailure {
                    log("tail FAILED: $it")
                    allOk = false
                }

                passageJson.put("stages", stagesJson)
                passagesJson.put(passageJson)
            }

            results.put("pin", TA_PIN)
            results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            results.put("passages", passagesJson)
            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            File(outDir, "kokoro_stages_max.json").writeText(results.toString(2))
            log("kokoro_stages_max.json written to $outDir")
            log("DONE (stage pipeline)")
            allOk
        } catch (e: Throwable) {
            log("stage pipeline unavailable: $e")
            false
        }

    /**
     * P5 verdict loop: runs the pinned chain repeatedly for [durationMs] on ONE
     * leg — prosody cpu ("cpu") or prosody HTP-context ("htp"; the rest of the
     * chain is CPU either way, the vocoder has no context per decision #12) —
     * accumulating per-window wall ms, RTF (window = 806400 samples = 33.6s)
     * and peak PSS (Debug.getPss). Writes kokoro_p5_<leg>.json; the shell takes
     * dumpsys batterystats deltas around each run for the power comparison.
     */
    fun runSustained(
        leg: String,
        durationMs: Long,
        log: (String) -> Unit,
    ): Boolean =
        try {
            require(leg == "cpu" || leg == "htp") { "leg must be cpu|htp" }
            val prosodyHtp = leg == "htp"
            val vocab = KokoroVocabulary.parse(File(stageDir, "kokoro_config.json").readText())
            val voices = KokoroVoiceBank.load(voicesFile)
            val corpus =
                corpusFile.readLines().mapNotNull { l ->
                    val p = l.split('\t')
                    if (p.size == 3) p else null
                }
            if (prosodyHtp && !File(File(context.filesDir, "models"), "contexts-${platform()}/prosody.context.bin").isFile) {
                throw IllegalStateException("no prosody AOT context staged for htp leg")
            }
            log("p5 $leg start: ${Build.MANUFACTURER} ${Build.MODEL}, duration ${durationMs / 1000}s")

            val tStart = System.currentTimeMillis()
            val windows = JSONArray()
            var peakPssKb = 0L
            var idx = 0
            var fail = 0
            while (System.currentTimeMillis() - tStart < durationMs) {
                for ((text, language, phonemes) in corpus) {
                    if (System.currentTimeMillis() - tStart >= durationMs) break
                    val voice = VOICES[language] ?: continue
                    val tWin0 = System.currentTimeMillis()
                    var teMs = 0L
                    var proMs = 0L
                    var proOpenMs = 0L
                    var noiseMs = 0L
                    var vocMs = 0L
                    var tailMs = 0L
                    try {
                        val styleRow =
                            voices.styleFor(voice, WINDOW)
                                ?: throw IllegalStateException("unknown voice $voice")
                        val styleS = styleRow.copyOfRange(128, 256)
                        val styleTimbre = styleRow.copyOfRange(0, 128)
                        val ids = phonemes.mapNotNull { vocab[it] }.take(510).toIntArray()
                        if (ids.isEmpty()) continue
                        val idsPadded = LongArray(WINDOW)
                        for (i in ids.indices) idsPadded[i + 1] = ids[i].toLong()

                        var dOut: FloatArray? = null
                        var tEnOut: FloatArray? = null
                        var durOut: LongArray? = null
                        var en: FloatArray? = null
                        var asr: FloatArray? = null
                        var f0: FloatArray? = null
                        var n: FloatArray? = null
                        var xs0: FloatArray? = null
                        var xs1: FloatArray? = null
                        var xPre: FloatArray? = null

                        open(TEXT_ENCODER, qnn = false).use { s ->
                            val t0 = System.currentTimeMillis()
                            val outs =
                                runStage(
                                    s,
                                    mapOf(
                                        "input_ids" to
                                            OnnxTensor.createTensor(env, LongBuffer.wrap(idsPadded), longArrayOf(1, WINDOW.toLong())),
                                        "style" to tensor(env, styleRow, longArrayOf(1, 256)),
                                        "speed" to tensor(env, floatArrayOf(1.0f), longArrayOf(1)),
                                    ),
                                )
                            teMs = System.currentTimeMillis() - t0
                            durOut = outs[0] as LongArray
                            dOut = outs[1] as FloatArray
                            tEnOut = outs[2] as FloatArray
                        }
                        val (enReal, asrReal) = align(dOut!!, tEnOut!!, durOut!!)
                        en = padReplicate(enReal, 640, durOut!!.sum().toInt())
                        asr = padReplicate(asrReal, 512, durOut!!.sum().toInt())
                        System.gc()

                        // prosody: the leg under test
                        if (prosodyHtp) {
                            val tOpen = System.currentTimeMillis()
                            val ctx = openContext(PROSODY)!!
                            proOpenMs = System.currentTimeMillis() - tOpen
                            ctx.use { s ->
                                val t0 = System.currentTimeMillis()
                                val outs =
                                    runStage(
                                        s,
                                        mapOf(
                                            "/MatMul_output_0" to tensor(env, en!!, longArrayOf(1, 640, TA_PIN.toLong())),
                                            "/Slice_output_0" to tensor(env, styleS, longArrayOf(1, 128)),
                                        ),
                                    )
                                proMs = System.currentTimeMillis() - t0
                                f0 = outs[0] as FloatArray
                                n = outs[1] as FloatArray
                            }
                        } else {
                            open(PROSODY, qnn = false).use { s ->
                                val t0 = System.currentTimeMillis()
                                val outs =
                                    runStage(
                                        s,
                                        mapOf(
                                            "/MatMul_output_0" to tensor(env, en!!, longArrayOf(1, 640, TA_PIN.toLong())),
                                            "/Slice_output_0" to tensor(env, styleS, longArrayOf(1, 128)),
                                        ),
                                    )
                                proMs = System.currentTimeMillis() - t0
                                f0 = outs[0] as FloatArray
                                n = outs[1] as FloatArray
                            }
                        }

                        open(NOISE, qnn = false).use { s ->
                            val t0 = System.currentTimeMillis()
                            val outs =
                                runStage(
                                    s,
                                    mapOf(
                                        "/If_output_0" to tensor(env, f0!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                        "/Slice_2_output_0" to tensor(env, styleTimbre, longArrayOf(1, 128)),
                                    ),
                                )
                            noiseMs = System.currentTimeMillis() - t0
                            xs0 = outs[0] as FloatArray
                            xs1 = outs[1] as FloatArray
                        }

                        open(VOCODER, qnn = false).use { s ->
                            val t0 = System.currentTimeMillis()
                            val outs =
                                runStage(
                                    s,
                                    mapOf(
                                        "/MatMul_1_output_0" to tensor(env, asr!!, longArrayOf(1, 512, TA_PIN.toLong())),
                                        "/If_output_0" to tensor(env, f0!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                        "/If_1_output_0" to tensor(env, n!!, longArrayOf(1, (TA_PIN * 2).toLong())),
                                        "/decoder/generator/noise_res.0/Add_8_output_0" to
                                            tensor(env, xs0!!, longArrayOf(1, 256, (TA_PIN * 20).toLong())),
                                        "/decoder/generator/noise_res.1/Add_8_output_0" to
                                            tensor(env, xs1!!, longArrayOf(1, 128, (TA_PIN * 120 + 1).toLong())),
                                        "/Slice_2_output_0" to tensor(env, styleTimbre, longArrayOf(1, 128)),
                                    ),
                                )
                            vocMs = System.currentTimeMillis() - t0
                            xPre = outs[0] as FloatArray
                        }
                        en = null
                        asr = null
                        System.gc()

                        open(TAIL, qnn = false).use { s ->
                            val t0 = System.currentTimeMillis()
                            val outs =
                                runStage(
                                    s,
                                    mapOf(
                                        "/decoder/generator/LeakyRelu_2_output_0" to
                                            tensor(env, xPre!!, longArrayOf(1, 128, (TA_PIN * 120 + 1).toLong())),
                                    ),
                                )
                            tailMs = System.currentTimeMillis() - t0
                        }
                        xPre = null
                        System.gc()

                        val totalMs = System.currentTimeMillis() - tWin0
                        val pssKb = Debug.getPss()
                        if (pssKb > peakPssKb) peakPssKb = pssKb
                        val rtf = totalMs / 33600.0
                        windows.put(
                            JSONObject()
                                .put("w", idx)
                                .put("lang", language)
                                .put("total_ms", totalMs)
                                .put("rtf", rtf)
                                .put("te_ms", teMs)
                                .put("pro_ms", proMs)
                                .put("pro_open_ms", proOpenMs)
                                .put("noise_ms", noiseMs)
                                .put("voc_ms", vocMs)
                                .put("tail_ms", tailMs)
                                .put("pss_kb", pssKb),
                        )
                        log(
                            "p5 $leg w$idx $language wall=${totalMs}ms rtf=${"%.2f".format(
                                rtf,
                            )} te=$teMs pro=$proMs(+${proOpenMs}o) noise=$noiseMs voc=$vocMs tail=$tailMs pss=${pssKb / 1024}MB",
                        )
                        idx++
                    } catch (e: Throwable) {
                        fail++
                        log("p5 $leg window FAILED: $e")
                    }
                }
            }
            val tEnd = System.currentTimeMillis()

            var rtfSum = 0.0
            var wallSum = 0L
            for (i in 0 until windows.length()) {
                rtfSum += windows.getJSONObject(i).getDouble("rtf")
                wallSum += windows.getJSONObject(i).getLong("total_ms")
            }
            val nWin = windows.length()
            val agg =
                JSONObject()
                    .put("leg", leg)
                    .put("windows", nWin)
                    .put("failures", fail)
                    .put("elapsed_ms", tEnd - tStart)
                    .put("mean_rtf", if (nWin > 0) rtfSum / nWin else -1.0)
                    .put("mean_wall_ms", if (nWin > 0) wallSum / nWin else -1)
                    .put("peak_pss_kb", peakPssKb)
            val res =
                JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("pin", TA_PIN)
                    .put("leg", leg)
                    .put("start_epoch_ms", tStart)
                    .put("end_epoch_ms", tEnd)
                    .put("agg", agg)
                    .put("windows", windows)
            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            File(outDir, "kokoro_p5_$leg.json").writeText(res.toString(2))
            log(
                "p5 $leg done: windows=$nWin failures=$fail elapsed=${tEnd - tStart}ms mean_rtf=${"%.3f".format(
                    agg.getDouble("mean_rtf"),
                )} peak_pss=${peakPssKb / 1024}MB -> kokoro_p5_$leg.json",
            )
            nWin > 0
        } catch (e: Throwable) {
            log("p5 $leg unavailable: $e")
            false
        }
}
