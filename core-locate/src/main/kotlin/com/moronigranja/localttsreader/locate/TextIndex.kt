package com.moronigranja.localttsreader.locate

import com.moronigranja.localttsreader.model.Book

/**
 * In-memory text index over imported books, populated by the import pipeline (§8):
 * every parsed book is added here so the share feature can identify book + passage.
 *
 * Stores per-passage precomputed gram sets, so a query only normalizes the snippet and
 * scores it against each passage (linear in total passage count — fine up to a few
 * thousand passages; an inverted n-gram index is the documented scaling follow-up).
 * add/remove/clear are synchronized; query works on a snapshot so it never blocks
 * concurrent indexing for longer than the snapshot copy.
 */
class TextIndex {
    private data class PassageRef(
        val chapterIndex: Int,
        val chapterTitle: String?,
        val passageIndex: Int,
        val grams: TextMatcher.PassageGrams,
    )

    private data class BookIndex(
        val id: String,
        val title: String,
        val passages: List<PassageRef>,
    )

    private val books = linkedMapOf<String, BookIndex>()

    @Synchronized
    fun add(book: Book) {
        books[book.id] = BookIndex(book.id, book.title, flatten(book))
    }

    @Synchronized
    fun remove(bookId: String) {
        books.remove(bookId)
    }

    @Synchronized
    fun clear() {
        books.clear()
    }

    @Synchronized
    fun bookCount(): Int = books.size

    /** Whether [bookId] (the content hash) is already indexed — the import idempotency check. */
    @Synchronized
    fun contains(bookId: String): Boolean = books.containsKey(bookId)

    /** Indexed book ids in insertion order — the launch-time rebuild's purge set (P2). */
    @Synchronized
    fun bookIds(): Set<String> = books.keys.toSet()

    private fun flatten(book: Book): List<PassageRef> =
        book.chapters.flatMap { chapter ->
            chapter.passages.mapIndexed { index, passage ->
                PassageRef(
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    passageIndex = index,
                    grams = TextMatcher.indexGrams(TextNormalizer.normalize(passage.text)),
                )
            }
        }

    /**
     * Best match for [snippet] across all indexed books, or null when no passage reaches
     * [minConfidence] (the user-configurable threshold from settings, default 0.6).
     * Ties keep the earliest book/passage in index (insertion) order.
     */
    @Synchronized
    fun query(
        snippet: String,
        minConfidence: Double,
    ): MatchResult? = bestLocked(snippet)?.takeIf { it.confidence >= minConfidence }

    @Synchronized
    fun best(snippet: String): MatchResult? = bestLocked(snippet)

    @Synchronized
    private fun snapshot(): List<BookIndex> = books.values.toList()

    @Synchronized
    private fun bestLocked(snippet: String): MatchResult? {
        val normalized = TextNormalizer.normalize(snippet)
        if (normalized.isEmpty()) return null
        var best: MatchResult? = null
        for (book in snapshot()) {
            for (passage in book.passages) {
                val recall = TextMatcher.scoreNormalized(normalized, passage.grams)
                if (best == null || recall > best.confidence) {
                    best =
                        MatchResult(
                            bookId = book.id,
                            bookTitle = book.title,
                            chapterIndex = passage.chapterIndex,
                            chapterTitle = passage.chapterTitle,
                            passageIndex = passage.passageIndex,
                            confidence = recall,
                        )
                }
            }
        }
        return best
    }
}
