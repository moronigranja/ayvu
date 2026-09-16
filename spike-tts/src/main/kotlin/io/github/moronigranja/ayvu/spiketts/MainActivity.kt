package io.github.moronigranja.ayvu.spiketts

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * T3 spike runner: loads the CosyVoice3-0.5B ONNX pipeline (jiangzhuo int4
 * export, sokuji-corrected semantics), synthesizes a fixed English sentence
 * in a bundled voice, and reports RTF, peak RAM and thermal behavior for the
 * engine-order decision.
 */
class MainActivity : Activity() {
    companion object {
        private const val TAG = "T3Spike"
        private val TEXT = (
            "The quick brown fox jumps over the lazy dog, and the orange cat " +
                "sits on the windowsill watching the morning rain fall on the " +
                "empty street below."
        )
        private const val THREADS = 6
        private const val RUNS = 3
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        // A multi-minute benchmark must not let the screen sleep: a demoted
        // foreground app is reclaimed by lmkd under memory pressure.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this)
        status.textSize = 13f
        status.text = "T3 spike starting…"
        scroll.addView(
            status,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)
        Thread { runBenchmark() }.start()
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        runOnUiThread { status.text = status.text.toString() + "\n" + line }
    }

    private fun runBenchmark() {
        val outDir = getExternalFilesDir(null) ?: filesDir
        val extModels = File(outDir, "models")
        // Android 11+ FUSE hides shell-pushed files under Android/data/<pkg>,
        // so the benchmark also accepts models staged in internal storage via
        // `adb shell run-as <pkg> cp -r /data/local/tmp/models files/models`.
        val models =
            if (File(extModels, "onnx/flow_estimator.onnx").exists()) {
                extModels
            } else {
                File(filesDir, "models")
            }
        try {
            check(File(models, "onnx/flow_estimator.onnx").exists()) {
                "models not found at ${models.absolutePath} — push them first"
            }
            log("model dir: ${models.absolutePath}")
            log("device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}, threads=$THREADS")

            val tok = Bpe.load(models)
            log("tokenizer loaded (vocab entries: ${tok.vocab.size})")

            val sessions = Sessions(models, THREADS)
            log("session manager ready (graphs load lazily per stage)")

            val s16 = Wav.read(File(models, "voices/sarah16.wav"))
            val s24 = Wav.read(File(models, "voices/sarah24.wav"))
            val transcript = File(models, "voices/sarah.txt").readText().trim()
            log("prompt wavs: 16k=${s16.size} samples, 24k=${s24.size} samples")

            val pipeline = Pipeline(sessions)
            val tP = System.currentTimeMillis()
            val prompt = pipeline.processPrompt(tok, s16, s24, transcript)
            val promptMs = System.currentTimeMillis() - tP
            var spkNorm = 0.0
            for (v in prompt.spkEmbedding) spkNorm += v * v
            log(
                "prompt processed in $promptMs ms: tokens=${prompt.speechTokens.size}, " +
                    "melFrames=${prompt.melFrames}, spkNorm=${Math.sqrt(spkNorm).toFloat()}",
            )

            // Deterministic prompt mel is host-comparable (matches sokuji's
            // golden-tested melodics); dump it for exact cross-checking.
            val melBytes =
                java.nio.ByteBuffer
                    .allocate(prompt.mel.size * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in prompt.mel) melBytes.putFloat(v)
            File(outDir, "prompt_mel.bin").writeBytes(melBytes.array())
            var pmSum = 0.0
            for (v in prompt.mel) pmSum += v
            log("prompt mel dump written (mean=${"%.3f".format(pmSum / prompt.mel.size)})")

            // Cold graphs (speech_tokenizer 924 MB + campplus) ran once; free them
            // before synthesis so peak native memory stays low enough for lmkd.
            sessions.release(Sessions.COLD_GRAPHS)
            log("released cold graphs")

            val results = JSONObject()
            val runs = org.json.JSONArray()
            val thermal = ThermalProbe(this, TAG)
            thermal.start()
            // D3 corpus mode (decisions #93): when d3_corpus.tsv is staged
            // in filesDir, loop the corpus (first entry RUNS=3, rest RUNS=1)
            // and write d3_results_cosyvoice.json with the same per-run
            // field names; otherwise the legacy single-TEXT run. The sarah
            // prompt is processed once above, shared by every entry.
            val corpusFile = File(filesDir, "d3_corpus.tsv")
            val corpus = if (corpusFile.isFile) parseCorpus(corpusFile) else null
            if (corpus == null) log("no d3_corpus.tsv — legacy single-sentence run")
            results.put("mode", if (corpus != null) "d3_corpus" else "legacy_single_text")
            val entries: List<Triple<String, String, String>> =
                corpus ?: listOf(Triple("", "", TEXT))
            for ((index, entry) in entries.withIndex()) {
                val (id, lang, text) = entry
                val runsForEntry = if (index == 0) RUNS else 1
                for (run in 1..runsForEntry) {
                    runs.put(
                        synthesizeOnce(pipeline, tok, prompt, sessions, text, run, id, lang, outDir),
                    )
                }
            }

            // Premade-voice recheck (#93 follow-up): the blind gate heard
            // wrong-language/duplicated audio from the sarah-cloned probes.
            // Generate the same honorific probe with the pack's premade
            // classic-zh voice (its own prompt wav + transcript) for a
            // like-for-like listen, writing
            // d3_results_cosyvoice_premade.json.
            val classicWav = File(models, "voices/classic-zh.wav")
            if (classicWav.isFile && corpus != null) {
                log("premade-voice pass: classic-zh prompt")
                val cPrompt =
                    pipeline.processPrompt(
                        tok,
                        Wav.read(File(models, "voices/classic-zh16.wav")),
                        Wav.read(File(models, "voices/classic-zh24.wav")),
                        File(models, "voices/classic-zh.txt").readText().trim(),
                    )
                val premade = JSONObject().put("voice", "classic-zh")
                val premadeRuns = org.json.JSONArray()
                val rivera = corpus!!.firstOrNull { it.first == "probe-miss-rivera" }
                if (rivera != null) {
                    premadeRuns.put(
                        synthesizeOnce(
                            pipeline,
                            tok,
                            cPrompt,
                            sessions,
                            rivera.third,
                            1,
                            rivera.first + "-premade",
                            "zh",
                            outDir,
                        ),
                    )
                }
                premadeRuns.put(
                    synthesizeOnce(
                        pipeline,
                        tok,
                        cPrompt,
                        sessions,
                        "你好，这是预置音色的听感测试，希望它比克隆音色更自然。",
                        1,
                        "probe-zh-premade",
                        "zh",
                        outDir,
                    ),
                )
                premade.put("runs", premadeRuns)
                File(outDir, "d3_results_cosyvoice_premade.json")
                    .writeText(premade.toString(2))
                log("d3_results_cosyvoice_premade.json written")
            }

            thermal.stop()
            // run rows were appended above; the shared result block follows.
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            results.put("prompt_ms", promptMs)
            results.put("runs", runs)
            results.put("vm_hwm_kb", readVmHwm())
            results.put("total_pss_kb", mem.totalPss)
            results.put("thermal_status_max", thermal.maxStatus)
            results.put("thermal_headroom_max", thermal.maxHeadroom)
            results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            results.put("threads", THREADS)
            val resultFileName = if (corpus != null) "d3_results_cosyvoice.json" else "results.json"
            File(outDir, resultFileName).writeText(results.toString(2))
            log("$resultFileName written to $outDir")
            log("peak status: ${thermal.maxStatus}, headroom: ${thermal.maxHeadroom}")
            log("VmHWM: ${readVmHwm()} kB, totalPss: ${mem.totalPss} kB")
            log("DONE")
            sessions.close()
        } catch (e: Throwable) {
            Log.e(TAG, "benchmark failed", e)
            log("FAILED: $e\n${e.stackTraceToString().lineSequence().take(6).joinToString("\n")}")
        }
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }

    /**
     * One CosyVoice3 synthesis round: llm → flow → hift with per-stage
     * session release, WAV written per run. [id] empty = legacy single-TEXT
     * run (`out_run$run.wav`); otherwise a D3 corpus row
     * (`d3_cosyvoice_run$run_$id.wav`) and the row id/language land in the
     * run JSON.
     */
    private fun synthesizeOnce(
        pipeline: Pipeline,
        tok: Bpe.Tokenizer,
        prompt: Pipeline.VoicePrompt,
        sessions: Sessions,
        text: String,
        run: Int,
        id: String,
        lang: String,
        outDir: File,
    ): JSONObject {
        val rng = Random(1234L + run)
        val tLlm0 = System.currentTimeMillis()
        val (flowTokens, _) = pipeline.llmGenerate(tok, text, prompt, rng)
        val llmMs = System.currentTimeMillis() - tLlm0
        sessions.release(Sessions.LLM_GROUP)
        val tFlow0 = System.currentTimeMillis()
        val mel = pipeline.flowGenerate(flowTokens, prompt, rng)
        val flowMs = System.currentTimeMillis() - tFlow0
        sessions.release(Sessions.FLOW_GROUP)
        val tHift0 = System.currentTimeMillis()
        val (audio, hiftStats) = pipeline.hiftGenerate(mel, mel.size / 80)
        val hiftMs = System.currentTimeMillis() - tHift0
        sessions.release(Sessions.HIFT_GROUP)
        var fSum = 0.0
        for (v in mel) fSum += v
        val fMean = fSum / mel.size
        var fVar = 0.0
        for (v in mel) fVar += (v - fMean) * (v - fMean)
        val fMax = mel.max()
        log(
            "  flow mel: mean=${"%.3f".format(fMean)} std=${"%.3f".format(Math.sqrt(fVar / mel.size))} " +
                "max=${"%.3f".format(fMax)} | f0: mean=${"%.1f".format(hiftStats.f0Mean)} " +
                "std=${"%.1f".format(hiftStats.f0Std)} | src: rms=${"%.4f".format(hiftStats.srcRms)} len=${hiftStats.srcLen}",
        )
        val synthMs = llmMs + flowMs + hiftMs
        val dur = audio.size.toDouble() / Pipeline.SAMPLE_RATE
        var peak = 0.0f
        var rms = 0.0
        for (v in audio) {
            peak = maxOf(peak, kotlin.math.abs(v))
            rms += v * v
        }
        rms = Math.sqrt(rms / audio.size)
        val finite = audio.all { it.isFinite() }
        val runJson =
            JSONObject()
                .put("run", run)
                .put("llm_ms", llmMs)
                .put("flow_ms", flowMs)
                .put("hift_ms", hiftMs)
                .put("flow_mel_mean", fMean)
                .put("flow_mel_max", fMax)
                .put("f0_mean", hiftStats.f0Mean)
                .put("src_rms", hiftStats.srcRms)
                .put("synth_ms", synthMs)
                .put("audio_seconds", dur)
                .put("rtf", synthMs / 1000.0 / dur)
                .put("samples", audio.size)
                .put("peak_abs", peak)
                .put("rms", rms)
                .put("finite", finite)
        if (id.isNotEmpty()) {
            runJson.put("id", id).put("language", lang)
        }
        val label = if (id.isEmpty()) "run $run" else "run $run [$id]"
        log(
            "$label: llm=$llmMs ms flow=$flowMs ms hift=$hiftMs ms, " +
                "audio=${"%.2f".format(dur)} s, RTF=${"%.3f".format(synthMs / 1000.0 / dur)}, " +
                "peak=$peak, rms=${"%.4f".format(rms)}, finite=$finite",
        )
        val wavName = if (id.isEmpty()) "out_run$run" else "d3_cosyvoice_run${run}_$id"
        Wav.write(File(outDir, "$wavName.wav"), audio, Pipeline.SAMPLE_RATE)
        return runJson
    }

    /** `id \t lang \t raw_text \t …` — only the first three columns are used. */
    private fun parseCorpus(corpusFile: File): List<Triple<String, String, String>> {
        val entries = ArrayList<Triple<String, String, String>>()
        for ((index, line) in corpusFile.readLines().withIndex()) {
            if (index == 0 || line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            entries += Triple(parts[0], parts[1], parts[2])
        }
        return entries
    }
}
