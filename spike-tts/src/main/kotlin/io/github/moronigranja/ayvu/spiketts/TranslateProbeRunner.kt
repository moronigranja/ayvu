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
 * Phase J NMT probe on-device (roadmap Phase J): A/B of the per-pair OPUS-MT
 * baseline (decisions #101 direction) against the single many-to-many
 * M2M-100-418M across FOUR source->target pairs (it->es, en->pt-br, en->it,
 * es->en), fp32 AND dynamic-int8, through ORT-android (pinned 1.29.0). The
 * question is cost, not adoption: is ONE many-to-many model worth its extra
 * memory/speed/disk over the per-pair direction, once quality is held equal?
 *
 * All model-dependent inputs are host-prepared into `files/translate_inputs.json`
 * (the D3/D4 host-prepared-inputs pattern, decisions #93): tokenized
 * input_ids/attention_mask, per-family decoder_start (Marian pad; tc-big via a
 * ">>pob<< " source prefix folded in host-side; M2M-100 target lang id), the
 * per-model EOS and the FLORES reference for the chr-F leg. The device runs
 * inference only and needs no tokenizer: produced token ids are recorded and
 * decoded host-side.
 *
 * Greedy-decode contract (mirrors tools/export_nmt_onnx.py's parity loop):
 * encoder once -> decoder step 0 (no past) which emits
 * `present.{N}.{decoder,encoder}.*` -> decoder_with_past thereafter. The
 * with-past graph requires ALL `past_key_values.*` inputs: the cross-attention
 * `*.encoder.*` entries are CONSTANT after step 0 (the with-past graph only
 * re-emits `present.N.decoder.*`) and are re-fed unchanged each step. Graph IO
 * names are matched against session.inputNames so Marian and M2M-100 exports
 * run unchanged. Loop ends at EOS or max_len = max(512, 2*input_len + 50).
 *
 * Tensor lifetimes: the encoder `last_hidden_state` and the mask stay open as
 * decoder feeds for the whole item; step-0 present tensors stay open as the
 * constant cross-attention past; each step's non-retained outputs (logits) and
 * the superseded decoder self past are closed explicitly.
 *
 * Session options follow the #93 MOSS lesson (memory patterns + CPU arena
 * off, 6 intra-op threads); results flush after every leg (lmkd can kill
 * mid-run). A self-contained probe: no core-tts / TTSEngine involvement.
 */
private class LegStats {
    var finiteAll = true
    var logitsMin = Float.MAX_VALUE
    var logitsMax = -Float.MAX_VALUE
}

class TranslateProbeRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "TranslateProbe"
        private const val INPUTS_FILE = "translate_inputs.json"
        private const val RESULTS_FILE = "translate_results.json"
        private val PRECISIONS = arrayOf("fp32", "int8")
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
                .put("inputs", JSONObject(context.filesDir.resolve(INPUTS_FILE).readText()))
        val outFile = File(outDir, RESULTS_FILE)

        fun flush() {
            File(outDir, "$RESULTS_FILE.tmp").writeText(merged.toString(2))
            outFile.delete()
            File(outDir, "$RESULTS_FILE.tmp").renameTo(outFile)
        }

        val pairs = merged.getJSONObject("inputs").getJSONObject("pairs")
        val modelsRoot = File(context.filesDir, "models")

        for (pair in pairs.keys()) {
            val pairResults = JSONObject()
            merged.put(pair, pairResults)
            val models = pairs.getJSONObject(pair).getJSONObject("models")
            for (modelId in models.keys()) {
                for (precision in PRECISIONS) {
                    val legKey = "$modelId/$precision"
                    try {
                        val dir =
                            File(
                                modelsRoot,
                                "$modelId/" + if (precision == "fp32") "onnx" else "onnx-$precision",
                            )
                        pairResults.put(legKey, probeLeg(models.getJSONArray(modelId), dir, log))
                    } catch (t: Throwable) {
                        pairResults.put(
                            legKey,
                            JSONObject().put("unavailable", t.message ?: t.toString()),
                        )
                    }
                    flush()
                }
            }
            flush()
        }

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        merged.put("total_pss_kb", mem.totalPss).put("vm_hwm_kb", readVmHwm())
        flush()
        log("translate_results.json written to $outDir")
        return merged
    }

    // ---- one leg: pair x model x precision ----

    private fun probeLeg(
        items: JSONArray,
        dir: File,
        log: (String) -> Unit,
    ): JSONObject {
        check(File(dir, "encoder_model.onnx").isFile) { "graphs missing at $dir" }
        val enc = open(File(dir, "encoder_model.onnx"), log)
        val dec = open(File(dir, "decoder_model.onnx"), log)
        val decp = open(File(dir, "decoder_with_past_model.onnx"), log)
        val openMs =
            JSONObject()
                .put("encoder", enc.second)
                .put("decoder", dec.second)
                .put("decoder_with_past", decp.second)

        try {
            val perItem = JSONArray()
            var encoderMs = 0.0
            var decoderMs = 0.0
            var tokensTotal = 0L
            val st = LegStats()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val inputIds = longArray(item.getJSONArray("input_ids"))
                val mask = longArray(item.getJSONArray("attention_mask"))
                val start = longArray(item.getJSONArray("decoder_start"))
                val eos = item.getLong("eos")
                val maxLen = maxOf(512L, 2L * inputIds.size + 50)

                // encoder once; hidden + mask stay open as feeds for the whole
                // greedy loop (no Result/use close — it would close the tensor)
                val idsT =
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(inputIds),
                        longArrayOf(1, inputIds.size.toLong()),
                    )
                val maskT =
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(mask),
                        longArrayOf(1, mask.size.toLong()),
                    )
                val t0 = System.nanoTime()
                val hidden: OnnxTensor
                try {
                    val feeds =
                        mapOf("input_ids" to idsT, "attention_mask" to maskT)
                            .filterKeys { it in enc.first.inputNames }
                    val out = enc.first.run(feeds)
                    hidden = out.get(0) as OnnxTensor
                } catch (t: Throwable) {
                    idsT.close()
                    maskT.close()
                    throw t
                }
                idsT.close()
                encoderMs += (System.nanoTime() - t0) / 1_000_000.0

                val sharedFeeds =
                    mapOf(
                        "encoder_hidden_states" to hidden,
                        "encoder_attention_mask" to maskT,
                    ).filterKeys { it in dec.first.inputNames || it in decp.first.inputNames }

                // Sequential start: feed every decoder_start token in order
                // (Marian [pad]; M2M-100 [eos, target-lang]); the argmax after
                // each start position is DISCARDED — the argmax after the LAST
                // start token is the first generated token. Verified identical
                // to HF generate (forced_bos) for both families, host ORT.
                val seq = ArrayList<Long>(start.size)
                var past = LinkedHashMap<String, OnnxTensor>()
                var encPast: Map<String, OnnxTensor> = emptyMap()
                var eosHit = false
                var decodeMs = 0.0
                try {
                    var best = Int.MIN_VALUE
                    for (s in start.indices) {
                        val t1 = System.nanoTime()
                        val session = if (past.isEmpty()) dec.first else decp.first
                        val feeds = LinkedHashMap<String, OnnxTensor>(sharedFeeds)
                        val tokT =
                            OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(longArrayOf(start[s])),
                                longArrayOf(1, 1),
                            )
                        feeds["input_ids"] = tokT
                        feeds.putAll(past)
                        val out = session.run(filterFeeds(feeds, session))
                        tokT.close()
                        decodeMs += (System.nanoTime() - t1) / 1_000_000.0
                        best = argmaxAndStats(out.get(0) as OnnxTensor, st)
                        val merged = adoptPast(out, past, encPast)
                        past = merged.first
                        encPast = merged.second
                    }

                    while (seq.size < maxLen) {
                        seq.add(best.toLong())
                        if (best.toLong() == eos) {
                            eosHit = true
                            break
                        }
                        val t1 = System.nanoTime()
                        val feeds = LinkedHashMap<String, OnnxTensor>(sharedFeeds)
                        val tokT =
                            OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(longArrayOf(seq[seq.size - 1])),
                                longArrayOf(1, 1),
                            )
                        feeds["input_ids"] = tokT
                        feeds.putAll(past)
                        val out = decp.first.run(filterFeeds(feeds, decp.first))
                        tokT.close()
                        decodeMs += (System.nanoTime() - t1) / 1_000_000.0
                        best = argmaxAndStats(out.get(0) as OnnxTensor, st)
                        val merged = adoptPast(out, past, encPast)
                        past = merged.first
                        encPast = merged.second
                    }
                } finally {
                    past.values.forEach { it.close() }
                    hidden.close()
                    maskT.close()
                }

                // seq holds only generated tokens now (starts are fed, not appended)
                val tokens = seq.size.toLong()
                tokensTotal += tokens
                decoderMs += decodeMs
                val outIds = JSONArray()
                for (k in 0 until seq.size) outIds.put(seq[k])
                perItem.put(
                    JSONObject()
                        .put("kind", item.getString("kind"))
                        .put("input_len", inputIds.size)
                        .put("tokens", tokens)
                        .put("eos_hit", eosHit)
                        .put("decode_ms", Math.round(decodeMs * 10) / 10.0)
                        .put("output_ids", outIds),
                )
                log(
                    "item $i (${item.getString("kind")}): $tokens tok in ${Math.round(decodeMs)} ms" +
                        " eos=$eosHit",
                )
            }

            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            return JSONObject()
                .put("open_ms", openMs)
                .put("encoder_ms", Math.round(encoderMs * 10) / 10.0)
                .put("decoder_ms", Math.round(decoderMs * 10) / 10.0)
                .put("decoder_ms_per_token", if (tokensTotal > 0) decoderMs / tokensTotal else 0.0)
                .put("tokens", tokensTotal)
                .put("items", perItem)
                .put("finite", st.finiteAll)
                .put("logits_min", if (st.logitsMin == Float.MAX_VALUE) 0.0 else st.logitsMin.toDouble())
                .put("logits_max", if (st.logitsMax == -Float.MAX_VALUE) 0.0 else st.logitsMax.toDouble())
                .put("pss_kb", mem.totalPss)
                .put("vm_hwm_kb", readVmHwm())
        } finally {
            enc.first.close()
            dec.first.close()
            decp.first.close()
        }
    }

    // ---- helpers ----

    /** Keeps only the entries the session actually declares (Marian vs M2M naming). */
    private fun filterFeeds(
        feeds: Map<String, OnnxTensor>,
        session: OrtSession,
    ): Map<String, OnnxTensor> = feeds.filterKeys { it in session.inputNames }

    /**
     * Argmax over logits [1, 1, vocab] plus finiteness scan; updates the
     * leg-wide logit min/max. Caller closes the logits tensor.
     */
    private fun argmaxAndStats(
        logits: OnnxTensor,
        st: LegStats,
    ): Int {
        val fb = logits.floatBuffer
        var best = Int.MIN_VALUE
        var bestV = -Float.MAX_VALUE
        for (k in 0 until fb.remaining()) {
            val v = fb.get(k)
            if (v.isNaN() || v.isInfinite()) st.finiteAll = false
            if (v < st.logitsMin) st.logitsMin = v
            if (v > st.logitsMax) st.logitsMax = v
            if (v > bestV) {
                bestV = v
                best = k
            }
        }
        logits.close()
        return best
    }

    /**
     * Takes over the non-logits outputs of a run: with-past re-emits only
     * `present.N.decoder.*` (encoder cross past stays constant from step 0);
     * the no-past decoder emits both. Returns the merged
     * `past_key_values.*` map plus the constant encoder entries. Superseded
     * tensors are closed; retained ones are not.
     */
    private fun adoptPast(
        out: OrtSession.Result,
        previous: LinkedHashMap<String, OnnxTensor>,
        encPast: Map<String, OnnxTensor>,
    ): Pair<LinkedHashMap<String, OnnxTensor>, Map<String, OnnxTensor>> {
        val nextPast = LinkedHashMap<String, OnnxTensor>()
        val entries = out.iterator()
        while (entries.hasNext()) {
            val (name, value) = entries.next()
            if (name == "logits") continue
            nextPast[name.replaceFirst("present", "past_key_values")] = value as OnnxTensor
        }
        return if (encPast.isEmpty()) {
            // step 0 (no-past decoder): emits BOTH decoder + encoder present
            val enc = nextPast.filterKeys { ".encoder." in it }
            previous.values.forEach { if (it !in nextPast.values) it.close() }
            nextPast to enc
        } else {
            previous.values.forEach {
                if (it !in nextPast.values && it !in encPast.values) it.close()
            }
            LinkedHashMap(encPast + nextPast) to encPast
        }
    }

    private fun longArray(a: JSONArray): LongArray = LongArray(a.length()) { a.getLong(it) }

    /**
     * Opens a graph on CPU, or on an accelerated EP when a staging flag file
     * exists (decisions #114 GPU/NPU tests): `files/ep_nnapi` -> NNAPI,
     * `files/ep_qnn` -> QNN Hexagon HTP (the AAR bundles libQnnHtp* incl. the
     * V69 skel for SD 8 Gen 1), `files/ep_qnn_gpu` -> QNN Adreno GPU backend.
     * Dynamic-shape autoregressive decode is expected to partition poorly;
     * measured either way.
     */
    private fun open(
        graph: File,
        log: (String) -> Unit,
    ): Pair<OrtSession, Long> {
        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions()
        val files = context.filesDir
        val ep =
            when {
                File(files, "ep_qnn_gpu").isFile -> "qnn_gpu"
                File(files, "ep_qnn").isFile -> "qnn_htp"
                File(files, "ep_nnapi").isFile -> "nnapi"
                else -> "cpu"
            }
        if (ep == "nnapi") {
            opts.addNnapi()
            log("EP: NNAPI requested for ${graph.name}")
        } else if (ep == "qnn_htp") {
            opts.addQnn(mapOf("backend_path" to "libQnnHtp.so"))
            log("EP: QNN HTP requested for ${graph.name}")
        } else if (ep == "qnn_gpu") {
            opts.addQnn(mapOf("backend_path" to "libQnnGpu.so"))
            log("EP: QNN GPU requested for ${graph.name}")
        } else {
            opts.setIntraOpNumThreads(6)
        }
        // MOSS lesson (decisions #93): truthful memory numbers.
        opts.setMemoryPatternOptimization(false)
        opts.setCPUArenaAllocator(false)
        val session =
            try {
                env.createSession(graph.absolutePath, opts)
            } catch (t: Throwable) {
                log("NNAPI session failed for ${graph.name}: ${t.message}; falling back to CPU")
                opts.setIntraOpNumThreads(6)
                env.createSession(graph.absolutePath, opts)
            }
        val ms = System.currentTimeMillis() - t0
        log("opened ${graph.name} in $ms ms (ep=$ep)")
        return session to ms
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
