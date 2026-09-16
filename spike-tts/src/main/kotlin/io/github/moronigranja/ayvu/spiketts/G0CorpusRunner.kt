package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build
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
 * G0 narration-corpus device pass: synthesizes every row of the staged
 * five-column `g0_corpus.tsv` (`id, lang, category, text, phonemes`) with the
 * shipped Kokoro engine, writing one `g0_<id>_<lang>_<category>.wav` per
 * entry plus `g0_results.json` = `{"engine_open_ms", "device", "entries":
 * [...]}`. Findings need every line synthesized, so a single entry whose
 * synthesis throws is recorded `{"error": "<msg>"}` and the pass continues —
 * only corpus-parse emptiness aborts (same rule as D3).
 *
 * Phonemization is excluded from measurement: the host-precomputed `phonemes`
 * column is answered by a passthrough [Phonemizer] keyed on `(lang, text)`
 * (en-US/en-GB share verbatim lines whose accents differ, so text alone is
 * not a safe key).
 */
class G0CorpusRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "G0Corpus"

        /** espeak code → first female voice of the family (KokoroVoiceMetadata). */
        private val VOICES =
            mapOf(
                "en-us" to "af_heart",
                "en-gb" to "bf_alice",
                "es" to "ef_dora",
                "fr-fr" to "ff_siwis",
                "it" to "if_sara",
                "pt-br" to "pf_dora",
                "ja" to "jf_alpha",
                "cmn" to "zf_xiaobei",
                "hi" to "hf_alpha",
            )
    }

    private val models = File(context.filesDir, "models")

    private class Entry(
        val id: String,
        val lang: String,
        val category: String,
        val text: String,
        val phonemes: String,
    )

    fun run(
        corpusFile: File,
        outDir: File,
        log: (String) -> Unit,
    ): JSONObject {
        log("G0 corpus: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
        val entries = parseCorpus(corpusFile, log)
        log("g0 corpus: ${entries.size} rows with phonemes")
        val lookup = entries.associate { it.lang to it.text to it.phonemes }
        val languages = entries.map { it.lang }.toSet()

        val result = JSONObject()
        val outFile = File(outDir, "g0_results.json")

        // Flush after every entry: on low-RAM devices lmkd can kill the
        // process mid-pass and the finished entries must survive on disk.
        fun flush() {
            File(outDir, "g0_results.json.tmp").writeText(result.toString(2))
            outFile.delete()
            File(outDir, "g0_results.json.tmp").renameTo(outFile)
        }

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
        result.put("engine_open_ms", engineOpenMs)
        result.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")

        val rowsJson = JSONArray()
        result.put("entries", rowsJson)
        try {
            for (entry in entries) {
                val voice =
                    VOICES[entry.lang]
                        ?: error("no voice mapped for corpus language '${entry.lang}'")
                val row =
                    JSONObject()
                        .put("id", entry.id)
                        .put("lang", entry.lang)
                        .put("category", entry.category)
                        .put("text", entry.text)
                        .put("phonemes", entry.phonemes)
                try {
                    var outcome: SynthesisOutcome? = null
                    val millis =
                        measureTimeMillis {
                            outcome = runBlocking { engine.synthesize(SynthesisRequest(entry.text, voice)) }
                        }
                    when (val res = outcome) {
                        null -> error("synthesis returned null outcome")
                        is SynthesisOutcome.Audio -> {
                            val wavName = "g0_${entry.id}_${entry.lang}_${entry.category}.wav"
                            val floats = pcmToFloats(res.pcm)
                            Wav.write(File(outDir, wavName), floats, KokoroEngine.SAMPLE_RATE)
                            row
                                .put("wav", wavName)
                                .put("synth_ms", millis)
                                .put("sample_rate", KokoroEngine.SAMPLE_RATE)
                                .put("samples", res.pcm.size / 2)
                            log(
                                "[${entry.id}] ${entry.lang}/${entry.category}: " +
                                    "${"%.2f".format(res.pcm.size / 2.0 / KokoroEngine.SAMPLE_RATE)}s audio " +
                                    "in $millis ms",
                            )
                        }
                        is SynthesisOutcome.Failed ->
                            row.put("error", "synthesis failed: ${res.reason}")
                        SynthesisOutcome.Unavailable ->
                            row.put("error", "packs not ready on device")
                    }
                } catch (e: Throwable) {
                    // One entry must not stop the pass — every row needs a verdict.
                    row.put("error", e.message ?: e.toString())
                    log("[${entry.id}] ERROR: ${e.message}")
                }
                rowsJson.put(row)
                flush()
            }
        } finally {
            engine.close()
        }
        flush()
        log("g0_results.json written to $outDir")
        return result
    }

    private fun parseCorpus(
        corpusFile: File,
        log: (String) -> Unit,
    ): List<Entry> {
        check(corpusFile.isFile) { "corpus not found at ${corpusFile.absolutePath}" }
        val entries = ArrayList<Entry>()
        for ((index, line) in corpusFile.readLines().withIndex()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 5) {
                log("SKIP malformed row ${index + 1}")
                continue
            }
            val (id, lang, category, text, phonemes) = parts
            if (phonemes.isBlank()) {
                log("SKIP $id: no phonemes")
                continue
            }
            entries += Entry(id, lang, category, text, phonemes)
        }
        check(entries.isNotEmpty()) { "no runnable g0 rows in ${corpusFile.name}" }
        return entries
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
        private val corpus: Map<Pair<String, String>, String>,
        private val languages: Set<String>,
    ) : Phonemizer {
        override fun phonemize(
            text: String,
            language: String,
        ): String =
            corpus[language to text]
                ?: throw PhonemizeException("corpus mismatch for lang=$language text: '${text.take(40)}…'")

        override fun supportedLanguages(): Set<String> = languages
    }
}
