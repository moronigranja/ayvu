package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.tts.SegmentAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The single observable player surface the service writes and the reader UI
 * reads (T4-2, moved to core-player as part of the A6 composition cutover —
 * feature modules observe it through this core type, never through the
 * implementation). The [PlayerStateMachine] remains the only *persistence*
 * writer; this holder is in-memory UI/edge state only.
 */
object PlaybackStateHolder {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state

    fun update(transform: (PlaybackUiState) -> PlaybackUiState) = _state.update(transform)

    fun reset() {
        _state.value = PlaybackUiState()
    }
}

data class PlaybackUiState(
    val bookId: String? = null,
    val bookTitle: String = "",
    /** The book's authors, spine order — the player card subtitle. */
    val authors: List<String> = emptyList(),
    val chapterIndex: Int = 0,
    val passageIndex: Int = 0,
    /** 0-based index of the current passage across the WHOLE book (chapter
     * prefix sums + [passageIndex]) — the reader's stable X/Y indicator,
     * invariant under font scale, viewport width and immersive toggles. */
    val bookPassageIndex: Int = 0,
    /** Total passages across every chapter of the book. */
    val bookPassageCount: Int = 0,
    val passageText: String = "",
    /** Book-time seconds the current passage's audio spans (speed-independent). */
    val passageDurationSeconds: Double = 0.0,
    /** Chapter titles in spine order — the chapter selector. */
    val chapters: List<String> = emptyList(),
    /** The current chapter's passage texts, in order — the stitched reader
     * surface (decisions #51 follow-up).
     *
     * NAVIGATION CONTRACT: index == passage index into the chapter, and the
     * texts are the ORIGINAL-language passages. Every reader path keys off
     * that positional identity — page anchoring (`computePassageOffsets`),
     * follow, play-from-view, the long-press menu — and so does persistence:
     * a `Bookmark` and the resume row both store
     * `(bookId, chapterIndex, passageIndex)` and re-resolve it against the
     * original parsed `Book` through `BookLayout.isValid`, never against this
     * list.
     *
     * A displayed TRANSLATION must therefore be a separate projection, never
     * a list that interleaves or reorders this one. Interleaving here would
     * silently re-point every bookmark, resume row and share match in the
     * book — the one thing the reader's language display has to preserve
     * (owner requirement, 2026-09-15). */
    val chapterPassages: List<String> = emptyList(),
    /** Sentence spans of the current passage's audio (decisions #31). */
    val segments: List<SegmentAnchor> = emptyList(),
    /** Live playhead, book-time seconds within the passage. */
    val offsetSeconds: Double = 0.0,
    /** [0..1] book position — completed passages over the book's total. */
    val readFraction: Float = 0f,
    /** Book-time seconds elapsed at the playhead (1.0×) — the card's elapsed. */
    val elapsedSeconds: Double = 0.0,
    /** Estimated listening time left in the book at the current speed. */
    val timeLeftSeconds: Double = 0.0,
    val speed: Double = 1.0,
    /** Book-time seconds of pre-generated audio queued strictly ahead of the
     * playhead (`PregenQueue.aheadSeconds`); 0 when no fill runs. */
    val generatedAheadSeconds: Double = 0.0,
    /** Torrent-style run-length coverage: spans of generated vs not-generated
     * audio over the book spine (fractions of total chars). Empty until the
     * service computes it. */
    val coverageSpans: List<CoverageSpan> = emptyList(),
    /** Chapter start fractions (first chapter omitted) — the bar's ticks. */
    val chapterMarks: List<Float> = emptyList(),
    /** Smooth char-weighted playhead (elapsed/total book seconds at 1.0×) —
     * the coverage bar's static marker. Unlike readFraction (passage-granular)
     * it moves continuously with the passage offset. */
    val playheadFraction: Float = 0f,
    val phase: PlayerPhase = PlayerPhase.IDLE,
    /** True when the degraded system voice is the active engine (C1.5,
     * decisions #102) — the PlayerCard's static "Device voice" pill. */
    val degraded: Boolean = false,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val canUndo: Boolean = false,
    /** The book's bookmarks, newest first — the reader's bookmark menu. */
    val bookmarks: List<Bookmark> = emptyList(),
    val failure: String? = null,
) {
    /** The read-along highlight index: the sentence the playhead is inside. */
    val activeSentenceIndex: Int
        get() {
            var last = -1
            for ((index, segment) in segments.withIndex()) {
                if (segment.startSeconds <= offsetSeconds) last = index else break
            }
            return if (last >= 0) last else 0
        }

    /** [0..1] fullness of the pregen cushion: listenable book-time seconds
     * queued strictly ahead of the playhead, measured against the fixed
     * [PREGEN_HORIZON_SECONDS] horizon — NOT book time. The original
     * book-time-remaining denominator painted sub-pixel segments on long
     * books (45 s cushion vs ~27 h remaining ≈ 0.05% of the bar; B4
     * finding, decisions #95), so the segment now answers "how full is the
     * buffer right now", not "how much of the book is it" (decisions #98).
     * Clamped. */
    val generatedAheadFraction: Float
        get() = (generatedAheadSeconds / PREGEN_HORIZON_SECONDS).toFloat().coerceIn(0f, 1f)

    companion object {
        /** Denominator of [generatedAheadFraction] (decisions #98): 4× the
         * service's 30 s look-ahead target (PREFILL_LOOKAHEAD_SECONDS, D1
         * decisions #155 — it was 45 s when this ratio was written), so a
         * steady-state cushion sits ~25% into the segment and manual pregen
         * can overfill visibly. */
        const val PREGEN_HORIZON_SECONDS = 120.0

        /** The reader's single book-wide position line (item 4):
         * `"Passage X/Y (P%)"` — [index] is 0-based, the line is 1-based.
         * `null` when no position is publishable (count 0); percent clamps at
         * 100. Owned here so the regular footer, the immersive footer and the
         * tests all render the same string (no second convention). */
        fun passageIndicatorLabel(
            index: Int,
            count: Int,
        ): String? =
            if (count <= 0) {
                null
            } else {
                val pct = (index * 100 / count).coerceIn(0, 100)
                "Passage ${index + 1}/$count ($pct%)"
            }
    }
}

/** One cumulative run-length span over the spine: [endFraction] is the
 * span's end in [0..1] of total book chars; the span starts where the
 * previous ended (0 for the first). */
data class CoverageSpan(
    val endFraction: Float,
    val generated: Boolean,
)
