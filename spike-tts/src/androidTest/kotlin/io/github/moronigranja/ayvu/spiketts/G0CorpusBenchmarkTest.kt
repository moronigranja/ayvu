package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * G0 narration-corpus device measurement: one instrumented run over the
 * staged `g0_corpus.tsv` (five columns, host-phonemized per g0Corpus) writing
 * `g0_results.json` and one `g0_<id>_<lang>_<category>.wav` per entry.
 * Requires the G0 corpus + the kokoro packs staged (see build.md).
 *
 * Usage (device staged per build.md, screen may be off/locked):
 *   adb shell am instrument -w \
 *     -e class io.github.moronigranja.ayvu.spiketts.G0CorpusBenchmarkTest \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class G0CorpusBenchmarkTest {
    @Test
    fun measureG0CorpusOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpus = File(context.filesDir, "g0_corpus.tsv")
        assertTrue("g0_corpus.tsv not staged (see build.md)", corpus.isFile)
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val result = G0CorpusRunner(context).run(corpus, outDir) { Log.d("G0Corpus", it) }
        assertTrue(
            "g0_results.json must carry a non-empty entries array",
            result.optJSONArray("entries")?.length() ?: 0 > 0,
        )
        Log.d(
            "G0Corpus",
            "results ok: ${result.optJSONArray("entries")?.length()} entries, " +
                "engine_open_ms=${result.optLong("engine_open_ms", -1)}",
        )
    }
}
