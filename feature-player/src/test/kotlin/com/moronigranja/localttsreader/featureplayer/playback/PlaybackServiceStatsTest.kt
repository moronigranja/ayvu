package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.player.ActivityChunk
import com.moronigranja.localttsreader.player.ActivityKind
import com.moronigranja.localttsreader.player.ActivityRow
import com.moronigranja.localttsreader.player.ActivityStore
import com.moronigranja.localttsreader.player.LocalDays
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase H listening capture at the service edge (decisions #109): the
 * accumulator the playback loop opens on the onAudioStarted transition is
 * closed by [PlaybackService.stopEverything] — every audio exit — and the
 * chunks route through the [PlaybackService.activitySink] seam. The service
 * is constructed bare (no context/Hilt), like the other host-test seams;
 * Robolectric satisfies the Service superclass.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceStatsTest {
    /** Captured store writes for the inline (teardown) flush path. */
    private class FakeActivityStore : ActivityStore {
        val writes = mutableListOf<ActivityChunk>()

        override suspend fun record(chunks: List<ActivityChunk>) {
            writes += chunks
        }

        override fun observeSince(sinceDayKey: String): Flow<List<ActivityRow>> = MutableStateFlow(emptyList())

        override fun observeActiveDays(): Flow<List<String>> = MutableStateFlow(emptyList())
    }

    private val service = PlaybackService()
    private val flushed = mutableListOf<ActivityChunk>()

    init {
        service.activitySink = { chunks -> flushed += chunks }
    }

    @After
    fun restoreClock() {
        PlaybackService.clock = System::currentTimeMillis
    }

    private fun setClock(nowMs: Long) {
        PlaybackService.clock = { nowMs }
    }

    @Test
    fun `stopEverything closes the open listening span`() {
        setClock(1_000_000_000)
        service.listenAccumulator.start("b1") // the loop edge's onAudioStarted call
        setClock(1_000_000_000 + 30_000)

        service.stopEverything()

        assertTrue("flushed=\$flushed", flushed.size == 1)
        val chunk = flushed.single()
        assertEquals("b1", chunk.bookId)
        assertEquals(ActivityKind.LISTEN, chunk.kind)
        assertEquals(30L, chunk.seconds)
    }

    @Test
    fun `a second stop flushes nothing - the span closed once`() {
        setClock(1_000_000_000)
        service.listenAccumulator.start("b1")
        setClock(1_000_000_000 + 30_000)
        service.stopEverything()
        flushed.clear()

        service.stopEverything()

        assertEquals(emptyList<ActivityChunk>(), flushed)
    }

    @Test
    fun `flushListening is the advance-boundary close and returns chunks to the sink`() {
        setClock(1_000_000_000)
        service.listenAccumulator.start("b1")
        setClock(1_000_000_000 + 10_000)

        service.flushListening() // what the loop calls after onPassageFinished

        assertTrue("flushed=\$flushed", flushed.size == 1)
        assertEquals(10L, flushed.single().seconds)
    }

    @Test
    fun `the sync flush writes through the store inline`() {
        val store = FakeActivityStore()
        service.activityStore = store
        // The init block replaced the sink with the capture list; the inline
        // (teardown) path writes through the store port, so restore the default.
        service.activitySink = { chunks -> service.activityStore.record(chunks) }
        setClock(1_000_000_000)
        service.listenAccumulator.start("b1")
        setClock(1_000_000_000 + 45_000)

        service.flushListeningSync() // STOP/kill teardown path

        assertEquals(1, store.writes.size)
        val write = store.writes.single()
        assertEquals("b1", write.bookId)
        assertEquals(ActivityKind.LISTEN, write.kind)
        assertEquals(45L, write.seconds)
        assertEquals(LocalDays.key(1_000_000_000), write.dayKey)
        assertEquals(emptyList<ActivityChunk>(), flushed) // sync path bypasses the sink
    }
}
