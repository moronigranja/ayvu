package com.moronigranja.localttsreader.tts

/**
 * Float samples in [-1, 1) → signed 16-bit little-endian PCM (truncation like
 * numpy) — the [SynthesisOutcome.Audio] pcm encoding every engine emits.
 */
internal fun pcm16(audio: FloatArray): ByteArray {
    val out = ByteArray(audio.size * 2)
    var i = 0
    for (sample in audio) {
        val value = (sample * 32767.0).toInt().coerceIn(-32768, 32767)
        out[i++] = (value and 0xFF).toByte()
        out[i++] = ((value shr 8) and 0xFF).toByte()
    }
    return out
}
