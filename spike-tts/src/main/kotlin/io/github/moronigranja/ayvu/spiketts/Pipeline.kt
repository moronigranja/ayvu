package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CosyVoice3 pure-ONNX zero-shot TTS pipeline: voice-prompt processing, the
 * LLM autoregressive loop (ras_sampling, silent-token suppression), CFM flow
 * with classifier-free guidance and cosine schedule, and the HiFT vocoder.
 * Direct port of sokuji's cosyvoice3/pipeline.py (the audio-verified fix for
 * the CosyVoice2-constants landmine). Deltas from the reference:
 *  - Resampling is NOT implemented here: the caller pre-resamples the prompt
 *    clip to 16 kHz and 24 kHz (soxr on the host) and passes both.
 *  - speed support is omitted (spike always synthesizes at 1.0).
 *  - Every OnnxTensor (inputs and outputs) is closed after its last use;
 *    leaking them made the autoregressive loop blow past 6 GB of anon memory
 *    on the 8 GB device and get killed by lmkd.
 */
internal class Pipeline(
    private val sessions: Sessions,
) {
    companion object {
        const val SAMPLE_RATE = 24000
        const val SPEECH_TOKEN_SIZE = 6561
        const val SOS = 6561
        const val TASK_ID = 6563
        const val STOP_TOKEN_MIN = SPEECH_TOKEN_SIZE // ids 6561..6760 all stop
        const val TOKEN_MEL_RATIO = 2
        const val CFG_RATE = 0.7
        const val N_TIMESTEPS = 10
        const val MIN_TOKEN_TEXT_RATIO = 2
        const val MAX_TOKEN_TEXT_RATIO = 20
        const val HARD_MAX_TOKENS = 1500
        const val MAX_CONSECUTIVE_SILENT = 5

        // Chunked by the LLM's byte-BPE: ids that carry no spoken content.
        val SILENT_TOKENS = setOf(1, 2, 28, 29, 55, 248, 494, 2241, 2242, 2322, 2323)
    }

    class VoicePrompt(
        val speechTokens: LongArray, // [1, S] int64 flat
        val spkEmbedding: FloatArray, // [1, 192] flat
        val mel: FloatArray, // [2S, 80] flat
        val melFrames: Int,
        val promptTextIds: IntArray,
    )

    class HiftStats(
        val f0Mean: Float,
        val f0Std: Float,
        val srcRms: Float,
        val srcLen: Int,
    )

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()

    private fun run(
        sessionKey: String,
        inputs: Map<String, OnnxTensor>,
    ): List<OnnxTensor> {
        val out = sessions[sessionKey].run(inputs)
        return List(out.size()) { i -> out[i] as OnnxTensor }
    }

    private fun closeTensors(ts: Iterable<OnnxTensor>) {
        for (t in ts) t.close()
    }

    /** Single-f32-output run: copies the tensor, then closes everything. */
    private fun runF32(
        sessionKey: String,
        inputs: Map<String, OnnxTensor>,
    ): Pair<FloatArray, LongArray> {
        val outs = run(sessionKey, inputs)
        try {
            val data = Tensors.toF32(outs[0])
            val shape = Tensors.shape(outs[0])
            return data to shape
        } finally {
            closeTensors(outs)
            closeTensors(inputs.values)
        }
    }

    /** Single-int-output run: copies the token ids, then closes everything. */
    private fun runI64(
        sessionKey: String,
        inputs: Map<String, OnnxTensor>,
    ): LongArray {
        val outs = run(sessionKey, inputs)
        try {
            return Tensors.toI64(outs[0])
        } finally {
            closeTensors(outs)
            closeTensors(inputs.values)
        }
    }

    private fun f32(
        data: FloatArray,
        shape: LongArray,
    ) = Tensors.f32(env, data, shape)

    private fun i64(
        data: LongArray,
        shape: LongArray,
    ) = Tensors.i64(env, data, shape)

    // ----------------------------------------------------------------------
    // Prompt processing
    // ----------------------------------------------------------------------

    fun processPrompt(
        tok: Bpe.Tokenizer,
        audio16k: FloatArray,
        audio24k: FloatArray,
        transcript: String,
    ): VoicePrompt {
        val (feats, featsShape) = Mel.whisperLogMel128(audio16k) // [1, 128, T]
        val tokens =
            runI64(
                "speech_tokenizer",
                mapOf(
                    "feats" to f32(feats, featsShape.map { it.toLong() }.toLongArray()),
                    "feats_length" to Tensors.i32(env, intArrayOf(featsShape[2]), longArrayOf(1)),
                ),
            )

        val (fbank, fbankShape) = Mel.kaldiFbank80Cmn(audio16k) // [1, T, 80]
        val (spkRaw, _) =
            runF32(
                "campplus",
                mapOf("input" to f32(fbank, fbankShape.map { it.toLong() }.toLongArray())),
            )
        val spkEmbedding = spkRaw // flat [1, 192]

        val (melFlat, melShape) = Mel.matchaMel80(audio24k) // [frames, 80]
        val melFrames = melShape[0]
        val tokenLen = minOf(melFrames / TOKEN_MEL_RATIO, tokens.size)
        val melRows = TOKEN_MEL_RATIO * tokenLen
        val mel = FloatArray(melRows * 80)
        for (r in 0 until melRows) {
            for (c in 0 until 80) mel[r * 80 + c] = melFlat[r * 80 + c]
        }
        val speechTokens = LongArray(tokenLen) { tokens[it] }
        val promptTextIds = Frontend.buildPromptTextIds(tok, transcript)

        return VoicePrompt(speechTokens, spkEmbedding, mel, melRows, promptTextIds)
    }

    // ----------------------------------------------------------------------
    // LLM autoregressive loop
    // ----------------------------------------------------------------------

    private fun speechEmb(ids: LongArray): FloatArray {
        val (emb, _) =
            runF32(
                "speech_embedding",
                mapOf("token" to i64(ids, longArrayOf(1, ids.size.toLong()))),
            )
        return emb
    }

    fun llmGenerate(
        tok: Bpe.Tokenizer,
        ttsText: String,
        prompt: VoicePrompt,
        rng: Random,
    ): Pair<LongArray, Int> {
        val ttsIds = Frontend.encodeTtsText(tok, ttsText)
        val combined =
            IntArray(prompt.promptTextIds.size + ttsIds.size) { i ->
                if (i < prompt.promptTextIds.size) prompt.promptTextIds[i] else ttsIds[i - prompt.promptTextIds.size]
            }
        val combinedL = LongArray(combined.size) { combined[it].toLong() }
        val (textEmb, textEmbShape) =
            runF32(
                "text_embedding",
                mapOf("input_ids" to i64(combinedL, longArrayOf(1, combinedL.size.toLong()))),
            )

        val sosEmb = speechEmb(longArrayOf(SOS.toLong()))
        val taskEmb = speechEmb(longArrayOf(TASK_ID.toLong()))
        val promptSpeechEmb = speechEmb(prompt.speechTokens)

        val d = textEmbShape[2].toInt()
        val lmLen = 1 + combined.size + 1 + prompt.speechTokens.size
        val lmInput = FloatArray(lmLen * d)
        var pos = 0
        for (k in 0 until d) lmInput[pos * d + k] = sosEmb[k]
        pos++
        for (t in 0 until combined.size) for (k in 0 until d) lmInput[pos * d + k] = textEmb[t * d + k]
        pos++
        for (k in 0 until d) lmInput[pos * d + k] = taskEmb[k]
        pos++
        for (t in 0 until prompt.speechTokens.size) for (k in 0 until d) lmInput[pos * d + k] = promptSpeechEmb[t * d + k]

        val seqLen = lmLen
        val mask = FloatArray(seqLen) { 1f }
        val initOuts =
            run(
                "llm_initial",
                mapOf(
                    "inputs_embeds" to f32(lmInput, longArrayOf(1, seqLen.toLong(), d.toLong())),
                    "attention_mask" to f32(mask, longArrayOf(1, seqLen.toLong())),
                ),
            )
        val initHidden = initOuts[0]
        var past = initOuts[1]
        val (lastHidden, _) = Tensors.lastPos(Tensors.toF32(initHidden), Tensors.shape(initHidden))
        initHidden.close()
        val (logitsF, _) =
            runF32(
                "llm_decoder",
                mapOf("hidden_state" to f32(lastHidden, longArrayOf(1, 1, d.toLong()))),
            )
        var logits = DoubleArray(logitsF.size) { logitsF[it].toDouble() }

        val vocab = logits.size

        var minLen = MIN_TOKEN_TEXT_RATIO * ttsIds.size
        val maxLen = minOf(MAX_TOKEN_TEXT_RATIO * ttsIds.size, HARD_MAX_TOKENS)
        minLen = minOf(minLen, maxLen - 1)

        val outTokens = ArrayList<Int>(maxLen)
        val flowTokens = ArrayList<Int>(maxLen)
        var consecutiveSilent = 0
        for (i in 0 until maxLen) {
            val logp = Sampling.logSoftmax(logits)
            if (i < minLen) {
                for (v in STOP_TOKEN_MIN until vocab) logp[v] = Double.NEGATIVE_INFINITY
            }
            val tokenId = Sampling.rasSampling(logp, outTokens, rng)
            if (tokenId >= STOP_TOKEN_MIN) break
            outTokens.add(tokenId)
            if (tokenId in SILENT_TOKENS) {
                consecutiveSilent++
                if (consecutiveSilent <= MAX_CONSECUTIVE_SILENT) flowTokens.add(tokenId)
            } else {
                consecutiveSilent = 0
                flowTokens.add(tokenId)
            }
            val nextEmb = speechEmb(longArrayOf(tokenId.toLong()))
            val decOuts =
                run(
                    "llm_decode",
                    mapOf(
                        "inputs_embeds" to f32(nextEmb, longArrayOf(1, 1, d.toLong())),
                        "attention_mask" to
                            f32(
                                FloatArray(seqLen + outTokens.size) { 1f },
                                longArrayOf(1, (seqLen + outTokens.size).toLong()),
                            ),
                        "past_key_values" to past,
                    ),
                )
            val newHidden = decOuts[0]
            val newPast = decOuts[1]
            past.close() // previous past fully consumed by this decode step
            past = newPast
            // runF32 closes newHidden (it is the decoder's input tensor)
            val (logitsF, _) =
                runF32(
                    "llm_decoder",
                    mapOf("hidden_state" to newHidden),
                )
            logits = DoubleArray(logitsF.size) { logitsF[it].toDouble() }
        }
        past.close()

        return LongArray(flowTokens.size) { flowTokens[it].toLong() } to outTokens.size
    }

    // ----------------------------------------------------------------------
    // Flow (CFM with true CFG + cosine schedule)
    // ----------------------------------------------------------------------

    fun flowGenerate(
        flowTokens: LongArray,
        prompt: VoicePrompt,
        rng: Random,
    ): FloatArray {
        var norm = 0.0
        for (v in prompt.spkEmbedding) norm += v.toDouble() * v
        norm = sqrt(norm) + 1e-8
        val embNorm = FloatArray(prompt.spkEmbedding.size) { (prompt.spkEmbedding[it] / norm).toFloat() }
        val (spks, spksShape) =
            runF32(
                "flow_spk_projection",
                mapOf("embedding" to f32(embNorm, longArrayOf(1, embNorm.size.toLong()))),
            )

        val allTokens =
            LongArray(prompt.speechTokens.size + flowTokens.size) { i ->
                if (i < prompt.speechTokens.size) prompt.speechTokens[i] else flowTokens[i - prompt.speechTokens.size]
            }
        val (tokenEmb, tokenEmbShape) =
            runF32(
                "flow_token_embedding",
                mapOf("token" to i64(allTokens, longArrayOf(1, allTokens.size.toLong()))),
            )
        val (h, hShape) =
            runF32(
                "flow_pre_lookahead",
                mapOf("token_embedded" to f32(tokenEmb, tokenEmbShape)),
            )
        val melLen = hShape[1].toInt()
        val dim = hShape[2].toInt()
        require(dim == 80) { "flow hidden dim must be 80, got $dim" }
        val melLen1 = prompt.melFrames

        val (mu, muShape) = Tensors.transposeBLDToBDL(h, hShape) // [1, 80, L]

        val conds = FloatArray(80 * melLen)
        for (r in 0 until 80) {
            for (c in 0 until melLen1) conds[r * melLen + c] = prompt.mel[c * 80 + r] // mel.T
        }
        val mask = FloatArray(melLen) { 1f }

        val x = FloatArray(80 * melLen) { gaussian(rng) }

        val tSpan =
            DoubleArray(N_TIMESTEPS + 1) {
                1.0 - cos(it.toDouble() / N_TIMESTEPS * 0.5 * PI)
            }

        val xShape = longArrayOf(2, 80L, melLen.toLong())
        val mask2 =
            FloatArray(mask.size * 2).also {
                mask.copyInto(it)
                mask.copyInto(it, mask.size)
            }
        val maskShape = longArrayOf(2, 1L, melLen.toLong())
        val (mu2, mu2Shape) = Tensors.concatZeroAlongBatch(mu, muShape)
        val (spks2, spks2Shape) = Tensors.concatZeroAlongBatch(spks, spksShape)
        val (conds2, conds2Shape) = Tensors.concatZeroAlongBatch(conds, longArrayOf(1, 80L, melLen.toLong()))

        var t = tSpan[0]
        var dt = tSpan[1] - tSpan[0]
        for (step in 1..N_TIMESTEPS) {
            // The flow_estimator input is the CURRENT trajectory point doubled
            // along the batch (numpy concat([x, x]) per step). It must be
            // rebuilt here: feeding a snapshot of the initial noise froze the
            // diffusion in place and produced a hot, clipped mel.
            val x2 =
                FloatArray(x.size * 2).also {
                    x.copyInto(it)
                    x.copyInto(it, x.size)
                }
            val (vel, _) =
                runF32(
                    "flow_estimator",
                    mapOf(
                        "x" to f32(x2, xShape),
                        "mask" to f32(mask2, maskShape),
                        "mu" to f32(mu2, mu2Shape),
                        "t" to f32(floatArrayOf(t.toFloat(), t.toFloat()), longArrayOf(2)),
                        "spks" to f32(spks2, spks2Shape),
                        "cond" to f32(conds2, conds2Shape),
                    ),
                )
            val n = 80 * melLen
            for (i in 0 until n) {
                x[i] = x[i] + (dt * ((1.0 + CFG_RATE) * vel[i] - CFG_RATE * vel[i + n])).toFloat()
            }
            t += dt
            if (step < N_TIMESTEPS) dt = tSpan[step + 1] - t
        }

        // out = x[:, :, melLen1:]  -> [1, 80, L - melLen1]
        val outLen = melLen - melLen1
        val out = FloatArray(80 * outLen)
        for (r in 0 until 80) {
            for (c in 0 until outLen) out[r * outLen + c] = x[r * melLen + melLen1 + c]
        }
        return out
    }

    // ----------------------------------------------------------------------
    // HiFT vocoder
    // ----------------------------------------------------------------------

    fun hiftGenerate(
        mel: FloatArray,
        melLen: Int,
    ): Pair<FloatArray, HiftStats> {
        val l2 = melLen
        val (f0, _) = runF32("hift_f0", mapOf("mel" to f32(mel, longArrayOf(1, 80L, l2.toLong()))))
        val (source, _) =
            runF32(
                "hift_source",
                mapOf("f0" to f32(f0, longArrayOf(1, 1, l2.toLong()))),
            )
        val (stft, nFrames) = Mel.stft16x4(source)
        val magPhase =
            run(
                "hift_decoder",
                mapOf(
                    "mel" to f32(mel, longArrayOf(1, 80L, l2.toLong())),
                    "source_stft" to f32(stft, longArrayOf(1, 18L, nFrames.toLong())),
                ),
            )
        val magArr: FloatArray
        val phaseArr: FloatArray
        try {
            magArr = Tensors.toF32(magPhase[0])
            phaseArr = Tensors.toF32(magPhase[1])
        } finally {
            closeTensors(magPhase)
        }
        val audio = Mel.istft16x4(magArr, nFrames, phaseArr)
        for (i in audio.indices) audio[i] = audio[i].coerceIn(-0.99f, 0.99f)
        var f0Sum = 0.0
        for (v in f0) f0Sum += v
        val f0Mean = (f0Sum / f0.size).toFloat()
        var f0Var = 0.0
        for (v in f0) f0Var += (v - f0Mean) * (v - f0Mean)
        var srcSum = 0.0
        for (v in source) srcSum += v * v
        val stats =
            HiftStats(
                f0Mean = f0Mean,
                f0Std = sqrt(f0Var / f0.size).toFloat(),
                srcRms = sqrt(srcSum / source.size).toFloat(),
                srcLen = source.size,
            )
        return audio to stats
    }

    private fun gaussian(rng: Random): Float = (sqrt(-2.0 * ln(rng.nextDouble())) * cos(2.0 * PI * rng.nextDouble())).toFloat()
}
