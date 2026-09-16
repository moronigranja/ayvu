package io.github.moronigranja.ayvu.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Single-session intra-op thread sweep (decisions #147) — the device number
 * behind the shipped `tts_threads` setting (#137) and the energy question
 * behind it: fewer threads, lower power, longer wall — which wins?
 *
 * Runs `ThreadSweepRunner` on the staged model + corpus, locked/screen-off.
 * Writes `kokoro_thread_sweep.json` to the external files dir after every leg
 * (partial results survive an lmkd kill or an unplug).
 *
 * Usage (device staged per build.md):
 *   adb shell am instrument -w \
 *     -e class io.github.moronigranja.ayvu.spiketts.ThreadSweepBenchmarkTest \
 *     io.github.moronigranja.ayvu.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Optional args: `-e threads 1,6` (subset), `-e runs 2`, `-e corpus corpus_pregen.tsv`,
 * `-e wait_unplugged true` (start the sweep when the cable is pulled).
 *
 * Energy: run at least one pass with the cable PULLED — the battery current
 * while plugged is a charge current, not a load signal, and the JSON reports
 * `unplugged_fraction` so a plugged leg cannot be mistaken for a measured
 * one. The runner keeps going after the USB detaches; re-attach and pull the
 * JSON.
 */
@RunWith(AndroidJUnit4::class)
class ThreadSweepBenchmarkTest {
    @Test
    fun measureThreadSweepOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val threads =
            args
                .getString("threads")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 1..32 }
                ?.takeIf { it.isNotEmpty() }
                ?: ThreadSweepRunner.THREADS
        val runs = args.getString("runs")?.toIntOrNull()?.coerceIn(1, 10) ?: ThreadSweepRunner.RUNS
        val corpus = args.getString("corpus") ?: ThreadSweepRunner.CORPUS
        val waitUnplugged = args.getString("wait_unplugged") == "true"
        val ok = ThreadSweepRunner(context).run(threads, runs, corpus, waitUnplugged) { Log.d("KokoroSpike", it) }
        assertTrue("thread sweep failed", ok)
    }
}
