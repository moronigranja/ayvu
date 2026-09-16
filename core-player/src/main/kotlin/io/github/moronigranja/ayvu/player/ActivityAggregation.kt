package io.github.moronigranja.ayvu.player

import java.time.LocalDate

// Phase H aggregation (roadmap; post-v1-plan Slice A): pure math over
// per-day activity rows — the TODAY card's read/listen minutes, the 7-day
// mini bar, and the consecutive-days streak. Sandbox-testable: midnight and
// gap boundaries are pinned by injected day keys, never the wall clock.

/** The activity table's pure view (kind-paired seconds for one book-day). */
data class ActivityRow(
    val dayKey: String,
    val kind: ActivityKind,
    val seconds: Long,
)

/** One local day's totals. Seconds are the stored truth; minutes round for display only. */
data class TodaySummary(
    val readSeconds: Long,
    val listenedSeconds: Long,
) {
    /** Display minutes, rounded half-up so a real 30 s+ session shows. */
    val readMinutes: Int
        get() = displayMinutes(readSeconds)

    val listenedMinutes: Int
        get() = displayMinutes(listenedSeconds)

    /** The card's headline total — the displayed figures summed, so the card
     * never claims more than its own lines show. */
    val totalMinutes: Int
        get() = readMinutes + listenedMinutes

    companion object {
        fun displayMinutes(seconds: Long): Int = ((seconds + 30) / 60).toInt()
    }
}

object DailyTotals {
    /** Read/listened seconds for [dayKey] over [rows]. */
    fun summarize(
        dayKey: String,
        rows: List<ActivityRow>,
    ): TodaySummary {
        val today = rows.filter { it.dayKey == dayKey }
        return TodaySummary(
            readSeconds = today.sumOf { if (it.kind == ActivityKind.READ) it.seconds else 0L },
            listenedSeconds = today.sumOf { if (it.kind == ActivityKind.LISTEN) it.seconds else 0L },
        )
    }
}

/** One mini-bar entry: a day's read/listen seconds (zeros for days without rows). */
data class DayBar(
    val dayKey: String,
    val readSeconds: Long,
    val listenedSeconds: Long,
) {
    val totalSeconds: Long
        get() = readSeconds + listenedSeconds
}

object WeekSummary {
    /** The 7 local days ending [todayKey], oldest first. */
    fun series(
        rows: List<ActivityRow>,
        todayKey: String,
    ): List<DayBar> =
        (6 downTo 0).map { back ->
            val day = LocalDate.parse(todayKey).minusDays(back.toLong()).toString()
            val ofDay = rows.filter { it.dayKey == day }
            DayBar(
                dayKey = day,
                readSeconds = ofDay.sumOf { if (it.kind == ActivityKind.READ) it.seconds else 0L },
                listenedSeconds = ofDay.sumOf { if (it.kind == ActivityKind.LISTEN) it.seconds else 0L },
            )
        }

    /** ISO key of the first day of [todayKey]'s 7-day window. */
    fun startKey(todayKey: String): String = LocalDate.parse(todayKey).minusDays(6).toString()
}

/**
 * Consecutive-days streak (post-v1-plan Slice A): consecutive days with
 * [ACTIVE_MINIMUM_SECONDS] of combined activity, ending today — or yesterday
 * when today has none yet, so a morning before the first session keeps the
 * streak visible. Pure over the active-day set; the caller supplies the set
 * (the DAO filters days by the combined minimum).
 */
object Streak {
    const val ACTIVE_MINIMUM_SECONDS = 60L

    fun count(
        activeDayKeys: Set<String>,
        todayKey: String,
    ): Int {
        var day = LocalDate.parse(todayKey)
        if (day.toString() !in activeDayKeys) day = day.minusDays(1)
        var streak = 0
        while (day.toString() in activeDayKeys) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }
}

/** The TODAY card's state (feature-library renders it; EMPTY until data lands). */
data class TodayStats(
    val dayKey: String,
    val today: TodaySummary,
    val week: List<DayBar>,
    val streakDays: Int,
) {
    /** True when the user has no activity in the window — the card stays hidden. */
    val isEmpty: Boolean
        get() = week.all { it.totalSeconds == 0L } && streakDays == 0

    companion object {
        val EMPTY =
            TodayStats(
                dayKey = "",
                today = TodaySummary(0, 0),
                week = emptyList(),
                streakDays = 0,
            )
    }
}
