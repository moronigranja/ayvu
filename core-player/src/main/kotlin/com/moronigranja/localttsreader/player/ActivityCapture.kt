package com.moronigranja.localttsreader.player

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

// Phase H capture (roadmap; decisions #109): real consumption measurement.
// Listening = wall-clock while `PlayerPhase == PLAYING`; reading =
// page-flip-active reader dwell. Whole seconds are stored and split across
// local-day boundaries; only the display rounds (so short valid sessions are
// not discarded). These types are pure JVM — the Android edges
// ([com.moronigranja.localttsreader.featureplayer.playback.PlaybackService]
// for listening, the reader surface for reading) drive them.

/**
 * The activity store's seam (Phase H, decisions #109): the write side the
 * playback edge flushes through, and the day window the library's stats card
 * observes. Both directions used to inject `ActivitySecondsDao` straight from
 * feature modules — the contract lives here so the Room types stay inside
 * core-persistence, exactly like [com.moronigranja.localttsreader.player.PlayerStore]
 * and [com.moronigranja.localttsreader.model.LibraryStore] (A6).
 */
interface ActivityStore {
    /** Adds [chunks]' seconds onto their (day, book, kind) rows — additive, never a replace. */
    suspend fun record(chunks: List<ActivityChunk>)

    /** Rows on/after [sinceDayKey] (inclusive) — the TODAY card's window. */
    fun observeSince(sinceDayKey: String): Flow<List<ActivityRow>>

    /** Local day keys with at least a minute of combined activity — the streak's floor. */
    fun observeActiveDays(): Flow<List<String>>
}

/** The two consumption kinds the dashboard counts (decisions #109). */
enum class ActivityKind { READ, LISTEN }

/** One whole-second credit, keyed to a local day — the write unit. */
data class ActivityChunk(
    val dayKey: String,
    val bookId: String,
    val kind: ActivityKind,
    val seconds: Long,
)

/**
 * Local-day keying ("yyyy-MM-dd" in the device zone — "today" is local,
 * decisions #109) plus the exclusive end of the day containing a moment, the
 * pair the span splitters need to cut a span at midnight. Injected into the
 * accumulators as function references so tests pin both.
 */
object LocalDays {
    fun key(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    /** Exclusive end (epoch ms) of the local day containing [epochMs]. */
    fun end(epochMs: Long): Long =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}

/**
 * Splits a wall-clock span into per-local-day whole-second chunks (floored
 * per chunk — stored seconds never round up). Spans shorter than
 * [minSpanMillis] are dropped entirely (decisions #109: the sub-10-second
 * floor exists for READING spans; listening keeps every second). A
 * regressed clock yields nothing.
 */
internal fun splitSpan(
    startMs: Long,
    endMs: Long,
    bookId: String,
    kind: ActivityKind,
    minSpanMillis: Long,
    dayKey: (Long) -> String,
    dayEnd: (Long) -> Long,
): List<ActivityChunk> {
    if (endMs <= startMs) return emptyList()
    if (endMs - startMs < minSpanMillis) return emptyList()
    val chunks = mutableListOf<ActivityChunk>()
    var cursor = startMs
    while (cursor < endMs) {
        val boundary = dayEnd(cursor).coerceAtLeast(cursor + 1)
        val end = minOf(endMs, boundary)
        val seconds = (end - cursor) / 1_000
        if (seconds > 0) chunks += ActivityChunk(dayKey(cursor), bookId, kind, seconds)
        cursor = end
    }
    return chunks
}

/**
 * Listening capture (decisions #109): starts on the PLAYING transition,
 * flushed on every exit. The edge (PlaybackService) owns the clock.
 */
class TimeSpanAccumulator(
    private val kind: ActivityKind,
    private val clock: () -> Long,
    private val dayKey: (Long) -> String,
    private val dayEnd: (Long) -> Long,
    private val minSpanMillis: Long = 0,
) {
    private var running: Pair<String, Long>? = null

    /** Opens the accrual window for [bookId]; a no-op while already running. */
    fun start(bookId: String) {
        if (running == null) running = bookId to clock()
    }

    /** Closes the open span (if any) and returns its per-day chunks. */
    fun stop(): List<ActivityChunk> {
        val (bookId, startMs) = running ?: return emptyList()
        running = null
        return splitSpan(startMs, clock(), bookId, kind, minSpanMillis, dayKey, dayEnd)
    }
}

/**
 * Page-flip-active reading capture (decisions #109): a user page flip opens
 * the active window; the window closes at the next flip, an interrupt
 * (screen off / reader left / playback started) or — bounded — the active
 * window cap, so a book left open stops accruing. Spans under 10 s are
 * dropped. Reader-open dwell without any flip accrues nothing (not raw
 * dwell).
 */
class ReadingSpanTracker(
    private val clock: () -> Long,
    private val dayKey: (Long) -> String,
    private val dayEnd: (Long) -> Long,
    private val minSpanMillis: Long = MIN_SPAN_MILLIS,
    private val activeWindowMillis: Long = ACTIVE_WINDOW_MILLIS,
) {
    /** bookId to the flip epoch ms that opened the window. */
    private var open: Pair<String, Long>? = null

    /** A user page flip: closes the previous window, opens one for [bookId]. */
    fun onFlip(bookId: String): List<ActivityChunk> {
        val now = clock()
        val closed = closeAt(now)
        open = bookId to now
        return closed
    }

    /** Screen off / reader left / playback started: closes the open window. */
    fun onInterrupt(): List<ActivityChunk> = closeAt(clock())

    private fun closeAt(now: Long): List<ActivityChunk> {
        val (bookId, startMs) = open ?: return emptyList()
        open = null
        // The cap bounds parked-screen credit (decisions #109 "a book left
        // open stops accruing"): a window never counts past one active-window
        // length, however late the closing event arrives.
        val end = startMs + (now - startMs).coerceAtMost(activeWindowMillis)
        return splitSpan(startMs, end, bookId, ActivityKind.READ, minSpanMillis, dayKey, dayEnd)
    }

    companion object {
        /** Decisions #109: spans under 10 s are dropped. */
        const val MIN_SPAN_MILLIS = 10_000L

        /** One flip credits at most this much dwell (decisions #109 left the
         * window bound open; 3 min covers a slow page while bounding the
         * parked-screen credit). */
        const val ACTIVE_WINDOW_MILLIS = 180_000L
    }
}
