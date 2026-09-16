package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * KittenTTS Nano D3 measurement as an instrumented test: runs on a locked or
 * screen-off device — no Activity, `am instrument` keeps the process exempt
 * from the freezer. Reports through logcat (`KittenSpike`) and writes
 * `d3_results_kitten.json` + WAVs to the external files dir. Skips itself
 * when no D3 corpus is staged.
 *
 * Experiment sweep hooks (decisions #93): `-e threads N -e optProfile
 * default|memOff|arenaOff|bothOff -e limit N` isolate the device-side NaN
 * divergence; without extras the defaults reproduce the measured pass.
 *
 * Usage (device staged per build.md, screen may be off/locked):
 *   adb shell am instrument -w \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KittenDeviceBenchmarkTest {
    @Test
    fun measureKittenOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpus = File(context.filesDir, "d3_corpus.tsv")
        if (!corpus.isFile) {
            Log.d("KittenSpike", "no d3_corpus.tsv staged — skipping Kitten D3 pass")
            return
        }
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val args =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.let { c ->
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
            }
        val threads = args.getString("threads")?.toIntOrNull() ?: KittenBenchmarkRunner.THREADS
        val optProfile = args.getString("optProfile") ?: "default"
        val limit = args.getString("limit")?.toIntOrNull() ?: 0
        val suffix =
            if (args.getString("optProfile") != null || args.getString("threads") != null) {
                "_${optProfile}_t$threads"
            } else {
                ""
            }
        val results =
            KittenBenchmarkRunner(context).run(
                corpus,
                outDir,
                { Log.d("KittenSpike", it) },
                threads,
                optProfile,
                limit,
            )
        File(outDir, "d3_results_kitten$suffix.json").writeText(results.toString(2))
        Log.d("KittenSpike", "d3_results_kitten$suffix.json written to $outDir")
    }
}
