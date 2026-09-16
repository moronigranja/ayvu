package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.min

/**
 * Port of OpenMOSS/MOSS-TTS-Nano `examples/android_onnx_runtime/.../
 * MossOnnxDemoEngine.kt` @ cc7bdf1 (Apache-2.0; provenance recorded here and
 * in decisions #93). Clean-room rules do not apply — upstream is Apache-2.0
 * and attribution is this header.
 *
 * Differences from the demo, all D3-harness-driven:
 * - corpus text enters as sentencepiece token ids from the staged corpus TSV
 *   (host-generated against the pinned `tokenizer.model`; template assembly
 *   stays here, in [buildInputRows], exactly as upstream);
 * - thread count is a constructor parameter (the demo pins 2 — its own
 *   choice; the D3 harness measures the 6-thread parity setting and records
 *   the 2-thread warm-up separately);
 * - the synthesis entry point returns the PCM floats + per-stage timings
 *   instead of writing a WAV (the runner owns measurement + files).
 */
internal class MossEngine(
    modelRoot: File,
    private val cpuThreads: Int = 6,
) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val manifestPath = resolveManifestPath(modelRoot)
    private val manifestDir = manifestPath.parentFile ?: modelRoot
    private val manifest = ModelManifest.fromJson(readJson(manifestPath))
    private val ttsMetaPath = resolveManifestRelativePath(manifest.modelFiles.ttsMeta)
    private val codecMetaPath = resolveManifestRelativePath(manifest.modelFiles.codecMeta)
    private val ttsMeta = TtsMeta.fromJson(readJson(ttsMetaPath))
    private val codecMeta = CodecMeta.fromJson(readJson(codecMetaPath))
    private val ttsDir = ttsMetaPath.parentFile ?: manifestDir
    private val codecDir = codecMetaPath.parentFile ?: manifestDir
    private val sessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // The AR decode loop grows the KV cache by one frame per step; with
            // memory-pattern planning ON, ORT retains one planned arena block per
            // distinct sequence shape and RSS ballooned to 6.6 GB (lmkd kill on
            // the S22). Planning off keeps the arena reused; recorded as a
            // session-option deviation from the demo in decisions #93.
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            setIntraOpNumThreads(cpuThreads.coerceAtLeast(1))
            setInterOpNumThreads(1)
        }

    // Per-session memory log (MossSpike): the S22 lmkd killed the process at
    // 6.6 GB RSS during session creation — this pinpoints which session
    // balloons and feeds the #93 memory column.
    private val prefillSession = createSession(File(ttsDir, ttsMeta.files.prefill)).also { logSession("prefill") }
    private val decodeSession = createSession(File(ttsDir, ttsMeta.files.decodeStep)).also { logSession("decode_step") }
    private val localFixedFrameSession =
        createSession(
            File(ttsDir, ttsMeta.files.localFixedSampledFrame),
        ).also { logSession("local_fixed") }
    private val codecDecodeSession = createSession(File(codecDir, codecMeta.files.decodeFull)).also { logSession("codec_decode") }

    private fun logSession(name: String) {
        val mem = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(mem)
        android.util.Log.d(
            "MossSpike",
            "session $name open: totalPss=${mem.totalPss} kB, " +
                "dalvikPss=${mem.dalvikPss}, nativePss=${mem.nativePss}",
        )
    }

    val sampleRate: Int get() = codecMeta.codecConfig.sampleRate
    val maxFrames: Int get() = manifest.generationDefaults.maxNewFrames
    val voiceName: String get() = selectedVoice.voice
    val allVoiceNames: List<String> get() = manifest.builtinVoices.map { it.voice }

    /**
     * Deterministic builtin-voice pick: the manifest marks languages via the
     * `group` field ("English Male"/"English Female"); prefer an English
     * Female entry to gender-match the Kokoro af_heart baseline, else the
     * first English entry, else the first builtin (limitation recorded by the
     * runner).
     */
    private val selectedVoice: BuiltinVoice =
        manifest.builtinVoices
            .firstOrNull { it.group?.contains("English") == true && it.group?.contains("Female") == true }
            ?: manifest.builtinVoices.firstOrNull { it.group?.contains("English") == true }
            ?: manifest.builtinVoices.firstOrNull { it.promptAudioCodes.isNotEmpty() }
            ?: error("no builtin voices in ${manifestPath.name}")

    class StageTimings(
        var prefillMs: Long = 0,
        var decodeMs: Long = 0,
        var codecMs: Long = 0,
    )

    class Synthesis(
        val pcm: FloatArray,
        val generatedFrames: Int,
        val truncated: Boolean,
        val timings: StageTimings,
    )

    fun synthesize(
        textTokenIds: IntArray,
        voice: String = selectedVoice.voice,
        maxFrames: Int = manifest.generationDefaults.maxNewFrames,
        seed: Long = 1234L,
    ): Synthesis {
        require(textTokenIds.isNotEmpty()) { "textTokenIds must not be empty" }
        val timings = StageTimings()
        val inputRows = buildInputRows(textTokenIds, voice)
        var prefillResult: PrefillResult? = null
        val tP = System.currentTimeMillis()
        try {
            prefillResult = runPrefill(inputRows)
        } finally {
            timings.prefillMs += System.currentTimeMillis() - tP
        }
        val tD = System.currentTimeMillis()
        try {
            val audioTokens = runDecode(requireNotNull(prefillResult), maxFrames, seed)
            val truncated = audioTokens.size >= maxFrames
            val tC = System.currentTimeMillis()
            val pcm = decodeAudioTokens(audioTokens)
            timings.codecMs = System.currentTimeMillis() - tC
            return Synthesis(pcm, audioTokens.size, truncated, timings)
        } finally {
            timings.decodeMs += System.currentTimeMillis() - tD - timings.codecMs
        }
    }

    override fun close() {
        codecDecodeSession.close()
        localFixedFrameSession.close()
        decodeSession.close()
        prefillSession.close()
        sessionOptions.close()
    }

    private fun createSession(modelFile: File): OrtSession {
        require(modelFile.isFile) { "Missing ONNX file: ${modelFile.absolutePath}" }
        return env.createSession(modelFile.absolutePath, sessionOptions)
    }

    private fun resolveManifestRelativePath(relativePath: String): File {
        val direct = File(manifestDir, relativePath).canonicalFile
        if (direct.exists()) {
            return direct
        }
        val alias =
            relativePath
                .replace("MOSS-TTS-Nano-ONNX-CPU", "MOSS-TTS-Nano-100M-ONNX")
                .replace("MOSS-Audio-Tokenizer-Nano-ONNX-CPU", "MOSS-Audio-Tokenizer-Nano-ONNX")
        return File(manifestDir, alias).canonicalFile
    }

    private fun buildInputRows(
        textTokenIds: IntArray,
        voice: String,
    ): InputRows {
        val cfg = manifest.ttsConfig
        val rowWidth = cfg.nVq + 1
        val promptAudioCodes = selectBuiltinVoicePromptAudioCodes(voice)
        val prefixTokens = manifest.promptTemplates.userPromptPrefixTokenIds + cfg.audioStartTokenId
        val suffixTokens =
            intArrayOf(cfg.audioEndTokenId) +
                manifest.promptTemplates.userPromptAfterReferenceTokenIds +
                textTokenIds +
                manifest.promptTemplates.assistantPromptPrefixTokenIds +
                intArrayOf(cfg.audioStartTokenId)
        val rows = ArrayList<IntArray>()
        rows += buildTextRows(prefixTokens, cfg, rowWidth)
        rows += buildAudioRows(promptAudioCodes, cfg, rowWidth)
        rows += buildTextRows(suffixTokens, cfg, rowWidth)
        return InputRows(rows.toTypedArray(), IntArray(rows.size) { 1 })
    }

    private fun buildTextRows(
        tokens: IntArray,
        cfg: TtsConfig,
        rowWidth: Int,
    ): List<IntArray> =
        tokens.map { token ->
            IntArray(rowWidth) { index -> if (index == 0) token else cfg.audioPadTokenId }
        }

    private fun buildAudioRows(
        audioCodes: List<IntArray>,
        cfg: TtsConfig,
        rowWidth: Int,
    ): List<IntArray> =
        audioCodes.map { codeRow ->
            IntArray(rowWidth) { index ->
                when {
                    index == 0 -> cfg.audioUserSlotTokenId
                    index - 1 < min(codeRow.size, cfg.nVq) -> codeRow[index - 1]
                    else -> cfg.audioPadTokenId
                }
            }
        }

    private fun selectBuiltinVoicePromptAudioCodes(voice: String): List<IntArray> {
        val selected =
            manifest.builtinVoices.firstOrNull {
                it.voice == voice && it.promptAudioCodes.isNotEmpty()
            } ?: manifest.builtinVoices.firstOrNull { it.promptAudioCodes.isNotEmpty() }
        return selected?.promptAudioCodes
            ?: error("No builtin voice prompt_audio_codes found in ${manifestPath.absolutePath}")
    }

    private fun runPrefill(inputRows: InputRows): PrefillResult {
        val seqLen = inputRows.inputIds.size
        val rowWidth = inputRows.inputIds[0].size
        val inputIdsFlat = IntArray(seqLen * rowWidth)
        var offset = 0
        for (row in inputRows.inputIds) {
            for (value in row) {
                inputIdsFlat[offset++] = value
            }
        }
        OnnxTensor
            .createTensor(
                env,
                IntBuffer.wrap(inputIdsFlat),
                longArrayOf(1, seqLen.toLong(), rowWidth.toLong()),
            ).use { inputIdsTensor ->
                OnnxTensor
                    .createTensor(
                        env,
                        IntBuffer.wrap(inputRows.attentionMask),
                        longArrayOf(1, seqLen.toLong()),
                    ).use { maskTensor ->
                        val outputs =
                            prefillSession.run(
                                mapOf(
                                    "input_ids" to inputIdsTensor,
                                    "attention_mask" to maskTensor,
                                ),
                            )
                        return PrefillResult(
                            globalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden")),
                            pastValidLengths = seqLen,
                            pastResult = outputs,
                        )
                    }
            }
    }

    private fun runDecode(
        prefillResult: PrefillResult,
        maxFrames: Int,
        seed: Long,
    ): List<IntArray> {
        val cfg = manifest.ttsConfig
        val audioTokens = ArrayList<IntArray>()
        val rowWidth = cfg.nVq + 1
        val cappedMaxFrames = maxFrames.coerceAtMost(manifest.generationDefaults.maxNewFrames)
        val previousTokenSets = Array(cfg.nVq) { HashSet<Int>() }
        val decodePastInputNames = ttsMeta.onnx.decodeInputNames.drop(2)
        val decodePresentOutputNames = ttsMeta.onnx.decodeOutputNames.drop(1)
        val random = java.util.Random(seed)
        var pastValidLengths = prefillResult.pastValidLengths
        var globalHidden = prefillResult.globalHidden
        var pastResult: OrtSession.Result? = prefillResult.pastResult

        try {
            for (step in 0 until cappedMaxFrames) {
                val frameResult = runLocalFixedSampledFrame(globalHidden, previousTokenSets, random)
                if (!frameResult.shouldContinue) {
                    break
                }
                val audioRow =
                    IntArray(rowWidth) { index ->
                        if (index == 0) cfg.audioAssistantSlotTokenId else cfg.audioPadTokenId
                    }
                for (quantizer in 0 until cfg.nVq) {
                    val token = frameResult.frame[quantizer]
                    audioRow[quantizer + 1] = token
                    previousTokenSets[quantizer].add(token)
                }
                audioTokens += frameResult.frame
                if (step % 50 == 0) {
                    val mem = android.os.Debug.MemoryInfo()
                    android.os.Debug.getMemoryInfo(mem)
                    android.util.Log.d(
                        "MossSpike",
                        "decode step $step/$cappedMaxFrames: totalPss=${mem.totalPss} kB",
                    )
                }
                OnnxTensor
                    .createTensor(
                        env,
                        IntBuffer.wrap(audioRow),
                        longArrayOf(1, 1, rowWidth.toLong()),
                    ).use { inputTensor ->
                        OnnxTensor
                            .createTensor(
                                env,
                                IntBuffer.wrap(intArrayOf(pastValidLengths)),
                                longArrayOf(1),
                            ).use { pastTensor ->
                                val feeds =
                                    linkedMapOf<String, OnnxTensorLike>(
                                        "input_ids" to inputTensor,
                                        "past_valid_lengths" to pastTensor,
                                    )
                                val previousPastResult = pastResult ?: error("Missing decode KV cache")
                                for (index in decodePastInputNames.indices) {
                                    feeds[decodePastInputNames[index]] =
                                        previousPastResult.requiredTensor(decodePresentOutputNames[index])
                                }
                                val outputs = decodeSession.run(feeds)
                                val nextGlobalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden"))
                                globalHidden.close()
                                previousPastResult.close()
                                pastResult = outputs
                                globalHidden = nextGlobalHidden
                                pastValidLengths += 1
                            }
                    }
            }
        } finally {
            globalHidden.close()
            pastResult?.close()
        }
        return audioTokens
    }

    private fun runLocalFixedSampledFrame(
        globalHidden: OnnxTensor,
        previousTokenSets: Array<HashSet<Int>>,
        random: java.util.Random,
    ): LocalFrameResult {
        val cfg = manifest.ttsConfig
        val audioCodebookSize = cfg.audioCodebookSizes.firstOrNull() ?: 1024
        val seenMask = IntArray(cfg.nVq * audioCodebookSize)
        for (channelIndex in previousTokenSets.indices) {
            val channelOffset = channelIndex * audioCodebookSize
            for (tokenId in previousTokenSets[channelIndex]) {
                if (tokenId in 0 until audioCodebookSize) {
                    seenMask[channelOffset + tokenId] = 1
                }
            }
        }
        val assistantRandom = floatArrayOf(random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat())
        val audioRandom =
            FloatArray(cfg.nVq) {
                random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat()
            }
        OnnxTensor
            .createTensor(
                env,
                IntBuffer.wrap(seenMask),
                longArrayOf(1, cfg.nVq.toLong(), audioCodebookSize.toLong()),
            ).use { seenTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(assistantRandom), longArrayOf(1)).use { assistantTensor ->
                    OnnxTensor
                        .createTensor(
                            env,
                            FloatBuffer.wrap(audioRandom),
                            longArrayOf(1, cfg.nVq.toLong()),
                        ).use { audioTensor ->
                            val outputs =
                                localFixedFrameSession.run(
                                    mapOf(
                                        "global_hidden" to globalHidden,
                                        "repetition_seen_mask" to seenTensor,
                                        "assistant_random_u" to assistantTensor,
                                        "audio_random_u" to audioTensor,
                                    ),
                                )
                            outputs.use {
                                return LocalFrameResult(
                                    shouldContinue = it.requiredTensor("should_continue").scalarInt() > 0,
                                    frame = it.requiredTensor("frame_token_ids").intArrayValue(),
                                )
                            }
                        }
                }
            }
    }

    private fun decodeAudioTokens(audioTokens: List<IntArray>): FloatArray {
        require(audioTokens.isNotEmpty()) { "No audio tokens generated" }
        val numFrames = audioTokens.size
        val numQuantizers = manifest.ttsConfig.nVq
        val audioCodesFlat = IntArray(numFrames * numQuantizers)
        var offset = 0
        for (frame in audioTokens) {
            for (quantizer in 0 until numQuantizers) {
                audioCodesFlat[offset++] = frame[quantizer]
            }
        }
        OnnxTensor
            .createTensor(
                env,
                IntBuffer.wrap(audioCodesFlat),
                longArrayOf(1, numFrames.toLong(), numQuantizers.toLong()),
            ).use { codesTensor ->
                OnnxTensor
                    .createTensor(
                        env,
                        IntBuffer.wrap(intArrayOf(numFrames)),
                        longArrayOf(1),
                    ).use { lengthsTensor ->
                        val outputs =
                            codecDecodeSession.run(
                                mapOf(
                                    "audio_codes" to codesTensor,
                                    "audio_code_lengths" to lengthsTensor,
                                ),
                            )
                        outputs.use {
                            val audio = it.requiredTensor("audio").value as Array<*>
                            val batch = audio[0] as Array<*>
                            val channels = batch.map { channel -> channel as FloatArray }
                            val reportedLength = it.requiredTensor("audio_lengths").scalarInt()
                            val length = min(reportedLength, channels.minOfOrNull { channel -> channel.size } ?: 0)
                            return FloatArray(length) { sampleIndex ->
                                channels.sumOf { channel -> channel[sampleIndex].toDouble() }.toFloat() / channels.size
                            }
                        }
                    }
            }
    }

    private class InputRows(
        val inputIds: Array<IntArray>,
        val attentionMask: IntArray,
    )

    private class PrefillResult(
        val globalHidden: OnnxTensor,
        val pastValidLengths: Int,
        val pastResult: OrtSession.Result,
    )

    private class LocalFrameResult(
        val shouldContinue: Boolean,
        val frame: IntArray,
    )

    private data class ModelManifest(
        val modelFiles: ModelFiles,
        val ttsConfig: TtsConfig,
        val promptTemplates: PromptTemplates,
        val generationDefaults: GenerationDefaults,
        val builtinVoices: List<BuiltinVoice>,
    ) {
        companion object {
            fun fromJson(json: JSONObject): ModelManifest =
                ModelManifest(
                    modelFiles = ModelFiles.fromJson(json.getJSONObject("model_files")),
                    ttsConfig = TtsConfig.fromJson(json.getJSONObject("tts_config")),
                    promptTemplates = PromptTemplates.fromJson(json.getJSONObject("prompt_templates")),
                    generationDefaults = GenerationDefaults.fromJson(json.optJSONObject("generation_defaults")),
                    builtinVoices =
                        json.optJSONArray("builtin_voices")?.let { voices ->
                            List(voices.length()) { index -> BuiltinVoice.fromJson(voices.getJSONObject(index)) }
                        } ?: emptyList(),
                )
        }
    }

    private data class ModelFiles(
        val ttsMeta: String,
        val codecMeta: String,
    ) {
        companion object {
            fun fromJson(json: JSONObject): ModelFiles =
                ModelFiles(
                    ttsMeta = json.getString("tts_meta"),
                    codecMeta = json.getString("codec_meta"),
                )
        }
    }

    private data class TtsConfig(
        val nVq: Int,
        val audioPadTokenId: Int,
        val audioStartTokenId: Int,
        val audioEndTokenId: Int,
        val audioUserSlotTokenId: Int,
        val audioAssistantSlotTokenId: Int,
        val audioCodebookSizes: IntArray,
    ) {
        companion object {
            fun fromJson(json: JSONObject): TtsConfig =
                TtsConfig(
                    nVq = json.getInt("n_vq"),
                    audioPadTokenId = json.getInt("audio_pad_token_id"),
                    audioStartTokenId = json.getInt("audio_start_token_id"),
                    audioEndTokenId = json.getInt("audio_end_token_id"),
                    audioUserSlotTokenId = json.optInt("audio_user_slot_token_id", 8),
                    audioAssistantSlotTokenId = json.getInt("audio_assistant_slot_token_id"),
                    audioCodebookSizes = json.getJSONArray("audio_codebook_sizes").toIntArrayCompat(),
                )

            private fun JSONArray.toIntArrayCompat(): IntArray = IntArray(length()) { index -> getInt(index) }
        }
    }

    private data class PromptTemplates(
        val userPromptPrefixTokenIds: IntArray,
        val userPromptAfterReferenceTokenIds: IntArray,
        val assistantPromptPrefixTokenIds: IntArray,
    ) {
        companion object {
            fun fromJson(json: JSONObject): PromptTemplates =
                PromptTemplates(
                    userPromptPrefixTokenIds = json.getJSONArray("user_prompt_prefix_token_ids").toIntArrayCompat(),
                    userPromptAfterReferenceTokenIds =
                        json.getJSONArray("user_prompt_after_reference_token_ids").toIntArrayCompat(),
                    assistantPromptPrefixTokenIds =
                        json.getJSONArray("assistant_prompt_prefix_token_ids").toIntArrayCompat(),
                )

            private fun JSONArray.toIntArrayCompat(): IntArray = IntArray(length()) { index -> getInt(index) }
        }
    }

    private data class GenerationDefaults(
        val maxNewFrames: Int = 375,
    ) {
        companion object {
            fun fromJson(json: JSONObject?): GenerationDefaults =
                GenerationDefaults(maxNewFrames = json?.optInt("max_new_frames", 375) ?: 375)
        }
    }

    private data class BuiltinVoice(
        val voice: String,
        val group: String?,
        val promptAudioCodes: List<IntArray>,
    ) {
        companion object {
            fun fromJson(json: JSONObject): BuiltinVoice =
                BuiltinVoice(
                    voice = json.optString("voice", ""),
                    group = if (json.has("group")) json.getString("group") else null,
                    promptAudioCodes =
                        json.optJSONArray("prompt_audio_codes")?.let { outer ->
                            List(outer.length()) { index ->
                                val row = outer.getJSONArray(index)
                                IntArray(row.length()) { itemIndex -> row.getInt(itemIndex) }
                            }
                        } ?: emptyList(),
                )
        }
    }

    private data class TtsMeta(
        val files: TtsFiles,
        val onnx: TtsOnnxNames,
    ) {
        companion object {
            fun fromJson(json: JSONObject): TtsMeta =
                TtsMeta(
                    files = TtsFiles.fromJson(json.getJSONObject("files")),
                    onnx = TtsOnnxNames.fromJson(json.getJSONObject("onnx")),
                )
        }
    }

    private data class TtsFiles(
        val prefill: String,
        val decodeStep: String,
        val localFixedSampledFrame: String,
    ) {
        companion object {
            fun fromJson(json: JSONObject): TtsFiles =
                TtsFiles(
                    prefill = json.getString("prefill"),
                    decodeStep = json.getString("decode_step"),
                    localFixedSampledFrame = json.getString("local_fixed_sampled_frame"),
                )
        }
    }

    private data class TtsOnnxNames(
        val decodeInputNames: List<String>,
        val decodeOutputNames: List<String>,
    ) {
        companion object {
            fun fromJson(json: JSONObject): TtsOnnxNames =
                TtsOnnxNames(
                    decodeInputNames = json.getJSONArray("decode_input_names").toStringList(),
                    decodeOutputNames = json.getJSONArray("decode_output_names").toStringList(),
                )

            private fun JSONArray.toStringList(): List<String> = List(length()) { index -> getString(index) }
        }
    }

    private data class CodecMeta(
        val files: CodecFiles,
        val codecConfig: CodecConfig,
    ) {
        companion object {
            fun fromJson(json: JSONObject): CodecMeta =
                CodecMeta(
                    files = CodecFiles.fromJson(json.getJSONObject("files")),
                    codecConfig = CodecConfig.fromJson(json.getJSONObject("codec_config")),
                )
        }
    }

    private data class CodecFiles(
        val decodeFull: String,
    ) {
        companion object {
            fun fromJson(json: JSONObject): CodecFiles = CodecFiles(decodeFull = json.getString("decode_full"))
        }
    }

    private data class CodecConfig(
        val sampleRate: Int,
    ) {
        companion object {
            fun fromJson(json: JSONObject): CodecConfig = CodecConfig(sampleRate = json.getInt("sample_rate"))
        }
    }

    companion object {
        private fun resolveManifestPath(modelRoot: File): File {
            val candidates =
                listOf(
                    File(modelRoot, "browser_poc_manifest.json"),
                    File(modelRoot, "MOSS-TTS-Nano-100M-ONNX/browser_poc_manifest.json"),
                    File(modelRoot, "MOSS-TTS-Nano-ONNX-CPU/browser_poc_manifest.json"),
                )
            return candidates.firstOrNull { it.isFile }
                ?: error("browser_poc_manifest.json not found. Tried: ${candidates.joinToString { it.absolutePath }}")
        }

        private fun readJson(file: File): JSONObject {
            require(file.isFile) { "Missing JSON file: ${file.absolutePath}" }
            return JSONObject(file.readText(Charsets.UTF_8))
        }

        private fun flattenIntTensorValue(raw: Any?): IntArray {
            val values = ArrayList<Int>()

            fun append(value: Any?) {
                when (value) {
                    is Int -> values += value
                    is Long -> values += value.toInt()
                    is Short -> values += value.toInt()
                    is Byte -> values += value.toInt()
                    is IntArray -> values += value.toList()
                    is LongArray -> value.forEach { values += it.toInt() }
                    is ShortArray -> value.forEach { values += it.toInt() }
                    is ByteArray -> value.forEach { values += it.toInt() }
                    is Array<*> -> value.forEach { append(it) }
                    null -> Unit
                    else -> error("Unsupported int tensor value: ${value.javaClass}")
                }
            }
            append(raw)
            return values.toIntArray()
        }

        private fun extractLastHiddenTensor(tensor: OnnxTensor): OnnxTensor {
            val shape = tensor.info.shape
            val hidden =
                when (shape.size) {
                    2 -> {
                        val value = tensor.value as Array<*>
                        value[0] as FloatArray
                    }
                    3 -> {
                        val value = tensor.value as Array<*>
                        val batch = value[0] as Array<*>
                        batch[batch.size - 1] as FloatArray
                    }
                    else -> error("Unexpected global_hidden rank: ${shape.size}")
                }
            return OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(hidden.copyOf()),
                longArrayOf(1, hidden.size.toLong()),
            )
        }

        private fun OrtSession.Result.requiredValue(name: String): OnnxValue =
            get(name).orElseThrow {
                IllegalStateException("Missing ONNX output: $name")
            }

        private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor = requiredValue(name) as OnnxTensor

        private fun OnnxTensor.scalarInt(): Int = flattenIntTensorValue(value).firstOrNull() ?: error("Scalar int tensor is empty")

        private fun OnnxTensor.intArrayValue(): IntArray = flattenIntTensorValue(value)
    }
}
