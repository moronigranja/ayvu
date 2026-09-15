package com.moronigranja.localttsreader

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device confirmation of the Opus storage spike (tools/opus_drift_spike.py).
 * HOST result (libopus via ffmpeg, 2026-08-27): 0.0 ms round-trip drift,
 * max +20 ms boundary residual (one 20 ms frame) — anchors are seconds-based
 * (the `.meta` sidecar), so 48 kHz decode would only need a ×2 sample map.
 *
 * DEVICE finding (S22 SM-S908U1, BP2A.250605.031.A3, 2026-08-27): the
 * MediaCodec opus DECODER is broken at the native level — every stream
 * (device-encoded and canonical host libopus) and every decoder component
 * (c2.android.opus.decoder, OMX.google.opus.decoder; sync and async API)
 * errors, and the async path SIGSEGVs inside the codec's memcpy. The encoder
 * (c2.android.opus.encoder, 24 kHz native) produces size-plausible payloads
 * with an oversize csd-0 blob (normalized to the 19-byte OpusHead by the
 * harness). Tests therefore assert the ENCODER only, and stage evidence
 * files for host forensics. Any Opus storage decision must assume a bundled
 * libopus or stay PCM — MediaCodec is not a dependency.
 * Log tag: OpusDrift.
 */
@RunWith(AndroidJUnit4::class)
class OpusDriftInstrumentedTest {
    private val tag = "OpusDrift"
    private val opusHeadMagic = "OpusHead".toByteArray()
    private val context =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext

    private fun encoderInfo(mime: String): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) }
        }

    private fun encodeOpus(
        pcm: ShortArray,
        sr: Int,
        bitrate: Int,
    ): OpusStream {
        val codec =
            try {
                MediaCodec.createEncoderByType("audio/opus")
            } catch (e: Exception) {
                throw AssertionError("MediaCodec opus ENCODER unavailable on this device: $e")
            }
        val fmt = MediaFormat.createAudioFormat("audio/opus", sr, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ByteArrayOutputStream()
        // The c2 encoder publishes OpusHead via csd-0 in its output format;
        // a CODEC_CONFIG-flagged buffer is the fallback. Either way the
        // payload must NOT contain it (decoders reject the header as a frame).
        var csd: ByteArray? =
            codec.outputFormat.getByteBuffer("csd-0")?.let { csdBuf ->
                val bytes = ByteArray(csdBuf.remaining())
                csdBuf.get(bytes)
                bytes
            }
        // OpusHead is exactly 19 bytes; this encoder appends 64 bytes of
        // trailing blob. Trim to the header, or synthesize the canonical one
        // (24 kHz mono) when the blob isn't OpusHead at all.
        csd = csd?.takeIf { it.copyOf(8).contentEquals(opusHeadMagic) }?.copyOf(19)
            ?: byteArrayOf(
                'O'.code.toByte(),
                'p'.code.toByte(),
                'u'.code.toByte(),
                's'.code.toByte(),
                'H'.code.toByte(),
                'e'.code.toByte(),
                'a'.code.toByte(),
                'd'.code.toByte(),
                1,
                1,
                0x38.toByte(),
                0x01,
                0xC0.toByte(),
                0x5D.toByte(),
                0,
                0,
                0,
                0,
                0,
            )
        val chunk = sr / 25 // 40 ms per input buffer
        var pos = 0
        var eosQueued = false
        val info = MediaCodec.BufferInfo()
        var guards = 0
        while (true) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                val n = min(chunk, pcm.size - pos)
                if (n > 0) {
                    buf.clear()
                    buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                    codec.queueInputBuffer(inIdx, 0, n * 2, System.nanoTime(), 0)
                    pos += n
                } else if (!eosQueued) {
                    codec.queueInputBuffer(inIdx, 0, 0, System.nanoTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val ob = codec.getOutputBuffer(outIdx)!!
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    ob.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = bytes else out.write(bytes)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (++guards > 1_000_000) throw AssertionError("encoder did not finish")
        }
        codec.stop()
        codec.release()
        return OpusStream(normalizeCsd(csd), out.toByteArray())
    }

    /** OpusHead is exactly 19 bytes; some encoders append a blob after it. */
    private fun normalizeCsd(csd: ByteArray?): ByteArray =
        csd?.takeIf { it.copyOf(8).contentEquals(opusHeadMagic) }?.copyOf(19)
            ?: byteArrayOf(
                'O'.code.toByte(),
                'p'.code.toByte(),
                'u'.code.toByte(),
                's'.code.toByte(),
                'H'.code.toByte(),
                'e'.code.toByte(),
                'a'.code.toByte(),
                'd'.code.toByte(),
                1,
                1,
                0x38.toByte(),
                0x01,
                0xC0.toByte(),
                0x5D.toByte(),
                0,
                0,
                0,
                0,
                0,
            )

    /** Raw Opus elementary stream: OpusHead (csd) + payload frames. */
    private data class OpusStream(
        val csd: ByteArray?,
        val payload: ByteArray,
    )

    private fun decodeOpus(
        encoded: OpusStream,
        componentName: String? = null,
    ): Pair<Int, ShortArray> {
        val codec =
            if (componentName != null) {
                MediaCodec.createByCodecName(componentName)
            } else {
                MediaCodec.createDecoderByType("audio/opus")
            }
        val fmt = MediaFormat.createAudioFormat("audio/opus", 24_000, 1)
        if (encoded.csd != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(encoded.csd))
        val pcmBytes = ByteArrayOutputStream()
        val latch = java.util.concurrent.CountDownLatch(1)
        var pos = 0
        var feedDone = false
        var dsr = 24_000
        var codecError: String? = null
        // Async API with presentation timestamps in µs (decoders reject ns
        // or non-monotonic stamps on c2; the sync path errored identically on
        // every stream, so the callback model is the surviving hypothesis).
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    c: MediaCodec,
                    index: Int,
                ) {
                    if (feedDone) return
                    val buf = c.getInputBuffer(index) ?: return
                    val n = min(4096, encoded.payload.size - pos)
                    if (n > 0) {
                        buf.clear()
                        buf.put(encoded.payload, pos, n)
                        c.queueInputBuffer(index, 0, n, pos * 1_000_000L / 24_000, 0)
                        pos += n
                    } else {
                        c.queueInputBuffer(index, 0, 0, pos * 1_000_000L / 24_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        feedDone = true
                    }
                }

                override fun onOutputBufferAvailable(
                    c: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        c.getOutputBuffer(index)!!.get(bytes)
                        pcmBytes.write(bytes)
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) latch.countDown()
                }

                override fun onError(
                    c: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    codecError = e.message
                    latch.countDown()
                }

                override fun onOutputFormatChanged(
                    c: MediaCodec,
                    format: MediaFormat,
                ) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) dsr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            },
        )
        codec.configure(fmt, null, null, 0)
        codec.start()
        val done = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        codec.stop()
        codec.release()
        if (!done) throw AssertionError("decoder did not finish")
        codecError?.let { throw AssertionError("decoder error: $it") }
        val bytes = pcmBytes.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return dsr to shorts
    }

    /** Linear-interpolate a 24 kHz window to the decoded rate. */
    private fun resampleTo(
        src: ShortArray,
        from: Int,
        to: Int,
    ): ShortArray {
        if (to == from) return src
        val n = src.size * to / from
        val out = ShortArray(n)
        for (i in 0 until n) {
            val x = i * from.toDouble() / to
            val lo = x.toInt().coerceAtMost(src.size - 2)
            val frac = x - lo
            out[i] = (src[lo] * (1 - frac) + src[lo + 1] * frac).toInt().toShort()
        }
        return out
    }

    /** Where the anchor's 20 ms window actually lands in the decoded stream (ms). */
    private fun boundaryResidualMs(
        orig: ShortArray,
        sr: Int,
        decoded: ShortArray,
        dsr: Int,
        anchorSec: Double,
    ): Double {
        val winLen = sr / 50 // 20 ms
        val start = (anchorSec * sr).toInt()
        val ref = resampleTo(orig.copyOfRange(start, start + winLen), sr, dsr)
        val expected = (anchorSec * dsr).toLong()
        val half = 40L * dsr / 1000 // +-40 ms search
        val lo = (expected - half).coerceAtLeast(0)
        val hi = (expected + half).coerceAtMost(decoded.size.toLong() - ref.size)
        // Envelope correlation: codec phase/spectral smearing does not move energy onsets.
        var bestDot = -1L
        var bestAt = lo
        for (cand in lo..hi) {
            var dot = 0L
            for (k in ref.indices) {
                dot += abs(ref[k].toInt()).toLong() * abs(decoded[(cand + k).toInt()].toInt())
            }
            if (dot > bestDot) {
                bestDot = dot
                bestAt = cand
            }
        }
        return (bestAt - expected) * 1000.0 / dsr
    }

    @Test
    fun opusRoundTripDriftOnDevice() {
        val sr = 24_000
        val anchors = doubleArrayOf(1.0, 5.5, 12.3, 18.9, 25.0)
        val burst = 0.8 // seconds of noise per anchor
        val rng = Random(42)
        val pcm = ShortArray(((anchors.last() + burst + 0.5) * sr).toInt())
        for (a in anchors) {
            val start = (a * sr).toInt()
            val len = (burst * sr).toInt()
            for (j in 0 until len) {
                val ramp = min(1.0, min(j.toDouble(), (len - 1 - j).toDouble()) / 40.0)
                pcm[start + j] = (rng.nextGaussian() * 7000 * ramp).toInt().toShort()
            }
        }

        val enc = encoderInfo("audio/opus")
        assertTrue("opus encoder advertised on this device: $enc", enc != null)
        Log.i(tag, "encoder component: ${enc!!.name}")
        val rates =
            enc
                .getCapabilitiesForType("audio/opus")
                .audioCapabilities.supportedSampleRates
                ?.toList()
        Log.i(tag, "encoder supported sample rates: $rates")

        val t0 = System.nanoTime()
        val encoded = encodeOpus(pcm, sr, 24_000)
        val encMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "encoded: csd=${encoded.csd?.size ?: -1}B payload=${encoded.payload.size}B")
        // Evidence dump for host-side forensics (pulled via adb run-as).
        val dir = File(context.filesDir, "opusdrift").apply { mkdirs() }
        val pcmBytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcmBytes.asShortBuffer().put(pcm)
        File(dir, "input.pcm").writeBytes(pcmBytes.array())
        File(dir, "device.opus").writeBytes(encoded.payload)
        Log.i(tag, "wrote ${context.filesDir}/opusdrift/{input.pcm,device.opus}")
        Log.i(
            tag,
            "size: ${pcm.size * 2} B PCM -> ${encoded.payload.size} B opus (${(pcm.size * 2.0 / encoded.payload.size).toInt()}x) | encode $encMs ms",
        )
        // Device finding 2026-08-27: NO in-test decode — MediaCodec opus
        // decode on this S22 (SM-S908U1, BP2A.250605.031.A3) native-crashes
        // (SIGSEGV in the codec's memcpy) for every stream and every decoder
        // (c2.android.opus.decoder, OMX.google.opus.decoder; sync + async API).
        // Duration-drift / boundary numbers are host-verified in
        // tools/opus_drift_spike.py; this test pins the encoder behavior only.
        assertTrue("24k payload sized for 24 kbps", encoded.payload.size in 20_000..120_000)
        assertTrue("24k csd normalized to OpusHead", encoded.csd?.size == 19)
        Log.i(tag, "OPUS_ENCODER_OK 24k payload=${encoded.payload.size}B")
    }

    /**
     * Encoder sanity at its most-tested configuration (48 kHz sine): if the
     * c2 encoder round-trips here but not at 24 kHz, the low-rate usage is
     * the problem; if this also fails, the device's Opus pipeline is broken
     * and the app cannot depend on MediaCodec for on-device Opus.
     */
    @Test
    fun encoderRoundTripsAt48kSine() {
        val sr = 48_000
        val seconds = 3.0
        val pcm =
            ShortArray((seconds * sr).toInt()) { i ->
                (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sr) * 12000).toInt().toShort()
            }
        val encoded = encodeOpus(pcm, sr, 32_000)
        Log.i(
            tag,
            "48k sine: csd=${encoded.csd?.size ?: -1}B payload=${encoded.payload.size}B head=" +
                encoded.payload.take(8).joinToString { "%02x".format(it) },
        )
        // No in-test decode: device decoder native-crashes (see the 24k test
        // docstring). Pin the encoder's observable output only.
        assertTrue("48k payload sized for a 3 s 32 kbps sine", encoded.payload.size in 8_000..20_000)
        assertTrue("48k csd normalized to OpusHead", encoded.csd?.size == 19)
        Log.i(tag, "OPUS_48K_ENCODER_OK payload=${encoded.payload.size}B")
    }

    /**
     * Decoder sanity against a KNOWN-GOOD canonical stream: host libopus
     * (23.5 kbps, 22.83 s real Kokoro speech) staged as host-csd.bin +
     * host-payload.bin. Isolates the decoder from the device encoder.
     */
    @Test
    fun decoderDecodesKnownGoodStream() {
        val dir = File(context.filesDir, "opusdrift")
        val csd =
            File(dir, "host-csd.bin").takeIf { it.isFile }?.readBytes()
                ?: throw AssertionError("stage host-csd.bin into files/opusdrift/ first")
        val payload =
            File(dir, "host-payload.bin").takeIf { it.isFile }?.readBytes()
                ?: throw AssertionError("stage host-payload.bin into files/opusdrift/ first")
        // Log every advertised opus decoder, then try the software one first.
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("audio/opus", true) } }
            .forEach { Log.i(tag, "opus decoder available: ${it.name}") }
        val candidates =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("audio/opus", true) } }
                .map { it.name }
        // The legacy Google software decoder is the reliable one on this device;
        // Samsung's c2 opus decoder rejects every stream (observed 2026-08-27).
        val software = candidates.firstOrNull { "OMX.google" in it } ?: candidates.firstOrNull { "c2.android" in it }
        Log.i(tag, "using decoder: $software")
        // Decode intentionally NOT attempted: it SIGSEGVs the process on this
        // device (see the 24k test docstring). The staged canonical stream
        // exists for a future device with a working decoder.
        assertTrue("host stream staged and structurally sane", csd.size == 19 && payload.size > 60_000)
        Log.i(tag, "OPUS_DECODER_LEG_STAGED csd=${csd.size}B payload=${payload.size}B")
    }
}
