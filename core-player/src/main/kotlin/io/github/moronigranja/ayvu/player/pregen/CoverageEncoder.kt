package io.github.moronigranja.ayvu.player.pregen

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.player.CoverageSpan

/**
 * Torrent-style book coverage: which stretches of the spine have generated
 * audio on disk, as run-length [CoverageSpan]s, plus chapter boundary marks.
 *
 * Weights are passage text length (the chars/15 duration model shared with
 * [io.github.moronigranja.ayvu.player.BookProgress] — NOT equal passage
 * widths, so a 400-char passage colors more bar than a 100-char one).
 * Chapters are walked in spine (list) order — the model contract — passages
 * in order. Keys outside the book's spine are ignored; an empty book (0
 * chars) yields empty lists.
 */
object CoverageEncoder {
    /**
     * Run-length spans of generated/not-generated over the book spine,
     * weighted by passage text length. Each span's [CoverageSpan.endFraction]
     * is the cumulative char fraction where it ends; the first starts at 0.
     * A generated flag flip between adjacent non-empty passages closes a run.
     */
    fun spans(
        book: Book,
        generated: Set<PregenKey>,
    ): List<CoverageSpan> {
        val marks =
            generated
                .filterTo(HashSet()) { it.bookId == book.id }
                .mapTo(HashSet()) { it.chapterIndex to it.passageIndex }
        var totalChars = 0L
        var cumulative = 0L
        val raw = mutableListOf<Pair<Long, Boolean>>() // end char (exclusive) of each run
        var runGenerated = false
        var haveRun = false
        for (chapter in book.chapters) {
            for ((index, passage) in chapter.passages.withIndex()) {
                val chars = passage.text.length
                if (chars == 0) continue
                totalChars += chars
                cumulative += chars
                val isGenerated = (chapter.index to index) in marks
                if (!haveRun) {
                    runGenerated = isGenerated
                    haveRun = true
                } else if (isGenerated != runGenerated) {
                    raw += (cumulative - chars) to runGenerated
                    runGenerated = isGenerated
                }
            }
        }
        if (totalChars == 0L) return emptyList()
        raw += cumulative to runGenerated
        return raw.map { (endChars, isGenerated) ->
            CoverageSpan((endChars.toFloat() / totalChars).coerceIn(0f, 1f), isGenerated)
        }
    }

    /**
     * Start fraction of every chapter whose char weight > 0, in spine order;
     * the first chapter's 0.0 mark is omitted, and marks that land on an
     * already-emitted fraction (empty chapters) are deduplicated.
     */
    fun chapterMarks(book: Book): List<Float> {
        val total = book.chapters.sumOf { chapter -> chapter.passages.sumOf { it.text.length } }
        if (total == 0) return emptyList()
        val marks = mutableListOf<Float>()
        var cumulative = 0L
        for (chapter in book.chapters) {
            val chars = chapter.passages.sumOf { it.text.length }
            if (chars > 0) {
                val fraction = cumulative.toFloat() / total
                if (fraction > 0f && (marks.isEmpty() || marks.last() != fraction)) {
                    marks += fraction
                }
                cumulative += chars
            }
        }
        return marks
    }
}
