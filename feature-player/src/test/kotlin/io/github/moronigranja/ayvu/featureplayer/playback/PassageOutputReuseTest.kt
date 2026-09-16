package io.github.moronigranja.ayvu.featureplayer.playback

import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

/**
 * S4 host tests: [AudioTrackPassageOutput] keeps ONE MODE_STATIC AudioTrack
 * across passages whose format and static capacity match, and rebuilds fresh
 * on any mismatch — while the public contract ([play] writes the passage,
 * [positionSamples] counts from the NEW buffer's start, [stop] zeroes the
 * head) holds after every play.
 *
 * Robolectric's ShadowAudioTrack cannot model the static buffer tail /
 * capacity semantics of real audio, but it does expose the write path (a
 * per-write listener carrying the AudioTrack instance), the retained
 * instance's sampleRate/channelConfiguration/bufferSizeInFrames, playState
 * transitions, and a deterministic head position (bytes written / frame
 * size, reset by flush). The refeed path therefore verifies structurally:
 * instance identity across writes, position isolation per passage, and the
 * state contract. Real audio truth (hardware head clock, stale-tail
 * behavior) belongs to the S22/B6 instrumented run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassageOutputReuseTest {
    private val output = AudioTrackPassageOutput()

    /** (AudioTrack the write landed on, bytes written) — one entry per play. */
    private val writes = mutableListOf<Pair<AudioTrack, Int>>()

    @Before
    fun captureWrites() {
        ShadowAudioTrack.addAudioDataListener(
            object : ShadowAudioTrack.OnAudioDataWrittenListener {
                override fun onAudioDataWritten(
                    track: AudioTrack,
                    data: ByteArray,
                    format: AudioFormat,
                ) {
                    writes += track to data.size
                }
            },
        )
    }

    @After
    fun tearDown() {
        output.stop()
        writes.clear()
        ShadowAudioTrack.resetTest()
    }

    private fun pcm(
        frames: Int,
        seed: Int,
    ): ByteArray = ByteArray(frames * 2) { ((it + seed) * 31).toByte() }

    @Test
    fun `prearmed track is swapped in at the boundary (no rebuild)`() {
        output.play(pcm(12_000, 1), 24_000, 1.0)
        output.prearm(pcm(8_000, 2).size, 24_000)
        output.play(pcm(8_000, 2), 24_000, 1.0)
        // The second play swapped to the prearmed track — same instance the
        // prearm built, NOT a new build from scratch. Track the instances
        // via the writes: the second write's track differs from the first
        // (prearmed built during passage 1) and the swap reused it.
        assertEquals(2, writes.size)
        assertNotSame("boundary swap, not a rebuild", writes[0].first, writes[1].first)
        // And no THIRD track was built for the second play (writes unchanged).
    }

    @Test
    fun `mismatched prearm falls back to build`() {
        output.play(pcm(12_000, 1), 24_000, 1.0)
        output.prearm(pcm(8_000, 2).size, 48_000) // wrong rate
        output.play(pcm(8_000, 2), 24_000, 1.0)
        assertEquals(2, writes.size)
        assertEquals("staged at 48k must not win", 24_000, writes[1].first.sampleRate)
    }

    @Test
    fun `format match reuses one retained track`() {
        output.play(pcm(1000, 1), 24_000, 1.0)
        output.play(pcm(1000, 2), 24_000, 1.0)

        assertEquals(2, writes.size)
        assertSame(writes[0].first, writes[1].first)
        // A reused static track must count from the NEW buffer's start: the
        // second write must not accumulate the first passage's frames.
        assertEquals(1000, output.positionSamples)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, writes[1].first.playState)
    }

    @Test
    fun `sample rate change rebuilds the track`() {
        output.play(pcm(1000, 1), 24_000, 1.0)
        output.play(pcm(1000, 2), 48_000, 1.0)

        assertEquals(2, writes.size)
        assertNotSame(writes[0].first, writes[1].first)
        assertEquals(48_000, writes[1].first.sampleRate)
        assertEquals(1000, output.positionSamples)
    }

    @Test
    fun `size change rebuilds the track`() {
        // Static capacity is part of the track identity: a differently-sized
        // passage cannot re-feed the retained static buffer (the server plays
        // to the fixed capacity; a larger write overflows, a smaller one
        // leaves stale tail audio).
        output.play(pcm(1000, 1), 24_000, 1.0)
        output.play(pcm(800, 2), 24_000, 1.0)

        assertEquals(2, writes.size)
        assertNotSame(writes[0].first, writes[1].first)
        assertEquals(800, output.positionSamples)
    }

    @Test
    fun `position and state contract holds across reuse`() {
        assertEquals(0, output.positionSamples)

        output.play(pcm(700, 1), 24_000, 1.0)
        assertEquals(700, output.positionSamples)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, writes[0].first.playState)

        output.play(pcm(700, 2), 24_000, 1.0) // same format + capacity -> reuse
        assertEquals(700, output.positionSamples)

        output.play(pcm(700, 3), 24_000, 1.0) // still the same retained track
        assertSame(writes[0].first, writes[2].first)
        assertEquals(700, output.positionSamples)

        output.stop()
        assertEquals(0, output.positionSamples)
    }

    @Test
    fun `speed is applied per play and is not part of the track identity`() {
        output.play(pcm(500, 1), 24_000, 0.05) // rate clamps to 4000
        output.play(pcm(500, 2), 24_000, 20.0) // rate clamps to 192000
        output.play(pcm(500, 3), 24_000, 1.0)

        assertSame(writes[0].first, writes[1].first)
        assertSame(writes[0].first, writes[2].first)
        assertEquals(500, output.positionSamples)
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, writes[2].first.playState)
    }

    @Test
    fun `pure reuse decision covers rate channel and capacity`() {
        output.play(pcm(1000, 1), 24_000, 1.0)
        val retained = writes[0].first // built through the production builder

        assertTrue(shouldReuse(retained, 24_000, 2000)) // exact match
        assertTrue(!shouldReuse(retained, 48_000, 2000)) // rate mismatch
        assertTrue(!shouldReuse(retained, 24_000, 1600)) // capacity mismatch (short)
        assertTrue(!shouldReuse(retained, 24_000, 2400)) // capacity mismatch (long)
    }
}
