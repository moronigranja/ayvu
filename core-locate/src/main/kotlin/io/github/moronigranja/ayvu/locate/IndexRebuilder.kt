package io.github.moronigranja.ayvu.locate

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Syncs a [TextIndex] to the cached library (P2): consumes the flat cached-parse
 * rows from storage — never a source file — which is the "never re-parse on
 * launch" contract.
 *
 * Semantics are mirror-set based, so a relaunch, a duplicate call, or a
 * concurrent import landing in the middle cannot corrupt the index:
 * - every cached book is (re)indexed from its cached passages (same id = same
 *   content hash = identical parse, so re-adding is a harmless overwrite);
 * - every indexed id absent from the cache is dropped.
 */
class IndexRebuilder(
    private val index: TextIndex,
) {
    private val ready = CompletableDeferred<Unit>()

    /**
     * Completes after the first successful [rebuild]. Share/query entry
     * points that can start the process (ACTION_SEND) await this so a
     * cold-start share never queries an empty index (the app launches the
     * rebuild asynchronously at startup).
     */
    val readiness: Deferred<Unit> get() = ready

    fun rebuild(cache: List<CachedBook>) {
        for (cached in cache) {
            index.add(cached.toBook())
        }
        val cachedIds = cache.mapTo(HashSet(), CachedBook::id)
        for (id in index.bookIds()) {
            if (id !in cachedIds) index.remove(id)
        }
        if (!ready.isCompleted) ready.complete(Unit)
    }

    /** Reconstructs the canonical [Book] from the flat cache rows. */
    private fun CachedBook.toBook(): Book =
        Book(
            id = id,
            title = title,
            authors = authors,
            chapters =
                passages
                    .groupBy { it.chapterIndex }
                    .toSortedMap()
                    .map { (chapterIndex, rows) ->
                        Chapter(
                            index = chapterIndex,
                            title = rows.first().chapterTitle,
                            passages = rows.sortedBy { it.passageIndex }.map { TextPassage(it.text) },
                        )
                    },
        )
}
