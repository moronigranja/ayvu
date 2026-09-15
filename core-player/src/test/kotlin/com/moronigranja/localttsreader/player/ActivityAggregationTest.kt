package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase H aggregation (post-v1-plan Slice A): TODAY totals with display
 * rounding, the 7-day series, and the streak walk over midnight/gap
 * boundaries — all over pinned day keys, never the wall clock.
 */
class ActivityAggregationTest {
    // ------------------------------------------------------------------
    // DailyTotals

    @Test
    fun `totals sum per kind and round minutes half-up for display only`() {
        val summary =
            DailyTotals.summarize(
                "2026-09-13",
                listOf(
                    ActivityRow("2026-09-13", ActivityKind.LISTEN, 1740), // 29 m
                    ActivityRow("2026-09-13", ActivityKind.READ, 725), // 12 m + 5 s
                    ActivityRow("2026-09-12", ActivityKind.LISTEN, 600), // other day: excluded
                    ActivityRow("2026-09-13", ActivityKind.READ, 30), // rounds into 12 m
                ),
            )

        assertEquals(755, summary.readSeconds)
        assertEquals(1740, summary.listenedSeconds)
        assertEquals(13, summary.readMinutes)
        assertEquals(29, summary.listenedMinutes)
        assertEquals(42, summary.totalMinutes)
    }

    @Test
    fun `89 seconds displays as one minute and 90 as two`() {
        assertEquals(1, TodaySummary.displayMinutes(89))
        assertEquals(2, TodaySummary.displayMinutes(90))
        assertEquals(0, TodaySummary.displayMinutes(0))
    }

    @Test
    fun `a day with no rows summarizes to zero`() {
        val summary = DailyTotals.summarize("2026-09-13", emptyList())
        assertEquals(TodaySummary(0, 0), summary)
    }

    // ------------------------------------------------------------------
    // WeekSummary

    @Test
    fun `series returns the 7 days ending today oldest-first with zero fill`() {
        val bars =
            WeekSummary.series(
                listOf(
                    ActivityRow("2026-09-08", ActivityKind.LISTEN, 600),
                    ActivityRow("2026-09-13", ActivityKind.READ, 60),
                    ActivityRow("2026-09-13", ActivityKind.LISTEN, 120),
                    ActivityRow("2026-09-15", ActivityKind.LISTEN, 60), // outside the window
                ),
                todayKey = "2026-09-13",
            )

        assertEquals(7, bars.size)
        assertEquals("2026-09-07", bars.first().dayKey) // today - 6
        assertEquals(600, bars[1].listenedSeconds) // 09-08
        assertEquals(0, bars[2].totalSeconds) // 09-09: zero-filled gap inside the window
        assertEquals(60, bars.last().readSeconds)
        assertEquals(120, bars.last().listenedSeconds)
    }

    // ------------------------------------------------------------------
    // Streak

    @Test
    fun `streak counts consecutive active days ending today`() {
        assertEquals(
            3,
            Streak.count(
                setOf("2026-09-11", "2026-09-12", "2026-09-13"),
                todayKey = "2026-09-13",
            ),
        )
    }

    @Test
    fun `streak survives a morning before the first session - ends yesterday`() {
        // Today has nothing yet; yesterday closes the chain.
        assertEquals(
            2,
            Streak.count(setOf("2026-09-11", "2026-09-12"), todayKey = "2026-09-13"),
        )
    }

    @Test
    fun `a gap ends the streak`() {
        assertEquals(0, Streak.count(setOf("2026-09-10"), todayKey = "2026-09-13"))
        assertEquals(0, Streak.count(emptySet(), todayKey = "2026-09-13"))
        assertEquals(1, Streak.count(setOf("2026-09-13"), todayKey = "2026-09-13"))
    }

    @Test
    fun `non-consecutive days do not add up`() {
        // 09-12 is missing: the chain breaks after today.
        assertEquals(
            1,
            Streak.count(setOf("2026-09-11", "2026-09-13"), todayKey = "2026-09-13"),
        )
    }
}
