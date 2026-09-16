package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.zip.ZipFile
import kotlin.system.measureTimeMillis

/**
 * D3 KittenTTS Nano v0.8 leg (decisions #92/#93): measures the pinned
 * `KittenML/kitten-tts-nano-0.8-fp32` pack on the same harness contract as
 * [KokoroBenchmarkRunner] — CPU EP only, 6 intra-op threads, per-entry
 * `synth_ms`/`audio_seconds`/`rtf`/`samples`/`peak_abs`/`rms`/`finite` JSON
 * plus the shared thermal/memory block, WAVs to the external files dir.
 *
 * Graph contract (verified from KittenML/KittenTTS `kittentts/onnx_model.py`
 * @ be57585, Apache-2.0): inputs `input_ids` (int64 [1,N] — espeak-ng en-us
 * phonemes through a TextCleaner symbol table, `0` prepended, `10` and `0`
 * appended; host-generated into the corpus TSV), `style` (fp32 [1,S] row of
 * `voices.npz[voice]`, row index `min(len(text), rows-1)`), `speed`
 * (fp32 [1]); output float audio with the last 5000 samples trimmed,
 * 24 kHz.
 *
 * Token ids come from the TSV (host phonemized + cleaned); rows without
 * `kitten_tokens` (non-en or column left empty) are logged as SKIP and do not
 * abort the pass — lang coverage is itself a measured result.
 */
class KittenBenchmarkRunner(
    private val context: Context,
) {
    companion object {
        const val TAG = "KittenSpike"
        const val SAMPLE_RATE = 24000
        const val THREADS = 6

        /** Voice pinned for the D3 pass: gender-matches the Kokoro af_heart baseline. */
        const val VOICE = "expr-voice-2-f"

        /** Alias map copied verbatim from upstream `all_voice_names` → `expr-voice-*`. */
        val VOICE_ALIASES =
            mapOf(
                "Bella" to "expr-voice-2-f",
                "Jasper" to "expr-voice-2-m",
                "Luna" to "expr-voice-3-f",
                "Bruno" to "expr-voice-3-m",
                "Rosie" to "expr-voice-4-f",
                "Hugo" to "expr-voice-4-m",
                "Kiki" to "expr-voice-5-f",
                "Leo" to "expr-voice-5-m",
            )

        /**
         * Graph hard cap measured host-side on the pinned fp32 pack: total
         * sequence ≤ 509 tokens (incl. `0` prefix and `10`,`0` suffix) —
         * anything longer fails in `/bert/Expand` ("invalid expand shape").
         * Upstream never hits this because `generate()` chunks text before
         * synthesis; we chunk the token stream at punctuation boundaries and
         * concatenate the per-chunk audio (same framing per chunk).
         */
        const val MAX_SEQUENCE = 509
        private val BOUNDARY_TOKENS = setOf(3L, 4L, 5L, 6L) // , . ! ?
    }

    private val models = File(context.filesDir, "models/kitten")
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * [optProfile]: the S22 (ARM, ORT-android 1.23.2) produced NaN audio for
     * this fp32 graph with the default session options while the same inputs
     * are finite host-side (x86 ORT 1.23.2/1.24.3) — the profiles sweep the
     * session-option axes to isolate the divergence for decisions #93.
     */
    fun run(
        corpusFile: File,
        outDir: File,
        log: (String) -> Unit,
        threads: Int = THREADS,
        optProfile: String = "default",
        limit: Int = 0,
    ): JSONObject {
        val results = JSONObject()
        results.put("opt_profile", optProfile)
        results.put("session_threads", threads)
        var sessionRef: OrtSession? = null
        return try {
            val modelFile = File(models, "kitten_tts_nano_v0_8.onnx")
            val voicesFile = File(models, "voices.npz")
            check(modelFile.isFile && voicesFile.isFile) {
                "kitten pack not found under ${models.absolutePath} — stage it first (see build.md)"
            }
            val allEntries = parseCorpus(corpusFile)
            val entries = if (limit > 0) allEntries.take(limit) else allEntries
            log("candidate=kitten device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}")
            log("corpus: ${entries.size} runnable of ${countLines(corpusFile)} rows (${models.absolutePath})")
            log("voice: $VOICE; opt_profile=$optProfile threads=$threads")

            val tOpen = System.currentTimeMillis()
            val options =
                OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    when (optProfile) {
                        "basic" -> setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                        "memOff" -> setMemoryPatternOptimization(false)
                        "arenaOff" -> setCPUArenaAllocator(false)
                        "bothOff" -> {
                            setMemoryPatternOptimization(false)
                            setCPUArenaAllocator(false)
                        }
                    }
                }
            val session = env.createSession(modelFile.absolutePath, options)
            sessionRef = session
            val engineOpenMs = System.currentTimeMillis() - tOpen
            log("engine open: $engineOpenMs ms (candidate=kitten)")
            val voices = NpzReader.load(voicesFile)
            val styleTable =
                voices[VOICE]
                    ?: error("voice '$VOICE' not in voices.npz (${voices.keys.sorted()})")

            val thermal = ThermalProbe(context, TAG)
            thermal.start()
            val runsJson = JSONArray()
            val runJson = JSONObject().put("run", 1)
            val rowsJson = JSONArray()
            for (entry in entries) {
                val styleRow = minOf(entry.text.length, styleTable.rows - 1)
                val style = styleTable.row(styleRow)
                val chunks = chunkTokens(entry.tokens)
                var result: FloatArray = FloatArray(0)
                val millis =
                    measureTimeMillis {
                        for (chunk in chunks) {
                            result += synthesize(session, chunk, style, entry.speed)
                        }
                    }
                val seconds = result.size / 2.0 / SAMPLE_RATE
                var peak = 0.0f
                var rms = 0.0
                for (v in result) {
                    peak = maxOf(peak, kotlin.math.abs(v))
                    rms += v * v
                }
                rms = Math.sqrt(rms / result.size)
                val finite = result.all { it.isFinite() }
                val rtf = if (seconds > 0) millis / 1000.0 / seconds else Double.NaN

                fun num(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

                fun fnum(v: Float): Any = if (v.isFinite()) v else JSONObject.NULL
                rowsJson.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("language", entry.lang)
                        .put("voice", VOICE)
                        .put("style_row", styleRow)
                        .put("chunks", chunks.size)
                        .put("tokens", entry.tokens.size)
                        .put("synth_ms", millis)
                        .put("audio_seconds", seconds)
                        .put("rtf", num(rtf))
                        .put("samples", result.size)
                        .put("peak_abs", fnum(peak))
                        .put("rms", num(rms))
                        .put("finite", finite),
                )
                log(
                    "run 1 [${entry.id}]: ${"%.2f".format(seconds)}s audio in $millis ms, " +
                        "RTF=${"%.3f".format(rtf)}, rms=${"%.4f".format(rms)}",
                )
                Wav.write(File(outDir, "d3_kitten_run1_${entry.id}.wav"), result, SAMPLE_RATE)
            }
            runJson.put("passages", rowsJson)
            runsJson.put(runJson)
            thermal.stop()
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            results.put("engine", "kitten-tts-nano-0.8-fp32")
            results.put("voice", VOICE)
            results.put("sample_rate", SAMPLE_RATE)
            results.put("runs", runsJson)
            results.put("vm_hwm_kb", readVmHwm())
            results.put("total_pss_kb", mem.totalPss)
            results.put("thermal_status_max", thermal.maxStatus)
            results.put("thermal_headroom_max", thermal.maxHeadroom)
            results.put("screen", "off/locked (instrumented)")
            results.put("threads", THREADS)
            results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            results.put("engine_open_ms", engineOpenMs)
            results.put("phonemization", "excluded (host-precomputed corpus)")
            options.close()
            log("VmHWM: ${readVmHwm()} kB, totalPss: ${mem.totalPss} kB")
            log("DONE (candidate=kitten)")
            results
        } catch (e: Throwable) {
            log("candidate kitten unavailable: $e")
            results.put("unavailable", e.message ?: e.toString())
            results
        } finally {
            // Late-initialization failures (bad npz, missing voice) must not
            // leak the 56 MB session into the following legs.
            runCatching { sessionRef?.close() }
            sessionRef = null
        }
    }

    private class Entry(
        val id: String,
        val lang: String,
        val text: String,
        val tokens: LongArray,
        val speed: Float,
    )

    private fun countLines(corpusFile: File): Int = corpusFile.readLines().count { it.isNotBlank() } - 1

    /**
     * Loads `d3_corpus.tsv` (`id \t lang \t raw_text \t kokoro_phonemes \t
     * kitten_tokens \t moss_token_ids`); rows without kitten tokens are
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
            val text = parts[2]
            val kitten = parts[4]
            if (kitten.isBlank()) {
                android.util.Log.d(TAG, "SKIP $id: no kitten_tokens (lang=$lang)")
                continue
            }
            val tokens = kitten.split(',').map { it.trim().toLong() }
            entries += Entry(id, lang, text, tokens.toLongArray(), 1.0f)
        }
        check(entries.isNotEmpty()) { "no runnable kitten rows in ${corpusFile.name}" }
        return entries
    }

    /**
     * Splits a token stream into graph-capacity chunks: cut at the last
     * punctuation boundary that keeps the framed sequence ≤ [MAX_SEQUENCE],
     * hard-split when no boundary exists. Each chunk is framed (`0` …
     * `10`,`0`) by [synthesize].
     */
    internal fun chunkTokens(tokens: LongArray): List<LongArray> {
        if (tokens.size <= MAX_SEQUENCE) return listOf(tokens)
        val out = ArrayList<LongArray>()
        var start = 0
        while (start < tokens.size) {
            val room = MAX_SEQUENCE - 3 // 0 prefix + 10,0 suffix
            val end = minOf(start + room, tokens.size)
            if (end < tokens.size) {
                var cut = -1
                for (i in end - 1 downTo start + 1) {
                    if (tokens[i] in BOUNDARY_TOKENS) {
                        cut = i + 1
                        break
                    }
                }
                if (cut > start) out += tokens.copyOfRange(start, cut) else out += tokens.copyOfRange(start, end)
                start = if (cut > start) cut else end
            } else {
                out += tokens.copyOfRange(start, end)
                start = end
            }
        }
        return out
    }

    private fun synthesize(
        session: OrtSession,
        tokens: LongArray,
        style: FloatArray,
        speed: Float,
    ): FloatArray {
        OnnxTensor
            .createTensor(
                env,
                LongBuffer.wrap(tokens),
                longArrayOf(1, tokens.size.toLong()),
            ).use { inputIds ->
                OnnxTensor
                    .createTensor(
                        env,
                        FloatBuffer.wrap(style),
                        longArrayOf(1, style.size.toLong()),
                    ).use { styleTensor ->
                        OnnxTensor
                            .createTensor(
                                env,
                                FloatBuffer.wrap(floatArrayOf(speed)),
                                longArrayOf(1),
                            ).use { speedTensor ->
                                session
                                    .run(
                                        mapOf(
                                            "input_ids" to inputIds,
                                            "style" to styleTensor,
                                            "speed" to speedTensor,
                                        ),
                                    ).use { outputs ->
                                        val name = session.outputNames.iterator().next()
                                        val raw =
                                            when (val v = (outputs.get(name).orElse(null) as? OnnxTensor)?.value) {
                                                is Array<*> -> (v[0] as FloatArray)
                                                is FloatArray -> v
                                                else -> error("unexpected output type: ${v?.javaClass}")
                                            }
                                        // Upstream trims `audio[..., :-5000]` (tail padding).
                                        if (raw.size <= 5000) return FloatArray(0)
                                        return raw.copyOfRange(0, raw.size - 5000)
                                    }
                            }
                    }
            }
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }
}

/**
 * Minimal `.npz` reader: a zip of `.npy` v1 files. Parses the numpy header
 * (magic, version, header length, `descr`/`shape`) and the little-endian
 * fp32 payload. Only what `voices.npz` needs — no numpy dependency exists in
 * the repo.
 */
internal object NpzReader {
    class NpyArray(
        val rows: Int,
        val cols: Int,
        private val data: FloatArray,
    ) {
        /** Row [index] copied out (the model consumes one style row per call). */
        fun row(index: Int): FloatArray = data.copyOfRange(index * cols, (index + 1) * cols)
    }

    fun load(file: File): Map<String, NpyArray> {
        ZipFile(file).use { zip ->
            val out = LinkedHashMap<String, NpyArray>()
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".npy")) continue
                out[entry.name.removeSuffix(".npy")] = readNpy(zip.getInputStream(entry).readBytes())
            }
            return out
        }
    }

    private fun readNpy(bytes: ByteArray): NpyArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(buf.int == 0x4D554E93) { "not a .npy member" } // \x93NUM (of \x93NUMPY, little-endian)
        check(buf.short == 0x5950.toShort()) { "not a .npy member" } // 'PY'
        val major = buf.get().toInt()
        buf.get() // minor
        val headerLen = if (major == 1) buf.short.toInt() and 0xFFFF else buf.int
        val header = String(bytes, buf.position(), headerLen, Charsets.UTF_8)
        val descr =
            Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1)
                ?: error("npy header missing descr")
        check(descr == "<f4" || descr == "|f4") { "unsupported npy dtype $descr (need fp32)" }
        val shapeStr =
            Regex("'shape':\\s*\\(([^)]*)\\)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.trimEnd(',') ?: error("npy header missing shape")
        val dims =
            shapeStr
                .split(',')
                .map { part -> part.trim().filter { c -> c.isDigit() }.toLong() }
                .map { if (it == 0L) 1L else it }
        val rows = dims.getOrNull(0)?.toInt() ?: 1
        val cols = dims.getOrNull(1)?.toInt() ?: 1
        val count = rows * cols
        val out = FloatArray(count)
        val fb = buf.asFloatBuffer()
        fb.get(out)
        return NpyArray(rows, cols, out)
    }
}
