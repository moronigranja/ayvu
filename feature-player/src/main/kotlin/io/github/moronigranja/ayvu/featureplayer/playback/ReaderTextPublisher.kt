package io.github.moronigranja.ayvu.featureplayer.playback

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.player.passageText

/**
 * Owns [PlaybackService]'s in-memory active [Book] and derives the
 * READER-FACING text publication (passageText, chapterPassages, the chapter
 * titles, bookPassageIndex, bookPassageCount) from it — the block extracted
 * out of `PlaybackService.stateCopy` (cleanup pass H, 2026-09-16). The
 * reader's surfaces (the reader itself, play-from-view, the chapter
 * selector's X/Y indicator) consume these through the published
 * [io.github.moronigranja.ayvu.player.PlaybackUiState], so the
 * derivation lives with ONE owner instead of inside the big copy block.
 *
 * Behaviour-preserving by construction: every field's value and publish
 * timing is identical to the pre-extraction stateCopy — [book]'s production
 * setter ([PlaybackService.bindBook], plus the host-test assignments against
 * `PlaybackService.book` which delegate here) is the single entry point.
 */
internal class ReaderTextPublisher {
    /** The active book (null = nothing published). */
    var book: Book? = null
        internal set

    /** The current passage's text (the read-along surface). */
    fun passageText(position: PlayerPosition?): String = position?.let { p -> book?.passageText(p.chapterIndex, p.passageIndex) } ?: ""

    /** The current chapter's passage texts, ORIGINAL language, index == passage
     * index — the published surface the reader's block projection consumes. */
    fun chapterPassages(position: PlayerPosition?): List<String> =
        position
            ?.let { p ->
                book
                    ?.chapters
                    ?.firstOrNull { it.index == p.chapterIndex }
                    ?.passages
                    ?.map { it.text }
            } ?: emptyList()

    /** Chapter titles in spine order — the chapter selector. */
    fun chapterTitles(): List<String> = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()

    /** Book-wide index of the current passage (chapter prefix sums + the
     * in-chapter index) — the standard X/Y indicator. */
    fun bookPassageIndex(position: PlayerPosition?): Int =
        book
            ?.let {
                it.chapters.filter { ch -> ch.index < (position?.chapterIndex ?: 0) }.sumOf { ch -> ch.passages.size } +
                    (position?.passageIndex ?: 0)
            } ?: 0

    /** Total passages across every chapter. */
    fun bookPassageCount(): Int = book?.chapters?.sumOf { it.passages.size } ?: 0
}
