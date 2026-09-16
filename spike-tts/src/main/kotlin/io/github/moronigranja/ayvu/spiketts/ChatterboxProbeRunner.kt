package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * D5 Chatterbox device leg (roadmap D5): the q4 multilingual export
 * (`BricksDisplay/chatterbox-multilingual-ONNX-q4`, MIT) run end-to-end through
 * ORT-android, mirroring the onnx-community reference loop exactly:
 *
 *   speech_encoder      audio [1,N]              -> audio_features [1,33,1024],
 *                                                  audio_tokens [1,L],
 *                                                  speaker_embeddings [1,192],
 *                                                  speaker_features [1,F,80]
 *   embed_tokens        ids + position_ids + exaggeration -> inputs_embeds
 *   language_model      llama q4, 30 layers, KV-cache (past.* 60 tensors) ->
 *                       logits + present.*
 *   conditional_decoder speech_tokens + speaker emb/feat -> waveform (24 kHz)
 *
 * Text arrives PRE-TOKENIZED (host HF AutoTokenizer — the on-device BPE/G2P
 * plus the zh/ja/he/ko processing is a recorded D5 integration gap, the same
 * host-prepared-tokens arrangement as the Pocket leg, decisions #149/#171).
 * The REF passage is greedy (argmax + repetition penalty 1.2 — deterministic,
 * the parity gate vs `chat_ref_meta.json` token ids + audio); the longer
 * passages sample top-p 0.95 / temperature 0.8 seeded Random(42) — sampling is
 * exactly what rescues those prompts from the greedy loop (measured: greedy
 * p2 -> 1209 tokens, no EOS, noise; sampled -> 642, EOS, speech).
 *
 * Staging (build.md "D5 Chatterbox staging"): files/models/chatterbox/ onnx files,
 * files/chat_voice_24k.f32, files/d5_chat_inputs.json, files/chat_ref_meta.json,
 * files/chat_ref_audio.f32.
 */
class ChatterboxProbeRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "ChatterboxSpike"
        const val SR = 24000
        const val START_SPEECH_TOKEN = 6561L
        const val STOP_SPEECH_TOKEN = 6562L
        const val REP_PENALTY = 1.2f
        const val N_LAYERS = 30
        const val N_KV_HEADS = 16
        const val HEAD_DIM = 64
        const val COND_DIM = 1024
        const val VOCAB = 8194
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun run(
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val dir = File(context.filesDir, "models/chatterbox")
        val inputs = JSONObject(File(context.filesDir, "d5_chat_inputs.json").readText())
        val refMetaF = File(context.filesDir, "chat_ref_meta.json")
        val refAudioF = File(context.filesDir, "chat_ref_audio.f32")

        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("ort_native", OrtEnvironment.getEnvironment().version)
                .put("export", "BricksDisplay/chatterbox-multilingual-ONNX-q4")
        val legs = JSONArray()
        results.put("legs", legs)

        val threadLegs = inputs.optJSONArray("threads_legs") ?: JSONArray().put(4)
        val passages = inputs.getJSONArray("passages")
        val exaggeration = inputs.optDouble("exaggeration", 0.5).toFloat()

        for (t in 0 until threadLegs.length()) {
            val threads = threadLegs.getInt(t)
            log("=== threads=$threads ===")
            try {
                legs.put(
                    runLeg(dir, inputs, passages, threads, exaggeration, outDir, refMetaF, refAudioF, log),
                )
            } catch (e: Throwable) {
                log("leg threads=$threads FAILED: $e")
                Log.e(TAG, "leg failed", e)
                legs.put(JSONObject().put("threads", threads).put("error", e.toString()))
            }
            flush(outDir, results, log)
        }

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        results.put("vm_hwm_kb", readVmHwm()).put("total_pss_kb", mem.totalPss)
        flush(outDir, results, log)
        return results
    }

    private fun runLeg(
        dir: File,
        inputs: JSONObject,
        passages: JSONArray,
        threads: Int,
        exaggeration: Float,
        outDir: File,
        refMetaF: File,
        refAudioF: File,
        log: (String) -> Unit,
    ): JSONObject {
        val legJson = JSONObject().put("threads", threads)
        val openMs = JSONObject()

        fun open(
            name: String,
            file: String,
            opt: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
        ): OrtSession {
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(opt)
            opts.setIntraOpNumThreads(threads)
            opts.setInterOpNumThreads(1)
            val t0 = System.currentTimeMillis()
            val s = env.createSession(File(dir, file).absolutePath, opts)
            openMs.put(name, System.currentTimeMillis() - t0)
            log("  open $name: ${openMs.getLong(name)} ms")
            return s
        }

        val enc = open("speech_encoder", "speech_encoder.onnx")
        val embed = open("embed_tokens", "embed_tokens.onnx")
        val lm = open("language_model", "language_model.onnx")
        val dec = open("conditional_decoder", "conditional_decoder.onnx")
        legJson.put("open_ms", openMs)

        try {
            // Voice prompt conditioning, once per leg (voice-only).
            val voice = readF32(File(context.filesDir, inputs.getString("voice_pcm")))
            val tEnc = System.currentTimeMillis()
            val encOut =
                enc.run(
                    mapOf(
                        "audio_values" to
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(voice), longArrayOf(1, voice.size.toLong())),
                    ),
                )
            val audioFeatures = f32Of(encOut[0] as OnnxTensor)
            val audioTokens = longOf(encOut[1] as OnnxTensor)
            val speakerEmbeddings = f32Of(encOut[2] as OnnxTensor)
            val speakerFeatures = f32Of(encOut[3] as OnnxTensor)
            legJson
                .put("voice_encode_ms", System.currentTimeMillis() - tEnc)
                .put("prompt_audio_tokens", audioTokens.size)

            val runs = JSONArray()
            legJson.put("runs", runs)
            var refId: String? = null
            if (refMetaF.isFile) refId = JSONObject(refMetaF.readText()).optString("id")

            for (p in 0 until passages.length()) {
                val passage = passages.getJSONObject(p)
                val res =
                    try {
                        synthesize(
                            passage,
                            exaggeration,
                            audioFeatures,
                            audioTokens,
                            speakerEmbeddings,
                            speakerFeatures,
                            embed,
                            lm,
                            dec,
                            outDir,
                            log,
                        )
                    } catch (e: Throwable) {
                        log("passage ${passage.optString("id")} FAILED: ${e.stackTraceToString()}")
                        Log.e(TAG, "passage failed", e)
                        JSONObject().put("id", passage.optString("id")).put("error", e.toString())
                    }
                runs.put(res)
                if (res.optString("id") == refId && res.has("speech_tokens_ids")) {
                    val parity =
                        attachParity(res, JSONObject(refMetaF.readText()), refAudioF, File(outDir, "d5_chat_${res.getString("id")}.wav"))
                    res.put("parity", parity)
                    log(
                        "parity: tokens ${parity.optInt("tokens_match")} " +
                            "(${parity.optInt("ref_tokens")} vs ${parity.optInt("dev_tokens")}), " +
                            "audio_max_abs_diff ${parity.optDouble("audio_max_abs_diff")}",
                    )
                }
            }
        } finally {
            enc.close()
            embed.close()
            lm.close()
            dec.close()
        }

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        legJson.put("vm_hwm_kb", readVmHwm()).put("total_pss_kb", mem.totalPss)
        log("threads=$threads: VmHWM ${readVmHwm()} kB, PSS ${mem.totalPss} kB")
        return legJson
    }

    // ------------------------------------------------------------ synthesis

    private fun synthesize(
        passage: JSONObject,
        exaggeration: Float,
        audioFeatures: FloatArray,
        audioTokens: LongArray,
        speakerEmbeddings: FloatArray,
        speakerFeatures: FloatArray,
        embed: OrtSession,
        lm: OrtSession,
        dec: OrtSession,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val ids = passage.getJSONArray("input_ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val maxTokens = passage.optInt("max_new_tokens", 512)
        val temperature = if (passage.has("temperature")) passage.optDouble("temperature") else null
        val topP = if (passage.has("top_p")) passage.optDouble("top_p") else 0.95
        val t0 = System.currentTimeMillis()
        val stage = JSONObject().put("lm_ms", 0L).put("emb_ms", 0L).put("dec_ms", 0L)

        // position_ids = where(ids >= START, 0, arange-1) — the reference shape.
        val positionIds = LongArray(ids.size) { i -> if (ids[i] >= START_SPEECH_TOKEN) 0L else (i - 1).toLong() }

        fun embedTokens(
            tokenIds: LongArray,
            pos: LongArray,
        ): FloatArray {
            val m0 = System.currentTimeMillis()
            val t = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong()))
            val p = OnnxTensor.createTensor(env, LongBuffer.wrap(pos), longArrayOf(1, pos.size.toLong()))
            val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(exaggeration)), longArrayOf(1))
            val emb =
                try {
                    f32Of(embed.run(mapOf("input_ids" to t, "position_ids" to p, "exaggeration" to e))[0] as OnnxTensor)
                } catch (ex: Throwable) {
                    throw RuntimeException("embedTokens: $ex", ex)
                }
            t.close()
            p.close()
            e.close()
            stage.put("emb_ms", stage.getLong("emb_ms") + System.currentTimeMillis() - m0)
            return emb
        }

        val textEmb = embedTokens(ids, positionIds)
        // i == 0: voice conditioning ahead of the text embeddings.
        val promptLen = audioFeatures.size / COND_DIM
        val totalLen = promptLen + ids.size
        var inputsEmbeds = FloatArray(totalLen * COND_DIM)
        System.arraycopy(audioFeatures, 0, inputsEmbeds, 0, audioFeatures.size)
        System.arraycopy(textEmb, 0, inputsEmbeds, audioFeatures.size, textEmb.size)

        val kv = Array(2 * N_LAYERS) { FloatArray(0) }
        var kvFrames = 0

        fun kvGrow(neededFrames: Int) {
            if (neededFrames <= kvFrames) return
            for (i in kv.indices) {
                val needed = neededFrames * N_KV_HEADS * HEAD_DIM
                if (kv[i].size < needed) kv[i] = kv[i].copyOf(max(needed, kv[i].size * 2))
            }
            kvFrames = neededFrames
        }

        var mask = LongArray(totalLen) { 1L }
        val generated = ArrayList<Long>()
        generated.add(START_SPEECH_TOKEN)
        val rng = Random(42)
        var stopped = false

        for (step in 0 until maxTokens) {
            val lm0 = System.currentTimeMillis()
            val feeds = HashMap<String, OnnxTensor>()
            feeds["inputs_embeds"] =
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(inputsEmbeds),
                    longArrayOf(1, (inputsEmbeds.size / COND_DIM).toLong(), COND_DIM.toLong()),
                )
            feeds["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, mask.size.toLong()))
            // Device GQA kernel (the export ships GroupQueryAttention): the past
            // buffer must be PRE-GROWN to past+current (validated seqlens_k <=
            // buffer; host kernels do not check). The grow row is zero-padded
            // (copyOf); the model writes the current token's KV into it. The
            // buffer length is captured BEFORE kvGrow mutates kvFrames.
            val bufferFrames = kvFrames + 1
            kvGrow(bufferFrames)
            for (i in kv.indices) {
                feeds[pastName(i)] =
                    OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(kv[i], 0, bufferFrames * N_KV_HEADS * HEAD_DIM),
                        longArrayOf(1, N_KV_HEADS.toLong(), bufferFrames.toLong(), HEAD_DIM.toLong()),
                    )
            }
            val out =
                try {
                    lm.run(feeds)
                } catch (ex: Throwable) {
                    throw RuntimeException("lm step=$step kvFrames=$kvFrames inputsEmb=${inputsEmbeds.size / COND_DIM}: $ex", ex)
                }
            val logits = f32Of(out[0] as OnnxTensor)
            stage.put("lm_ms", stage.getLong("lm_ms") + System.currentTimeMillis() - lm0)

            // The model processes the FULL current sequence, so logits span
            // [1, S, 8194]. The reference reads logits[:, -1, :] — only the
            // LAST token row is the next-step distribution. Under the device's
            // padded-buffer convention, present.* come back with
            // mask.size rows (past + current; the pad row carries the current
            // token's KV), so the KV frame count FOLLOWS the mask.
            val newFrames = mask.size
            kvGrow(newFrames)
            for (i in kv.indices) {
                System.arraycopy(f32Of(out[1 + i] as OnnxTensor), 0, kv[i], 0, newFrames * N_KV_HEADS * HEAD_DIM)
            }

            val lastLogits = logits.copyOfRange(logits.size - VOCAB, logits.size)
            repetitionPenaltyInPlace(lastLogits, generated)
            val next =
                if (temperature != null) {
                    topPSample(lastLogits, temperature, topP, rng)
                } else {
                    argmax(lastLogits)
                }
            generated.add(next.toLong())
            if (next == STOP_SPEECH_TOKEN.toInt()) {
                stopped = true
                break
            }

            inputsEmbeds = embedTokens(longArrayOf(next.toLong()), longArrayOf((step + 1).toLong()))
            mask = mask.copyOf(mask.size + 1).also { it[mask.size] = 1L }
        }

        // speech_tokens = prompt_token ++ generated[1..last-1] (drop START, STOP)
        val speechTokens = LongArray(audioTokens.size + generated.size - 2)
        System.arraycopy(audioTokens, 0, speechTokens, 0, audioTokens.size)
        for (i in 1 until generated.size - 1) speechTokens[audioTokens.size + i - 1] = generated[i]

        val d0 = System.currentTimeMillis()
        val wavOut =
            dec.run(
                mapOf(
                    "speech_tokens" to
                        OnnxTensor.createTensor(env, LongBuffer.wrap(speechTokens), longArrayOf(1, speechTokens.size.toLong())),
                    "speaker_embeddings" to
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(speakerEmbeddings), longArrayOf(1, speakerEmbeddings.size.toLong())),
                    "speaker_features" to
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(speakerFeatures),
                            longArrayOf(1, speakerFeatures.size / 80.toLong(), 80.toLong()),
                        ),
                ),
            )
        val pcm = f32Of(wavOut[0] as OnnxTensor)
        stage.put("dec_ms", stage.getLong("dec_ms") + System.currentTimeMillis() - d0)

        val wall = System.currentTimeMillis() - t0
        val audioS = pcm.size / SR.toDouble()
        val rtf = wall / 1000.0 / audioS
        var rms = 0.0
        var finite = true
        for (v in pcm) {
            rms += v.toDouble() * v
            if (!v.isFinite()) finite = false
        }
        rms = sqrt(rms / maxOf(1, pcm.size))
        Wav.write(File(outDir, "d5_chat_${passage.getString("id")}.wav"), pcm, SR)

        val res =
            JSONObject()
                .put("id", passage.optString("id"))
                .put("tokens", speechTokens.size)
                .put("audio_seconds", audioS)
                .put("wall_ms", wall)
                .put("rtf", rtf)
                .put("stages_ms", stage)
                .put("rms", rms)
                .put("finite", finite)
                .put("stop_reached", stopped)
        if (!passage.has("temperature")) {
            res.put("speech_tokens_ids", JSONArray().also { for (t in speechTokens) it.put(t) })
        }
        log(
            "${passage.getString("id")}: ${speechTokens.size} tokens, ${"%.2f".format(audioS)}s audio " +
                "in $wall ms (RTF ${"%.3f".format(rtf)}), rms=${"%.4f".format(rms)}, stop=$stopped)",
        )
        return res
    }

    private fun topPSample(
        logits: FloatArray,
        temperature: Double,
        topP: Double,
        rng: Random,
    ): Int {
        val n = logits.size
        val temp = temperature.toFloat()
        val scored = FloatArray(n) { logits[it] / temp }
        val order = Array(n) { it }
        order.sortWith(Comparator { a, b -> java.lang.Float.compare(scored[b], scored[a]) })
        val mx = scored[order[0]]
        val probs = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val p = Math.exp((scored[order[i]] - mx).toDouble())
            probs[i] = p
            sum += p
        }
        var keep = 0
        var cum = 0.0
        for (i in 0 until n) {
            cum += probs[i] / sum
            if (i > 0 && (cum - probs[i] / sum) > topP) break
            keep = i + 1
        }
        var keptSum = 0.0
        for (i in 0 until keep) keptSum += probs[i] / sum
        val u = rng.nextDouble() * keptSum
        var acc = 0.0
        for (i in 0 until keep) {
            acc += probs[i] / sum
            if (u <= acc) return order[i]
        }
        return order[keep - 1]
    }

    private fun repetitionPenaltyInPlace(
        logits: FloatArray,
        generated: List<Long>,
    ) {
        for (id in generated) {
            val i = id.toInt()
            if (i in logits.indices) {
                logits[i] = if (logits[i] < 0f) logits[i] * REP_PENALTY else logits[i] / REP_PENALTY
            }
        }
    }

    private fun argmax(logits: FloatArray): Int {
        var bi = 0
        for (i in 1 until logits.size) if (logits[i] > logits[bi]) bi = i
        return bi
    }

    private fun pastName(i: Int) = "past_key_values.${i / 2}.${if (i % 2 == 0) "key" else "value"}"

    private fun attachParity(
        res: JSONObject,
        meta: JSONObject,
        refAudioF: File,
        wavF: File,
    ): JSONObject {
        val refIds = meta.optJSONArray("speech_tokens_ids")
        val devIds = res.optJSONArray("speech_tokens_ids")
        val match =
            refIds != null && devIds != null && refIds.length() == devIds.length() &&
                (0 until refIds.length()).all { refIds.getLong(it) == devIds.getLong(it) }
        val refA = readF32(refAudioF)
        val dev = Wav.read(wavF)
        val audioMax = if (dev.size == refA.size) maxAbsDiff(dev, refA) else -1.0
        return JSONObject()
            .put("tokens_match", match)
            .put("ref_tokens", refIds?.length() ?: -1)
            .put("dev_tokens", devIds?.length() ?: -1)
            .put("audio_samples_match", dev.size == refA.size)
            .put("audio_max_abs_diff", audioMax)
    }

    private fun f32Of(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun longOf(t: OnnxTensor): LongArray {
        val b = t.longBuffer
        return LongArray(b.remaining()).also { b.get(it) }
    }

    private fun readF32(file: File): FloatArray {
        val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val fb = bb.asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
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
            File(outDir, "d5_chatterbox_results.json").writeText(results.toString(1))
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
}
