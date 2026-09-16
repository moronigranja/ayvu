package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * D3 MOSS-TTS-Nano leg (decisions #92/#93): wraps [MossEngine] (the ported
 * Apache-2.0 demo engine) in the same runner/JSON/WAV pattern as
 * [KokoroBenchmarkRunner] and [KittenBenchmarkRunner].
 *
 * Session policy (recorded in the first run, per the plan): one warm-up
 * entry with the demo's 2-thread session setting, then the measured pass at
 * 6 intra-op threads (harness parity with the Kokoro/Kitten legs). Corpus
 * text token ids come from the TSV; the pt-BR row is attempted — lang
 * coverage is itself a measured result. A row whose decode hits
 * `generation_defaults.max_new_frames` is recorded `truncated` and excluded
 * from the corpus-average RTF.
 */
class MossBenchmarkRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "MossSpike"
        const val THREADS = 6
        const val DEMO_THREADS = 2
    }

    private val models = File(context.filesDir, "models/moss")

    fun run(
        corpusFile: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val results = JSONObject()
        return try {
            val entries = parseCorpus(corpusFile)
            log("candidate=moss device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
            log("corpus: ${entries.size} runnable rows (${models.absolutePath})")

            // Warm-up: demo-parity 2-thread session on the first entry only.
            log("warm-up: opening engine with demo threads=$DEMO_THREADS for one entry")
            MossEngine(models, DEMO_THREADS).use { warm ->
                val t = measureTimeMillis { warm.synthesize(entries.first().tokens) }
                log(
                    "warm-up done: first entry '${entries.first().id}' in $t ms (threads=$DEMO_THREADS, " +
                        "voice=${warm.voiceName}, maxFrames=${warm.maxFrames}, sampleRate=${warm.sampleRate})",
                )
            }

            val tOpen = System.currentTimeMillis()
            val engine = MossEngine(models, THREADS)
            val engineOpenMs = System.currentTimeMillis() - tOpen
            log("engine open: $engineOpenMs ms (candidate=moss, threads=$THREADS)")
            log(
                "builtin voices: ${engine.allVoiceNames} — chose '${engine.voiceName}' " +
                    "(first English group entry, Female preferred to match the Kokoro af_heart baseline)",
            )

            val thermal = ThermalProbe(context, TAG)
            thermal.start()
            val runJson = JSONObject().put("run", 1)
            val rowsJson = JSONArray()
            var rtfSum = 0.0
            var rtfCount = 0
            for (entry in entries) {
                var synthesis: MossEngine.Synthesis? = null
                val millis =
                    measureTimeMillis {
                        synthesis = engine.synthesize(entry.tokens)
                    }
                val result = requireNotNull(synthesis)
                val seconds = result.pcm.size / 2.0 / engine.sampleRate
                var peak = 0.0f
                var rms = 0.0
                for (v in result.pcm) {
                    peak = maxOf(peak, kotlin.math.abs(v))
                    rms += v * v
                }
                rms = if (result.pcm.isNotEmpty()) Math.sqrt(rms / result.pcm.size) else 0.0
                val finite = result.pcm.all { it.isFinite() }
                val rtf = millis / 1000.0 / seconds
                if (!result.truncated && seconds > 0) {
                    rtfSum += rtf
                    rtfCount++
                }
                rowsJson.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("language", entry.lang)
                        .put("voice", engine.voiceName)
                        .put("text_tokens", entry.tokens.size)
                        .put("prefill_ms", result.timings.prefillMs)
                        .put("decode_ms", result.timings.decodeMs)
                        .put("codec_ms", result.timings.codecMs)
                        .put("synth_ms", millis)
                        .put("generated_frames", result.generatedFrames)
                        .put("truncated", result.truncated)
                        .put("audio_seconds", seconds)
                        .put("rtf", rtf)
                        .put("samples", result.pcm.size)
                        .put("peak_abs", peak)
                        .put("rms", rms)
                        .put("finite", finite),
                )
                log(
                    "run 1 [${entry.id}]: ${"%.2f".format(seconds)}s audio in $millis ms " +
                        "(prefill=${result.timings.prefillMs} decode=${result.timings.decodeMs} " +
                        "codec=${result.timings.codecMs}), RTF=${"%.3f".format(rtf)}, " +
                        "frames=${result.generatedFrames}${if (result.truncated) " TRUNCATED" else ""}",
                )
                Wav.write(File(outDir, "d3_moss_run1_${entry.id}.wav"), result.pcm, engine.sampleRate)
            }
            thermal.stop()
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            results.put("engine", "MOSS-TTS-Nano-100M-ONNX")
            results.put("voice", engine.voiceName)
            results.put("builtin_voices", JSONArray(engine.allVoiceNames))
            results.put("sample_rate", engine.sampleRate)
            results.put("max_frames", engine.maxFrames)
            results.put("warmup_threads", DEMO_THREADS)
            results.put("runs", JSONArray().put(runJson.put("passages", rowsJson)))
            results.put("corpus_avg_rtf", if (rtfCount > 0) rtfSum / rtfCount else JSONObject.NULL)
            results.put("corpus_avg_rtf_entries", rtfCount)
            results.put("vm_hwm_kb", readVmHwm())
            results.put("total_pss_kb", mem.totalPss)
            results.put("thermal_status_max", thermal.maxStatus)
            results.put("thermal_headroom_max", thermal.maxHeadroom)
            results.put("screen", "off/locked (instrumented)")
            results.put("threads", THREADS)
            results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            results.put("engine_open_ms", engineOpenMs)
            results.put("tokenization", "excluded (host sentencepiece ids in corpus)")
            engine.close()
            log(
                "corpus avg RTF (non-truncated): ${if (rtfCount > 0) "%.3f".format(rtfSum / rtfCount) else "n/a"} " +
                    "over $rtfCount entries",
            )
            log("VmHWM: ${readVmHwm()} kB, totalPss: ${mem.totalPss} kB")
            log("DONE (candidate=moss)")
            results
        } catch (e: Throwable) {
            log("candidate moss unavailable: $e")
            results.put("engine", "MOSS-TTS-Nano-100M-ONNX")
            results.put("unavailable", e.message ?: e.toString())
            results
        }
    }

    private class Entry(
        val id: String,
        val lang: String,
        val tokens: IntArray,
    )

    /**
     * Loads `d3_corpus.tsv` (`id \t lang \t raw_text \t kokoro_phonemes \t
     * kitten_tokens \t moss_token_ids`); rows without moss token ids are
     * logged as SKIP and excluded.
     */
    private fun parseCorpus(corpusFile: File): List<Entry> {
        check(corpusFile.isFile) { "corpus not found at ${corpusFile.absolutePath}" }
        val entries = ArrayList<Entry>()
        for ((index, line) in corpusFile.readLines().withIndex()) {
            if (index == 0 || line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 6) continue
            val id = parts[0]
            val lang = parts[1]
            val moss = parts[5]
            if (moss.isBlank()) {
                android.util.Log.d(TAG, "SKIP $id: no moss_token_ids")
                continue
            }
            entries += Entry(id, lang, moss.split(',').map { it.trim().toInt() }.toIntArray())
        }
        check(entries.isNotEmpty()) { "no runnable moss rows in ${corpusFile.name}" }
        return entries
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }
}
