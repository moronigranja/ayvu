package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D3 unified device measurement (decisions #93): one instrumented run over
 * the staged `d3_corpus.tsv` producing the merged `d3_results.json`
 * (`{"kokoro": …, "kitten": …, "moss": …}`). Requires the D3 corpus + the
 * kokoro packs staged (see build.md §"D3 engine comparison staging"); the
 * Kitten/MOSS legs degrade to `unavailable` entries when their packs are
 * missing, but the Kokoro baseline must complete.
 *
 * Usage (device staged per build.md, screen may be off/locked):
 *   adb shell am instrument -w \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class D3CompareBenchmarkTest {
    @Test
    fun measureD3EnginesOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpus = File(context.filesDir, "d3_corpus.tsv")
        assertTrue("d3_corpus.tsv not staged (see build.md D3 staging)", corpus.isFile)
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val merged = D3CompareRunner(context).run(corpus, outDir) { Log.d("D3Compare", it) }
        assertTrue(
            "d3_results.json must carry all three legs",
            merged.has("kokoro") && merged.has("kitten") && merged.has("moss"),
        )
        val kokoro = merged.getJSONObject("kokoro")
        assertTrue(
            "kokoro baseline must complete",
            !kokoro.has("unavailable") && kokoro.optLong("engine_open_ms", -1) > 0,
        )
        Log.d(
            "D3Compare",
            "merged results ok: kokoro complete, kitten=${merged.optJSONObject("kitten")?.has("unavailable") == false}, " +
                "moss=${merged.optJSONObject("moss")?.has("unavailable") == false}",
        )
    }
}
