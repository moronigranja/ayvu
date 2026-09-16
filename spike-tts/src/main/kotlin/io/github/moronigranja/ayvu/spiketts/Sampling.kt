package io.github.moronigranja.ayvu.spiketts

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * CosyVoice3 official sampler: nucleus top-p/top-k with repetition-aware
 * resampling — port of sokuji's sampling.py (numpy) / official
 * cosyvoice/utils/common.py ras_sampling.
 */
internal object Sampling {
    /** Numerically stable log-softmax over a full vocabulary vector. */
    fun logSoftmax(logits: DoubleArray): DoubleArray {
        var m = Double.NEGATIVE_INFINITY
        for (v in logits) if (v > m) m = v
        var sum = 0.0
        for (v in logits) sum += exp(v - m)
        val logSum = ln(sum)
        return DoubleArray(logits.size) { logits[it] - logSum - m }
    }

    private fun softmax(logp: DoubleArray): DoubleArray {
        var m = logp[0]
        for (v in logp) if (v > m) m = v
        var sum = 0.0
        for (v in logp) sum += exp(v - m)
        return DoubleArray(logp.size) { exp(logp[it] - m) / sum }
    }

    /** Nucleus (top-p) sampling with top-k constraint, stable descending sort. */
    private fun nucleusSample(
        probs: DoubleArray,
        rng: Random,
        topP: Double,
        topK: Int,
    ): Int {
        val order = probs.indices.sortedByDescending { probs[it] }
        var cum = 0.0
        val sel = ArrayList<Int>(topK)
        for (idx in order) {
            if (cum < topP && sel.size < topK) {
                cum += probs[idx]
                sel.add(idx)
            } else {
                break
            }
        }
        var wSum = 0.0
        for (idx in sel) wSum += probs[idx]
        var r = rng.nextDouble() * wSum
        for (idx in sel) {
            r -= probs[idx]
            if (r <= 0.0) return idx
        }
        return sel.last()
    }

    /**
     * Repetition-aware nucleus sampling: if the sampled token appeared too
     * frequently in the recent window, ban it and resample from the full
     * vocabulary.
     */
    fun rasSampling(
        logp: DoubleArray,
        decodedTokens: List<Int>,
        rng: Random,
        topP: Double = 0.8,
        topK: Int = 25,
        winSize: Int = 10,
        tauR: Double = 0.1,
    ): Int {
        val probs = softmax(logp)
        var topId = nucleusSample(probs, rng, topP, topK)
        val window = decodedTokens.subList(maxOf(0, decodedTokens.size - winSize), decodedTokens.size)
        val repNum = window.count { it == topId }
        if (repNum >= winSize * tauR) {
            val banned = logp.copyOf()
            banned[topId] = Double.NEGATIVE_INFINITY
            val p = softmax(banned)
            var r = rng.nextDouble()
            for (i in p.indices) {
                r -= p[i]
                if (r <= 0.0) {
                    topId = i
                    break
                }
            }
        }
        return topId
    }
}
