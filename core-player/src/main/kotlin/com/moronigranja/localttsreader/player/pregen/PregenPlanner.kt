package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book

/**
 * S1/O3 shared pre-generation planner (decisions #75): the single
 * spine-order passage walk used by both pre-generation executors —
 * [OfflinePregen]'s whole-book runs and [PregenQueue]'s in-process
 * look-ahead. Previously each re-implemented the walk (OfflinePregen with
 * nested chapter/passage loops, PregenQueue with its own `next` cursor) plus
 * the [PregenKey] construction.
 *
 * Pure: no cache, no engine, no launch/cancel. Two entry points match the two
 * executor shapes:
 * - [plan] — non-suspend; called from the queue's critical section to build
 *   its look-ahead list (its callbacks never suspend).
 * - [walk] — suspend; called by the whole-book run whose callbacks suspend
 *   (progress events, cache puts, synthesis).
 * Budgets, cache/queue hit checks, yield-to-playback and every other stop
 * decision stay with the caller; the planner owns only the walk and the key.
 */
class PregenPlanner(
    private val book: Book,
    private val voice: String,
    private val speed: Double,
    /** Engine whose voice/speed the walk keys — part of the [PregenKey] cache path. */
    private val engine: String = PregenKey.DEFAULT_ENGINE,
    /** Read-in-language target code for this walk's keys (decisions #114);
     * null = the book's original language. */
    private val translateLang: String? = null,
    /** Translator identity for this walk's keys (decisions #162) — see
     * [PregenKey.translator]. Only meaningful with [translateLang]. */
    private val translator: String? = null,
) {
    /** The book's first passage (spine start). */
    val first: Pair<Int, Int> get() = 0 to 0

    /** The passage strictly after [pos] in spine order; null past the book's end. */
    fun nextAfter(pos: Pair<Int, Int>): Pair<Int, Int>? {
        var chapterIndex = pos.first
        var passageIndex = pos.second + 1
        while (chapterIndex < book.chapters.size) {
            if (passageIndex < book.chapters[chapterIndex].passages.size) {
                return chapterIndex to passageIndex
            }
            chapterIndex += 1
            passageIndex = 0
        }
        return null
    }

    fun key(
        chapterIndex: Int,
        passageIndex: Int,
    ): PregenKey = PregenKey(book.id, chapterIndex, passageIndex, voice, speed, engine, translateLang, translator)

    /**
     * Non-suspend spine walk for plan-building phases (the queue's
     * look-ahead). [from] null starts at the book's first passage (inclusive);
     * a non-null [from] starts strictly after it. Per visited position:
     * [stop] true halts the walk; else [shouldVisit] false skips; else
     * [onCandidate] receives the passage. No chapter hooks — the queue
     * schedules connectively with no chapter accounting.
     */
    fun plan(
        from: Pair<Int, Int>? = null,
        stop: ((chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Boolean)? = null,
        shouldVisit: (chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Boolean = { _, _, _ -> true },
        onCandidate: (chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Unit = { _, _, _ -> },
    ) {
        var chapterIndex = from?.first ?: 0
        var passageIndex = from?.second?.plus(1) ?: 0
        while (chapterIndex < book.chapters.size) {
            val chapter = book.chapters[chapterIndex]
            while (passageIndex < chapter.passages.size) {
                val key = key(chapterIndex, passageIndex)
                if (stop?.invoke(chapterIndex, passageIndex, key) == true) return
                if (shouldVisit(chapterIndex, passageIndex, key)) {
                    onCandidate(chapterIndex, passageIndex, key)
                }
                passageIndex += 1
            }
            chapterIndex += 1
            passageIndex = 0
        }
    }

    /**
     * Suspend spine walk for processing phases (the whole-book run). Same
     * iteration as [plan] plus chapter hooks and a suspendable
     * [onCandidate]; returning `false` from [onChapter] or [onCandidate]
     * halts the walk. [onChapter] fires at the first passage of each chapter;
     * [onChapterDone] fires after a chapter's passages complete normally
     * (a walk halted mid-chapter does NOT fire it — the whole-book executor
     * counts only fully walked chapters).
     */
    suspend fun walk(
        from: Pair<Int, Int>? = null,
        stop: ((chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Boolean)? = null,
        shouldVisit: suspend (chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Boolean = { _, _, _ -> true },
        onCandidate: suspend (chapterIndex: Int, passageIndex: Int, key: PregenKey) -> Boolean = { _, _, _ -> true },
        onChapter: (suspend (chapterIndex: Int) -> Boolean)? = null,
        onChapterDone: (suspend (chapterIndex: Int) -> Unit)? = null,
    ) {
        var chapterIndex = from?.first ?: 0
        var passageIndex = from?.second?.plus(1) ?: 0
        while (chapterIndex < book.chapters.size) {
            val chapter = book.chapters[chapterIndex]
            if (passageIndex == 0 && onChapter != null && !onChapter(chapterIndex)) return
            while (passageIndex < chapter.passages.size) {
                val key = key(chapterIndex, passageIndex)
                if (stop?.invoke(chapterIndex, passageIndex, key) == true) return
                if (shouldVisit(chapterIndex, passageIndex, key) &&
                    !onCandidate(chapterIndex, passageIndex, key)
                ) {
                    return
                }
                passageIndex += 1
            }
            onChapterDone?.invoke(chapterIndex)
            chapterIndex += 1
            passageIndex = 0
        }
    }
}
