package com.moronigranja.localttsreader.tts.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.LongBuffer

/**
 * The SMaLL-100 translator, ported verbatim from the Phase J device probe
 * (`spike-tts/…/TranslateProbeRunner.kt`, device-verified) minus the JSON
 * harness. Three CPU `OrtSession`s (encoder / decoder / decoder_with_past,
 * int8 graphs), opened with the #93 MOSS lesson
 * (`setMemoryPatternOptimization(false)` + `setCPUArenaAllocator(false)`) and
 * the caller's [sessionFactory] (intra-op threads).
 *
 * Conditioning is the #114 contract: encode `[tgt_lang, X, eos]`; the decoder
 * starts on `[eos]` with NO forced bos — the argmax after the eos feed IS the
 * first generated token (feeding the lang id as decoder start degenerates to
 * single-token loops, the "adget" artifacts). Greedy decode over
 * decoder_with_past until EOS or `max(512, 2L + 50)` tokens. Past tensors
 * are adopted per step exactly as the probe does (tensor close hygiene is
 * the memory contract on a ~1 GB graph leg).
 *
 * Single-flight: the sessions are re-entrant per run in ORT, but translation
 * is serialized so two passages never interleave on the tuple — and the
 * ~1 GB resident leg is a single logical unit anyway ([TranslateRuntime]
 * owns the idle-close policy).
 */
class Sm100Translator private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val decoderWithPast: OrtSession,
    private val tokenizer: Small100Tokenizer,
    private val mutex: Mutex,
) {
    /** Translates [text] into the SMaLL-100 [tgtLang] code; single-flight. */
    suspend fun translate(
        text: String,
        tgtLang: String,
    ): String = mutex.withLock { translateLocked(text, tgtLang) }

    fun close() {
        encoder.close()
        decoder.close()
        decoderWithPast.close()
    }

    private fun translateLocked(
        text: String,
        tgtLang: String,
    ): String {
        val ids = tokenizer.encode(text, tgtLang)
        if (ids.size == 2) return "" // condition+[eos] only: nothing to translate
        val maxLen = maxOf(512L, 2L * ids.size + 50)
        val eos = tokenizer.eosId.toLong()

        val idsT =
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }),
                longArrayOf(1, ids.size.toLong()),
            )
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), longArrayOf(1, ids.size.toLong()))
        val hidden: OnnxTensor
        try {
            val feeds = mapOf("input_ids" to idsT, "attention_mask" to maskT).filterKeys { it in encoder.inputNames }
            hidden = encoder.run(feeds).get(0) as OnnxTensor
        } catch (t: Throwable) {
            idsT.close()
            maskT.close()
            throw t
        }
        idsT.close()

        val shared =
            mapOf(
                "encoder_hidden_states" to hidden,
                "encoder_attention_mask" to maskT,
            ).filterKeys { it in decoder.inputNames || it in decoderWithPast.inputNames }

        val seq = ArrayList<Long>()
        var past = LinkedHashMap<String, OnnxTensor>()
        var encPast: Map<String, OnnxTensor> = emptyMap()
        var best = Int.MIN_VALUE
        try {
            // Step 0: decoder (no past) fed [eos]; its argmax is DISCARDED —
            // the next argmax is the first generated token.
            val step0 = LinkedHashMap<String, OnnxTensor>(shared)
            val startT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(eos)), longArrayOf(1, 1))
            step0["input_ids"] = startT
            val out0 = decoder.run(step0.filterKeys { it in decoder.inputNames })
            startT.close()
            best = argmax(out0.get(0) as OnnxTensor)
            val adopted = adoptPast(out0, past, encPast)
            past = adopted.first
            encPast = adopted.second

            while (seq.size < maxLen) {
                seq.add(best.toLong())
                if (best.toLong() == eos) break
                val feeds = LinkedHashMap<String, OnnxTensor>(shared)
                val tokT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(seq[seq.size - 1])), longArrayOf(1, 1))
                feeds["input_ids"] = tokT
                feeds.putAll(past)
                val out = decoderWithPast.run(feeds.filterKeys { it in decoderWithPast.inputNames })
                tokT.close()
                best = argmax(out.get(0) as OnnxTensor)
                val next = adoptPast(out, past, encPast)
                past = next.first
                encPast = next.second
            }
        } finally {
            past.values.forEach { it.close() }
            hidden.close()
            maskT.close()
        }

        return tokenizer.decode(IntArray(seq.size) { seq[it].toInt() })
    }

    private fun argmax(logits: OnnxTensor): Int {
        val fb = logits.floatBuffer
        var best = Int.MIN_VALUE
        var bestValue = -Float.MAX_VALUE
        while (fb.hasRemaining()) {
            val v = fb.get()
            if (v > bestValue) {
                bestValue = v
                best = fb.position() - 1
            }
        }
        logits.close()
        return best
    }

    /**
     * Takes over the non-logits outputs of a run: the with-past graph
     * re-emits only `present.N.decoder.*` (the encoder cross-past stays
     * constant from step 0); the no-past decoder emits both. Returns the
     * merged `past_key_values.*` map plus the constant encoder entries;
     * superseded tensors are closed, retained ones are not.
     */
    private fun adoptPast(
        out: OrtSession.Result,
        previous: LinkedHashMap<String, OnnxTensor>,
        encPast: Map<String, OnnxTensor>,
    ): Pair<LinkedHashMap<String, OnnxTensor>, Map<String, OnnxTensor>> {
        val next = LinkedHashMap<String, OnnxTensor>()
        val entries = out.iterator()
        while (entries.hasNext()) {
            val (name, value) = entries.next()
            if (name == "logits") continue
            next[name.replaceFirst("present", "past_key_values")] = value as OnnxTensor
        }
        return if (encPast.isEmpty()) {
            // Step 0 (no-past decoder): emits BOTH decoder + encoder present.
            val enc = next.filterKeys { ".encoder." in it }
            previous.values.forEach { if (it !in next.values) it.close() }
            next to enc
        } else {
            previous.values.forEach {
                if (it !in next.values && it !in encPast.values) it.close()
            }
            LinkedHashMap(encPast + next) to encPast
        }
    }

    companion object {
        /**
         * Opens the three graphs in [modelDir] (encoder_model.onnx,
         * decoder_model.onnx, decoder_with_past_model.onnx). The tokenizer
         * (SPM + vocab) is loaded separately by the caller. [sessionFactory]
         * runs over each session's options — the caller applies
         * `setIntraOpNumThreads` — after the fixed #93 settings.
         */
        fun open(
            modelDir: File,
            tokenizer: Small100Tokenizer,
            sessionFactory: (OrtSession.SessionOptions) -> Unit = {},
        ): Sm100Translator {
            val env = OrtEnvironment.getEnvironment()

            fun openGraph(name: String): OrtSession {
                val graph = File(modelDir, name)
                check(graph.isFile) { "SMaLL-100 graph missing: $graph" }
                val opts = OrtSession.SessionOptions()
                // MOSS lesson (decisions #93): truthful memory numbers.
                opts.setMemoryPatternOptimization(false)
                opts.setCPUArenaAllocator(false)
                sessionFactory(opts)
                return env.createSession(graph.absolutePath, opts)
            }
            return Sm100Translator(
                env,
                openGraph("encoder_model.onnx"),
                openGraph("decoder_model.onnx"),
                openGraph("decoder_with_past_model.onnx"),
                tokenizer,
                Mutex(),
            )
        }
    }
}
