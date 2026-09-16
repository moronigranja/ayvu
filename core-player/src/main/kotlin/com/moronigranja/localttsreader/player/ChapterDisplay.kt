package com.moronigranja.localttsreader.player

/** How the reader lays the chapter out when a display language is set
 * (read-in-language display): the original passage followed by its
 * translation (one column), or the translation alone. A reading STYLE —
 * global, not per book. */
enum class DisplayMode(
    val key: String,
) {
    INTERLEAVED("interleaved"),
    TRANSLATED_ONLY("translated_only"),
    ;

    companion object {
        fun from(raw: String?): DisplayMode = entries.firstOrNull { it.key == raw } ?: INTERLEAVED
    }
}

/** What one projected block renders — [DisplayBlock.kind]. */
enum class DisplayKind {
    Original,
    Translation,
    Pending,
    Unavailable,
}

/**
 * One block of the projected chapter: Original (the passage's own text) or a
 * translation-state block, ORIGINAL-KEYED — [passageIndex] is always the
 * index into the original chapter passages, so navigation (page anchoring,
 * follow, play-from-view, long-press) stays original-keyed and the
 * bookmark/resume invariant holds by construction.
 *
 * [text] is the block's own text — EMPTY for [DisplayKind.Pending] (and
 * [DisplayKind.Unavailable]): those render their placeholder/note through
 * [ChapterDisplay.join]/[ChapterDisplay.offsets], never through the block.
 */
data class DisplayBlock(
    val passageIndex: Int,
    val kind: DisplayKind,
    val text: String,
)

/** A passage's per-session translation state (the reader-owned map). */
sealed interface TranslationState {
    /** The stored translation text — the reader merged it from [TranslationReady]. */
    data class Ready(
        val text: String,
    ) : TranslationState

    /** A decode is possible but the text has not landed yet (loading dots). */
    data object Pending : TranslationState

    /** A decode cannot happen (translator pack removed / session failed): the
     * original renders plus an inline note; retried on the next page entry. */
    data object Unavailable : TranslationState
}

/**
 * The reader's block projection (read-in-language display): one Original
 * block per passage; in [DisplayMode.TRANSLATED_ONLY] the Original blocks
 * are omitted but every block keeps [DisplayBlock.passageIndex] — a passage
 * the translation map does not cover renders its Original block even in
 * translated-only mode (never a blank gap, and navigation stays dense).
 *
 * The projection is pure and lives HERE, not in [PlaybackUiState]: blocks are
 * composed from the already-published `chapterPassages` plus a reader-owned
 * translation map, so the published list can never be interleaved and the
 * `chapterPassages: index == original passage index` invariant holds by
 * construction.
 *
 * Pending/Unavailable join/offset as their placeholder text (render-length
 * consistent): the measured layout and the page render always agree —
 * pagination keys on a FIXED line grid, so a differently-pitched block would
 * corrupt [TextPagination] (the `lineHeightPx` uniform-pitch contract).
 */
object ChapterDisplay {
    /** The Pending placeholder — loading dots spanning ≈ one line. */
    private const val PENDING_PLACEHOLDER = "⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯ ⋯"

    /** The Unavailable inline note. */
    private const val UNAVAILABLE_NOTE = "translation unavailable"

    /**
     * One Original block per passage; plus one translation block per passage
     * when [translations] is non-empty for it. [DisplayMode.TRANSLATED_ONLY]
     * omits the Original blocks but keeps [DisplayBlock.passageIndex] on
     * every block, so navigation stays original-keyed.
     */
    fun project(
        passages: List<String>,
        translations: Map<Int, TranslationState>,
        mode: DisplayMode,
    ): List<DisplayBlock> {
        val blocks = ArrayList<DisplayBlock>(passages.size * 2)
        for (i in passages.indices) {
            val state = translations[i]
            when (mode) {
                DisplayMode.INTERLEAVED -> {
                    blocks += DisplayBlock(i, DisplayKind.Original, passages[i])
                    if (state != null) blocks += stateBlock(i, state)
                }
                DisplayMode.TRANSLATED_ONLY ->
                    blocks +=
                        state?.let { stateBlock(i, it) } ?: DisplayBlock(i, DisplayKind.Original, passages[i])
            }
        }
        return blocks
    }

    /** The display text: blocks joined with "\n\n" (the existing join);
     * Pending renders the loading dots, Unavailable the inline note. */
    fun join(blocks: List<DisplayBlock>): String = blocks.joinToString("\n\n") { renderText(it) }

    /** Char offset of each block's start in [join] — replaces
     * `computePassageOffsets` and its `passage.length + 2` hardcode. */
    fun offsets(blocks: List<DisplayBlock>): IntArray {
        val offsets = IntArray(blocks.size)
        var acc = 0
        for (index in blocks.indices) {
            offsets[index] = acc
            acc += renderText(blocks[index]).length + 2 // "\n\n"
        }
        return offsets
    }

    /** passageIndex → the index of the FIRST block for that passage (the
     * Original block in interleaved mode, the translation block in
     * translated-only). The reader's passage-start-line map and page-follow
     * anchor. */
    fun firstBlockOfPassage(
        blocks: List<DisplayBlock>,
        passageCount: Int,
    ): IntArray {
        val first = IntArray(passageCount) { -1 }
        for (i in blocks.indices) {
            val p = blocks[i].passageIndex
            if (p in first.indices && first[p] < 0) first[p] = i
        }
        return first
    }

    /** The block's RENDERED length in [join]'s text — [offsets] plus this is
     * the block's char range (Pending/Unavailable render placeholders, not
     * their empty block text). */
    fun renderedLength(block: DisplayBlock): Int = renderText(block).length

    private fun stateBlock(
        passageIndex: Int,
        state: TranslationState,
    ): DisplayBlock =
        when (state) {
            is TranslationState.Ready -> DisplayBlock(passageIndex, DisplayKind.Translation, state.text)
            TranslationState.Pending -> DisplayBlock(passageIndex, DisplayKind.Pending, "")
            TranslationState.Unavailable -> DisplayBlock(passageIndex, DisplayKind.Unavailable, "")
        }

    private fun renderText(block: DisplayBlock): String =
        when (block.kind) {
            DisplayKind.Original, DisplayKind.Translation -> block.text
            DisplayKind.Pending -> PENDING_PLACEHOLDER
            DisplayKind.Unavailable -> UNAVAILABLE_NOTE
        }
}
