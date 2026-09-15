package com.moronigranja.localttsreader.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * kokoro-hexagon P5: sustained-loop verdict leg. Runs the pinned chain for
 * `minutes` (instrumentation arg, default 12) on ONE leg (`leg` = cpu|htp —
 * prosody CPU vs prosody HTP-context; the rest of the chain is CPU either
 * way). Accumulates per-window RTF + peak PSS into kokoro_p5_<leg>.json; the
 * shell takes dumpsys batterystats deltas around each run for the CPU-vs-HTP
 * power comparison.
 *
 * Usage (screen may be off; keep it on via svc power stayon true):
 *   adb shell am instrument -w -e class \
 *     com.moronigranja.localttsreader.spiketts.KokoroP5LoopTest \
 *     -e leg htp -e minutes 12 \
 *     com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class KokoroP5LoopTest {
    @Test
    fun sustainedLoop() {
        val args = InstrumentationRegistry.getArguments()
        val leg = args.getString("leg") ?: "cpu"
        val minutes = args.getString("minutes")?.toLongOrNull() ?: 12L
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ok =
            KokoroStageRunner(context).runSustained(leg, minutes * 60_000L) { line: String ->
                Log.d("KokoroSpike", line)
            }
        assertTrue("p5 $leg loop failed", ok)
    }
}
