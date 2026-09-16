package io.github.moronigranja.ayvu.tts.kokoro

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Mono port of librosa's `effects.trim` as extracted into kokoro-onnx
 * (`trim.py`, MIT) — leading and trailing silence removal by frame RMS
 * relative to the loudest frame, float32 arithmetic like the reference.
 *
 * Returns the trimmed signal and the `[start, end)` sample range it came from;
 * a fully silent signal trims to an empty range `[0, 0)` (librosa semantics).
 */
object AudioTrim {
    fun trim(
        y: FloatArray,
        topDb: Float = 60f,
        frameLength: Int = 2048,
        hopLength: Int = 512,
    ): Pair<FloatArray, IntRange> {
        val nonSilent = signalToFrameNonSilent(y, frameLength, hopLength, topDb)

        val first = nonSilent.indexOfFirst { it }
        if (first < 0) return FloatArray(0) to (0 until 0)

        val start = first * hopLength
        val end = minOf(y.size, (nonSilent.indexOfLast { it } + 1) * hopLength)
        return y.copyOfRange(start, end) to (start until end)
    }

    private fun signalToFrameNonSilent(
        y: FloatArray,
        frameLength: Int,
        hopLength: Int,
        topDb: Float,
    ): BooleanArray {
        val padding = frameLength / 2
        val padded = FloatArray(y.size + 2 * padding)
        y.copyInto(padded, padding)

        val nFrames = 1 + (padded.size - frameLength) / hopLength
        val db = FloatArray(nFrames)
        var peak = 0.0f
        for (frame in 0 until nFrames) {
            val offset = frame * hopLength
            var sum = 0.0
            for (i in 0 until frameLength) {
                val sample = padded[offset + i]
                sum += sample.toDouble() * sample
            }
            val rms = sqrt(sum / frameLength).toFloat()
            if (rms > peak) peak = rms
            db[frame] = rms
        }

        // amplitude_to_db(rms, ref=max, amin=1e-5, top_db=None):
        // 20*log10(max(amin, rms)) - 20*log10(max(amin, ref))
        val amin = 1e-5f
        val refDb = 20f * ln(maxOf(amin, peak).toDouble() / amin).toFloat() / ln(10f).toFloat()
        return BooleanArray(nFrames) { frame ->
            val rms = maxOf(amin, db[frame])
            val frameDb = 20f * ln(rms.toDouble() / amin).toFloat() / ln(10f).toFloat()
            frameDb - refDb > -topDb
        }
    }
}
