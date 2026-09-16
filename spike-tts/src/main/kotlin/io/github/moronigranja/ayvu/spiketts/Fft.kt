package io.github.moronigranja.ayvu.spiketts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exact DFT/FFT helpers mirroring numpy.fft semantics needed by the CosyVoice3
 * mel front-ends (n_fft 400, 512, 1920) and the HiFT 16-pt STFT/ISTFT.
 *
 * Arbitrary-length transforms use Bluestein's algorithm (chirp-z) so results are
 * bit-comparable with numpy.fft.rfft/irfft; power-of-two sizes use a radix-2 core.
 */
internal object Fft {
    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    /** In-place iterative radix-2 FFT. `re`/`im` must have a power-of-two length. */
    private fun radix2(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vIm = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nCr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nCr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Full FFT of length n (any n). Overwrites and returns re/im. */
    fun fft(
        re: DoubleArray,
        im: DoubleArray,
    ): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        if (n and (n - 1) == 0) {
            radix2(re, im)
            return re to im
        }
        // Bluestein: X_k = e^{-iπk²/N} * Σ_j (x_j e^{-iπj²/N}) * e^{+iπ(k-j)²/N},
        // computed as a length-m circular convolution (m >= 2n-1, so it equals
        // the linear convolution on the indices we need).
        val m = nextPow2(2 * n - 1)
        val a = DoubleArray(m)
        val ia = DoubleArray(m)
        val b = DoubleArray(m)
        val ib = DoubleArray(m)
        for (j in 0 until n) {
            val w = -PI * (j.toLong() * j) / n
            a[j] = re[j] * cos(w) - im[j] * sin(w)
            ia[j] = re[j] * sin(w) + im[j] * cos(w)
        }
        // Chirp b_t = e^{iπ t²/n} on t in [0, n-1], mirrored e^{iπ (t-m)²/n} on
        // t in [m-n+1, m-1] (covers k-j in [-(n-1), n-1]); unused middle is 0.
        for (j in 0 until m) {
            val t =
                when {
                    j < n -> j.toLong()
                    j > m - n -> (j - m).toLong()
                    else -> continue
                }
            val w = PI * t * t / n
            b[j] = cos(w)
            ib[j] = sin(w)
        }
        radix2(a, ia)
        radix2(b, ib)
        for (j in 0 until m) {
            val nb = a[j] * b[j] - ia[j] * ib[j]
            ia[j] = a[j] * ib[j] + ia[j] * b[j]
            b[j] = nb
        }
        radix2(b, ia)
        val scale = 1.0 / m
        for (j in 0 until n) {
            val yRe = b[j] * scale
            val yIm = ia[j] * scale
            val w = -PI * (j.toLong() * j) / n
            re[j] = yRe * cos(w) - yIm * sin(w)
            im[j] = yRe * sin(w) + yIm * cos(w)
        }
        return re to im
    }

    /** Real-input FFT of length n: returns [re, im] each of size n/2+1. */
    fun rfft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        if (n == 1) return DoubleArray(1) { x[0] } to DoubleArray(1)
        val re = x.copyOf()
        val im = DoubleArray(n)
        fft(re, im)
        val h = n / 2 + 1
        return re.copyOf(h) to im.copyOf(h)
    }

    /** Inverse FFT of a real spectrum (re/im of size n/2+1) with output length n. */
    fun irfft(
        re: DoubleArray,
        im: DoubleArray,
        n: Int,
    ): DoubleArray {
        val fullRe = DoubleArray(n)
        val fullIm = DoubleArray(n)
        val h = re.size
        for (k in 0 until h) {
            fullRe[k] = re[k]
            fullIm[k] = im[k]
        }
        for (k in 1 until (n + 1) / 2) {
            fullRe[n - k] = re[k]
            fullIm[n - k] = -im[k]
        }
        fft(fullRe, fullIm)
        return DoubleArray(n) { fullRe[it] / n }
    }
}
