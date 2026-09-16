package io.github.moronigranja.ayvu.persistence

/**
 * One ChapterCountRow: total passages of one (book, chapter) from the cached
 * parse — the denominator for the library's read/listened progress fraction
 * (progress rows are passage-granular, so the fraction is
 * passages-before + current over the book's total passages).
 */
data class ChapterCount(
    val bookId: String,
    val chapterIndex: Int,
    val passageCount: Int,
)
