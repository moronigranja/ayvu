package io.github.moronigranja.ayvu.spiketts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader (PCM 16/32-bit, mono/stereo) and 16-bit writer. */
internal object Wav {
    fun read(file: File): FloatArray {
        // WAVE chunks are little-endian; the ASCII magics appear reversed in LE ints.
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(buf.int == 0x46464952) { "not RIFF" } // 'RIFF'
        buf.int // chunk size
        check(buf.int == 0x45564157) { "not WAVE" } // 'WAVE'
        var numChannels = 1
        var sampleRate = 0
        var bits = 16
        var dataSize = 0
        var dataPos = -1
        while (buf.remaining() >= 8) {
            val id = buf.int
            val size = buf.int
            when (id) {
                0x20746D66 -> { // 'fmt '
                    val audioFormat = buf.short.toInt()
                    check(audioFormat == 1 || audioFormat == 3) { "unsupported PCM format $audioFormat" }
                    numChannels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bits = buf.short.toInt()
                    if (size > 16) buf.position(buf.position() + (size - 16))
                }
                0x61746164 -> { // 'data'
                    dataSize = size
                    dataPos = buf.position()
                    break
                }
                else -> buf.position(buf.position() + size)
            }
        }
        check(dataPos >= 0 && sampleRate > 0) { "no data chunk" }
        buf.position(dataPos)
        val nFrames = dataSize / (numChannels * bits / 8)
        val out = FloatArray(nFrames)
        when (bits) {
            16 -> for (i in 0 until nFrames) {
                var sum = 0.0
                for (c in 0 until numChannels) sum += buf.short / 32768.0
                out[i] = (sum / numChannels).toFloat()
            }
            32 -> for (i in 0 until nFrames) {
                var sum = 0.0
                for (c in 0 until numChannels) sum += buf.float
                out[i] = (sum / numChannels).toFloat()
            }
            else -> throw IllegalStateException("unsupported bit depth $bits")
        }
        return out
    }

    fun write(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
    ) {
        val dataSize = samples.size * 2
        val alloc = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        alloc.putInt(0x46464952) // 'RIFF'
        alloc.putInt(36 + dataSize)
        alloc.putInt(0x45564157) // 'WAVE'
        alloc.putInt(0x20746D66) // 'fmt '
        alloc.putInt(16)
        alloc.putShort(1) // PCM
        alloc.putShort(1) // mono
        alloc.putInt(sampleRate)
        alloc.putInt(sampleRate * 2)
        alloc.putShort(2)
        alloc.putShort(16)
        alloc.putInt(0x61746164) // 'data'
        alloc.putInt(dataSize)
        for (s in samples) {
            alloc.putShort(((s.coerceIn(-1f, 1f) * 32767).toInt().toShort()).toInt().toShort())
        }
        file.writeBytes(alloc.array())
    }
}
