package com.moronigranja.localttsreader.tts.kokoro

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToLong

/**
 * [KokoroSession] over ONNX Runtime — the same graph contract kokoro-onnx
 * consumes (v1.1 exports): `input_ids` int64 `[1, N]` (older exports name it
 * "tokens"), `style` float32 `[1, 256]`, `speed` float32 `[1]` (some exports
 * want an integer speed); outputs `waveform` float32 `[N]` plus `duration`
 * int64 when the graph was exported with timings.
 *
 * The runtime itself is deliberately NOT a dependency here: core-tts compiles
 * against the ORT Java API (compileOnly) and the host/device provides the
 * platform artifact — the JVM jar for tests and benchmarks, the
 * onnxruntime-android AAR inside the app. Synthesizing is CPU-bound and
 * thread-safe; the engine serializes per-window calls but sessions may be
 * shared across engines.
 */
class OrtKokoroSession private constructor(
    private val session: OrtSession,
    override val embeddedVocab: Map<Char, Int>,
    override val hasTimings: Boolean,
    private val tokenInputName: String,
    private val speedTensor: SpeedTensorKind,
    /** Static-graph window width (kokoro-hexagon P1): input_ids pinned to [1, T], right-0-padded. */
    private val pinnedWindowLength: Int?,
) : KokoroSession {
    private var closed = false

    override fun infer(
        tokens: IntArray,
        styleRow: FloatArray,
        speed: Double,
    ): InferResult {
        check(!closed) { "session is closed" }
        val env = OrtEnvironment.getEnvironment()

        // The graph expects the BOS/EOS pads around the window (reference _infer).
        // A static export pins the window dim (input_ids [1, T]); trailing 0s are
        // pad tokens whose rendered silence the engine's AudioTrim removes.
        val width = pinnedWindowLength ?: (tokens.size + 2)
        check(tokens.size + 2 <= width) { "window ${tokens.size + 2} exceeds pinned T=$width" }
        val padded = IntArray(width)
        tokens.copyInto(padded, 1)
        val inputs = LinkedHashMap<String, OnnxTensor>(3)
        inputs[tokenInputName] =
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(width) { padded[it].toLong() }),
                longArrayOf(1, width.toLong()),
            )
        inputs["style"] =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(styleRow),
                longArrayOf(1, styleRow.size.toLong()),
            )
        inputs["speed"] = speedTensor.build(env, speed)
        try {
            val outputs = if (hasTimings) setOf(WAVEFORM_OUTPUT, DURATION_OUTPUT) else setOf(WAVEFORM_OUTPUT)
            session.run(inputs, outputs).use { result ->
                // ORT 1.23 returns Optional per output name.
                val audio = readFloatArray(result.get(WAVEFORM_OUTPUT).orElseThrow() as OnnxTensor)
                val duration =
                    if (hasTimings) {
                        readLongArray(result.get(DURATION_OUTPUT).orElseThrow() as OnnxTensor)
                    } else {
                        null
                    }
                return InferResult(audio, duration)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Process-scoped, never closed in production; kept for tests/benchmarks.
     */
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
    }

    private sealed interface SpeedTensorKind {
        fun build(
            env: OrtEnvironment,
            speed: Double,
        ): OnnxTensor

        data object Float32 : SpeedTensorKind {
            override fun build(
                env: OrtEnvironment,
                speed: Double,
            ): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed.toFloat())), longArrayOf(1))
        }

        /** Integer exports truncate fractional speeds; clamp to ≥ 1 (reference _speed_value). */
        data object Integer : SpeedTensorKind {
            override fun build(
                env: OrtEnvironment,
                speed: Double,
            ): OnnxTensor =
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speed.roundToLong().coerceAtLeast(1L))), longArrayOf(1))
        }
    }

    companion object {
        private const val CONFIG_METADATA_KEY = "kokoro_config"
        private const val WAVEFORM_OUTPUT = "waveform"
        private const val DURATION_OUTPUT = "duration"

        fun open(
            modelFile: File,
            sessionFactory: (OrtSession.SessionOptions) -> Unit = {},
            pinnedWindowLength: Int? = null,
        ): OrtKokoroSession {
            require(modelFile.isFile) { "model file not found: $modelFile" }
            // Explicit options mirror the device-verified T3 harness settings
            // (spike-tts Sessions.kt): ALL_OPT graph optimization + 6 intra-op
            // threads. ORT's no-options overload was observed to stall session
            // creation of the 325 MB fp32 graph on the S22 Ultra (2026-08-26,
            // decisions #30) while the optioned path loads in seconds.
            val sessionOptions =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(6)
                }
            sessionFactory(sessionOptions)
            val session = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, sessionOptions)
            try {
                val inputs = session.inputInfo
                val tokenInput =
                    when {
                        "input_ids" in inputs -> "input_ids"
                        "tokens" in inputs -> "tokens"
                        else -> error("unsupported Kokoro graph: no input_ids/tokens input in ${inputs.keys}")
                    }
                val speedInput = inputs["speed"] ?: error("unsupported Kokoro graph: no speed input")
                val speedKind =
                    if ((speedInput.info as TensorInfo).type == OnnxJavaType.INT32 ||
                        (speedInput.info as TensorInfo).type == OnnxJavaType.INT64
                    ) {
                        SpeedTensorKind.Integer
                    } else {
                        SpeedTensorKind.Float32
                    }
                val outputs = session.outputInfo
                require(WAVEFORM_OUTPUT in outputs || outputs.size == 1) {
                    "unsupported Kokoro graph outputs: ${outputs.keys}"
                }
                val hasTimings = DURATION_OUTPUT in outputs
                val vocab = parseEmbeddedVocab(session)
                return OrtKokoroSession(session, vocab, hasTimings, tokenInput, speedKind, pinnedWindowLength)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }

        private fun parseEmbeddedVocab(session: OrtSession): Map<Char, Int> {
            val config = session.metadata.customMetadata[CONFIG_METADATA_KEY] ?: return emptyMap()
            return try {
                KokoroVocabulary.parse(config)
            } catch (e: Throwable) {
                throw IllegalArgumentException("unreadable $CONFIG_METADATA_KEY metadata", e)
            }
        }

        private fun readFloatArray(tensor: OnnxTensor): FloatArray {
            val buffer: FloatBuffer = tensor.floatBuffer
            val result = FloatArray(buffer.remaining())
            buffer.get(result)
            return result
        }

        private fun readLongArray(tensor: OnnxTensor): IntArray {
            val buffer: LongBuffer = tensor.longBuffer
            val result = IntArray(buffer.remaining())
            for (i in result.indices) {
                result[i] = buffer.get().toInt()
            }
            return result
        }
    }
}
