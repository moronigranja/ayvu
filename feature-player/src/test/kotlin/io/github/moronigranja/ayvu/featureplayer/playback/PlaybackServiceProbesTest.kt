package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Measurement probes (docs/generate-play-goals.md §Measurement): the probes —
 * `AyvuTap` (tap-to-audio, L1/L2/L3) and `AyvuGap` (boundary gap, GAP1) — are
 * debug-gated, log-only, and must never block, publish, or reorder, so nothing
 * here may perturb the 50 ms poll loop or CR-2/CR-5/CR-7 ordering.
 *
 * The real play loop is not driven (it is a private suspend loop with a 60 s
 * buffer-before-start wait on a short book — no house test seam; the
 * PublishGuard harness documents the same limitation). Instead the extracted
 * pure decision ([PlaybackService.computeGapMs]) and the gated emit
 * ([PlaybackService.probe], exercised reflectively with Robolectric's
 * deterministic ShadowLog) pin the probe contract:
 *   - silence when the app build is not debuggable (zero production cost),
 *   - tap/gap emission when debuggable,
 *   - baseline reset on stopEverything (a fresh start is never a gap),
 *   - the gap is reported only for consecutive same-loop plays.
 *
 * Timing VALUES are never asserted — only the decisions and the log surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceProbesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(
            context,
            settings,
        ) {
        override fun engine(): TTSEngine? = FakeEngine()

        override val failureReason: String? = null
    }

    private class FakeOutput : PassageOutput {
        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) = Unit

        override fun stop() = Unit

        override val positionSamples: Int = 0

        override fun setVolume(multiplier: Float) = Unit
    }

    /** A directly-constructed service with base context attached and a fake
     * output (the Hilt-transformed onCreate cannot run under plain
     * Robolectric — same seam as the A57/PublishGuard harnesses). */
    private fun service(): PlaybackService =
        PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            this.output = FakeOutput()
        }

    /** Debug-build simulation: symlinks the app's debuggable runtime flag —
     * the gate the probes check (there is no feature-player BuildConfig). */
    private fun setDebuggable(service: PlaybackService) {
        val info = service.applicationInfo
        val flags = ApplicationInfo::class.java.getField("flags")
        flags.setInt(info, info.flags or ApplicationInfo.FLAG_DEBUGGABLE)
    }

    private fun invokeProbe(
        service: PlaybackService,
        tag: String,
        message: String,
    ) {
        val method: Method = PlaybackService::class.java.getDeclaredMethod("probe", String::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(service, tag, message)
    }

    private fun field(name: String): Field = PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun logsFor(tag: String): List<ShadowLog.LogItem> = ShadowLog.getLogsForTag(tag)

    // ------------------------------------------------------------------
    // Gate
    // ------------------------------------------------------------------

    /** Release builds (FLAG_DEBUGGABLE unset) must emit nothing — the
     * "zero production-path behavior" half of the acceptance. */
    @Test
    fun `probe is silent when the build is not debuggable`() {
        val service = service() // no FLAG_DEBUGGABLE

        invokeProbe(service, "AyvuTap", "tap-to-audio ms=1 source=disk action=play")
        invokeProbe(service, "AyvuGap", "gap-ms=12 passage=0/1")

        assertTrue("release builds must never log probes", logsFor("AyvuTap").isEmpty())
        assertTrue("release builds must never log probes", logsFor("AyvuGap").isEmpty())
    }

    /** Debug builds (FLAG_DEBUGGABLE set — the app's debug build) emit every
     * probe through the single gated helper, in the tag+shape a dev script
     * consumes. */
    @Test
    fun `probe emits through the gate on a debuggable build`() {
        val service = service()
        setDebuggable(service)

        invokeProbe(service, "AyvuTap", "tap-to-audio ms=42 source=pregen action=play")
        invokeProbe(service, "AyvuGap", "gap-ms=12 passage=0/1")

        assertEquals(1, logsFor("AyvuTap").size)
        assertEquals("tap-to-audio ms=42 source=pregen action=play", logsFor("AyvuTap")[0].msg)
        assertEquals(1, logsFor("AyvuGap").size)
        assertEquals("gap-ms=12 passage=0/1", logsFor("AyvuGap")[0].msg)
    }

    // ------------------------------------------------------------------
    // GAP1 decision — pure function
    // ------------------------------------------------------------------

    /** The gap is reported only between CONSECUTIVE same-loop plays: a fresh
     * start (prevPlayAt == 0 — what resume/seek leave behind via
     * stopEverything) is never a gap, the duration term is
     * frames/rate*1000 from the PREVIOUS play, and a backwards clock is not
     * a gap. Timing values themselves are not asserted. */
    @Test
    fun `computeGapMs reports a gap only for consecutive same-loop plays`() {
        // Fresh start / baseline reset: playAt == 0 → no gap.
        assertNull(
            "baseline reset (resume/seek) must suppress the gap",
            PlaybackService.computeGapMs(now = 1_512L, prevPlayAt = 0L, prevFrames = 12_000, sampleRate = 24_000),
        )
        // Consecutive plays: play N dispatched at 1_000 ms with exactly
        // 12_000 frames (500 ms of mono 16-bit at 24 kHz); play N+1 at
        // 1_512 ms → gap = 12 ms.
        assertEquals(
            12L,
            PlaybackService.computeGapMs(now = 1_512L, prevPlayAt = 1_000L, prevFrames = 12_000, sampleRate = 24_000),
        )
        // Degenerate inputs (no previous frame count / no rate) → no gap.
        assertNull(PlaybackService.computeGapMs(now = 1_512L, prevPlayAt = 1_000L, prevFrames = 0, sampleRate = 24_000))
        assertNull(PlaybackService.computeGapMs(now = 1_512L, prevPlayAt = 1_000L, prevFrames = 12_000, sampleRate = 0))
        // Clock went backwards → not a gap.
        assertNull(
            "a backwards clock must not report a gap",
            PlaybackService.computeGapMs(now = 1_400L, prevPlayAt = 1_000L, prevFrames = 12_000, sampleRate = 24_000),
        )
    }

    // ------------------------------------------------------------------
    // Baseline lifecycle
    // ------------------------------------------------------------------

    /** stopEverything breaks the consecutive-play chain (every resume/seek/
     * stop path runs it first): playAt/prevFrames reset so the next play is a
     * fresh start, never a gap — but the tap arm SURVIVES (an open/play tap
     * races its own command's stopEverything and must reach the first play
     * dispatch, or tap-to-audio would never measure). */
    @Test
    fun `stopEverything resets the gap baseline but keeps the tap arm`() {
        val service = service()
        // Fabricate an in-loop state: a previous play at 1_000 ms with
        // 12_000 frames rendered, and a tap armed by the last dispatch.
        field("playAt").setLong(service, 1_000L)
        field("prevFrames").setInt(service, 12_000)
        field("tapAt").setLong(service, 990L)

        service.stopEverything()

        assertEquals("gap baseline reset", 0L, field("playAt").getLong(service))
        assertEquals("gap baseline reset", 0, field("prevFrames").getInt(service))
        assertEquals(
            "the tap arm survives its own command's stopEverything",
            990L,
            field("tapAt").getLong(service),
        )
    }
}
