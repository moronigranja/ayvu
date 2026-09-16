package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MOSS-TTS-Nano D3 measurement as an instrumented test: runs on a locked or
 * screen-off device — no Activity, `am instrument` keeps the process exempt
 * from the freezer. Reports through logcat (`MossSpike`) and writes
 * `d3_results_moss.json` + WAVs to the external files dir. Skips itself when
 * no D3 corpus is staged.
 *
 * Usage (device staged per build.md, screen may be off/locked):
 *   adb shell am instrument -w \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class MossDeviceBenchmarkTest {
    @Test
    fun measureMossOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpus = File(context.filesDir, "d3_corpus.tsv")
        if (!corpus.isFile) {
            Log.d("MossSpike", "no d3_corpus.tsv staged — skipping MOSS D3 pass")
            return
        }
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val results = MossBenchmarkRunner(context).run(corpus, outDir) { Log.d("MossSpike", it) }
        File(outDir, "d3_results_moss.json").writeText(results.toString(2))
        Log.d("MossSpike", "d3_results_moss.json written to $outDir")
    }
}
