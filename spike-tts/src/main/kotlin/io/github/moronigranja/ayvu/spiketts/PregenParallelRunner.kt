package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build
import android.os.Debug
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.KokoroEngine
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.kokoro.PhonemizeException
import io.github.moronigranja.ayvu.tts.kokoro.Phonemizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * D2 2-engine parallel pre-generation leg (roadmap D2 additions, 2026-08-31;
 * the last unrun Phase D measurement). Measures whether running TWO Kokoro
 * ORT sessions with separate thread pools synthesizing independent passage
 * chunks beats the serial baseline on pregen wall-time, and at what memory
 * cost. Runs on the S22 only (HiBreak excluded by arithmetic — two ~834 MB
 * sessions cannot share a 3.9 GB device, decisions #93).
 *
 * Admission bar (roadmap D2): parallel pregen throughput >= 1.5x serial
 * without breaching the S22 memory envelope or the 0.001 PCM-oracle gate.
 *
 * Corpus: `files/corpus_pregen.tsv` (text<TAB>lang<TAB>phonemes, the D3
 * host-precomputed pattern — phonemization excluded, host espeak-ng via
 * tools/gen_pregen_corpus.py). Every passage is synthesized by BOTH the
 * serial leg and the parallel leg so the oracle gate compares identical
 * work: parallel PCM vs serial PCM, peak abs diff must stay <= 0.001
 * (both legs run the SAME pinned fp32 model — any divergence is machinery,
 * not a graph change, so a nonzero diff flags a real problem).
 *
 * Serial leg: one engine, all passages, wall = full batch time.
 * Parallel leg: two engines, corpus round-robined across them (evens/odds),
 * wall = elapsed while both run concurrently (the shared-queue pregen shape:
 * two workers pulling independent chunks).
 *
 * Determinism: the serial leg re-synthesizes the corpus on the SAME engine in
 * two consecutive runs and reports the run1-vs-run2 PCM diff, so a nonzero
 * parallel-vs-serial diff can be attributed to run-to-run nondeterminism vs
 * a real parallel-path divergence.
 *
 * Throughput = synthesized-audio-seconds / wall-seconds for each leg; the
 * ratio serial-thp/parallel-thp is the pregen speedup. Results flush as
 * `kokoro_pregen_parallel.json`; engine open + peak VmHWM/PSS are reported
 * per leg so the memory cost of two resident sessions is explicit.
 */
class PregenParallelRunner(
    private val context: Context,
) {
    companion object {
        const val RUNS = 2
        const val ORACLE_REJECT_THRESHOLD = 0.001f
        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
    }

    private val models = File(context.filesDir, "models")

    /** A corpus passage ready to synthesize (phonemes precomputed host-side). */
    private data class Passage(
        val text: String,
        val language: String,
        val voice: String,
        val phonemes: String,
    )

    /** One synthesized passage (PCM kept for the oracle). */
    private data class Synth(
        val passage: Passage,
        val millis: Long,
        val seconds: Double,
        val pcm: FloatArray,
    )

    fun run(log: (String) -> Unit): Boolean {
        return try {
            check(File(models, "kokoro-model").isFile && File(models, "kokoro-voices").isFile) {
                "models not found under ${models.absolutePath} — stage them first (see build.md)"
            }
            val corpusFile = File(context.filesDir, "corpus_pregen.tsv")
            check(corpusFile.isFile) { "corpus not found at ${corpusFile.absolutePath}" }

            val passages =
                corpusFile.readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 3) {
                        null
                    } else {
                        val voice = VOICES[parts[1]] ?: return@mapNotNull null
                        Passage(parts[0], parts[1], voice, parts[2])
                    }
                }
            check(passages.isNotEmpty()) { "corpus_pregen.tsv is empty or malformed (no known-language rows)" }
            log("device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
            log("corpus: ${passages.size} passages; phonemization = host-precomputed")

            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            val results =
                JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("threads_per_session", 6)
                    .put("runs", RUNS)

            runBlocking {
                // ---- SERIAL leg -------------------------------------------------
                val serial = serialLeg(passages, log)
                results.put("serial", serial.json())

                // ---- PARALLEL leg ----------------------------------------------
                // Two engines = two ORT sessions, each with its own 6-thread pool.
                val parallel = parallelLeg(passages, serial.pcmByPassage, log)
                results.put("parallel", parallel.json())

                // ---- throughput + gate -----------------------------------------
                val serialThp = serial.audioSeconds / serial.wallMs * 1000.0
                val parallelThp = parallel.audioSeconds / parallel.wallMs * 1000.0
                val speedup = serialThp / parallelThp
                results.put("serial_throughput_audio_s_per_s", serialThp)
                results.put("parallel_throughput_audio_s_per_s", parallelThp)
                results.put("speedup_serial_over_parallel", speedup)
                results.put(
                    "oracle",
                    JSONObject()
                        .put("max_abs_diff", parallel.maxAbsDiff)
                        .put("mean_abs_diff", parallel.meanAbsDiff)
                        .put("compared_against", "serial (same fp32 model)")
                        .put("rejected", parallel.maxAbsDiff > ORACLE_REJECT_THRESHOLD),
                )
                log(
                    "serial:   ${"%.2f".format(serial.audioSeconds)}s audio in ${serial.wallMs} ms " +
                        "(thp ${"%.2f".format(serialThp)} audio-s/s), VmHWM ${serial.vmHwmKb} kB",
                )
                log(
                    "parallel: ${"%.2f".format(parallel.audioSeconds)}s audio in ${parallel.wallMs} ms " +
                        "(thp ${"%.2f".format(parallelThp)} audio-s/s), VmHWM ${parallel.vmHwmKb} kB",
                )
                log("speedup (serial/parallel throughput): ${"%.2f".format(speedup)}x")
                log(
                    "oracle gate: max_abs_diff=${"%.6f".format(parallel.maxAbsDiff)} " +
                        "rejected=${parallel.maxAbsDiff > ORACLE_REJECT_THRESHOLD}",
                )
            }

            File(outDir, "kokoro_pregen_parallel.json").writeText(results.toString(2))
            log("kokoro_pregen_parallel.json written to $outDir")
            log("DONE")
            true
        } catch (e: Throwable) {
            log("parallel pregen leg unavailable: $e")
            false
        }
    }

    /** Serial leg result: all passages on one engine, repeated RUNS times. */
    private class SerialLeg(
        val wallMs: Long,
        val audioSeconds: Double,
        val vmHwmKb: Long,
        val pssKb: Int,
        val thermalMaxStatus: Int,
        val thermalMaxHeadroom: Float,
        val pcmByPassage: Map<String, FloatArray>, // latest run, for the oracle
        val runToRunMaxAbsDiff: Float,
        val runToRunMeanAbsDiff: Float,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("wall_ms", wallMs)
                .put("audio_seconds", audioSeconds)
                .put("vm_hwm_kb", vmHwmKb)
                .put("total_pss_kb", pssKb)
                .put("thermal_status_max", thermalMaxStatus)
                .put("thermal_headroom_max", thermalMaxHeadroom)
                .put("run_to_run_max_abs_diff", runToRunMaxAbsDiff)
                .put("run_to_run_mean_abs_diff", runToRunMeanAbsDiff)
    }

    private suspend fun serialLeg(
        passages: List<Passage>,
        log: (String) -> Unit,
    ): SerialLeg {
        val tOpen = System.currentTimeMillis()
        val engine =
            KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = File(models, "kokoro-model"),
                voicesFile = File(models, "kokoro-voices"),
                phonemizer = CorpusPhonemizer(passages),
                progress = { log("serial open stage: $it (${System.currentTimeMillis() - tOpen} ms)") },
                sessionFactory = {},
            )
        log("serial engine open: ${System.currentTimeMillis() - tOpen} ms")

        val thermal = ThermalProbe(context)
        thermal.start()
        var wallMs = Long.MAX_VALUE
        var audioSeconds = 0.0
        var latestPcm = emptyMap<String, FloatArray>()
        val runPcm = HashMap<Int, Map<String, FloatArray>>()
        for (run in 1..RUNS) {
            val runWall =
                measureTimeMillis {
                    var audio = 0.0
                    val pcm = HashMap<String, FloatArray>()
                    for (p in passages) {
                        val outcome = engine.synthesize(SynthesisRequest(p.text, p.voice))
                        val res =
                            outcome as? SynthesisOutcome.Audio
                                ?: throw IllegalArgumentException("serial synthesis failed [${p.language}]: $outcome")
                        val seconds = res.pcm.size / 2.0 / KokoroEngine.SAMPLE_RATE
                        audio += seconds
                        pcm[p.text] = pcmToFloats(res.pcm)
                    }
                    runPcm[run] = pcm
                    latestPcm = pcm
                    audioSeconds = audio
                }
            log(
                "serial run $run/$RUNS: ${"%.2f".format(audioSeconds)}s audio in $runWall ms, " +
                    "RTF=${"%.3f".format(runWall / 1000.0 / audioSeconds)}",
            )
            if (runWall < wallMs) wallMs = runWall
        }
        // Run-to-run determinism check (same model, same code): any nonzero diff
        // is ORT thread nondeterminism, not a graph change.
        val run1 = runPcm[1]
        val run2 = runPcm[2]
        var rrMax = 0f
        var rrMeanSum = 0.0
        var rrCount = 0
        if (run1 != null) {
            for (p in passages) {
                val a = run1[p.text]
                val b = run2?.get(p.text)
                if (a != null && b != null) {
                    val (peak, mean) = pcmDiff(a, b)
                    if (peak > rrMax) rrMax = peak
                    rrMeanSum += mean
                    rrCount++
                }
            }
        }
        val rrMean = if (rrCount > 0) (rrMeanSum / rrCount).toFloat() else 0f
        log("serial run1-vs-run2 deterministic check: max_abs_diff=${"%.6f".format(rrMax)} mean=${"%.6f".format(rrMean)} (n=$rrCount)")
        thermal.stop()
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val vmHwm = readVmHwm()
        log("serial VmHWM=$vmHwm kB, totalPss=${mem.totalPss} kB, thermal max=${thermal.maxStatus}")
        engine.close()
        return SerialLeg(wallMs, audioSeconds, vmHwm, mem.totalPss, thermal.maxStatus, thermal.maxHeadroom, latestPcm, rrMax, rrMean)
    }

    /** Parallel leg result: two engines round-robin, wall = concurrent elapsed. */
    private class ParallelLeg(
        val wallMs: Long,
        val audioSeconds: Double,
        val vmHwmKb: Long,
        val pssKb: Int,
        val thermalMaxStatus: Int,
        val thermalMaxHeadroom: Float,
        val maxAbsDiff: Float,
        val meanAbsDiff: Float,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("wall_ms", wallMs)
                .put("audio_seconds", audioSeconds)
                .put("vm_hwm_kb", vmHwmKb)
                .put("total_pss_kb", pssKb)
                .put("thermal_status_max", thermalMaxStatus)
                .put("thermal_headroom_max", thermalMaxHeadroom)
    }

    private suspend fun parallelLeg(
        passages: List<Passage>,
        oracle: Map<String, FloatArray>,
        log: (String) -> Unit,
    ): ParallelLeg {
        val tOpen = System.currentTimeMillis()
        val engineA =
            KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = File(models, "kokoro-model"),
                voicesFile = File(models, "kokoro-voices"),
                phonemizer = CorpusPhonemizer(passages),
                progress = { log("parallel open A: $it (${System.currentTimeMillis() - tOpen} ms)") },
                sessionFactory = {},
            )
        val engineBOpen = System.currentTimeMillis()
        val engineB =
            KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = File(models, "kokoro-model"),
                voicesFile = File(models, "kokoro-voices"),
                phonemizer = CorpusPhonemizer(passages),
                progress = { log("parallel open B: $it (${System.currentTimeMillis() - engineBOpen} ms)") },
                sessionFactory = {},
            )
        log("parallel engines open: ${System.currentTimeMillis() - tOpen} ms (2 sessions)")

        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val thermal = ThermalProbe(context)
        thermal.start()
        var wallMs = Long.MAX_VALUE
        var audioSeconds = 0.0
        var maxAbsDiff = 0f
        var worst: Synth? = null
        var worstSerial: FloatArray? = null
        var worstPeak = 0f
        var meanAbsDiffSum = 0.0
        var diffCount = 0
        for (run in 1..RUNS) {
            // Round-robin the corpus across the two engines (the shared-queue
            // pregen shape); both run concurrently inside the coroutineScope.
            val runWall =
                measureTimeMillis {
                    val results =
                        coroutineScope {
                            val evens =
                                async(Dispatchers.IO) {
                                    synthesizeAll(engineA, passages.filterIndexed { i, _ -> i % 2 == 0 })
                                }
                            val odds =
                                async(Dispatchers.IO) {
                                    synthesizeAll(engineB, passages.filterIndexed { i, _ -> i % 2 == 1 })
                                }
                            evens.await() + odds.await()
                        }
                    var audio = 0.0
                    for (s in results) {
                        audio += s.seconds
                        val oraclePcm = oracle[s.passage.text]
                        if (oraclePcm != null) {
                            val (peak, mean) = pcmDiff(s.pcm, oraclePcm)
                            if (peak > maxAbsDiff) maxAbsDiff = peak
                            if (peak > worstPeak) {
                                worstPeak = peak
                                worst = s
                                worstSerial = oraclePcm
                            }
                            meanAbsDiffSum += mean
                            diffCount++
                            log(
                                "  parallel[run $run] [${s.passage.language}] peak=${"%.6f".format(
                                    peak,
                                )} mean=${"%.6f".format(mean)} lenDelta=${s.pcm.size - oraclePcm.size}",
                            )
                        }
                    }
                    audioSeconds = audio
                }
            log(
                "parallel run $run/$RUNS: ${"%.2f".format(audioSeconds)}s audio in $runWall ms " +
                    "(concurrent wall), RTF=${"%.3f".format(runWall / 1000.0 / audioSeconds)}",
            )
            if (runWall < wallMs) wallMs = runWall
        }
        thermal.stop()
        // Persist the worst-divergence passage for post-hoc inspection.
        if (worst != null && worstSerial != null) {
            Wav.write(File(outDir, "kokoro_parallel_worst.wav"), worst!!.pcm, KokoroEngine.SAMPLE_RATE)
            Wav.write(File(outDir, "kokoro_serial_worst.wav"), worstSerial!!, KokoroEngine.SAMPLE_RATE)
        }
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val vmHwm = readVmHwm()
        val meanAbsDiff = if (diffCount > 0) (meanAbsDiffSum / diffCount).toFloat() else 0f
        log("parallel VmHWM=$vmHwm kB, totalPss=${mem.totalPss} kB (2 sessions resident), thermal max=${thermal.maxStatus}")
        engineA.close()
        engineB.close()
        return ParallelLeg(wallMs, audioSeconds, vmHwm, mem.totalPss, thermal.maxStatus, thermal.maxHeadroom, maxAbsDiff, meanAbsDiff)
    }

    private fun synthesizeAll(
        engine: KokoroEngine,
        passages: List<Passage>,
    ): List<Synth> =
        passages.map { p ->
            var outcome: SynthesisOutcome? = null
            val millis =
                measureTimeMillis {
                    outcome = runBlocking { engine.synthesize(SynthesisRequest(p.text, p.voice)) }
                }
            val res =
                outcome as? SynthesisOutcome.Audio
                    ?: throw IllegalArgumentException("parallel synthesis failed [${p.language}]: $outcome")
            Synth(p, millis, res.pcm.size / 2.0 / KokoroEngine.SAMPLE_RATE, pcmToFloats(res.pcm))
        }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        val buffer =
            java.nio.ByteBuffer
                .wrap(pcm)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
        for (i in out.indices) out[i] = buffer.get() / 32768.0f
        return out
    }

    private fun pcmDiff(
        a: FloatArray,
        b: FloatArray,
    ): Pair<Float, Float> {
        val n = minOf(a.size, b.size)
        var peak = 0.0f
        var total = 0.0
        for (i in 0 until n) {
            val d = abs(a[i] - b[i])
            if (d > peak) peak = d
            total += d
        }
        return peak to (if (n > 0) (total / n).toFloat() else 0f)
    }

    /** Answers phonemization from the precomputed corpus (host espeak-ng). */
    private class CorpusPhonemizer(
        private val passages: List<Passage>,
    ) : Phonemizer {
        private val lookup = passages.associate { it.text to it }

        override fun phonemize(
            text: String,
            language: String,
        ): String =
            lookup[text]
                ?.takeIf { it.language == language }
                ?.phonemes
                ?: throw PhonemizeException("corpus mismatch for text: '${text.take(40)}…'")

        override fun supportedLanguages(): Set<String> = passages.map { it.language }.toSet()
    }
}
