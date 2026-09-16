package io.github.moronigranja.ayvu.tts.kokoro

/**
 * The neural half of the Kokoro pipeline: one inference pass over a window of
 * tokens. The default implementation runs ONNX Runtime (see
 * [OrtKokoroSession]); tests and other runtimes implement this seam.
 *
 * Mirrors kokoro-onnx's `_infer`: the caller passes the *unpadded* token
 * window and the style row for its length; the implementation applies the
 * graph's [0, *tokens, 0] BOS/EOS padding and the speed tensor, and returns
 * the flattened waveform plus frame durations when the graph reports them.
 */
interface KokoroSession : AutoCloseable {
    /** The vocabulary embedded in the graph's metadata, empty when absent. */
    val embeddedVocab: Map<Char, Int>

    /** True when the graph carries a per-token `duration` output (the pinned v1.1 export does). */
    val hasTimings: Boolean

    /**
     * Runs one inference window.
     *
     * @throws IllegalStateException when the session is closed.
     */
    fun infer(
        tokens: IntArray,
        styleRow: FloatArray,
        speed: Double,
    ): InferResult

    override fun close()
}

data class InferResult(
    val audio: FloatArray,
    /** Frame count per token (BOS/EOS pads included), null without a duration output. */
    val duration: IntArray?,
)
