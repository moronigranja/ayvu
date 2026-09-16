package io.github.moronigranja.ayvu.locate

/**
 * Outcome of locating a shared snippet: the best book + chapter + passage and how
 * confident the match is. Null [TextIndex.query] result = no confident match (below
 * the user-configurable threshold, default 0.6).
 *
 * The consumer (share feature, then the player) loads the book from the library store
 * by [bookId] and resumes at [chapterIndex]/[passageIndex].
 */
data class MatchResult(
    val bookId: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val passageIndex: Int,
    val confidence: Double,
)
