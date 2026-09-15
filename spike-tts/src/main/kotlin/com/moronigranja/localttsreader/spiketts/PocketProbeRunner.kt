package com.moronigranja.localttsreader.spiketts
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * D5 Pocket TTS spike on-device (roadmap D5, decisions #149): the Kyutai
 * Pocket TTS ONNX export (`KevinAHM/pocket-tts-onnx` @ 58a6d00c, CC-BY-4.0,
 * sha256-pinned per file) run end-to-end through ORT-android, mirroring the
 * PocketTTS.cpp reference loop exactly:
 *
 *   mimi_encoder (fp32)  audio [1,1,N]                  -> voice emb [1,F,1024]
 *   text_conditioner     token_ids [1,T]                -> text emb [1,T,1024]
 *   flow_lm_main (int8)  stateful, 18 state tensors     -> conditioning [1,1024] + eos_logit
 *   flow_lm_flow (int8)  c [1,1024] s/t [1,1] x [1,32]  -> flow_dir [1,32]
 *   mimi_decoder (int8)  latent [1,1,32], states        -> audio [1,1,1920]
 *
 * Conditioning = two main calls through the text slot (voice emb, then text
 * emb) with an empty [1,0,32] latent prefix; the voice-conditioned KV state
 * is snapshotted after the first sentence and RESTORED for the rest
 * (PocketTTS.cpp tier-1 cache — the production shape). The AR loop runs one
 * main call per frame (the first `curr` is NaN — the graph substitutes BOS),
 * stops eos_extra frames after the EOS logit crosses -4.0, and decodes per
 * frame — the canonical streaming unit, since chunk size changes decoder
 * output (host-measured max abs diff 0.13 for 15-frame chunks; recorded
 * finding).
 *
 * The temperature-0 "ref" passage is deterministic (zero noise) and is
 * compared against the host reference (`pocket_ref_meta.json` +
 * pocket_ref_latents.f32 + pocket_ref_audio.f32) — the #86 fp16-stub parity
 * gate. Text arrives pre-tokenized (host SentencePiece); the on-device
 * tokenizer/G2P is a recorded D5 integration gap, not a spike leg.
 *
 * Staging (build.md "D5 Pocket TTS staging"): files/models/pocket/ graphs,
 * files/voice_24k.f32, files/d5_inputs.json, files/pocket_ref_meta.json,
 * files/pocket_ref_latents.f32, files/pocket_ref_audio.f32.
 */
class PocketProbeRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "PocketSpike"
        const val SR = 24000
        const val SAMPLES_PER_FRAME = 1920
        const val EOS_THRESHOLD = -4.0f
        const val XFADE_SAMPLES = 240
        const val KV_CAPACITY = 1000L
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun run(
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val dir = File(context.filesDir, "models/pocket/english_2026-04")
        val inputs = JSONObject(File(context.filesDir, "d5_inputs.json").readText())
        val refMeta = File(context.filesDir, "pocket_ref_meta.json")
        val refLatents = File(context.filesDir, "pocket_ref_latents.f32")
        val refAudio = File(context.filesDir, "pocket_ref_audio.f32")

        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("ort_native", OrtEnvironment.getEnvironment().version)
                .put(
                    "export",
                    "KevinAHM/pocket-tts-onnx@58a6d00cf13d239b6748cb0769f35c580a8f606c english_2026-04",
                )
        val legs = JSONArray()
        results.put("legs", legs)

        val threadLegs = inputs.optJSONArray("threads_legs") ?: JSONArray().put(4)
        val passages = inputs.getJSONArray("passages")

        for (t in 0 until threadLegs.length()) {
            val threads = threadLegs.getInt(t)
            log("=== threads=$threads ===")
            try {
                legs.put(runLeg(dir, inputs, passages, threads, outDir, refMeta, refLatents, refAudio, log))
            } catch (e: Throwable) {
                log("leg threads=$threads FAILED: $e")
                Log.e(TAG, "leg failed", e)
                legs.put(JSONObject().put("threads", threads).put("error", e.toString()))
            }
            flush(outDir, results, log)
        }

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        results.put("vm_hwm_kb", readVmHwm())
        results.put("total_pss_kb", mem.totalPss)
        flush(outDir, results, log)
        log("d5_pocket_results.json written to $outDir")
        return results
    }

    // ------------------------------------------------------------------ leg

    private fun runLeg(
        dir: File,
        inputs: JSONObject,
        passages: JSONArray,
        threads: Int,
        outDir: File,
        refMeta: File,
        refLatents: File,
        refAudio: File,
        log: (String) -> Unit,
    ): JSONObject {
        val thermal = ThermalProbe(context, TAG)
        val power = PowerProbe(context, TAG)
        val legJson = JSONObject().put("threads", threads)

        val openMs = JSONObject()

        fun open(
            name: String,
            file: String,
        ): OrtSession {
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            val t0 = System.currentTimeMillis()
            val s = env.createSession(File(dir, file).absolutePath, opts)
            openMs.put(name, System.currentTimeMillis() - t0)
            log("  open $name: ${openMs.getLong(name)} ms")
            return s
        }
        val precision = inputs.optString("precision", "int8")
        val sfx = if (precision == "int8") "_int8" else ""
        val enc = open("mimi_encoder", "mimi_encoder.onnx")
        val txt = open("text_conditioner", "text_conditioner.onnx")
        val mainS = open("flow_lm_main$sfx", "flow_lm_main$sfx.onnx")
        val flowS = open("flow_lm_flow$sfx", "flow_lm_flow$sfx.onnx")
        val decS = open("mimi_decoder$sfx", "mimi_decoder$sfx.onnx")
        legJson.put("open_ms", openMs).put("precision", precision)

        val main = StatefulGraph(env, mainS)
        val dec = StatefulGraph(env, decS)
        val rng = Random(42)
        val refId = if (refMeta.isFile) JSONObject(refMeta.readText()).optString("id") else null

        try {
            // ---- voice encoding leg (fp32 encoder on the staged 24 kHz PCM)
            val pcm = readF32(File(context.filesDir, inputs.getString("voice_pcm")))
            thermal.start()
            power.start()
            val tEnc = System.currentTimeMillis()
            enc
                .run(
                    mapOf("audio" to OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, 1, pcm.size.toLong()))),
                ).use { out ->
                    val voice = f32Of(out[0] as OnnxTensor)
                    val encodeMs = System.currentTimeMillis() - tEnc
                    legJson.put("voice_encode_ms", encodeMs).put("voice_frames", voice.size / 1024)
                    log("voice emb: ${voice.size / 1024} frames in $encodeMs ms")

                    val runs = JSONArray()
                    legJson.put("runs", runs)
                    for (p in 0 until passages.length()) {
                        val passage = passages.getJSONObject(p)
                        val res =
                            try {
                                synthesizePassage(main, dec, flowS, txt, voice, passage, rng, outDir, log)
                            } catch (e: Throwable) {
                                log("passage ${passage.optString("id")} FAILED: $e")
                                Log.e(TAG, "passage failed", e)
                                JSONObject().put("id", passage.optString("id")).put("error", e.toString())
                            }
                        runs.put(res)
                        if (passage.optString("id") == refId && res.has("latents_file")) {
                            val parity =
                                attachParity(
                                    res,
                                    JSONObject(refMeta.readText()),
                                    refLatents,
                                    refAudio,
                                    File(res.getString("latents_file")),
                                    File(outDir, "d5_${res.getString("id")}.wav"),
                                )
                            res.put("parity", parity)
                            log(
                                "parity: eos ${res.optInt("eos_frame")} vs ${parity.optInt("ref_eos")}, " +
                                    "latents maxdiff ${parity.optDouble("latents_max_abs_diff")}, " +
                                    "audio maxdiff ${parity.optDouble("audio_max_abs_diff")}",
                            )
                        }
                    }
                }

            thermal.stop()
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            legJson
                .put("vm_hwm_kb", readVmHwm())
                .put("total_pss_kb", mem.totalPss)
                .put("thermal_status_max", thermal.maxStatus)
                .put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
                .put("power_mean_mw", power.meanPowerMw)
                .put("power_unplugged_fraction", power.unpluggedFraction)
                .put("max_battery_temp_c", power.maxBatteryTempC)
            log(
                "threads=$threads: VmHWM ${readVmHwm()} kB, PSS ${mem.totalPss} kB, " +
                    "thermal<${thermal.maxStatus}>/${thermal.maxHeadroom}, pwr ${power.meanPowerMw} mW " +
                    "(unplugged ${(power.unpluggedFraction * 100).toInt()}%)",
            )
            return legJson
        } finally {
            enc.close()
            txt.close()
            mainS.close()
            flowS.close()
            decS.close()
        }
    }

    // ------------------------------------------------------------ synthesis

    private fun synthesizePassage(
        main: StatefulGraph,
        dec: StatefulGraph,
        flowS: OrtSession,
        txtS: OrtSession,
        voice: FloatArray,
        passage: JSONObject,
        rng: Random,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val temp = passage.optDouble("temperature", 0.7)
        val maxFrames = passage.optInt("max_frames", 500)
        val steps = passage.optInt("lsd_steps", 1)
        val sentences = passage.getJSONArray("sentences")
        val sigma = sqrt(temp).toFloat()
        val stage =
            JSONObject()
                .put("cond_ms", 0L)
                .put("main_ms", 0L)
                .put("flow_ms", 0L)
                .put("dec_ms", 0L)
        val t0 = System.currentTimeMillis()
        var allPcm = FloatArray(0)
        var totalFrames = 0
        var eosFrame = -1

        for (si in 0 until sentences.length()) {
            val sent = sentences.getJSONObject(si)
            val tokens = sent.getJSONArray("tokens").let { a -> LongArray(a.length()) { a.getLong(it) } }
            val eosExtra = sent.optInt("eos_extra", 3)

            // text conditioning
            val tc0 = System.currentTimeMillis()
            val tembT = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()))
            var tembShape: LongArray = longArrayOf()
            var temb = FloatArray(0)
            txtS.run(mapOf("token_ids" to tembT)).use { out ->
                val t = out[0] as OnnxTensor
                tembShape = t.info.shape
                temb = f32Of(t)
            }
            tembT.close()
            stage.put("cond_ms", stage.getLong("cond_ms") + System.currentTimeMillis() - tc0)

            // voice conditioning (first sentence), then KV restore for the rest
            val cond0 = System.currentTimeMillis()
            main.reinit()
            if (si == 0) {
                main.run(mapOf("sequence" to main.emptySeqT, "text_embeddings" to main.voiceT(voice)), "conditioning")
                main.snapshotState()
            } else {
                main.restoreState()
            }
            main.run(mapOf("sequence" to main.emptySeqT, "text_embeddings" to main.f32T(temb, tembShape)), "conditioning")
            stage.put("cond_ms", stage.getLong("cond_ms") + System.currentTimeMillis() - cond0)

            // AR loop
            val ctxBudget = (KV_CAPACITY - voice.size / 1024 - temb.size / 1024).toInt()
            val cap = min(maxFrames, ctxBudget)
            var curr = FloatArray(32) { Float.NaN }
            val latents = ArrayList<FloatArray>()
            var localEos = -1
            for (step in 0 until cap) {
                val m0 = System.currentTimeMillis()
                val frame = main.runFrame(curr)
                val m1 = System.currentTimeMillis()
                stage.put("main_ms", stage.getLong("main_ms") + m1 - m0)
                if (localEos < 0 && frame.eos > EOS_THRESHOLD) localEos = latents.size
                if (localEos >= 0 && latents.size >= localEos + eosExtra) break
                val x = if (temp <= 0.0) FloatArray(32) else FloatArray(32) { rng.nextGaussian().toFloat() * sigma }
                val f0 = System.currentTimeMillis()
                var s = 0f
                val dt = 1f / steps
                for (j in 0 until steps) {
                    val s0 = s
                    s += dt
                    flowS
                        .run(
                            mapOf(
                                "c" to OnnxTensor.createTensor(env, FloatBuffer.wrap(frame.cond), longArrayOf(1, 1024)),
                                "s" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(s0)), longArrayOf(1, 1)),
                                "t" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(s)), longArrayOf(1, 1)),
                                "x" to OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 32)),
                            ),
                        ).use { vo ->
                            val v = vo[0] as OnnxTensor
                            val vb = v.floatBuffer
                            for (i in 0 until 32) x[i] += vb.get(i) * dt
                        }
                }
                val f1 = System.currentTimeMillis()
                stage.put("flow_ms", stage.getLong("flow_ms") + f1 - f0)
                latents.add(x)
                curr = x
            }
            if (localEos >= 0 && eosFrame < 0) eosFrame = totalFrames + localEos
            totalFrames += latents.size

            // per-frame decode (canonical streaming unit; chunk size changes output)
            val pcm = FloatArray(latents.size * SAMPLES_PER_FRAME)
            val d0 = System.currentTimeMillis()
            dec.reinit()
            for (f in latents.indices) {
                val a = dec.run(mapOf("latent" to dec.f32T(latents[f], longArrayOf(1, 1, 32))), "audio_frame")
                System.arraycopy(a, 0, pcm, f * SAMPLES_PER_FRAME, SAMPLES_PER_FRAME)
            }
            val d1 = System.currentTimeMillis()
            stage.put("dec_ms", stage.getLong("dec_ms") + d1 - d0)

            allPcm = if (allPcm.isEmpty()) pcm else crossfade(allPcm, pcm)

            // the ref sentence's full latent sequence for the host parity compare
            if (sent.optBoolean("ref", false)) {
                val flat = FloatArray(latents.size * 32)
                for ((k, l) in latents.withIndex()) l.copyInto(flat, k * 32)
                writeF32(File(outDir, "d5_ref_latents.f32"), flat)
            }
        }

        val wall = System.currentTimeMillis() - t0
        val audioS = allPcm.size / SR.toDouble()
        val rtf = wall / 1000.0 / audioS
        var rms = 0.0
        var peak = 0.0
        for (v in allPcm) {
            rms += v.toDouble() * v
            val a = abs(v).toDouble()
            if (a > peak) peak = a
        }
        rms = sqrt(rms / maxOf(1, allPcm.size))
        log(
            "${passage.getString("id")}: $totalFrames frames, ${"%.2f".format(audioS)}s audio " +
                "in $wall ms (RTF ${"%.3f".format(rtf)}), eos=$eosFrame, rms=${"%.4f".format(rms)}",
        )

        Wav.write(File(outDir, "d5_${passage.getString("id")}.wav"), allPcm, SR)

        val result =
            JSONObject()
                .put("id", passage.getString("id"))
                .put("temperature", temp)
                .put("frames", totalFrames)
                .put("eos_frame", eosFrame)
                .put("audio_seconds", audioS)
                .put("wall_ms", wall)
                .put("rtf", rtf)
                .put("stages_ms", stage)
                .put("rms", rms)
                .put("peak", peak)
                .put("finite", allPcm.all { it.isFinite() })
        if (sentences.getJSONObject(0).optBoolean("ref", false)) {
            result.put("latents_file", File(outDir, "d5_ref_latents.f32").absolutePath)
        }
        return result
    }

    // ------------------------------------------------------------ parity

    private fun attachParity(
        res: JSONObject,
        meta: JSONObject,
        refLatents: File,
        refAudio: File,
        devLatents: File,
        wav: File,
    ): JSONObject {
        val refLat = readF32(refLatents)
        val devLat = if (devLatents.isFile) readF32(devLatents) else FloatArray(0)
        val latMax = maxAbsDiff(devLat, refLat)
        val refA = readF32(refAudio)
        val devA = Wav.read(wav)
        val audioMax = if (devA.size == refA.size) maxAbsDiff(devA, refA) else -1.0
        return JSONObject()
            .put("eos_frame_match", res.optInt("eos_frame", -1) == meta.optInt("eos_frame", -2))
            .put("frames_match", res.optInt("frames", -1) == meta.optInt("frames", -2))
            .put("ref_eos", meta.optInt("eos_frame"))
            .put("ref_frames", meta.optInt("frames"))
            .put("latents_max_abs_diff", latMax)
            .put("audio_max_abs_diff", audioMax)
    }

    // ------------------------------------------------------------ helpers

    private fun crossfade(
        prev: FloatArray,
        next: FloatArray,
    ): FloatArray {
        val x = min(XFADE_SAMPLES, min(prev.size, next.size))
        val out = FloatArray(prev.size + next.size - x)
        System.arraycopy(prev, 0, out, 0, prev.size)
        for (j in 0 until x) {
            val t = j.toFloat() / x
            out[prev.size - x + j] = prev[prev.size - x + j] * (1 - t) + next[j] * t
        }
        System.arraycopy(next, x, out, prev.size, next.size - x)
        return out
    }

    private fun f32Of(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun readF32(file: File): FloatArray {
        val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val fb = bb.asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun writeF32(
        file: File,
        data: FloatArray,
    ) {
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        val fb = bb.asFloatBuffer()
        fb.put(data)
        file.writeBytes(bb.array())
    }

    private fun maxAbsDiff(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs(a[i].toDouble() - b[i].toDouble()))
        return m
    }

    private fun flush(
        outDir: File,
        results: JSONObject,
        log: (String) -> Unit,
    ) {
        try {
            File(outDir, "d5_pocket_results.json").writeText(results.toString(1))
        } catch (e: Throwable) {
            log("flush failed: $e")
        }
    }

    private fun readVmHwm(): Long =
        try {
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("VmHWM") }
                .split(Regex("\\s+"))[1]
                .toLong()
        } catch (_: Throwable) {
            -1
        }

    /**
     * Stateful graph runner: every `state_*` input is a pre-allocated direct
     * buffer fed back from the matching `out_state_*` output after each run
     * (PocketTTS.cpp's StateBufferIO, minus the double-buffer — the copy is
     * unavoidable in the Java API and is part of the measured cost).
     */
    private class StateBuf(
        val name: String,
        val type: OnnxJavaType,
        val shape: LongArray,
        env: OrtEnvironment,
    ) {
        val count: Long = shape.fold(1L) { a, d -> a * d }
        val bytes: Int =
            when (type) {
                OnnxJavaType.FLOAT -> (count * 4).toInt()
                OnnxJavaType.INT64 -> (count * 8).toInt()
                else -> count.toInt()
            }
        val data: ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val tensor: OnnxTensor =
            when (type) {
                OnnxJavaType.FLOAT -> OnnxTensor.createTensor(env, data.asFloatBuffer(), shape)
                OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, data.asLongBuffer(), shape)
                OnnxJavaType.BOOL -> OnnxTensor.createTensor(env, data, shape, OnnxJavaType.BOOL)
                else -> error("unsupported state type $type")
            }

        fun zero() {
            data.clear()
            while (data.hasRemaining()) data.put(0.toByte())
            data.rewind()
        }

        fun copyFrom(t: OnnxTensor) {
            data.rewind()
            when (type) {
                OnnxJavaType.FLOAT -> data.asFloatBuffer().put(t.floatBuffer)
                OnnxJavaType.INT64 -> data.asLongBuffer().put(t.longBuffer)
                OnnxJavaType.BOOL -> data.put(t.byteBuffer)
                else -> error("unsupported state type $type")
            }
            data.rewind()
        }
    }

    private class StatefulGraph(
        private val env: OrtEnvironment,
        val session: OrtSession,
    ) {
        val states: List<StateBuf>

        init {
            val st = ArrayList<StateBuf>()
            for ((name, nodeInfo) in session.inputInfo) {
                val info = nodeInfo.info as TensorInfo
                if (name.startsWith("state_")) {
                    st.add(StateBuf(name, info.type, info.shape, env))
                }
            }
            states = st
            reinit()
        }

        val emptySeqT: OnnxTensor = emptyF32(longArrayOf(1, 0, 32))
        val emptyTextT: OnnxTensor = emptyF32(longArrayOf(1, 0, 1024))
        private val snap: MutableMap<String, ByteArray> = HashMap()

        private fun emptyF32(shape: LongArray): OnnxTensor =
            OnnxTensor.createTensor(
                env,
                ByteBuffer
                    .allocateDirect(shape.fold(1L) { a, b -> a * b }.toInt() * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer(),
                shape,
            )

        fun reinit() {
            for (s in states) s.zero()
        }

        fun f32T(
            data: FloatArray,
            shape: LongArray,
        ): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

        fun voiceT(v: FloatArray): OnnxTensor =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(v), longArrayOf(1, (v.size / 1024).toLong(), 1024))

        fun snapshotState() {
            for (s in states) {
                s.data.rewind()
                snap[s.name] = ByteArray(s.bytes).also { s.data.get(it) }
                s.data.rewind()
            }
        }

        fun restoreState() {
            for (s in states) {
                snap[s.name]?.let {
                    s.data.rewind()
                    s.data.put(it)
                    s.data.rewind()
                }
            }
        }

        class Frame(
            val cond: FloatArray,
            val eos: Float,
        )

        /** One AR frame: sequence [1,1,32] + empty text; returns conditioning + eos logit. */
        fun runFrame(curr: FloatArray): Frame {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(curr), longArrayOf(1, 1, 32)).use { t ->
                val inputs = HashMap<String, OnnxTensor>()
                inputs["sequence"] = t
                inputs["text_embeddings"] = emptyTextT
                for (s in states) inputs[s.name] = s.tensor
                session.run(inputs).use { out ->
                    for (s in states) {
                        val o = out.get("out_" + s.name).orElseThrow() as OnnxTensor
                        s.copyFrom(o)
                    }
                    val cond =
                        (out.get("conditioning").orElseThrow() as OnnxTensor).let { x ->
                            val b = x.floatBuffer
                            FloatArray(b.remaining()).also { b.get(it) }
                        }
                    val eos = (out.get("eos_logit").orElseThrow() as OnnxTensor).floatBuffer.get(0)
                    return Frame(cond, eos)
                }
            }
        }

        /** One graph run with state feedback; returns the named float output. */
        fun run(
            feeds: Map<String, OnnxTensor>,
            outName: String,
        ): FloatArray {
            val inputs = HashMap<String, OnnxTensor>(feeds)
            for (s in states) inputs[s.name] = s.tensor
            session.run(inputs).use { out ->
                for (s in states) {
                    val o = out.get("out_" + s.name).orElseThrow() as OnnxTensor
                    s.copyFrom(o)
                }
                val t = out.get(outName).orElseThrow() as OnnxTensor
                val b = t.floatBuffer
                return FloatArray(b.remaining()).also { b.get(it) }
            }
        }
    }
}
