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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * D3 unified device pass (decisions #93): runs the Kokoro baseline, the
 * KittenTTS Nano leg and the MOSS-TTS-Nano leg over the same staged
 * `d3_corpus.tsv` in one process and merges their result objects into
 * `d3_results.json` = `{"kokoro": …, "kitten": …, "moss": …}`.
 *
 * The Kokoro baseline MUST complete — a failure aborts the pass (same rule
 * as [KokoroBenchmarkRunner]'s CPU candidate). Kitten and MOSS legs that
 * fail to initialize log `candidate <name> unavailable` and the pass still
 * completes without them. All three legs exclude tokenization/phonemization
 * (host-precomputed corpus columns), so the numbers compare inference only.
 */
class D3CompareRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "D3Compare"
        const val RUNS = 1

        /** The runner's existing voice map (en-us→af_heart, pt-br→pf_dora). */
        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
    }

    private val models = File(context.filesDir, "models")

    private class Entry(
        val id: String,
        val lang: String,
        val text: String,
        val phonemes: String,
    )

    fun run(
        corpusFile: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val merged = JSONObject()
        log("D3 compare: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
        val outFile = File(outDir, "d3_results.json")

        // Write the merged file after every completed leg: on low-RAM devices
        // lmkd can kill the process mid-pass and the finished legs must
        // survive on disk (HiBreak: kokoro leg done, killed in kitten).
        fun flush() {
            File(outDir, "d3_results.json.tmp").writeText(merged.toString(2))
            outFile.delete()
            File(outDir, "d3_results.json.tmp").renameTo(outFile)
        }

        // Baseline first, and it must complete.
        val kokoro = runKokoro(corpusFile, outDir, log)
        merged.put("kokoro", kokoro)
        flush()

        val kitten =
            try {
                KittenBenchmarkRunner(context).run(corpusFile, outDir, log = { log("[kitten] $it") })
            } catch (e: Throwable) {
                log("candidate kitten unavailable: $e")
                JSONObject().put("unavailable", e.message ?: e.toString())
            }
        merged.put("kitten", kitten)
        flush()

        val moss =
            try {
                MossBenchmarkRunner(context).run(corpusFile, outDir) { log("[moss] $it") }
            } catch (e: Throwable) {
                log("candidate moss unavailable: $e")
                JSONObject().put("unavailable", e.message ?: e.toString())
            }
        merged.put("moss", moss)
        flush()

        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        merged.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        merged.put("vm_hwm_kb", readVmHwm())
        merged.put("total_pss_kb", mem.totalPss)
        flush()
        log("d3_results.json written to $outDir")
        return merged
    }

    /**
     * Kokoro baseline over the D3 corpus: `kokoro_phonemes` answered by a
     * passthrough phonemizer keyed on the raw corpus text (the
     * [KokoroBenchmarkRunner] corpus pattern), CPU session defaults, one
     * measured pass.
     */
    private fun runKokoro(
        corpusFile: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        val entries = parseCorpus(corpusFile, log)
        log("kokoro corpus: ${entries.size} rows with phonemes")
        val lookup = entries.associate { it.text to it.phonemes }
        val languages = entries.map { it.lang }.toSet()

        val tOpen = System.currentTimeMillis()
        val engine =
            KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = File(models, "kokoro-model"),
                voicesFile = File(models, "kokoro-voices"),
                phonemizer = CorpusPhonemizer(lookup, languages),
                progress = { log("open stage: $it (${System.currentTimeMillis() - tOpen} ms)") },
            )
        val engineOpenMs = System.currentTimeMillis() - tOpen
        log("engine open: $engineOpenMs ms (candidate=kokoro)")

        val thermal = ThermalProbe(context, TAG)
        thermal.start()
        try {
            val rowsJson = JSONArray()
            for (entry in entries) {
                val voice =
                    VOICES[entry.lang]
                        ?: error("no voice mapped for corpus language '${entry.lang}'")
                var outcome: SynthesisOutcome? = null
                val millis =
                    measureTimeMillis {
                        outcome = runBlocking { engine.synthesize(SynthesisRequest(entry.text, voice)) }
                    }
                when (val result = outcome) {
                    null -> error("synthesis returned null outcome")
                    is SynthesisOutcome.Audio -> {
                        val seconds = result.pcm.size / 2.0 / KokoroEngine.SAMPLE_RATE
                        val floats = pcmToFloats(result.pcm)
                        var peak = 0.0f
                        var rms = 0.0
                        for (v in floats) {
                            peak = maxOf(peak, kotlin.math.abs(v))
                            rms += v * v
                        }
                        rms = Math.sqrt(rms / floats.size)
                        val finite = floats.all { it.isFinite() }
                        val rtf = millis / 1000.0 / seconds
                        rowsJson.put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("language", entry.lang)
                                .put("voice", voice)
                                .put("synth_ms", millis)
                                .put("audio_seconds", seconds)
                                .put("rtf", rtf)
                                .put("samples", result.pcm.size / 2)
                                .put("peak_abs", peak)
                                .put("rms", rms)
                                .put("finite", finite),
                        )
                        log(
                            "run 1 [${entry.id}]: ${"%.2f".format(seconds)}s audio in $millis ms, " +
                                "RTF=${"%.3f".format(rtf)}, rms=${"%.4f".format(rms)}",
                        )
                        Wav.write(
                            File(outDir, "kokoro_d3_run1_${entry.id}.wav"),
                            floats,
                            KokoroEngine.SAMPLE_RATE,
                        )
                    }
                    is SynthesisOutcome.Failed -> throw IllegalArgumentException("synthesis failed [${entry.id}]: ${result.reason}")
                    SynthesisOutcome.Unavailable -> error("packs not ready on device")
                }
            }
            thermal.stop()
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            return JSONObject()
                .put("engine", "kokoro-82m-fp32")
                .put("runs", JSONArray().put(JSONObject().put("passages", rowsJson)))
                .put("vm_hwm_kb", readVmHwm())
                .put("total_pss_kb", mem.totalPss)
                .put("thermal_status_max", thermal.maxStatus)
                .put("thermal_headroom_max", thermal.maxHeadroom)
                .put("screen", "off/locked (instrumented)")
                .put("threads", 6)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("engine_open_ms", engineOpenMs)
                .put("phonemization", "excluded (host-precomputed corpus)")
        } finally {
            engine.close()
        }
    }

    private fun parseCorpus(
        corpusFile: File,
        log: (String) -> Unit,
    ): List<Entry> {
        check(corpusFile.isFile) { "corpus not found at ${corpusFile.absolutePath}" }
        val entries = ArrayList<Entry>()
        for ((index, line) in corpusFile.readLines().withIndex()) {
            if (index == 0 || line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 6) continue
            val id = parts[0]
            val lang = parts[1]
            val text = parts[2]
            val phonemes = parts[3]
            if (phonemes.isBlank()) {
                log("SKIP $id: no kokoro_phonemes")
                continue
            }
            entries += Entry(id, lang, text, phonemes)
        }
        check(entries.isNotEmpty()) { "no runnable kokoro rows in ${corpusFile.name}" }
        return entries
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

    /** Answers phonemization from the precomputed corpus (host espeak-ng). */
    private class CorpusPhonemizer(
        private val corpus: Map<String, String>,
        private val languages: Set<String>,
    ) : Phonemizer {
        override fun phonemize(
            text: String,
            language: String,
        ): String = corpus[text] ?: throw PhonemizeException("corpus mismatch for text: '${text.take(40)}…'")

        override fun supportedLanguages(): Set<String> = languages
    }
}
