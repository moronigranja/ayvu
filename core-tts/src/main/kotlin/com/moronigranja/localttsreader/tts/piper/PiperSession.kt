package com.moronigranja.localttsreader.tts.piper

/**
 * The neural half of the Piper pipeline: one inference pass over phoneme ids.
 * The default implementation runs ONNX Runtime (see [OrtPiperSession]); tests
 * implement this seam with fakes.
 */
interface PiperSession : AutoCloseable {
    /**
     * Runs one inference: [phonemeIds] is the mapped id sequence (BOS/PAD
     * framing included) and [scales] is `(noise_scale, length_scale, noise_w)`.
     *
     * @throws IllegalStateException when the session is closed.
     */
    fun infer(phonemeIds: IntArray, scales: FloatArray): FloatArray

    override fun close()
}