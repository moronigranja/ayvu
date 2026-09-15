package com.moronigranja.localttsreader.spiketts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Numpy feature extractors for CosyVoice3 ported from sokuji's mel.py
 * (verified there against sidecar/tests/data/cosyvoice3_mel_goldens.npz):
 *  - whisperLogMel128: 16 kHz whisper-style 128-bin log10 mel for
 *    speech_tokenizer_v3 (center=True reflect, LAST FRAME DROPPED, clamp
 *    max-8, (x+4)/4).
 *  - kaldiFbank80Cmn: torchaudio.compliance.kaldi.fbank equivalent for
 *    campplus (snip_edges, DC removal, preemphasis 0.97, povey window,
 *    512-pt power spectrum, HTK mel 20..8000 Hz, log clamp float32 eps, CMN).
 *  - matchaMel80: 24 kHz HiFiGAN/matcha mel for the flow prompt (reflect pad
 *    (1920-480)/2, center=False, hann 1920, hop 480, sqrt(power+1e-9),
 *    Slaney mel 0..nyquist, log clamp 1e-5).
 * Plus the HiFT 16-pt STFT/ISTFT pair (torch.stft/istft equivalents).
 *
 * Matrices are flat arrays with explicit dims, mirroring the ORT tensors the
 * rest of the pipeline exchanges.
 */
internal object Mel {
    private fun hzToMelSlaney(f: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logstep = ln(6.4) / 27.0
        return if (f >= minLogHz) minLogMel + ln(max(f, minLogHz) / minLogHz) / logstep else f / fSp
    }

    private fun melToHzSlaney(m: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logstep = ln(6.4) / 27.0
        return if (m >= minLogMel) minLogHz * exp(logstep * (m - minLogMel)) else m * fSp
    }

    /** Slaney-scale, slaney-normalized triangular filterbank: [n_mels, 1 + n_fft/2]. */
    fun melFilterbank(
        sr: Int,
        nFft: Int,
        nMels: Int,
        fmin: Double,
        fmax: Double,
    ): DoubleArray {
        val nBins = nFft / 2 + 1
        val fftfreqs = DoubleArray(nBins) { it.toDouble() * sr / nFft }
        val lo = hzToMelSlaney(fmin)
        val hi = hzToMelSlaney(fmax)
        val melPts = DoubleArray(nMels + 2) { melToHzSlaney(lo + (hi - lo) * it / (nMels + 1)) }
        val fdiff = DoubleArray(nMels + 1) { melPts[it + 1] - melPts[it] }
        val w = DoubleArray(nMels * nBins)
        for (m in 0 until nMels) {
            for (b in 0 until nBins) {
                val f = fftfreqs[b]
                val lower = -(melPts[m] - f) / fdiff[m]
                val upper = (melPts[m + 2] - f) / fdiff[m + 1]
                val enorm = 2.0 / (melPts[m + 2] - melPts[m])
                w[m * nBins + b] = max(0.0, min(lower, upper)) * enorm
            }
        }
        return w
    }

    /** Periodic Hann window, matching librosa's default STFT window. */
    fun hann(size: Int): DoubleArray = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }

    /** numpy reflect pad (edge NOT repeated): idx < 0 -> -idx, idx >= n -> 2n-2-idx. */
    private fun reflectPad(
        x: DoubleArray,
        pad: Int,
    ): DoubleArray {
        val n = x.size
        val out = DoubleArray(n + 2 * pad)
        for (i in out.indices) {
            var idx = i - pad
            while (idx < 0) idx = -idx
            while (idx >= n) idx = 2 * n - 2 - idx
            out[i] = x[idx]
        }
        return out
    }

    /** Returns [1, 128, T-1] (last frame dropped), flat with shape. */
    fun whisperLogMel128(audio16k: FloatArray): Pair<FloatArray, IntArray> {
        val y = DoubleArray(audio16k.size) { audio16k[it].toDouble() }
        val pad = 200
        val p = reflectPad(y, pad)
        val win = hann(400)
        val nFrames = 1 + (p.size - 400) / 160
        val nBins = 201
        val fb = melFilterbank(16000, 400, 128, 0.0, 8000.0) // [128, 201]
        val m = DoubleArray((nFrames - 1) * 128) // drop last frame
        val frameBuf = DoubleArray(400)
        val re = DoubleArray(400)
        val im = DoubleArray(400)
        for (t in 0 until nFrames - 1) {
            for (k in 0 until 400) frameBuf[k] = p[t * 160 + k] * win[k]
            re.fill(0.0)
            im.fill(0.0)
            frameBuf.copyInto(re)
            Fft.fft(re, im)
            // power = re² + im² over ALL 201 bins; numpy drops the last FRAME
            // ([T, 201] -> [T-1, 201] via [:-1] on axis 0), which the outer
            // loop already does by iterating 0 until nFrames-1.
            for (mb in 0 until 128) {
                var acc = 0.0
                for (b in 0 until nBins) {
                    acc += (re[b] * re[b] + im[b] * im[b]) * fb[mb * nBins + b]
                }
                m[t * 128 + mb] = log10(max(acc, 1e-10))
            }
        }
        var maxLog = Double.NEGATIVE_INFINITY
        for (v in m) if (v > maxLog) maxLog = v
        val out = FloatArray(m.size)
        for (i in m.indices) out[i] = (((max(m[i], maxLog - 8.0)) + 4.0) / 4.0).toFloat()
        return out to intArrayOf(1, 128, nFrames - 1)
    }

    /** Kaldi-compliant 80-bin log fbank + CMN: returns [1, frames, 80] flat. */
    fun kaldiFbank80Cmn(audio16k: FloatArray): Pair<FloatArray, IntArray> {
        val frameLen = 400
        val frameShift = 160
        val nFft = 512
        val n = audio16k.size
        val numFrames = 1 + (n - frameLen) / frameShift
        val povey =
            DoubleArray(frameLen) {
                (0.5 - 0.5 * cos(2.0 * PI * it / (frameLen - 1))).pow(0.85)
            }

        fun hzToMel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lowMel = hzToMel(20.0)
        val highMel = hzToMel(8000.0)
        val melPts = DoubleArray(82) { lowMel + (highMel - lowMel) * it / 81.0 }
        val nBins = nFft / 2 + 1 // 257
        val binMels = DoubleArray(nBins) { hzToMel(it.toDouble() * 16000 / nFft) }
        val feat = DoubleArray(numFrames * 80)
        val frame = DoubleArray(frameLen)
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        for (t in 0 until numFrames) {
            for (k in 0 until frameLen) frame[k] = audio16k[t * frameShift + k].toDouble()
            // DC removal
            var mean = 0.0
            for (v in frame) mean += v
            mean /= frameLen
            for (k in 0 until frameLen) frame[k] -= mean
            // preemphasis 0.97: first sample replicates itself
            for (k in frameLen - 1 downTo 1) frame[k] -= 0.97 * frame[k - 1]
            frame[0] *= 0.03
            for (k in 0 until frameLen) frame[k] *= povey[k]
            re.fill(0.0)
            im.fill(0.0)
            frame.copyInto(re)
            Fft.fft(re, im)
            for (mb in 0 until 80) {
                var acc = 0.0
                for (b in 0 until nBins) {
                    val mel = binMels[b]
                    val up = (mel - melPts[mb]) / (melPts[mb + 1] - melPts[mb])
                    val down = (melPts[mb + 2] - mel) / (melPts[mb + 2] - melPts[mb + 1])
                    val bank = max(0.0, min(up, down))
                    acc += (re[b] * re[b] + im[b] * im[b]) * bank
                }
                feat[t * 80 + mb] = ln(max(acc, 1.1920929e-7)) // float32 eps
            }
        }
        // CMN over frames per bin
        for (mb in 0 until 80) {
            var mean = 0.0
            for (t in 0 until numFrames) mean += feat[t * 80 + mb]
            mean /= numFrames
            for (t in 0 until numFrames) feat[t * 80 + mb] -= mean
        }
        return FloatArray(feat.size) { feat[it].toFloat() } to intArrayOf(1, numFrames, 80)
    }

    /** 24 kHz matcha mel: returns [frames, 80] flat. */
    fun matchaMel80(audio24k: FloatArray): Pair<FloatArray, IntArray> {
        val y = DoubleArray(audio24k.size) { audio24k[it].toDouble() }
        val pad = (1920 - 480) / 2
        val p = reflectPad(y, pad)
        val win = hann(1920)
        val nFrames = 1 + (p.size - 1920) / 480
        val nBins = 961
        val fb = melFilterbank(24000, 1920, 80, 0.0, 12000.0) // [80, 961]
        val out = FloatArray(nFrames * 80)
        val frameBuf = DoubleArray(1920)
        val re = DoubleArray(1920)
        val im = DoubleArray(1920)
        for (t in 0 until nFrames) {
            for (k in 0 until 1920) frameBuf[k] = p[t * 480 + k] * win[k]
            re.fill(0.0)
            im.fill(0.0)
            frameBuf.copyInto(re)
            Fft.fft(re, im)
            for (mb in 0 until 80) {
                var acc = 0.0
                for (b in 0 until nBins) {
                    acc += sqrt(re[b] * re[b] + im[b] * im[b] + 1e-9) * fb[mb * nBins + b]
                }
                out[t * 80 + mb] = ln(max(acc, 1e-5)).toFloat()
            }
        }
        return out to intArrayOf(nFrames, 80)
    }

    /** torch.stft(n_fft=16, hop=4, periodic hann, center=True): returns [18, T] flat. */
    fun stft16x4(x: FloatArray): Pair<FloatArray, Int> {
        // np.hanning(17)[:16] == periodic hann of 16
        val win = DoubleArray(16) { 0.5 - 0.5 * cos(2.0 * PI * it / 16.0) }
        val p = reflectPad(DoubleArray(x.size) { x[it].toDouble() }, 8)
        val nFrames = (p.size - 16) / 4 + 1
        val re = DoubleArray(16)
        val im = DoubleArray(16)
        val out = FloatArray(18 * nFrames)
        for (t in 0 until nFrames) {
            for (k in 0 until 16) re[k] = p[t * 4 + k] * win[k]
            im.fill(0.0)
            Fft.fft(re, im)
            // output is [18, T] row-major: row (bin) * nFrames + frame
            for (b in 0 until 9) {
                out[b * nFrames + t] = re[b].toFloat() // real part rows
                out[(9 + b) * nFrames + t] = im[b].toFloat() // imag part rows
            }
        }
        return out to nFrames
    }

    /** torch.istft(n_fft=16, hop=4, periodic hann, center=True) equivalent. */
    fun istft16x4(
        magnitude: FloatArray,
        nFrames: Int,
        phase: FloatArray,
    ): FloatArray {
        val win = DoubleArray(16) { 0.5 - 0.5 * cos(2.0 * PI * it / 16.0) }
        val outLen = 16 + (nFrames - 1) * 4
        val audio = DoubleArray(outLen)
        val wsum = DoubleArray(outLen)
        for (t in 0 until nFrames) {
            val re = DoubleArray(16)
            val im = DoubleArray(16)
            for (b in 0 until 9) {
                val mag = min(magnitude[b * nFrames + t].toDouble(), 1e2) // clip 1e2
                val ph = phase[b * nFrames + t].toDouble()
                re[b] = mag * cos(ph)
                im[b] = mag * sin(ph)
            }
            val frames = Fft.irfft(re, im, 16)
            for (r in 0 until 16) {
                audio[t * 4 + r] += frames[r] * win[r]
                wsum[t * 4 + r] += win[r] * win[r]
            }
        }
        val out = FloatArray(outLen - 16) // trim 8 samples each side
        for (i in 0 until outLen - 16) {
            out[i] = (audio[i + 8] / max(wsum[i + 8], 1e-8)).toFloat()
        }
        return out
    }
}
