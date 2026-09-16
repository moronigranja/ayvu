package io.github.moronigranja.ayvu.spiketts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Debug
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import io.github.moronigranja.ayvu.tts.kokoro.KokoroSession
import io.github.moronigranja.ayvu.tts.kokoro.KokoroTokenizer
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVocabulary
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceBank
import io.github.moronigranja.ayvu.tts.kokoro.OrtKokoroSession
import io.github.moronigranja.ayvu.tts.kokoro.PhonemeChunker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * Cross-app performance spike (decisions #148) — the six device numbers the
 * Phase D work is decided on: an int8 tier + its perceptual/blind evidence
 * (leg A), window length vs time-to-first-audio (leg B), per-window AudioTrack
 * feeding (leg C), scheduling including ADPF hint sessions (leg D), duty-cycle
 * energy (leg E), and the int4 / XNNPACK questions (legs F1/F2).
 *
 * None of this ships: no pack, engine or settings change comes out of it. The
 * corpus and models are the pinned fp32/voices pair plus the NekoSpeak int8
 * (92,361,271 B, `6e742170…`) staged per `docs/build.md`.
 *
 * Energy: every leg that reports power holds a `PARTIAL_WAKE_LOCK` for its
 * duration and the device must be **unplugged** — a plugged battery current is
 * a charge current, not a load, so `PowerProbe` reports mW only for on-battery
 * samples and a leg below [ENERGY_VALID_MIN_UNPLUGGED] is reported as "energy
 * not measured" instead of a number. Screen off (instrumented runs are exempt
 * from the process freezer); never `svc power stayon true` for these legs.
 *
 * Results flush to `perfspike_<leg>.json` after every step so an lmkd kill or
 * a cable pull costs one leg, not the run.
 */
class PerfSpikeRunner(
    private val context: Context,
    /**
     * Retry mode for the 3.9 GB HiBreak: disables ORT's memory pattern
     * optimization and CPU arena allocator (the MOSS lesson) in every session
     * this runner opens. Leg A cannot honour it — its session options belong to
     * [KokoroBenchmarkRunner.runPrecision] — which its JSON records.
     */
    private val memOff: Boolean = false,
) {
    companion object {
        const val RUNS = 3
        const val THREADS = 6
        const val IDLE_BASELINE_MS = 60_000L
        const val FP32_MODEL = "kokoro-model"
        const val INT8_MODEL = "kokoro-model-int8"
        const val RESHAPED_MODEL = "kokoro-model-2d"
        val WINDOW_CAPS = intArrayOf(150, 300, 510)
        const val SAMPLE_RATE = 24_000.0

        /** Below this unplugged fraction a leg's power fields are null. */
        const val ENERGY_VALID_MIN_UNPLUGGED = 0.9

        /**
         * Below this mean power the battery gauge is reporting nonsense rather
         * than a load. The S22 Ultra (SM-S908U1) reports ~0.3 mA and a frozen
         * charge counter while synthesizing (~4 W; the level drops 7% in 20 min),
         * which would otherwise look like a *valid* 1 mW energy measurement.
         * A working gauge on any phone we measure clears this floor by 1000x.
         */
        const val ENERGY_PLAUSIBLE_MIN_MW = 50.0

        // Int8 tier provenance (leg A). The released NekoSpeak asset names its
        // waveform output `audio`, which `OrtKokoroSession` rejects; the staged
        // file is that asset with the output renamed (metadata only, host-verified
        // bit-identical) so the *numbers* can be measured on the production path.
        // Shipping it would still need an output-name alias in the engine.
        const val INT8_RELEASE_URL = "https://github.com/siva-sub/NekoSpeak/releases/download/v1.0.0/kokoro-v1.0.int8.onnx"
        const val INT8_RELEASE_SHA256 = "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb"
        const val INT8_RELEASE_SIZE = 92_361_271L
        const val INT8_STAGED_SHA256 = "0b81e78af8e9266bc77164e737d9e9e80e0354a9cbd37dc3b51b4737f62f6d86"
        const val INT8_TRANSFORM = "output renamed audio->waveform (tools/adapt_kokoro_int8_output.py; bit-identical)"

        /** Wall-clock energy normalised to one hour of produced audio, Wh. */
        fun whPerAudioHour(
            meanPowerMw: Double,
            wallMs: Long,
            audioSeconds: Double,
        ): Double = (meanPowerMw / 1000.0) * (wallMs / 3_600_000.0) / (audioSeconds / 3600.0)

        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
    }

    private val models = File(context.filesDir, "models")
    private val outDir: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "perfspike")
    }

    private data class Passage(
        val voice: String,
        val phonemes: String,
    )

    /** One inference window, tokenized + style-ready once. */
    private data class Window(
        val tokens: IntArray,
        val style: FloatArray,
    )

    /** Per-window timings for one leg (union of every pass) plus the best pass. */
    private data class Timings(
        val windowMs: LongArray,
        val firstWindowMs: Long,
        val bestWallMs: Long,
        val audioSeconds: Double,
        val passes: Int,
    )

    // ------------------------------------------------------------------ legs

    /**
     * Leg A — the int8 tier: [KokoroBenchmarkRunner.runPrecision] (oracle-gated
     * against the pinned fp32, WAV pairs for the blind set) repeated [runs]
     * times with energy sampled around each pass, plus two same-session fp32
     * control passes so the int8 number has a live reference.
     */
    fun runInt8Tier(
        runs: Int,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_a.json")
        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("screen", screenState())
                .put("runs", runs)
                .put("mem_off", false)
                .put(
                    "mem_off_note",
                    "leg A runs through KokoroBenchmarkRunner.runPrecision, which owns its session options",
                )
        return try {
            if (!requireModels(listOf(INT8_MODEL, FP32_MODEL, "kokoro-voices"), log)) return false
            val corpus = File(context.filesDir, "corpus.tsv")
            check(corpus.isFile) { "corpus not found at ${corpus.absolutePath}" }
            val languages = corpus.readLines().mapNotNull { it.split('\t').getOrNull(1) }.distinct()

            val int8 = File(models, INT8_MODEL)
            val stagedSha = sha256(int8)
            val artifact =
                JSONObject()
                    .put("file", INT8_MODEL)
                    // The release digest identifies the weights measured; `staged_sha256`
                    // is the byte-level truth, because the file staged here is that asset
                    // with its waveform output renamed (see `transform`).
                    .put("sha256", INT8_RELEASE_SHA256)
                    .put("size_bytes", INT8_RELEASE_SIZE)
                    .put(
                        "release",
                        JSONObject()
                            .put("url", INT8_RELEASE_URL)
                            .put("sha256", INT8_RELEASE_SHA256)
                            .put("size_bytes", INT8_RELEASE_SIZE),
                    ).put("staged_sha256", stagedSha)
                    .put("staged_size_bytes", int8.length())
                    .put("expected_staged_sha256", INT8_STAGED_SHA256)
                    .put("staged_matches_expected", stagedSha == INT8_STAGED_SHA256)
                    .put("transform", INT8_TRANSFORM)
            results.put("artifact", artifact)
            flush(outFile, results)
            log(
                "artifact: $INT8_MODEL ${int8.length()} B staged sha256 $stagedSha " +
                    "(release ${INT8_RELEASE_SHA256}, rename=$INT8_TRANSFORM, " +
                    "matches expected=${artifact.getBoolean("staged_matches_expected")})",
            )

            val idle = measureIdleBaseline()
            results.put("idle", idle)
            flush(outFile, results)
            log(
                "idle: power ${"%.0f".format(idle.getDouble("power_mean_mw"))} mW, " +
                    "current ${"%.0f".format(idle.getDouble("current_mean_ma"))} mA, " +
                    "batt ${"%.1f".format(idle.getDouble("max_battery_temp_c"))}°C, " +
                    "unplugged ${"%.0f".format(idle.getDouble("unplugged_fraction") * 100)}%",
            )

            val runner = KokoroBenchmarkRunner(context)
            val passes = JSONArray()
            var energyValidAll = true
            withWakeLock {
                for (pass in 1..runs) {
                    log("int8 pass $pass/$runs…")
                    val thermal = ThermalProbe(context)
                    val power = PowerProbe(context)
                    thermal.start()
                    power.start()
                    val ok = runner.runPrecision(KokoroBenchmarkRunner.ModelPrecision.INT8, log)
                    power.stop()
                    thermal.stop()

                    val source = File(outDir, "kokoro_precision_int8.json")
                    val copy = File(outDir, "perfspike_a_pass$pass.json")
                    val passJson =
                        if (ok && source.isFile) {
                            val parsed = JSONObject(source.readText())
                            injectEnergy(
                                parsed,
                                power,
                                thermal,
                                idle.getDouble("power_mean_mw"),
                                passJson = true,
                            )
                            copy.writeText(parsed.toString(2))
                            parsed
                        } else {
                            JSONObject().put("error", "runPrecision(INT8) failed")
                        }
                    passJson.put("pass", pass)
                    // Only a pass that actually synthesized may contribute WAVs: copying
                    // unconditionally would publish a *previous* run's files as this
                    // pass's int8/fp32 evidence (measured on the HiBreak 2026-09-11,
                    // where the un-renamed int8 model failed and stale 2026-08-31 WAVs
                    // were listed as fresh).
                    val wavs = JSONArray()
                    if (ok) {
                        for (language in languages) {
                            copyWav(outDir, "kokoro_int8_run1_$language.wav", "perfspike_a_pass${pass}_int8_$language.wav")
                                ?.let { wavs.put(it) }
                            copyWav(outDir, "kokoro_int8_oracle_$language.wav", "perfspike_a_pass${pass}_fp32_$language.wav")
                                ?.let { wavs.put(it) }
                        }
                    } else {
                        for (language in languages) {
                            File(outDir, "perfspike_a_pass${pass}_int8_$language.wav").delete()
                            File(outDir, "perfspike_a_pass${pass}_fp32_$language.wav").delete()
                        }
                    }
                    passJson.put("wavs", wavs)
                    passes.put(passJson)
                    if (!passJson.optBoolean("energy_valid", false)) energyValidAll = false
                    results.put("passes", passes)
                    results.put("energy_valid_all", energyValidAll)
                    flush(outFile, results)
                    val rtf = passRtf(passJson)
                    log(
                        "int8 pass $pass/$runs: RTF ${if (rtf != null) "%.3f".format(rtf) else "n/a"}, " +
                            "unplugged ${"%.0f".format(passJson.optDouble("unplugged_fraction", 0.0) * 100)}%, " +
                            "power ${"%.0f".format(passJson.optDouble("power_mean_mw", 0.0))} mW, " +
                            "energy ${"%.3f".format(passJson.optDouble("wh_per_audio_hour", 0.0))} Wh/audio-h DONE",
                    )
                }

                val controls = JSONArray()
                for (pass in 1..2) {
                    log("fp32 control pass $pass/2…")
                    val thermal = ThermalProbe(context)
                    val power = PowerProbe(context)
                    thermal.start()
                    power.start()
                    val ok = runner.run(KokoroBenchmarkRunner.OrtProvider.CPU, log)
                    power.stop()
                    thermal.stop()
                    val source = File(outDir, "kokoro_results_cpu.json")
                    val controlJson =
                        if (ok && source.isFile) {
                            val parsed = JSONObject(source.readText())
                            injectEnergy(parsed, power, thermal, idle.getDouble("power_mean_mw"), passJson = false)
                            File(outDir, "perfspike_a_fp32_pass$pass.json").writeText(parsed.toString(2))
                            JSONObject()
                                .put("pass", pass)
                                .put("file", "perfspike_a_fp32_pass$pass.json")
                                .put("rtf", passRtf(parsed) ?: JSONObject.NULL)
                                .put("power_mean_mw", parsed.opt("power_mean_mw") ?: JSONObject.NULL)
                                .put("unplugged_fraction", parsed.optDouble("unplugged_fraction", 0.0))
                                .put("wh_per_audio_hour", parsed.opt("wh_per_audio_hour") ?: JSONObject.NULL)
                                .put("results_file", parsed)
                        } else {
                            JSONObject().put("pass", pass).put("error", "run(CPU) failed")
                        }
                    controls.put(controlJson)
                    results.put("fp32_reference_runs", controls)
                    flush(outFile, results)
                    log("fp32 control pass $pass/2: RTF ${controlJson.opt("rtf")} DONE")
                }
            }
            flush(outFile, results)
            log("perfspike_a.json written to $outDir")
            true
        } catch (e: Throwable) {
            log("leg A unavailable: $e")
            results.put("error", e.toString())
            flush(outFile, results)
            false
        }
    }

    /**
     * Leg B — window length vs latency/throughput on the 16-passage corpus:
     * one session per cap, warm-up window, then [runs] timed passes.
     */
    fun runWindowLength(
        corpusName: String,
        runs: Int,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_b.json")
        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("screen", screenState())
                .put("corpus", corpusName)
                .put("runs", runs)
                .put("window_ms_stats_over", "all timed windows across passes")
                .put("mem_off", memOff)
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val rows = corpusRows(corpusName, null)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            results.put("passages", rows.size)
            log("corpus: $corpusName — ${rows.size} passages, caps ${WINDOW_CAPS.joinToString(", ")}")

            val caps = JSONArray()
            for (cap in WINDOW_CAPS.sorted()) {
                val windows = rows.flatMap { windowsOf(it, tokenizer, voices, cap) }
                log("cap $cap: ${windows.size} windows…")
                val tOpen = System.currentTimeMillis()
                val session =
                    OrtKokoroSession.open(
                        File(models, FP32_MODEL),
                        sessionFactory = factory(THREADS),
                    )
                val openMs = System.currentTimeMillis() - tOpen
                session.use {
                    session.infer(windows.first().tokens, windows.first().style, 1.0)
                    val thermal = ThermalProbe(context)
                    thermal.start()
                    val timings = timePasses(session, windows, runs, cap, log)
                    thermal.stop()
                    val mem = Debug.MemoryInfo()
                    Debug.getMemoryInfo(mem)
                    val entry = timingJson(timings)
                    entry.put("cap", cap)
                    entry.put("windows", windows.size)
                    entry.put("engine_open_ms", openMs)
                    entry.put("vm_hwm_kb", readVmHwm())
                    entry.put("total_pss_kb", mem.totalPss)
                    entry.put("thermal_status_max", thermal.maxStatus)
                    entry.put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
                    caps.put(entry)
                    results.put("caps", caps)
                    flush(outFile, results)
                    log(
                        "cap $cap: ${"%.2f".format(timings.audioSeconds)}s audio best ${timings.bestWallMs} ms, " +
                            "RTF ${"%.3f".format(entry.getDouble("rtf"))}, first window ${timings.firstWindowMs} ms, " +
                            "p50 ${entry.getLong("window_ms_p50")} p95 ${entry.getLong("window_ms_p95")} " +
                            "max ${entry.getLong("window_ms_max")} ms",
                    )
                }
            }
            flush(outFile, results)
            log("perfspike_b.json written to $outDir")
            true
        } catch (e: Throwable) {
            log("leg B unavailable: $e")
            results.put("error", e.toString())
            flush(outFile, results)
            false
        }
    }

    /**
     * Leg C — whole-passage MODE_STATIC (the shipped path) vs per-window
     * MODE_STREAM feeding, on the 2-passage corpus. The verdict is conditional:
     * per-window feeding is worth wiring into playback only if it never
     * underruns and reaches first audio in ≤70% of the static path's time.
     */
    fun runIncrementalOutput(log: (String) -> Unit): Boolean {
        val outFile = File(outDir, "perfspike_c.json")
        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("screen", screenState())
                .put("corpus", "corpus.tsv")
                .put("volume", 0.05)
                .put("first_audio_ms_definition", "play() until playbackHeadPosition > 0")
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val rows = corpusRows("corpus.tsv", null)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = rows.flatMap { windowsOf(it, tokenizer, voices, null) }
            results.put("passages", rows.size).put("windows", windows.size)

            val session =
                OrtKokoroSession.open(File(models, FP32_MODEL), sessionFactory = factory(THREADS))
            session.use {
                session.infer(windows.first().tokens, windows.first().style, 1.0)

                log("static: synthesizing ${windows.size} windows…")
                val synthStart = System.currentTimeMillis()
                val pcm = concatPcm(windows.map { pcm16(it, session) })
                val synthMs = System.currentTimeMillis() - synthStart
                val audioSeconds = pcm.size / 2.0 / SAMPLE_RATE
                val staticResult = playStatic(pcm, audioSeconds) { log(it) }
                staticResult.put("mode", "static")
                staticResult.put("audio_seconds", audioSeconds)
                staticResult.put("rtf_synth", synthMs / 1000.0 / audioSeconds)
                results.put("static", staticResult)
                flush(outFile, results)
                log("static: first audio ${staticResult.opt("first_audio_ms")} ms, ${staticResult.opt("underrun_count")} underruns")

                log("stream: feeding window by window…")
                val streamResult = playStream(windows, session, audioSeconds) { log(it) }
                streamResult.put("mode", "stream")
                streamResult.put("audio_seconds", audioSeconds)
                results.put("stream", streamResult)
                flush(outFile, results)
                log("stream: first audio ${streamResult.opt("first_audio_ms")} ms, ${streamResult.opt("underrun_count")} underruns")

                val staticFirst = staticResult.optDouble("first_audio_ms", Double.NaN)
                val streamFirst = streamResult.optDouble("first_audio_ms", Double.NaN)
                val streamUnderruns = streamResult.optInt("underrun_count", -1)
                val verdict =
                    when {
                        streamUnderruns < 0 || streamFirst.isNaN() || staticFirst.isNaN() -> "inconclusive"
                        streamUnderruns == 0 && streamFirst <= 0.7 * staticFirst -> "feed_per_window"
                        else -> "keep_whole_passage_static"
                    }
                results.put(
                    "verdict_rule",
                    "stream.underrun_count == 0 && stream.first_audio_ms <= 0.7 * static.first_audio_ms",
                )
                results.put("verdict", verdict)
                flush(outFile, results)
                log("verdict: $verdict")
            }
            log("perfspike_c.json written to $outDir")
            true
        } catch (e: Throwable) {
            log("leg C unavailable: $e")
            results.put("error", e.toString())
            flush(outFile, results)
            false
        }
    }

    /**
     * Leg D — scheduling: baseline, no-spin, URGENT_AUDIO priority, ADPF hint
     * session and ADPF power-efficiency preference. Test sessions are fixed at
     * create, so every config opens a fresh one.
     */
    fun runScheduling(
        threads: Int,
        runs: Int,
        corpusName: String,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_d.json")
        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("screen", screenState())
                .put("corpus", corpusName)
                .put("threads", threads)
                .put("runs", runs)
                .put("mem_off", memOff)
                .put(
                    "cpus_observed_note",
                    "PowerProbe samples the calling thread only — this reports placement, it does not prove pool placement",
                )
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val rows = corpusRows(corpusName, 8)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = rows.flatMap { windowsOf(it, tokenizer, voices, null) }
            results.put("passages", rows.size).put("windows", windows.size)
            log("scheduling: ${rows.size} passages, ${windows.size} windows, $runs run(s) per config")

            var adpfTargetNs = 0L
            for (config in configs) {
                if (config.adpf && Build.VERSION.SDK_INT < 31) {
                    results.put(
                        config.id,
                        JSONObject()
                            .put("status", "unsupported")
                            .put("sdk", Build.VERSION.SDK_INT)
                            .put("reason", "PerformanceHintManager requires API 31"),
                    )
                    flush(outFile, results)
                    log("${config.id}: unsupported (SDK ${Build.VERSION.SDK_INT} < 31)")
                    continue
                }
                if (config.powerEfficient && Build.VERSION.SDK_INT < 35) {
                    results.put(
                        config.id,
                        JSONObject()
                            .put("status", "unsupported")
                            .put("sdk", Build.VERSION.SDK_INT)
                            .put("reason", "Session.setPreferPowerEfficiency requires API 35"),
                    )
                    flush(outFile, results)
                    log("${config.id}: unsupported (SDK ${Build.VERSION.SDK_INT} < 35)")
                    continue
                }
                log("${config.id}: opening session…")
                val tOpen = System.currentTimeMillis()
                val session =
                    OrtKokoroSession.open(
                        File(models, FP32_MODEL),
                        sessionFactory =
                            factory(threads) { options ->
                                if (config.noSpin) options.addConfigEntry("session.intra_op.allow_spinning", "0")
                            },
                    )
                val openMs = System.currentTimeMillis() - tOpen
                session.use {
                    session.infer(windows.first().tokens, windows.first().style, 1.0)
                    val entry: JSONObject
                    val originalPriority = Process.getThreadPriority(Process.myTid())
                    if (config.priority) {
                        Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
                    }
                    try {
                        if (config.adpf) {
                            val (ids, poolHinted) = discoverOrtThreads(threads, log)
                            entry =
                                runAdpfConfig(
                                    session,
                                    windows,
                                    runs,
                                    config,
                                    ids,
                                    poolHinted,
                                    adpfTargetNs,
                                    log,
                                )
                        } else {
                            entry = runPlainConfig(session, windows, runs, config, log)
                        }
                    } finally {
                        if (config.priority) {
                            Process.setThreadPriority(Process.myTid(), originalPriority)
                        }
                    }
                    entry.put("engine_open_ms", openMs)
                    entry.put("mem_off", memOff)
                    results.put(config.id, entry)
                    flush(outFile, results)
                    if (config.id == "d2_prio") {
                        adpfTargetNs = entry.getLong("window_ms_p50") * 1_000_000L
                        log("d2_prio median window ${entry.getLong("window_ms_p50")} ms → ADPF target $adpfTargetNs ns")
                    }
                    log(
                        "${config.id}: RTF ${"%.3f".format(entry.optDouble("rtf", Double.NaN))}, " +
                            "first window ${entry.optLong("first_window_ms", -1)} ms, " +
                            "p95 ${entry.optLong("window_ms_p95", -1)} ms, " +
                            "power ${entry.optDouble("power_mean_mw", 0.0).let { "%.0f".format(it) }} mW, " +
                            "threads hinted ${entry.opt("adpf_thread_ids") ?: "n/a"}",
                    )
                }
            }
            flush(outFile, results)
            log("perfspike_d.json written to $outDir")
            true
        } catch (e: Throwable) {
            log("leg D unavailable: $e")
            results.put("error", e.toString())
            flush(outFile, results)
            false
        }
    }

    /**
     * Leg E — duty cycling: the same passages back-to-back, then in 50% and 33%
     * duty cycles, to see whether letting the SoC rest between bursts lowers
     * energy per hour of audio faster than it costs wall time.
     */
    fun runDutyCycle(
        corpusName: String,
        passages: Int,
        runs: Int,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_e.json")
        val results =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("screen", screenState())
                .put("corpus", corpusName)
                .put("passages", passages)
                .put("runs", runs)
                .put("mem_off", memOff)
                .put("rtf_definition", "infer wall / produced audio (synthesis only)")
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val list = corpusRows(corpusName, passages)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = list.flatMap { windowsOf(it, tokenizer, voices, null) }
            results.put("windows", windows.size)
            log("duty cycle: ${list.size} passages, ${windows.size} windows, $runs pass(es) per mode")

            val idle = measureIdleBaseline()
            results.put("idle", idle)
            flush(outFile, results)
            log("idle baseline: ${"%.0f".format(idle.getDouble("power_mean_mw"))} mW")

            val modeRows = JSONArray()
            for (mode in modes) {
                var audioSeconds = 0.0
                var inferMs = 0L
                var idleMs = 0L
                var wallTotal = 0L
                var bestRtf = Double.MAX_VALUE
                for (pass in 1..runs) {
                    val session =
                        OrtKokoroSession.open(File(models, FP32_MODEL), sessionFactory = factory(THREADS))
                    session.use {
                        session.infer(windows.first().tokens, windows.first().style, 1.0)
                        val thermal = ThermalProbe(context)
                        val power = PowerProbe(context)
                        thermal.start()
                        power.start()
                        withWakeLock {
                            var cycleInferMs = 0L
                            var passAudio = 0.0
                            val passStart = System.currentTimeMillis()
                            var passInfer = 0L
                            var passIdle = 0L
                            for (window in windows) {
                                val windowMs =
                                    measureTimeMillis {
                                        passAudio += session.infer(window.tokens, window.style, 1.0).audio.size / SAMPLE_RATE
                                    }
                                passInfer += windowMs
                                cycleInferMs += windowMs
                                if (mode.inferTargetMs > 0 && cycleInferMs >= mode.inferTargetMs) {
                                    Thread.sleep(mode.sleepMs)
                                    passIdle += mode.sleepMs
                                    cycleInferMs = 0
                                }
                            }
                            val passWall = System.currentTimeMillis() - passStart
                            audioSeconds += passAudio
                            inferMs += passInfer
                            idleMs += passIdle
                            wallTotal += passWall
                            val rtf = passInfer / 1000.0 / passAudio
                            if (rtf < bestRtf) bestRtf = rtf
                            thermal.stop()
                            power.stop()
                            val entry =
                                JSONObject()
                                    .put("mode", mode.id)
                                    .put("pass", pass)
                                    .put("passages", list.size)
                                    .put("audio_seconds", passAudio)
                                    .put("wall_ms", passWall)
                                    .put("infer_ms", passInfer)
                                    .put("idle_ms", passIdle)
                                    .put("duty_ratio_actual", passInfer.toDouble() / passWall)
                                    .put("rtf", rtf)
                                    .put("power_mean_mw", power.meanPowerMw)
                                    .put("idle_power_mw", idle.getDouble("power_mean_mw"))
                                    .put(
                                        "wh_per_audio_hour",
                                        energyOrNull(
                                            power.meanPowerMw,
                                            passWall,
                                            passAudio,
                                            power.unpluggedFraction,
                                        ),
                                    ).put("unplugged_fraction", power.unpluggedFraction)
                                    .put("energy_valid", isEnergyValid(power.unpluggedFraction, power.meanPowerMw))
                                    .put(
                                        "energy_invalid_reason",
                                        energyInvalidReason(power.unpluggedFraction, power.meanPowerMw) ?: JSONObject.NULL,
                                    ).put("thermal_status_max", thermal.maxStatus)
                                    .put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
                                    .put("mem_off", memOff)
                            modeRows.put(entry)
                            results.put("modes", modeRows)
                            results.put(
                                "summary",
                                summary(runs, audioSeconds, inferMs, idleMs, wallTotal, bestRtf),
                            )
                            flush(outFile, results)
                            log(
                                "${mode.id} pass $pass/$runs: ${"%.1f".format(passAudio)}s audio in " +
                                    "$passWall ms (infer $passInfer + idle $passIdle), " +
                                    "duty ${"%.3f".format(entry.getDouble("duty_ratio_actual"))}, " +
                                    "RTF ${"%.3f".format(rtf)}, power ${"%.0f".format(power.meanPowerMw)} mW, " +
                                    "energy ${entry.get("wh_per_audio_hour")} Wh/audio-h",
                            )
                        }
                    }
                }
            }
            flush(outFile, results)
            log("perfspike_e.json written to $outDir")
            true
        } catch (e: Throwable) {
            log("leg E unavailable: $e")
            results.put("error", e.toString())
            flush(outFile, results)
            false
        }
    }

    /**
     * Leg F1 — the int4 conflict's device half: open the minimal
     * `MatMulNBits` graph on ORT-android's CPU EP and run it once. Session
     * creation is where a missing kernel shows up; a finite, non-degenerate
     * output proves the kernel computes.
     */
    fun runInt4Probe(log: (String) -> Unit): Boolean {
        val outFile = File(outDir, "perfspike_f1.json")
        val env = OrtEnvironment.getEnvironment()
        val json =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("screen", screenState())
                .put("engine", "onnxruntime-android")
                .put("model", "matmulnbits-probe")
        var opened = false
        var finite = false
        try {
            val model = File(models, "matmulnbits-probe")
            check(model.isFile) { "matmulnbits-probe not staged under ${models.absolutePath}" }
            val session = env.createSession(model.absolutePath, OrtSession.SessionOptions())
            opened = true
            session.use {
                val feeds = LinkedHashMap<String, OnnxTensor>()
                val shapes = JSONObject()
                try {
                    for ((name, nodeInfo) in session.inputInfo) {
                        val tensor = nodeInfo.info as TensorInfo
                        val shape = tensor.shape
                        shapes.put(name, JSONArray(shape.toList()))
                        feeds[name] = zeroTensor(env, tensor.type, shape)
                    }
                    json.put("input_shapes", shapes)
                    session.run(feeds).use { result ->
                        val tensor = result[0] as OnnxTensor
                        val values = FloatArray(tensor.floatBuffer.remaining())
                        tensor.floatBuffer.get(values)
                        var absSum = 0.0
                        var nonZero = false
                        var nan = false
                        for (value in values) {
                            if (value.isNaN()) nan = true
                            if (value != 0f) nonZero = true
                            absSum += abs(value.toDouble())
                        }
                        finite = !nan
                        json.put("output_shape", JSONArray(tensor.info.shape.toList()))
                        json.put("output_abs_sum", absSum)
                        json.put("output_nonzero", nonZero)
                        json.put("output_finite", finite)
                    }
                } finally {
                    feeds.values.forEach { it.close() }
                }
            }
        } catch (e: Throwable) {
            json.put("error", e.toString())
            log("int4 probe error: $e")
        }
        json.put("opened", opened)
        json.put("output_finite", finite)
        flush(outFile, json)
        log("int4 probe: opened=$opened output_finite=$finite")
        return opened && finite
    }

    /**
     * Leg F2 — XNNPACK partition on the 2-D-rewritten model. Gated on the
     * device-free parity result (`docs/prints/perfspike/xnnpack-reshape.md`):
     * the rewrite is arithmetically faithful, but the waveform gate fails
     * because the model amplifies fp32 kernel-order noise, so this leg is not
     * dispatched. It is implemented so the gate can be restated and the leg run
     * without new code.
     */
    fun runXnnpackPartition(
        threads: Int = THREADS,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_f2.json")
        val json =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("mem_off", memOff)
                .put("screen", screenState())
        return try {
            if (!requireModels(listOf(RESHAPED_MODEL, FP32_MODEL, "kokoro-voices"), log)) return false
            val rows = corpusRows("corpus.tsv", null)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = rows.flatMap { windowsOf(it, tokenizer, voices, null) }
            json.put("windows", windows.size)

            val control = runProvider(windows, File(models, RESHAPED_MODEL), "cpu", null, threads, log)
            json.put("control", control.json)
            flush(outFile, json)
            val oracle = runProvider(windows, File(models, FP32_MODEL), "cpu-oracle", null, threads, log)
            val diff = pcmDiff(oracle.audio, control.audio)
            json.put("control_max_abs_diff_vs_fp32", diff.first)
            json.put("control_mean_abs_diff_vs_fp32", diff.second)

            // Full claim: with CPU-EP fallback disabled, session creation succeeds
            // only when XNNPACK claims every node; the failure names the first
            // unassigned node, which is the pointer to the next rewrite.
            var fullClaim = false
            var claimError: String? = null
            val env = OrtEnvironment.getEnvironment()
            try {
                val options = OrtSession.SessionOptions()
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                env.createSession(File(models, RESHAPED_MODEL).absolutePath, options).close()
                fullClaim = true
            } catch (e: Throwable) {
                claimError = e.toString()
                log("xnnpack full claim rejected: $e")
            }
            json.put("xnnpack_full_claim", fullClaim)
            json.put("claim_error", claimError ?: JSONObject.NULL)

            val xnnpack = runProvider(windows, File(models, RESHAPED_MODEL), "xnnpack", "xnnpack", threads, log)
            json.put("xnnpack", xnnpack.json)
            val xnnpackDiff = pcmDiff(control.audio, xnnpack.audio)
            json.put("max_abs_diff_vs_cpu_control", xnnpackDiff.first)
            json.put("mean_abs_diff_vs_cpu_control", xnnpackDiff.second)

            val rtfCpu = control.json.optDouble("rtf", Double.NaN)
            val rtfXnn = xnnpack.json.optDouble("rtf", Double.NaN)
            val verdict =
                when {
                    fullClaim -> "full_claim"
                    rtfXnn.isNaN() || rtfCpu.isNaN() -> "no_claim"
                    xnnpackDiff.first > 0.0 && rtfXnn < rtfCpu -> "partial_offload_faster"
                    xnnpackDiff.first > 0.0 -> "partial_offload_slower"
                    else -> "no_claim"
                }
            json.put("verdict", verdict)
            flush(outFile, json)
            log("xnnpack: full_claim=$fullClaim rtf_cpu=$rtfCpu rtf_xnnpack=$rtfXnn verdict=$verdict")
            true
        } catch (e: Throwable) {
            log("leg F2 unavailable: $e")
            json.put("error", e.toString())
            flush(outFile, json)
            false
        }
    }

    // ------------------------------------------------- leg G (harness audit)

    /**
     * Leg G — harness-sensitivity audit (owner question, 2026-09-11).
     *
     * Several peer projects publish numbers this spike could not reproduce. The
     * working hypothesis is that our own harness *fixes* axes they vary, so each
     * config here isolates exactly one of them and every config is measured in
     * [rounds] round-robin passes, which spreads thermal drift across all configs
     * instead of charging it to whichever one ran last.
     *
     *  * `g0_fp32_t4` — the honest baseline: pinned fp32, explicit 4 threads (the
     *    #147 knee), plain CPU EP.
     *  * `g1_fp32_default_threads` — the *same* graph with **no**
     *    `setIntraOpNumThreads`. This is what every `KokoroBenchmarkRunner` leg
     *    actually ran (`OrtProvider.CPU.options = {}`), while its JSON records
     *    `"threads": 6`; and it has no warm-up, so ORT's lazy graph init and — for
     *    a quantized graph — weight prepacking land inside its timed number.
     *    `first_infer_ms` here is that cost, reported on its own.
     *  * `g2_fp32_xnnpack_t4` — pinned fp32 with the XNNPACK EP added. sherpa-onnx
     *    (Lectern, HayaiTTS, kokoro-reader's server) enables XNNPACK by default;
     *    no leg of this spike ever added it, so all of spike legs A–E measured the
     *    plain CPU EP (MLAS) path only.
     *  * `g3_xnnpack_claim` — creation-only probe with
     *    `session.disable_cpu_ep_fallback=1`: session creation succeeding proves
     *    XNNPACK claims *every* node; failing names the first unassigned node,
     *    which is the claim map the conv-only source reading never produced.
     *    Kokoro is 90 Conv + 7 ConvTranspose but also 102 MatMul + 73 Gemm — and
     *    Gemm/MatMul are the ops XNNPACK can claim with a dynamic axis
     *    (`input_ids` is `[1, 'input_ids_len']`).
     *  * `g3_fp32_xnnpack_nospin` — the same XNNPACK session with
     *    `session.intra_op.allow_spinning=0`, which is what ORT's own XNNPACK
     *    warning recommends when ORT's pool has >1 thread (the EP carries a
     *    second pthread pool).
     *  * `g4_int8_t4` — the int8 model alone, warm, 4 threads.
     *  * `g5_int8_oracle` — int8 measured the way leg A measured it: with a
     *    resident fp32 oracle session inferring between the timed candidate
     *    windows (two ORT pools in one process).
     *
     * RTF only: no wake lock is held and no energy field is emitted, so a plugged
     * device is fine and each row's conditions come from [screenState] rather than
     * from a constant.
     */
    fun runHarnessSensitivity(
        rounds: Int,
        corpusName: String,
        threads: Int,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_g.json")
        val json =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("mem_off", memOff)
                .put("screen", screenState())
                .put("threads", threads)
                .put("rounds", rounds)
                .put("corpus", corpusName)
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val hasInt8 = File(models, INT8_MODEL).isFile
            if (!hasInt8) log("int8 model not staged — g4/g5 will be skipped")
            val hasReshaped = File(models, RESHAPED_MODEL).isFile

            // Claim probe first: it needs no corpus and its verdict is a property
            // of the graph + ORT build (it does not vary by device), so it is
            // flushed immediately and survives whatever the RTF configs do.
            val probes = JSONArray()
            probes.put(claimProbe(FP32_MODEL, threads, log))
            if (hasReshaped) probes.put(claimProbe(RESHAPED_MODEL, threads, log))
            json.put("claim_probe", probes)
            flush(outFile, json)

            val rows = corpusRows(corpusName, null)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = rows.flatMap { windowsOf(it, tokenizer, voices, null) }
            json.put("windows", windows.size)

            val configs =
                buildList {
                    add(GConfig("g0_fp32_t4", FP32_MODEL, threads))
                    add(GConfig("g1_fp32_default_threads", FP32_MODEL, null))
                    add(GConfig("g2_fp32_xnnpack_t4", FP32_MODEL, threads, xnnpack = true))
                    add(GConfig("g3_fp32_xnnpack_nospin", FP32_MODEL, threads, xnnpack = true, noSpin = true))
                    if (hasInt8) {
                        add(GConfig("g4_int8_t4", INT8_MODEL, threads))
                        add(GConfig("g5_int8_oracle", INT8_MODEL, threads, oracle = true))
                    }
                }
            val results = JSONArray()
            json.put("results", results)
            for (round in 1..rounds) {
                for (config in configs) {
                    results.put(measureGConfig(config, windows, round, log))
                    flush(outFile, json)
                }
            }

            // Self-describing summary: best RTF per config (min over rounds) and
            // the ratios the owner question is actually about.
            val best = HashMap<String, Double>()
            for (i in 0 until results.length()) {
                val row = results.getJSONObject(i)
                val rtf = row.optDouble("rtf", Double.NaN)
                if (rtf.isNaN()) continue
                val id = row.getString("config")
                if (!best.containsKey(id) || rtf < best.getValue(id)) best[id] = rtf
            }
            val ratios = JSONObject()
            for ((id, rtf) in best) ratios.put(id, rtf)
            ratio(best, "g2_fp32_xnnpack_t4", "g0_fp32_t4")?.let { ratios.put("xnnpack_over_base", it) }
            ratio(best, "g1_fp32_default_threads", "g0_fp32_t4")?.let { ratios.put("default_threads_over_t$threads", it) }
            if (hasInt8) {
                ratio(best, "g4_int8_t4", "g0_fp32_t4")?.let { ratios.put("int8_over_fp32", it) }
                ratio(best, "g5_int8_oracle", "g4_int8_t4")?.let { ratios.put("int8_oracle_penalty", it) }
            }
            json.put("rtf_best", ratios)
            json.put("conditions", conditionsJson())
            flush(outFile, json)
            log("perfspike_g.json written to $outDir ($results.length() rows)")
            true
        } catch (e: Throwable) {
            log("leg G unavailable: $e")
            json.put("error", e.toString())
            flush(outFile, json)
            false
        }
    }

    /** One sensitivity config: which model, which thread setting, which EP/wrapper. */
    private data class GConfig(
        val id: String,
        val model: String,
        val threads: Int?,
        val xnnpack: Boolean = false,
        /**
         * `session.intra_op.allow_spinning=0`. ORT's own XNNPACK warning
         * (`xnnpack_execution_provider.cc:166`) says the EP runs its own
         * pthread pool and that with >1 ORT threads and spinning enabled "there
         * will be contention between the two thread pools, and performance will
         * suffer" — this config is that recommendation, measured.
         */
        val noSpin: Boolean = false,
        /**
         * Keep a resident fp32 session open and infer with it between timed
         * candidate windows — `KokoroBenchmarkRunner.measure`'s structure.
         */
        val oracle: Boolean = false,
    )

    private fun gFactory(
        threads: Int?,
        xnnpack: Boolean,
        noSpin: Boolean = false,
    ): (OrtSession.SessionOptions) -> Unit =
        { options ->
            if (threads != null) options.setIntraOpNumThreads(threads)
            if (xnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to (threads ?: THREADS).toString()))
            if (noSpin) options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (memOff) {
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
            }
        }

    private fun measureGConfig(
        config: GConfig,
        windows: List<Window>,
        round: Int,
        log: (String) -> Unit,
    ): JSONObject {
        val thermal = ThermalProbe(context)
        val power = PowerProbe(context)
        thermal.start()
        power.start()
        val row =
            JSONObject()
                .put("config", config.id)
                .put("round", round)
                .put("model", config.model)
                .put("threads", config.threads ?: JSONObject.NULL)
                .put("xnnpack", config.xnnpack)
                .put("oracle_resident", config.oracle)
        var oracle: KokoroSession? = null
        try {
            if (config.oracle) {
                oracle = OrtKokoroSession.open(File(models, FP32_MODEL), sessionFactory = gFactory(config.threads, false))
                oracle.infer(windows.first().tokens, windows.first().style, 1.0)
            }
            val tOpen = System.currentTimeMillis()
            val session =
                OrtKokoroSession.open(
                    File(models, config.model),
                    sessionFactory = gFactory(config.threads, config.xnnpack, config.noSpin),
                )
            row.put("engine_open_ms", System.currentTimeMillis() - tOpen)
            session.use {
                val firstMs = measureTimeMillis { session.infer(windows.first().tokens, windows.first().style, 1.0) }
                row.put("first_infer_ms", firstMs)
                val perWindow = LongArray(windows.size)
                var audioSeconds = 0.0
                val wall =
                    measureTimeMillis {
                        for ((i, window) in windows.withIndex()) {
                            val ms =
                                measureTimeMillis {
                                    audioSeconds += session.infer(window.tokens, window.style, 1.0).audio.size / SAMPLE_RATE
                                }
                            perWindow[i] = ms
                            oracle?.infer(window.tokens, window.style, 1.0)
                        }
                    }
                val sorted = perWindow.sortedArray()
                row.put("audio_seconds", audioSeconds)
                row.put("wall_ms", wall)
                row.put("rtf", wall / 1000.0 / audioSeconds)
                row.put("throughput_audio_s_per_s", audioSeconds / wall * 1000.0)
                row.put("window_ms_p50", percentile(sorted, 0.5))
                row.put("window_ms_p95", percentile(sorted, 0.95))
                row.put("window_ms_max", sorted.last())
                log(
                    "round $round ${config.id}: first=${firstMs}ms ${"%.2f".format(audioSeconds)}s audio in $wall ms, " +
                        "RTF ${"%.3f".format(row.getDouble("rtf"))}, p50=${row.getLong("window_ms_p50")}ms",
                )
            }
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            row.put("vm_hwm_kb", readVmHwm())
            row.put("total_pss_kb", mem.totalPss)
            row.put("battery_temp_c", power.maxBatteryTempC)
            row.put("cpus_observed", JSONArray(power.cpusObserved.toList()))
            row.put("thermal_status_max", thermal.maxStatus)
            row.put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
            row.put("screen", screenState())
        } finally {
            oracle?.close()
            power.stop()
            thermal.stop()
        }
        return row
    }

    /**
     * Creation-only XNNPACK claim probe. With CPU-EP fallback disabled, ORT
     * builds a session only when one EP covers every node; the thrown message
     * names the first node left unassigned, which is the claim map we want.
     */
    private fun claimProbe(
        model: String,
        threads: Int,
        log: (String) -> Unit,
    ): JSONObject {
        val probe = JSONObject().put("model", model)
        val env = OrtEnvironment.getEnvironment()
        try {
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            env.createSession(File(models, model).absolutePath, options).close()
            probe.put("full_claim", true)
            probe.put("error", JSONObject.NULL)
            log("claim probe [$model]: XNNPACK claims the whole graph")
        } catch (e: Throwable) {
            probe.put("full_claim", false)
            probe.put("error", e.toString())
            log("claim probe [$model]: rejected — ${e.message}")
        }
        return probe
    }

    private fun conditionsJson(): JSONObject =
        JSONObject()
            .put("screen", screenState())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("threads_argument", "explicit where noted; g1 leaves ORT's default")

    private fun ratio(
        best: Map<String, Double>,
        a: String,
        b: String,
    ): Double? {
        val ra = best[a] ?: return null
        val rb = best[b] ?: return null
        if (rb == 0.0) return null
        return ra / rb
    }

    // ------------------------------------------------- leg H (thread sweep)

    /**
     * Leg H — single-session intra-op thread sweep on the device under test,
     * re-running #147's T axis under leg-G methodology (warm-up included,
     * round-robin rounds, conditions observed rather than asserted).
     *
     * #147 measured this axis on the Fold 8 **unplugged with the display off**
     * (t1 1.2118, t2 0.6681, t3 0.6706, t4 0.5754, t6 0.4796, t8 0.6115 RTF,
     * best of 3, 2-passage corpus) and the shipped `tts_threads` default of 4
     * came from it — while leg G then measured ORT's *unset* thread setting 15 %
     * faster than an explicit 4 on the same graph, and #147's own numbers have
     * t6 best. This leg re-measures the axis and adds a `t_default` leg (no
     * `setIntraOpNumThreads` at all) so the unset case is identified instead of
     * assumed, recording the process's actual thread inventory per leg.
     *
     * Run it plugged: the device's energy columns are then null by construction
     * (`unplugged_fraction` ≈ 0), which is the point — this is a latency and
     * placement measurement, and every row carries the observed plug/screen
     * state so it can be compared with the unplugged #147 table only on the
     * time axis.
     */
    fun runThreadSweep(
        rounds: Int,
        corpusName: String,
        log: (String) -> Unit,
    ): Boolean {
        val outFile = File(outDir, "perfspike_h.json")
        val json =
            JSONObject()
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("mem_off", memOff)
                .put("screen", screenState())
                .put("cores", Runtime.getRuntime().availableProcessors())
                .put("rounds", rounds)
                .put("corpus", corpusName)
                .put("note", "single session, W=1, warm-up window before the timed pass; plugged runs have null energy")
        return try {
            if (!requireModels(listOf(FP32_MODEL, "kokoro-voices"), log)) return false
            val rows = corpusRows(corpusName, null)
            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = rows.flatMap { windowsOf(it, tokenizer, voices, null) }
            json.put("windows", windows.size)
            flush(outFile, json)

            val configs =
                buildList {
                    for (t in intArrayOf(1, 2, 3, 4, 6, 8)) add(GConfig("t$t", FP32_MODEL, t))
                    // What #150 actually ran: no thread setting at all.
                    add(GConfig("t_default", FP32_MODEL, null))
                }
            val results = JSONArray()
            json.put("results", results)
            for (round in 1..rounds) {
                for (config in configs) {
                    val row = measureGConfig(config, windows, round, log)
                    row.put("thread_inventory", threadInventory())
                    results.put(row)
                    flush(outFile, json)
                }
            }

            val best = HashMap<String, Double>()
            for (i in 0 until results.length()) {
                val row = results.getJSONObject(i)
                val rtf = row.optDouble("rtf", Double.NaN)
                if (rtf.isNaN()) continue
                val id = row.getString("config")
                if (!best.containsKey(id) || rtf < best.getValue(id)) best[id] = rtf
            }
            val summary = JSONObject()
            val byRtf = best.entries.sortedBy { it.value }
            for ((id, rtf) in byRtf) summary.put(id, rtf)
            val bestId = byRtf.firstOrNull()?.key
            for ((id, rtf) in best) {
                summary.put("${id}_over_best", if (bestId != null) rtf / best.getValue(bestId) else JSONObject.NULL)
            }
            json.put("rtf_best", summary)
            json.put("best_config", bestId ?: JSONObject.NULL)
            json.put("conditions", conditionsJson())
            flush(outFile, json)
            log("perfspike_h.json written to $outDir — best=$bestId (${results.length()} rows)")
            true
        } catch (e: Throwable) {
            log("leg H unavailable: $e")
            json.put("error", e.toString())
            flush(outFile, json)
            false
        }
    }

    /**
     * The process's thread inventory at the end of a leg: total threads plus
     * those whose `comm` names an ORT/MLAS pool worker. ORT's Android pool
     * threads are not guaranteed to be named, so this is read as a lower bound on
     * the pool size, next to the explicit T this leg set.
     */
    private fun threadInventory(): JSONObject {
        val comms =
            File("/proc/self/task").listFiles()?.mapNotNull { task ->
                runCatching { File(task, "comm").readText().trim() }.getOrNull()
            } ?: emptyList()
        val named = comms.filter { it.contains("ort", ignoreCase = true) || it.contains("mlas", ignoreCase = true) }
        return JSONObject()
            .put("process_threads", comms.size)
            .put("ort_named_threads", named.size)
            .put("named_sample", JSONArray(named.distinct().take(8)))
    }

    // ------------------------------------------------------------- leg D parts

    private data class Config(
        val id: String,
        val noSpin: Boolean = false,
        val priority: Boolean = false,
        val adpf: Boolean = false,
        val powerEfficient: Boolean = false,
    )

    private val configs =
        listOf(
            Config("d0_baseline"),
            Config("d1_nospin", noSpin = true),
            Config("d2_prio", priority = true),
            Config("d3_adpf", priority = true, adpf = true),
            Config("d4_adpf_power", priority = true, adpf = true, powerEfficient = true),
        )

    private data class DutyMode(
        val id: String,
        val inferTargetMs: Long,
        val sleepMs: Long,
    )

    private val modes =
        listOf(
            DutyMode("continuous", 0L, 0L),
            DutyMode("duty50", 30_000L, 30_000L),
            DutyMode("duty33", 20_000L, 40_000L),
        )

    private fun runPlainConfig(
        session: KokoroSession,
        windows: List<Window>,
        runs: Int,
        config: Config,
        log: (String) -> Unit,
    ): JSONObject {
        val thermal = ThermalProbe(context)
        val power = PowerProbe(context)
        thermal.start()
        power.start()
        val timings = timePasses(session, windows, runs, null, log)
        power.stop()
        thermal.stop()
        val entry = timingJson(timings).put("config", config.id)
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        entry.put("vm_hwm_kb", readVmHwm())
        entry.put("total_pss_kb", mem.totalPss)
        entry.put("power_mean_mw", power.meanPowerMw)
        entry.put("wh_per_audio_hour", energyOrNull(power.meanPowerMw, timings.bestWallMs, timings.audioSeconds, power.unpluggedFraction))
        entry.put("unplugged_fraction", power.unpluggedFraction)
        entry.put("energy_valid", isEnergyValid(power.unpluggedFraction, power.meanPowerMw))
        entry.put("energy_invalid_reason", energyInvalidReason(power.unpluggedFraction, power.meanPowerMw) ?: JSONObject.NULL)
        entry.put("cpus_observed", JSONArray(power.cpusObserved.toList()))
        entry.put("thermal_status_max", thermal.maxStatus)
        entry.put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
        return entry
    }

    private fun runAdpfConfig(
        session: KokoroSession,
        windows: List<Window>,
        runs: Int,
        config: Config,
        threadIds: List<Int>,
        poolHinted: Boolean,
        targetNs: Long,
        log: (String) -> Unit,
    ): JSONObject {
        val manager = context.getSystemService(PerformanceHintManager::class.java)
        val target = if (targetNs > 0) targetNs else 10_000_000L
        var hint: PerformanceHintManager.Session? = null
        val entry = JSONObject()
        try {
            hint = manager.createHintSession(threadIds.toIntArray(), target)
            if (config.powerEfficient && Build.VERSION.SDK_INT >= 35) hint?.setPreferPowerEfficiency(true)
        } catch (e: Throwable) {
            log("${config.id}: hint session unavailable: $e")
            entry.put("adpf_unavailable", e.toString())
            hint?.close()
            hint = null
        }
        if (hint == null && !entry.has("adpf_unavailable")) {
            // Samsung's ROM returns null instead of throwing when the ADPF service is absent
            // (S22 Ultra, SDK 36) — record it rather than leaving the config without numbers.
            entry.put("adpf_unavailable", "createHintSession returned null (ADPF service unavailable)")
            log("${config.id}: createHintSession returned null")
        }
        entry.put("adpf_thread_count", threadIds.size)
        hint?.let { hintSession ->
            // The ADPF measurement must not be able to abort the whole leg: a hint-session
            // call the platform rejects mid-run (observed on the S22 Ultra) would otherwise
            // leave this config without timing fields and kill the leg.
            val measured =
                runCatching {
                    hintSession.updateTargetWorkDuration(target)
                    val thermal = ThermalProbe(context)
                    val power = PowerProbe(context)
                    thermal.start()
                    power.start()
                    try {
                        val passWindowMs = ArrayList<Long>()
                        var bestWall = Long.MAX_VALUE
                        var audioSeconds = 0.0
                        var firstWindowMs = 0L
                        for (pass in 1..runs) {
                            val passMs = LongArray(windows.size)
                            var passAudio = 0.0
                            val wall =
                                measureTimeMillis {
                                    windows.forEachIndexed { index, window ->
                                        val windowMs =
                                            measureTimeMillis {
                                                passAudio += session.infer(window.tokens, window.style, 1.0).audio.size / SAMPLE_RATE
                                            }
                                        passMs[index] = windowMs
                                        hintSession.reportActualWorkDuration(windowMs * 1_000_000L)
                                        hintSession.updateTargetWorkDuration(target)
                                    }
                                }
                            if (pass == 1) firstWindowMs = passMs.first()
                            audioSeconds = passAudio
                            passWindowMs.addAll(passMs.toList())
                            if (wall < bestWall) bestWall = wall
                            log("  ${config.id} run $pass/$runs: RTF ${"%.3f".format(wall / 1000.0 / passAudio)}")
                        }
                        Timings(passWindowMs.toLongArray(), firstWindowMs, bestWall, audioSeconds, runs) to power
                    } finally {
                        power.stop()
                        thermal.stop()
                    }
                }
            measured.onFailure {
                log("${config.id}: adpf measurement failed: $it")
                entry.put("adpf_measure_error", it.toString())
            }
            measured.onSuccess { (timings, power) ->
                val fields = timingJson(timings)
                fields.keys().forEach { key -> entry.put(key, fields.get(key)) }
                val mem = Debug.MemoryInfo()
                Debug.getMemoryInfo(mem)
                entry.put("vm_hwm_kb", readVmHwm())
                entry.put("total_pss_kb", mem.totalPss)
                entry.put("power_mean_mw", power.meanPowerMw)
                entry.put(
                    "wh_per_audio_hour",
                    energyOrNull(power.meanPowerMw, timings.bestWallMs, timings.audioSeconds, power.unpluggedFraction),
                )
                entry.put("unplugged_fraction", power.unpluggedFraction)
                entry.put("energy_valid", isEnergyValid(power.unpluggedFraction, power.meanPowerMw))
                entry.put(
                    "energy_invalid_reason",
                    energyInvalidReason(power.unpluggedFraction, power.meanPowerMw) ?: JSONObject.NULL,
                )
                entry.put("cpus_observed", JSONArray(power.cpusObserved.toList()))
            }
            hintSession.close()
        }
        entry.put("adpf_thread_ids", JSONArray(threadIds))
        entry.put("adpf_pool_hinted", poolHinted)
        entry.put("adpf_target_ns", target)
        return entry
    }

    /**
     * ORT worker threads answer ADPF only if they are named. First try
     * `/proc/self/task/<tid>/comm` for an ORT/MLAS signature; otherwise take the
     * threads that were seen on ≥2 distinct CPUs during the warm-up window and
     * are not this harness's own probes. Neither found → hint the caller only.
     */
    private fun discoverOrtThreads(
        threads: Int,
        log: (String) -> Unit,
    ): Pair<List<Int>, Boolean> {
        val comms = LinkedHashMap<Int, String>()
        File("/proc/self/task").listFiles()?.forEach { task ->
            val tid = task.name.toIntOrNull() ?: return@forEach
            val comm = runCatching { File(task, "comm").readText().trim() }.getOrNull() ?: return@forEach
            comms[tid] = comm
        }
        log("  adpf: ${comms.size} threads, comms=${comms.values.distinct().sorted()}")
        val signature = Regex(".*(ort|Ort|ORT|onnx|MLAS|mlas|tf_).*")
        val named = comms.filterValues { signature.matches(it) }.keys.sorted()
        if (named.isNotEmpty()) return named.take(threads) to true

        val cpuSeen = HashMap<Int, MutableSet<Int>>()
        repeat(5) {
            comms.keys.forEach { tid ->
                cpuOf(tid)?.let { cpu -> cpuSeen.getOrPut(tid) { linkedSetOf() } += cpu }
            }
            Thread.sleep(250)
        }
        val fallback =
            cpuSeen
                .filterValues { it.size >= 2 }
                .filterKeys { tid ->
                    val comm = comms[tid] ?: ""
                    comm != "KokoroSpike" && comm != "ThermalProbe" && !comm.startsWith("Binder")
                }.keys
                .sorted()
                .take(threads)
        if (fallback.isNotEmpty()) return fallback to true
        return listOf(Process.myTid()) to false
    }

    /** `processor` (field 39) of an arbitrary tid from /proc/self/task/<tid>/stat. */
    private fun cpuOf(tid: Int): Int? {
        val line = runCatching { File("/proc/self/task/$tid/stat").readText() }.getOrNull() ?: return null
        val after = line.substringAfter(")", missingDelimiterValue = "").trim()
        if (after.isEmpty()) return null
        return after.split(' ').getOrNull(36)?.toIntOrNull()
    }

    // --------------------------------------------------------------- helpers

    private fun requireModels(
        names: List<String>,
        log: (String) -> Unit,
    ): Boolean {
        val missing = names.filterNot { File(models, it).isFile }
        if (missing.isEmpty()) return true
        log("missing under ${models.absolutePath}: ${missing.joinToString(", ")} — stage them first (see build.md)")
        return false
    }

    private fun factory(
        threads: Int,
        extra: (OrtSession.SessionOptions) -> Unit = {},
    ): (OrtSession.SessionOptions) -> Unit =
        { options ->
            options.setIntraOpNumThreads(threads)
            extra(options)
            if (memOff) {
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
            }
        }

    private fun <T> withWakeLock(block: () -> T): T =
        try {
            wakeLock.acquire()
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

    private fun corpusRows(
        corpusName: String,
        limit: Int?,
    ): List<Passage> {
        val file = File(context.filesDir, corpusName)
        check(file.isFile) { "corpus not found at ${file.absolutePath}" }
        val parsed =
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                val voice = if (parts.size == 3) VOICES[parts[1]] else null
                voice?.let { Passage(it, parts[2]) }
            }
        check(parsed.isNotEmpty()) { "$corpusName has no rows for a mapped voice" }
        return if (limit == null) parsed else parsed.take(limit)
    }

    private fun windowsOf(
        passage: Passage,
        tokenizer: KokoroTokenizer,
        voices: KokoroVoiceBank,
        cap: Int?,
    ): List<Window> {
        val collapsed = passage.phonemes.split(Regex("\\s+")).joinToString(" ")
        val batches = if (cap == null) PhonemeChunker.split(collapsed) else PhonemeChunker.split(collapsed, cap)
        return batches.map { batch ->
            val tokens = tokenizer.tokenize(batch)
            val style = voices.styleFor(passage.voice, tokens.size) ?: error("no style for voice ${passage.voice}")
            Window(tokens, style)
        }
    }

    /** [runs] timed passes over [windows]; the first window of pass 1 is the TTFA number. */
    private fun timePasses(
        session: KokoroSession,
        windows: List<Window>,
        runs: Int,
        cap: Int?,
        log: (String) -> Unit,
    ): Timings {
        val all = ArrayList<Long>(windows.size * runs)
        var firstWindowMs = 0L
        var bestWall = Long.MAX_VALUE
        var audioSeconds = 0.0
        for (pass in 1..runs) {
            var passAudio = 0.0
            val wall =
                measureTimeMillis {
                    for (window in windows) {
                        val ms =
                            measureTimeMillis {
                                passAudio += session.infer(window.tokens, window.style, 1.0).audio.size / SAMPLE_RATE
                            }
                        all += ms
                        if (pass == 1 && firstWindowMs == 0L) firstWindowMs = ms
                    }
                }
            audioSeconds = passAudio
            if (wall < bestWall) bestWall = wall
            log(
                "  ${if (cap == null) "cfg" else "cap $cap"} run $pass/$runs: " +
                    "${"%.2f".format(passAudio)}s audio in $wall ms, RTF ${"%.3f".format(wall / 1000.0 / passAudio)}",
            )
        }
        return Timings(all.toLongArray(), firstWindowMs, bestWall, audioSeconds, runs)
    }

    private fun timingJson(timings: Timings): JSONObject {
        val sorted = timings.windowMs.sortedArray()
        return JSONObject()
            .put("windows", timings.windowMs.size)
            .put("runs", timings.passes)
            .put("audio_seconds", timings.audioSeconds)
            .put("wall_ms_best", timings.bestWallMs)
            .put("rtf", timings.bestWallMs / 1000.0 / timings.audioSeconds)
            .put("throughput_audio_s_per_s", timings.audioSeconds / timings.bestWallMs * 1000.0)
            .put("first_window_ms", timings.firstWindowMs)
            .put("window_ms_p50", percentile(sorted, 0.5))
            .put("window_ms_p95", percentile(sorted, 0.95))
            .put("window_ms_max", sorted.last())
    }

    private fun percentile(
        sorted: LongArray,
        q: Double,
    ): Long = if (sorted.isEmpty()) 0L else sorted[(sorted.size * q).toInt().coerceIn(0, sorted.size - 1)]

    private fun measureIdleBaseline(): JSONObject {
        val thermal = ThermalProbe(context)
        val power = PowerProbe(context)
        thermal.start()
        power.start()
        Thread.sleep(IDLE_BASELINE_MS)
        power.stop()
        thermal.stop()
        return JSONObject()
            .put("duration_ms", IDLE_BASELINE_MS)
            .put("power_mean_mw", power.meanPowerMw)
            .put("current_mean_ma", power.meanCurrentMa)
            .put("peak_current_ma", power.peakAbsCurrentMa)
            .put("unplugged_fraction", power.unpluggedFraction)
            .put("max_battery_temp_c", power.maxBatteryTempC)
            .put("thermal_status_max", thermal.maxStatus)
            .put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
    }

    private fun summary(
        runs: Int,
        audioSeconds: Double,
        inferMs: Long,
        idleMs: Long,
        wallMs: Long,
        bestRtf: Double,
    ): JSONObject =
        JSONObject()
            .put("runs", runs)
            .put("audio_seconds", audioSeconds)
            .put("wall_ms", wallMs)
            .put("infer_ms", inferMs)
            .put("idle_ms", idleMs)
            .put("duty_ratio_actual", inferMs.toDouble() / wallMs)
            .put("rtf_best", if (bestRtf == Double.MAX_VALUE) JSONObject.NULL else bestRtf)

    private fun injectEnergy(
        json: JSONObject,
        power: PowerProbe,
        thermal: ThermalProbe,
        idlePowerMw: Double,
        passJson: Boolean,
    ) {
        val wallMs = passWallMs(json)
        val audioSeconds = passAudioSeconds(json)
        val valid = isEnergyValid(power.unpluggedFraction, power.meanPowerMw)
        json.put("power_mean_mw", if (valid) power.meanPowerMw else JSONObject.NULL)
        json.put("current_mean_ma", power.meanCurrentMa)
        json.put("peak_current_ma", power.peakAbsCurrentMa)
        json.put("max_battery_temp_c", power.maxBatteryTempC)
        json.put("unplugged_fraction", power.unpluggedFraction)
        json.put("energy_valid", valid)
        json.put("energy_invalid_reason", energyInvalidReason(power.unpluggedFraction, power.meanPowerMw) ?: JSONObject.NULL)
        json.put("thermal_status_max", thermal.maxStatus)
        json.put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
        json.put("idle_power_mw", if (valid) idlePowerMw else JSONObject.NULL)
        json.put(
            "wh_per_audio_hour",
            if (valid) whPerAudioHour(power.meanPowerMw, wallMs, audioSeconds) else JSONObject.NULL,
        )
        if (passJson) {
            json.put("wall_ms", wallMs)
            json.put("audio_seconds", audioSeconds)
        }
    }

    private fun passWallMs(json: JSONObject): Long =
        (json.optJSONArray("runs") ?: JSONArray())
            .let { runs ->
                var total = 0L
                for (i in 0 until runs.length()) {
                    val passages = runs.getJSONObject(i).optJSONArray("passages") ?: continue
                    for (j in 0 until passages.length()) total += passages.getJSONObject(j).optLong("synth_ms", 0L)
                }
                total
            }

    private fun passAudioSeconds(json: JSONObject): Double =
        (json.optJSONArray("runs") ?: JSONArray())
            .let { runs ->
                var total = 0.0
                for (i in 0 until runs.length()) {
                    val passages = runs.getJSONObject(i).optJSONArray("passages") ?: continue
                    for (j in 0 until passages.length()) total += passages.getJSONObject(j).optDouble("audio_seconds", 0.0)
                }
                total
            }

    private fun passRtf(json: JSONObject): Double? {
        val wall = passWallMs(json)
        val audio = passAudioSeconds(json)
        return if (wall > 0 && audio > 0) wall / 1000.0 / audio else null
    }

    private fun copyWav(
        dir: File,
        from: String,
        to: String,
    ): String? {
        val source = File(dir, from)
        if (!source.isFile) return null
        source.copyTo(File(dir, to), overwrite = true)
        return to
    }

    /** A power number is only reportable when the device was on battery AND the gauge cleared its floor. */
    private fun isEnergyValid(
        unpluggedFraction: Double,
        meanPowerMw: Double,
    ): Boolean = unpluggedFraction >= ENERGY_VALID_MIN_UNPLUGGED && meanPowerMw >= ENERGY_PLAUSIBLE_MIN_MW

    private fun energyInvalidReason(
        unpluggedFraction: Double,
        meanPowerMw: Double,
    ): String? =
        when {
            unpluggedFraction < ENERGY_VALID_MIN_UNPLUGGED -> "plugged_or_charging"
            meanPowerMw < ENERGY_PLAUSIBLE_MIN_MW -> "gauge_below_floor"
            else -> null
        }

    private fun energyOrNull(
        meanPowerMw: Double,
        wallMs: Long,
        audioSeconds: Double,
        unpluggedFraction: Double,
    ): Any = if (isEnergyValid(unpluggedFraction, meanPowerMw)) whPerAudioHour(meanPowerMw, wallMs, audioSeconds) else JSONObject.NULL

    // ---------------------------------------------------------- leg C helpers

    private fun pcm16(
        window: Window,
        session: KokoroSession,
    ): ByteArray = floatsToPcm16(session.infer(window.tokens, window.style, 1.0).audio)

    private fun concatPcm(chunks: List<ByteArray>): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private fun floatsToPcm16(audio: FloatArray): ByteArray {
        val pcm = ByteArray(audio.size * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (value in audio) {
            buffer.putShort((value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return pcm
    }

    private fun outputAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun outputFormat(): AudioFormat =
        AudioFormat
            .Builder()
            .setSampleRate(SAMPLE_RATE.toInt())
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

    /** The shipped path (decisions #84): one MODE_STATIC track sized to the PCM. */
    private fun playStatic(
        pcm: ByteArray,
        audioSeconds: Double,
        log: (String) -> Unit,
    ): JSONObject {
        val json = JSONObject()
        var track: AudioTrack? = null
        try {
            track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(outputAttributes())
                    .setAudioFormat(outputFormat())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size)
                    .build()
            track.setVolume(0.05f)
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            val started = System.currentTimeMillis()
            track.play()
            val firstAudioMs = awaitFirstAudio(track, started, audioSeconds)
            val deadline = started + 1000L * (3.0 * audioSeconds).toLong()
            while (track.playbackHeadPosition < pcm.size / 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            val wall = System.currentTimeMillis() - started
            val underruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else -1
            json.put("wall_ms", wall)
            json.put("first_audio_ms", firstAudioMs ?: JSONObject.NULL)
            json.put("underrun_count", underruns)
            json.put("underruns_per_audio_hour", if (underruns >= 0) underruns / (audioSeconds / 3600.0) else JSONObject.NULL)
            json.put("playback_head_position", track.playbackHeadPosition)
        } catch (e: Throwable) {
            log("static playback failed: $e")
            json.put("error", e.toString())
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
        }
        return json
    }

    /** Per-window MODE_STREAM feeding: the seam leg C exists to price. */
    private fun playStream(
        windows: List<Window>,
        session: KokoroSession,
        audioSeconds: Double,
        log: (String) -> Unit,
    ): JSONObject {
        val json = JSONObject()
        var track: AudioTrack? = null
        var synthMs = 0L
        var maxGap = 0L
        var firstWriteMs: Long? = null
        var firstAudioMs: Long? = null
        try {
            val minBuffer =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(outputAttributes())
                    .setAudioFormat(outputFormat())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE.toInt()))
                    .build()
            track.setVolume(0.05f)
            val started = System.currentTimeMillis()
            track.play()
            var lastWriteReturned = started
            for (window in windows) {
                var pcm: ByteArray? = null
                val windowSynthMs =
                    measureTimeMillis { pcm = floatsToPcm16(session.infer(window.tokens, window.style, 1.0).audio) }
                synthMs += windowSynthMs
                val chunk = pcm!!
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                val returned = System.currentTimeMillis()
                if (firstWriteMs == null) {
                    firstWriteMs = returned - started
                    firstAudioMs = awaitFirstAudio(track, started, audioSeconds)
                }
                val gap = returned - lastWriteReturned
                if (gap > maxGap) maxGap = gap
                lastWriteReturned = returned
                log("  stream write: synth $windowSynthMs ms, ${chunk.size / 2} frames, gap since last write $gap ms")
            }
            val wall = System.currentTimeMillis() - started
            val underruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else -1
            json.put("wall_ms", wall)
            json.put("first_audio_ms", firstAudioMs ?: JSONObject.NULL)
            json.put("first_write_ms", firstWriteMs ?: JSONObject.NULL)
            json.put("underrun_count", underruns)
            json.put("underruns_per_audio_hour", if (underruns >= 0) underruns / (audioSeconds / 3600.0) else JSONObject.NULL)
            json.put("max_inter_write_gap_ms", maxGap)
            json.put("rtf_synth", synthMs / 1000.0 / audioSeconds)
        } catch (e: Throwable) {
            log("stream playback failed: $e")
            json.put("error", e.toString())
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
        }
        return json
    }

    /**
     * First audible frame, sampled every 10 ms from *now* (not from [started]: in stream
     * mode the first write can take a minute on a weak device, and a deadline measured
     * from `play()` would already have expired when the poll begins).
     */
    private fun awaitFirstAudio(
        track: AudioTrack,
        started: Long,
        audioSeconds: Double,
    ): Long? {
        val deadline = System.currentTimeMillis() + minOf(10_000L, (audioSeconds * 1000).toLong())
        while (System.currentTimeMillis() < deadline) {
            if (track.playbackHeadPosition > 0) return System.currentTimeMillis() - started
            Thread.sleep(10)
        }
        return null
    }

    // ---------------------------------------------------------- leg F helpers

    private fun zeroTensor(
        env: OrtEnvironment,
        type: OnnxJavaType,
        shape: LongArray,
    ): OnnxTensor {
        val count = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
        return when (type) {
            OnnxJavaType.FLOAT ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(count)), shape)
            OnnxJavaType.UINT8, OnnxJavaType.INT8 -> {
                val buffer = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
                OnnxTensor.createTensor(env, buffer, shape, type)
            }
            else -> {
                val buffer = FloatBuffer.wrap(FloatArray(count))
                OnnxTensor.createTensor(env, buffer, shape)
            }
        }
    }

    private data class ProviderRun(
        val json: JSONObject,
        val audio: FloatArray,
    )

    private fun runProvider(
        windows: List<Window>,
        model: File,
        label: String,
        provider: String?,
        threads: Int,
        log: (String) -> Unit,
    ): ProviderRun {
        val tOpen = System.currentTimeMillis()
        val session =
            OrtKokoroSession.open(
                model,
                sessionFactory = { options ->
                    options.setIntraOpNumThreads(threads)
                    if (provider == "xnnpack") {
                        options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                    }
                },
            )
        val openMs = System.currentTimeMillis() - tOpen
        val run =
            session.use {
                session.infer(windows.first().tokens, windows.first().style, 1.0)
                var audioSeconds = 0.0
                val all = ArrayList<Float>()
                val wall =
                    measureTimeMillis {
                        for (window in windows) {
                            val audio = session.infer(window.tokens, window.style, 1.0).audio
                            audioSeconds += audio.size / SAMPLE_RATE
                            all.addAll(audio.toList())
                        }
                    }
                val json =
                    JSONObject()
                        .put("provider", label)
                        .put("engine_open_ms", openMs)
                        .put("audio_seconds", audioSeconds)
                        .put("wall_ms", wall)
                        .put("rtf", wall / 1000.0 / audioSeconds)
                log("$label: ${"%.2f".format(audioSeconds)}s audio in $wall ms, RTF ${"%.3f".format(json.getDouble("rtf"))}")
                ProviderRun(json, all.toFloatArray())
            }
        return run
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Screen/doze state at leg start. The plan's energy legs run screen-off;
     * the #147 measurement showed screen-off+unplugged stalls inference ~5x on
     * the Fold (restricted cpuset), so a re-run with the screen forced on
     * records that here instead of leaving the condition ambiguous.
     */
    private fun screenState(): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val stayOn =
            runCatching {
                android.provider.Settings.System.getInt(
                    context.contentResolver,
                    android.provider.Settings.System.STAY_ON_WHILE_PLUGGED_IN,
                    0,
                )
            }.getOrDefault(-1)
        val timeout =
            runCatching {
                android.provider.Settings.System
                    .getInt(context.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1)
            }.getOrDefault(-1)
        return "interactive=${power.isInteractive} stayon_while_plugged=$stayOn screen_off_timeout_ms=$timeout"
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }

    private fun flush(
        file: File,
        json: JSONObject,
    ) {
        val text = json.toString(2)
        file.writeText(text)
        // Mirror into internal storage: `/sdcard` (FUSE) is not reachable from
        // `adb shell run-as`, so a run whose only copy is the external file
        // cannot be pulled off a rebooted/screen-off device.
        runCatching { File(context.filesDir, file.name).writeText(text) }
    }
}
