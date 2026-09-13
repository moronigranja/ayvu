package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase H capture math (decisions #109): whole-second storage with local-day
 * splits, the reading span floor, and the flip-active window. Day keys and
 * day ends are pinned lambdas (midnight at 1_000_000_000 epoch seconds), so
 * boundaries are exact, not timezone-dependent.
 */
class ActivityCaptureTest {

    private companion object {
        const val MIDNIGHT = 1_000_000_000_000L // epoch ms: the fixed local midnight
        const val DAY_A = "2026-09-13"
        const val DAY_B = "2026-09-14"
    }

    /** Day A before the fixed midnight, day B after; dayEnd: A ends at midnight. */
    private val dayKey: (Long) -> String = { if (it < MIDNIGHT) DAY_A else DAY_B }
    private val dayEnd: (Long) -> Long = { if (it < MIDNIGHT) MIDNIGHT else 2 * MIDNIGHT }

    // ------------------------------------------------------------------
    // Listening (TimeSpanAccumulator)

    @Test
    fun `listening stores whole seconds and floors fractions`() {
        var now = 0L
        val acc = TimeSpanAccumulator(ActivityKind.LISTEN, { now }, dayKey, dayEnd)

        acc.start("b1")
        now += 90_940 // 90 whole seconds + 940 ms
        val chunks = acc.stop()

        assertEquals(listOf(ActivityChunk(DAY_A, "b1", ActivityKind.LISTEN, 90)), chunks)
    }

    @Test
    fun `listening splits across a local-day boundary`() {
        var now = MIDNIGHT - 30_940 // 30 s + 940 ms before midnight
        val acc = TimeSpanAccumulator(ActivityKind.LISTEN, { now }, dayKey, dayEnd)

        acc.start("b1")
        now += 75_000 // 44 s + 60 ms after midnight; 30 s before it
        val chunks = acc.stop()

        assertEquals(
            listOf(
                ActivityChunk(DAY_A, "b1", ActivityKind.LISTEN, 30),
                ActivityChunk(DAY_B, "b1", ActivityKind.LISTEN, 44),
            ),
            chunks,
        )
    }

    @Test
    fun `stop without start and restart are no-ops`() {
        var now = 0L
        val acc = TimeSpanAccumulator(ActivityKind.LISTEN, { now }, dayKey, dayEnd)

        assertEquals(emptyList<ActivityChunk>(), acc.stop())

        acc.start("b1")
        now += 5_000
        acc.start("b2") // while running: the open window is kept, not restarted
        now += 5_000

        assertEquals(listOf(ActivityChunk(DAY_A, "b1", ActivityKind.LISTEN, 10)), acc.stop())
        assertEquals(emptyList<ActivityChunk>(), acc.stop())
    }

    @Test
    fun `a regressed clock yields nothing`() {
        var now = 100_000L
        val acc = TimeSpanAccumulator(ActivityKind.LISTEN, { now }, dayKey, dayEnd)

        acc.start("b1")
        now -= 50_000

        assertEquals(emptyList<ActivityChunk>(), acc.stop())
    }

    // ------------------------------------------------------------------
    // Reading (ReadingSpanTracker)

    @Test
    fun `flip-to-flip dwell is counted when it clears the floor`() {
        var now = 0L
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        tracker.onFlip("b1")
        now += 40_000
        val closed = tracker.onFlip("b1") // next flip closes the first window

        assertEquals(listOf(ActivityChunk(DAY_A, "b1", ActivityKind.READ, 40)), closed)
    }

    @Test
    fun `sub-10-second spans are dropped`() {
        var now = 0L
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        tracker.onFlip("b1")
        now += 9_999 // one ms short of the floor
        assertEquals(emptyList<ActivityChunk>(), tracker.onFlip("b1"))

        tracker.onFlip("b1")
        now += 10_000 // exactly the floor counts
        assertEquals(listOf(ActivityChunk(DAY_A, "b1", ActivityKind.READ, 10)), tracker.onFlip("b1"))
    }

    @Test
    fun `a flip more than one window away credits only the window cap`() {
        var now = 0L
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        tracker.onFlip("b1")
        now += ReadingSpanTracker.ACTIVE_WINDOW_MILLIS + 500_000 // parked past the window
        val closed = tracker.onFlip("b1")

        assertEquals(
            listOf(ActivityChunk(DAY_A, "b1", ActivityKind.READ, ReadingSpanTracker.ACTIVE_WINDOW_MILLIS / 1000)),
            closed,
        )
    }

    @Test
    fun `interrupt closes the open window and leaves nothing to close twice`() {
        var now = 0L
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        assertEquals(emptyList<ActivityChunk>(), tracker.onInterrupt()) // nothing open

        tracker.onFlip("b1")
        now += 25_000
        assertEquals(listOf(ActivityChunk(DAY_A, "b1", ActivityKind.READ, 25)), tracker.onInterrupt())
        assertEquals(emptyList<ActivityChunk>(), tracker.onInterrupt())
    }

    @Test
    fun `a capped window straddling midnight splits per day`() {
        var now = MIDNIGHT - 120_000 // 2 min before midnight
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        tracker.onFlip("b1")
        now += 240_000 // still open at interrupt; the 3-min cap bounds the span
        val closed = tracker.onInterrupt() // 2 min before midnight + 1 min after it

        assertEquals(
            listOf(
                ActivityChunk(DAY_A, "b1", ActivityKind.READ, 120),
                ActivityChunk(DAY_B, "b1", ActivityKind.READ, 60),
            ),
            closed,
        )
    }

    @Test
    fun `dwell without any flip accrues nothing`() {
        var now = 0L
        val tracker = ReadingSpanTracker({ now }, dayKey, dayEnd)

        now += 600_000 // the reader sat open; nobody turned a page
        assertEquals(emptyList<ActivityChunk>(), tracker.onInterrupt())
    }
}
