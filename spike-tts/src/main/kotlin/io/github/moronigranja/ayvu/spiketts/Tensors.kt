package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Small helpers for exchanging flat arrays with ORT tensors. */
internal object Tensors {
    fun f32(
        env: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    fun i64(
        env: OrtEnvironment,
        data: LongArray,
        shape: LongArray,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    fun i32(
        env: OrtEnvironment,
        data: IntArray,
        shape: LongArray,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    /** Read a float tensor's payload (copies remaining floats). */
    fun toF32(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    /**
     * Read an int64 tensor's payload, tolerating an int32 graph output. ORT's
     * Java binding materializes int32 tensors as nested Java arrays
     * (int[]/int[][]) while int64 comes back as a LongBuffer.
     */
    fun toI64(t: OnnxTensor): LongArray {
        val value = t.value
        return when (value) {
            is LongBuffer -> {
                val out = LongArray(value.remaining())
                value.get(out)
                out
            }
            is java.nio.IntBuffer -> {
                val out = LongArray(value.remaining())
                for (i in out.indices) out[i] = value.get(i).toLong()
                out
            }
            is IntArray -> LongArray(value.size) { value[it].toLong() }
            is Array<*> -> flattenNested(value)
            else -> throw IllegalStateException("unexpected int tensor type: ${value?.javaClass}")
        }
    }

    private fun flattenNested(value: Array<*>): LongArray {
        val out = ArrayList<Long>()

        fun walk(v: Any?) {
            when (v) {
                is IntArray -> for (x in v) out.add(x.toLong())
                is LongArray -> for (x in v) out.add(x)
                is ShortArray -> for (x in v) out.add(x.toLong())
                is Array<*> -> for (e in v) walk(e)
                else -> throw IllegalStateException("unsupported nested int tensor leaf: ${v?.javaClass}")
            }
        }
        walk(value)
        return out.toLongArray()
    }

    fun shape(t: OnnxTensor): LongArray = t.info.shape

    fun total(shape: LongArray): Long {
        var n = 1L
        for (s in shape) n *= s
        return n
    }

    /**
     * numpy concat([t, zeros_like(t)], axis=0): doubles the batch dim, second
     * half zeroed. Returns the flat data + new shape.
     */
    fun concatZeroAlongBatch(
        data: FloatArray,
        shape: LongArray,
    ): Pair<FloatArray, LongArray> {
        val newShape = shape.copyOf()
        newShape[0] *= 2
        val out = FloatArray(data.size * 2)
        data.copyInto(out, 0)
        return out to newShape
    }

    /** Slice the last seq position of [B, L, D] (axis 1, -1:1): returns [B, 1, D]. */
    fun lastPos(
        data: FloatArray,
        shape: LongArray,
    ): Pair<FloatArray, LongArray> {
        val b = shape[0].toInt()
        val l = shape[1].toInt()
        val d = shape[2].toInt()
        val out = FloatArray(b * d)
        for (i in 0 until b) {
            for (k in 0 until d) {
                out[i * d + k] = data[(i * l + (l - 1)) * d + k]
            }
        }
        return out to longArrayOf(b.toLong(), 1L, d.toLong())
    }

    /** Transpose [B, L, D] -> [B, D, L] (numpy x.transpose(0, 2, 1)). */
    fun transposeBLDToBDL(
        data: FloatArray,
        shape: LongArray,
    ): Pair<FloatArray, LongArray> {
        val b = shape[0].toInt()
        val l = shape[1].toInt()
        val d = shape[2].toInt()
        val out = FloatArray(b * d * l)
        for (i in 0 until b) {
            for (j in 0 until l) {
                for (k in 0 until d) {
                    out[(i * d + k) * l + j] = data[(i * l + j) * d + k]
                }
            }
        }
        return out to longArrayOf(b.toLong(), d.toLong(), l.toLong())
    }
}
