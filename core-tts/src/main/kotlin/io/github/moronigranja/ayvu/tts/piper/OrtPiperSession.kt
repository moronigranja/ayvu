package io.github.moronigranja.ayvu.tts.piper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * [PiperSession] over ONNX Runtime — the VITS graph contract piper ships for
 * every voice (verified on en_US-lessac-medium): inputs `input` int64
 * `[1, L]`, `input_lengths` int64 `[1]`, `scales` float32 `[3]`; output
 * `output` float32 `[1, 1, 1, N]`.
 *
 * The invocation mirrors the proven D4 spike leg exactly (decisions #99 and
 * its 2026-09-12 correction; spike-tts D4ProbeRunner): the same inputs, the
 * same scales order, and the output read through [OnnxTensor.floatBuffer]
 * directly — never `getValue()` on the rank-4 tensor (the Aug-31 half-length
 * WAV defect came from the nested-array path).
 *
 * The runtime itself is deliberately NOT a dependency here: core-tts compiles
 * against the ORT Java API (compileOnly) and the host/device provides the
 * platform artifact (see OrtKokoroSession). Sessions are thread-safe; the
 * engine serializes nothing (single pass per request).
 */
class OrtPiperSession private constructor(
    private val session: OrtSession,
) : PiperSession {
    private var closed = false

    override fun infer(
        phonemeIds: IntArray,
        scales: FloatArray,
    ): FloatArray {
        check(!closed) { "session is closed" }
        require(scales.size == 3) { "scales must carry (noise_scale, length_scale, noise_w)" }
        val env = OrtEnvironment.getEnvironment()
        val ids = LongArray(phonemeIds.size) { phonemeIds[it].toLong() }
        val inputs =
            LinkedHashMap<String, OnnxTensor>(3).apply {
                put(INPUT, OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())))
                put(
                    INPUT_LENGTHS,
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)),
                )
                put(SCALES, OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3)))
            }
        try {
            session.run(inputs, setOf(OUTPUT)).use { result ->
                // ORT returns Optional per output name; read the buffer directly —
                // never getValue() on the rank-4 [1,1,1,N] tensor (decisions #99).
                val tensor = result.get(OUTPUT).orElseThrow() as OnnxTensor
                val buffer: FloatBuffer = tensor.floatBuffer
                val audio = FloatArray(buffer.remaining())
                buffer.get(audio)
                return audio
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

    companion object {
        private const val INPUT = "input"
        private const val INPUT_LENGTHS = "input_lengths"
        private const val SCALES = "scales"
        private const val OUTPUT = "output"

        fun open(
            modelFile: File,
            sessionFactory: (OrtSession.SessionOptions) -> Unit = {},
        ): OrtPiperSession {
            require(modelFile.isFile) { "model file not found: $modelFile" }
            // The proven D4 probe session options (decisions #93/#99): 6
            // intra-op threads, memory patterns + CPU arena off. The app caps
            // the thread pool via [sessionFactory] (decisions #137, Kokoro's
            // pattern); the memory options stay so the #93 lesson holds.
            val sessionOptions =
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(6)
                    setMemoryPatternOptimization(false)
                    setCPUArenaAllocator(false)
                }
            sessionFactory(sessionOptions)
            val session = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, sessionOptions)
            try {
                val inputs = session.inputInfo
                require(INPUT in inputs && INPUT_LENGTHS in inputs && SCALES in inputs) {
                    "unsupported Piper graph inputs: ${inputs.keys}"
                }
                require(OUTPUT in session.outputInfo) {
                    "unsupported Piper graph outputs: ${session.outputInfo.keys}"
                }
                return OrtPiperSession(session)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }
    }
}
