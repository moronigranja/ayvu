package io.github.moronigranja.ayvu.spiketts

import android.content.Context
import android.os.Build
import android.os.Debug
import io.github.moronigranja.ayvu.tts.kokoro.KokoroTokenizer
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVocabulary
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceBank
import io.github.moronigranja.ayvu.tts.kokoro.OrtKokoroSession
import io.github.moronigranja.ayvu.tts.kokoro.PhonemeChunker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Thread-count sweep (decisions #147) — the number behind the shipped
 * `tts_threads` setting.
 *
 * #137 shipped a 1-8 slider (default 4) on the S22's RTF reasoning and left
 * the on-device acceptance unrun. The question that actually matters for the
 * setting is narrower than the W×T sweep #139 ran: **one session, T intra-op
 * threads**, T = 1,2,3,4,6,8. #139 varied W and T together, so its 4-thread
 * points (2×2 = 0.945, 4×1 = 1.207 on the S22) fold session-splitting cost
 * into the thread axis; this leg holds W=1 and sweeps T alone.
 *
 * Measured per leg: RTF (wall / produced audio), session open time, VmHWM/PSS,
 * thermal status/headroom, and — from [PowerProbe] — battery power, energy per
 * hour of audio, peak battery temperature, and the CPUs the inference thread
 * was seen on. An **idle baseline leg** (60 s, no session open) runs first:
 * with the screen on, every leg sits on a constant display/system drain, so
 * the synthesis-attributable power is `leg − idle`, not the leg's absolute mW.
 * The energy axis is why the sweep exists: fewer threads means lower power but
 * a longer wall, and only a device measurement says which way the product
 * lands.
 *
 * **Run energy legs with the screen ON.** Unplugged + screen-off puts the app
 * process in the restricted background cpuset and stalls inference ~5×
 * (measured 2026-09-11: one t1 run went RTF 1.214 → 6.575 mid-leg after the
 * cable was pulled with the screen off; AP temperature stayed at 35-38 °C, so
 * it was a policy restriction, not thermal throttling).
 *
 * Corpus: `files/corpus.tsv` by default — the same two passages (en-us
 * af_heart + pt-br pf_dora) the #115 provider table measured the 8 Elite
 * Gen 5 CPU at RTF 0.43-0.66 on, so the T=6 leg here is directly comparable
 * with the recorded baseline. Pass `corpus` to point at the 16-passage
 * `corpus_pregen.tsv` instead.
 *
 * Runs best-of-N per leg (default 3) with an untimed warm-up inference, so
 * lazy graph initialization never lands inside a timed run. Results flush to
 * `kokoro_thread_sweep.json` after every leg.
 */
class ThreadSweepRunner(
    private val context: Context,
) {
    companion object {
        const val RUNS = 3
        const val CORPUS = "corpus.tsv"
        const val IDLE_BASELINE_MS = 60_000L
        val THREADS = listOf(1, 2, 3, 4, 6, 8)
        private val VOICES = mapOf("en-us" to "af_heart", "pt-br" to "pf_dora")
    }

    private val models = File(context.filesDir, "models")
    private val sampleRate = 24_000.0

    private data class Passage(
        val voice: String,
        val phonemes: String,
    )

    /** One inference window, tokenized + style-ready once. */
    private data class Window(
        val tokens: IntArray,
        val style: FloatArray,
    )

    fun run(
        threads: List<Int>,
        runs: Int,
        corpusName: String,
        waitUnplugged: Boolean,
        log: (String) -> Unit,
    ): Boolean =
        try {
            if (waitUnplugged) {
                if (PowerProbe.isPlugged(context)) {
                    log("waiting for the cable to be pulled — battery current while plugged is a charge current, not a load signal")
                    while (PowerProbe.isPlugged(context)) Thread.sleep(2_000)
                    log("cable pulled — starting the sweep on battery")
                } else {
                    log("already on battery — starting the sweep")
                }
            }
            check(File(models, "kokoro-model").isFile && File(models, "kokoro-voices").isFile) {
                "models not found under ${models.absolutePath} — stage them first (see build.md)"
            }
            val corpusFile = File(context.filesDir, corpusName)
            check(corpusFile.isFile) { "corpus not found at ${corpusFile.absolutePath}" }

            val passages =
                corpusFile.readLines().mapNotNull { line ->
                    val parts = line.split('\t')
                    val voice = if (parts.size == 3) VOICES[parts[1]] else null
                    voice?.let { Passage(it, parts[2]) }
                }
            check(passages.isNotEmpty()) { "$corpusName is empty or malformed (no known-language rows)" }

            val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
            val voices = KokoroVoiceBank.load(File(models, "kokoro-voices"))
            val windows = passages.flatMap { windowsOf(it, tokenizer, voices) }

            val outDir = context.getExternalFilesDir(null) ?: context.filesDir
            val outFile = File(outDir, "kokoro_thread_sweep.json")
            val results =
                JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("cores", Runtime.getRuntime().availableProcessors())
                    .put("corpus", corpusName)
                    .put("passages", passages.size)
                    .put("windows", windows.size)
                    .put("runs", runs)
            log(
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}, " +
                    "${Runtime.getRuntime().availableProcessors()} cores",
            )
            log("corpus: $corpusName — ${passages.size} passages, ${windows.size} windows, best of $runs")

            // Idle baseline first: an unplugged screen-on leg carries a constant
            // display/system drain, so the synthesis-attributable power is
            // `leg - idle`, not the leg's absolute mW. 60 s with no session open.
            log("idle baseline: sampling power for ${IDLE_BASELINE_MS / 1000}s with no inference…")
            val idle = measureIdleBaseline()
            results.put("idle", idle)
            outFile.writeText(results.toString(2))
            log(
                "idle: power ${"%.0f".format(idle.getDouble("power_mean_mw"))} mW, " +
                    "current ${"%.0f".format(idle.getDouble("current_mean_ma"))} mA, " +
                    "batt ${"%.1f".format(idle.getDouble("max_battery_temp_c"))}°C, " +
                    "unplugged ${"%.0f".format(idle.getDouble("unplugged_fraction") * 100)}%",
            )

            for (t in threads.sorted()) {
                log("leg t$t: opening one session at $t intra-op thread(s)…")
                val leg = runLeg(t, runs, windows, log)
                results.put("t$t", leg)
                outFile.writeText(results.toString(2)) // partial survives an OOM / unplug
                val rtf = leg.getDouble("rtf")
                val power = leg.getDouble("power_mean_mw")
                log(
                    "t$t: ${"%.2f".format(leg.getDouble("audio_seconds"))}s audio in ${leg.getLong("wall_ms")} ms " +
                        "(thp ${"%.2f".format(leg.getDouble("throughput_audio_s_per_s"))} audio-s/s, " +
                        "RTF ${"%.3f".format(rtf)}), open ${leg.getLong("open_ms")} ms, " +
                        "VmHWM ${leg.getLong("vm_hwm_kb")} kB, PSS ${leg.getInt("total_pss_kb")} kB, " +
                        "thermal<${leg.getInt("thermal_status_max")}>/${"%.2f".format(leg.getDouble("thermal_headroom_max"))}, " +
                        "batt ${"%.1f".format(leg.getDouble("max_battery_temp_c"))}°C, " +
                        "unplugged ${"%.0f".format(leg.getDouble("unplugged_fraction") * 100)}%, " +
                        "power ${"%.0f".format(power)} mW, " +
                        "energy ${"%.2f".format(leg.getDouble("energy_wh_per_audio_hour"))} Wh/audio-h",
                )
            }

            val baseline = results.getJSONObject("t${threads.min()}")
            for (t in threads.sorted()) {
                val leg = results.getJSONObject("t$t")
                log(
                    "  t$t speedup vs t${threads.min()}: " +
                        "${"%.2f".format(leg.getDouble("throughput_audio_s_per_s") / baseline.getDouble("throughput_audio_s_per_s"))}x",
                )
            }
            outFile.writeText(results.toString(2))
            log("kokoro_thread_sweep.json written to $outDir")
            log("DONE")
            true
        } catch (e: Throwable) {
            log("thread-sweep leg unavailable: $e")
            false
        }

    /** Power with no inference running — the constant every leg sits on top of. */
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

    private fun runLeg(
        threads: Int,
        runs: Int,
        windows: List<Window>,
        log: (String) -> Unit,
    ): JSONObject {
        val tOpen = System.currentTimeMillis()
        val session =
            OrtKokoroSession.open(
                File(models, "kokoro-model"),
                sessionFactory = { options -> options.setIntraOpNumThreads(threads) },
            )
        val openMs = System.currentTimeMillis() - tOpen
        session.use {
            // Untimed warm-up: the first infer pays lazy kernel/graph setup.
            session.infer(windows.first().tokens, windows.first().style, 1.0)

            val thermal = ThermalProbe(context)
            val power = PowerProbe(context)
            thermal.start()
            power.start()
            var bestWall = Long.MAX_VALUE
            var audioSeconds = 0.0
            for (run in 1..runs) {
                val wall =
                    measureTimeMillis {
                        var audio = 0.0
                        for (w in windows) audio += session.infer(w.tokens, w.style, 1.0).audio.size / sampleRate
                        audioSeconds = audio
                    }
                log(
                    "  t$threads run $run/$runs: ${"%.2f".format(audioSeconds)}s audio in $wall ms, " +
                        "RTF=${"%.3f".format(wall / 1000.0 / audioSeconds)}",
                )
                if (wall < bestWall) bestWall = wall
            }
            power.stop()
            thermal.stop()

            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            val rtf = bestWall / 1000.0 / audioSeconds
            return JSONObject()
                .put("threads", threads)
                .put("open_ms", openMs)
                .put("wall_ms", bestWall)
                .put("audio_seconds", audioSeconds)
                .put("rtf", rtf)
                .put("throughput_audio_s_per_s", audioSeconds / bestWall * 1000.0)
                .put("vm_hwm_kb", readVmHwm())
                .put("total_pss_kb", mem.totalPss)
                .put("thermal_status_max", thermal.maxStatus)
                .put("thermal_headroom_max", thermal.maxHeadroom.toDouble())
                .put("power_mean_mw", power.meanPowerMw)
                .put("current_mean_ma", power.meanCurrentMa)
                .put("peak_current_ma", power.peakAbsCurrentMa)
                .put("unplugged_fraction", power.unpluggedFraction)
                .put("max_battery_temp_c", power.maxBatteryTempC)
                .put("energy_wh_per_audio_hour", power.meanPowerMw * rtf / 1000.0)
                .put("cpus_observed", JSONArray(power.cpusObserved.toList()))
        }
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }
}
