package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build
import android.os.Debug
import io.github.moronigranja.ayvu.tts.kokoro.KokoroTokenizer
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVocabulary
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceBank
import io.github.moronigranja.ayvu.tts.kokoro.OrtKokoroSession
import io.github.moronigranja.ayvu.tts.kokoro.PhonemeChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * D2 follow-up (decisions #116) — the candela-derived chunk-parallel question
 * the D2 session-level leg never asked: does splitting ONE passage's
 * inference windows across W low-thread ORT sessions beat one 6-thread
 * session? (decisions #116 measured two 6-thread sessions and lost — an
 * oversubscription on the S22's 8 cores; candela's "1-8 engine instances,
 * each its own thread pool" is the W×T regime, not 6-thread-per-session.)
 *
 * Axis under test: throughput only. Emit-early's first-audio latency is
 * bounded by window[0]'s single inference, which is identical across every
 * config (one window = one `infer`, serialized by the graph), so it is
 * deliberately NOT a leg — the streaming seam (decisions #138) already
 * delivers it.
 *
 * Configs (W workers × T intra-op threads each), ordered by resident-memory
 * cost so a later OOM never loses earlier legs:
 *   1×6 (serial baseline), 2×2, 4×1, 2×4, 4×2.
 * W×T never exceeds 8 total threads — the S22 core count.
 *
 * Admission bar (roadmap D2 restated for the window granularity): a parallel
 * config is a win only if its throughput (audio-s / wall-s) beats serial-6
 * AND its W-session memory cost fits the device envelope. No oracle gate:
 * differing intra-op thread counts change mlas reduction order, so
 * cross-config PCM drift is expected nondeterminism (#116), not machinery.
 *
 * Corpus: `files/corpus_pregen.tsv` (text<TAB>lang<TAB>phonemes, host espeak-ng
 * via tools/gen_pregen_corpus.py) — the same corpus D2/D3 used. Windows are
 * split + tokenized in-process with core-tts primitives (PhonemeChunker,
 * KokoroTokenizer, KokoroVoiceBank), inference through raw OrtKokoroSession
 * opened at the per-config thread count; trim/pause/PCM post-processing is
 * identical and negligible both legs, so raw inference wall is the honest
 * throughput signal.
 *
 * Results flush after every leg to `kokoro_chunk_parallel.json` (partial
 * results survive a lmkd kill on the 4-session legs); engine open + VmHWM/PSS
 * reported per leg so the multi-session memory cost is explicit.
 */
class ChunkParallelRunner(
    private val context: Context,
) {
    companion object {
        const val RUNS = 3
        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
        private val CONFIGS =
            listOf(
                1 to 6,
                2 to 2,
                4 to 1,
                2 to 4,
                4 to 2,
            )
    }

    private val models = File(context.filesDir, "models")
    private val sampleRate = 24_000.0

    /** A corpus passage (phonemes precomputed host-side). */
    private data class Passage(
        val text: String,
        val language: String,
        val voice: String,
        val phonemes: String,
    )

    /** One inference window, tokenized + style-ready once. */
    private data class Window(
        val tokens: IntArray,
        val style: FloatArray,
    )

    private class LegResult(
        val workers: Int,
        val threads: Int,
        val wallMs: Long,
        val audioSeconds: Double,
        val vmHwmKb: Long,
        val pssKb: Int,
        val thermalMaxStatus: Int,
        val thermalMaxHeadroom: Float,
    ) {
        val throughput: Double get() = audioSeconds / wallMs * 1000.0
        val rtf: Double get() = wallMs / 1000.0 / audioSeconds

        fun json(): JSONObject =
            JSONObject()
                .put("workers", workers)
                .put("threads_per_worker", threads)
                .put("total_threads", workers * threads)
                .put("wall_ms", wallMs)
                .put("audio_seconds", audioSeconds)
                .put("throughput_audio_s_per_s", throughput)
                .put("rtf", rtf)
                .put("vm_hwm_kb", vmHwmKb)
                .put("total_pss_kb", pssKb)
                .put("thermal_status_max", thermalMaxStatus)
                .put("thermal_headroom_max", thermalMaxHeadroom)
    }

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
            val outFile = File(outDir, "kokoro_chunk_parallel.json")

            // Shared, open-once primitives: tokenizer + voice bank are the same
            // across configs (thread count only changes the ORT session).
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))

            val results =
                JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("runs", RUNS)

            // Pre-split every passage once — splitting/tokenization is CPU-cheap
            // and identical across configs, so excluding it keeps inference
            // wall-time the signal.
            val windowsByPassage = passages.map { p -> windowsOf(p, tokenizer, voices) }

            for ((workers, threads) in CONFIGS) {
                log("leg ${workers}x$threads: opening $workers session(s) at $threads intra-op thread(s)…")
                val leg = runLeg(workers, threads, windowsByPassage, log)
                results.put("${workers}x$threads", leg.json())
                outFile.writeText(results.toString(2)) // partial survives an OOM
                log(
                    "${workers}x$threads: ${"%.2f".format(leg.audioSeconds)}s audio in ${leg.wallMs} ms " +
                        "(thp ${"%.2f".format(leg.throughput)} audio-s/s, RTF ${"%.3f".format(leg.rtf)}), " +
                        "VmHWM ${leg.vmHwmKb} kB, PSS ${leg.pssKb} kB",
                )
            }

            val serialThp = results.getJSONObject("1x6").getDouble("throughput_audio_s_per_s")
            for ((workers, threads) in CONFIGS.drop(1)) {
                val key = "${workers}x$threads"
                val thp = results.getJSONObject(key).getDouble("throughput_audio_s_per_s")
                log("  $key speedup vs 1x6: ${"%.2f".format(thp / serialThp)}x")
            }

            outFile.writeText(results.toString(2))
            log("kokoro_chunk_parallel.json written to $outDir")
            log("DONE")
            true
        } catch (e: Throwable) {
            log("chunk-parallel leg unavailable: $e")
            false
        }
    }

    /** Splits one passage into tokenized, style-ready inference windows. */
    private fun windowsOf(
        passage: Passage,
        tokenizer: KokoroTokenizer,
        voices: KokoroVoiceBank,
    ): List<Window> {
        val collapsed = passage.phonemes.split(Regex("\\s+")).joinToString(" ")
        return PhonemeChunker.split(collapsed).map { batch ->
            val tokens = tokenizer.tokenize(batch)
            val style = voices.styleFor(passage.voice, tokens.size) ?: error("no style for voice ${passage.voice}")
            Window(tokens, style)
        }
    }

    /** One config: W sessions at T threads each, windows round-robined. */
    private fun runLeg(
        workers: Int,
        threads: Int,
        windowsByPassage: List<List<Window>>,
        log: (String) -> Unit,
    ): LegResult {
        val tOpen = System.currentTimeMillis()
        val sessions =
            (0 until workers).map {
                OrtKokoroSession.open(
                    File(models, "kokoro-model"),
                    sessionFactory = { options -> options.setIntraOpNumThreads(threads) },
                )
            }
        log("  ${workers}x$threads sessions open: ${System.currentTimeMillis() - tOpen} ms")

        val thermal = ThermalProbe(context)
        thermal.start()
        var bestWall = Long.MAX_VALUE
        var audioSeconds = 0.0
        for (run in 1..RUNS) {
            val wall =
                measureTimeMillis {
                    var audio = 0.0
                    for (windows in windowsByPassage) {
                        if (workers == 1) {
                            for (w in windows) audio += sessions[0].infer(w.tokens, w.style, 1.0).audio.size / sampleRate
                        } else {
                            audio +=
                                runBlocking {
                                    coroutineScope {
                                        windows
                                            .mapIndexed { i, w ->
                                                async(Dispatchers.IO) {
                                                    sessions[i % workers].infer(w.tokens, w.style, 1.0).audio.size / sampleRate
                                                }
                                            }.awaitAll()
                                            .sum()
                                    }
                                }
                        }
                    }
                    audioSeconds = audio
                }
            log(
                "  ${workers}x$threads run $run/$RUNS: ${"%.2f".format(audioSeconds)}s audio in $wall ms, " +
                    "RTF=${"%.3f".format(wall / 1000.0 / audioSeconds)}",
            )
            if (wall < bestWall) bestWall = wall
        }
        thermal.stop()
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val vmHwm = readVmHwm()
        log("  ${workers}x$threads VmHWM=$vmHwm kB, totalPss=${mem.totalPss} kB ($workers resident session(s))")
        sessions.forEach { it.close() }
        return LegResult(workers, threads, bestWall, audioSeconds, vmHwm, mem.totalPss, thermal.maxStatus, thermal.maxHeadroom)
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }
}
