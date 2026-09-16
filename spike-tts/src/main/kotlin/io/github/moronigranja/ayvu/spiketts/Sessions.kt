package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Lazy ONNX session manager for CosyVoice3, mirroring sokuji's runtime.py
 * graph inventory and per-graph EP policy (CPU-only for the spike). Sessions
 * are opened on first use and released between pipeline stages so peak native
 * memory stays ~1.3 GB instead of ~3.5 GB — the device (S22 Ultra, 8 GB) is
 * killed by lmkd when all 14 graphs are resident simultaneously, and stage
 * scoping matches how a production player would budget memory anyway.
 */
internal class Sessions(
    private val modelDir: File,
    private val threads: Int,
) : AutoCloseable {
    companion object {
        val GRAPH_FILES =
            mapOf(
                "text_embedding" to "onnx/text_embedding.onnx",
                "speech_tokenizer" to "onnx/speech_tokenizer_v3.onnx",
                "campplus" to "onnx/campplus.onnx",
                "llm_initial" to "onnx/llm_backbone_initial_int4.onnx",
                "llm_decode" to "onnx/llm_backbone_decode_int4.onnx",
                "llm_decoder" to "onnx/llm_decoder.onnx",
                "speech_embedding" to "onnx/llm_speech_embedding.onnx",
                "flow_token_embedding" to "onnx/flow_token_embedding.onnx",
                "flow_spk_projection" to "onnx/flow_speaker_projection.onnx",
                "flow_pre_lookahead" to "onnx/flow_pre_lookahead.onnx",
                "flow_estimator" to "onnx/flow_estimator.onnx",
                "hift_f0" to "onnx/hift_f0_predictor.onnx",
                "hift_source" to "onnx/hift_source_generator.onnx",
                "hift_decoder" to "onnx/hift_decoder.onnx",
            )
        val COLD_GRAPHS = setOf("speech_tokenizer", "campplus")
        val LLM_GROUP =
            setOf("text_embedding", "speech_embedding", "llm_initial", "llm_decode", "llm_decoder")
        val FLOW_GROUP =
            setOf("flow_token_embedding", "flow_spk_projection", "flow_pre_lookahead", "flow_estimator")
        val HIFT_GROUP = setOf("hift_f0", "hift_source", "hift_decoder")
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opened = HashMap<String, OrtSession>()
    val loadTimesMs = HashMap<String, Long>()

    operator fun get(key: String): OrtSession =
        opened.getOrPut(key) {
            val t0 = System.currentTimeMillis()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(threads)
            val session = env.createSession(File(modelDir, GRAPH_FILES.getValue(key)).absolutePath, opts)
            loadTimesMs[key] = System.currentTimeMillis() - t0
            session
        }

    fun release(keys: Set<String>) {
        for (k in keys) opened.remove(k)?.close()
    }

    override fun close() {
        for (s in opened.values) s.close()
        opened.clear()
    }
}
