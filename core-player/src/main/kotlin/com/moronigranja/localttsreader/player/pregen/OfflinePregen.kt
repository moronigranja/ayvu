package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One run's limits; null fields are unbounded. */
data class PregenBudget(
    /** Stop after this many passages processed (synthesized, cached-hit, or failed). */
    val maxPassages: Int? = null,
    /** Stop after this many chapters fully walked. */
    val maxChapters: Int? = null,
    /**
     * Stop after synthesizing this many listening-seconds of NEW audio. A
     * passage already on disk is skipped and does NOT consume the budget, so
     * a run covering an already-cached stretch keeps pre-generating past it
     * for the same total listening time (`maxSeconds`), never re-counting it.
     */
    val maxSeconds: Double? = null,
)

/**
 * Running tallies for one book; [OnProgress] fires after every passage.
 * [percent] is the run's OWN work, monotonic and comparable across runs: a
 * bounded listening-time run fills 0→100 over the slice it was asked to
 * generate ([synthesizedSeconds] vs [budgetSeconds], decisions #136), an
 * unbounded run fills against the whole-book walk ([totalPassages]).
 */
data class PregenProgress(
    val chaptersDone: Int = 0,
    val passagesSynthesized: Int = 0,
    val passagesCached: Int = 0,
    val failures: Int = 0,
    val totalChapters: Int,
    val totalPassages: Int,
    /** Listening seconds of NEW audio synthesized this run; cache hits skip. */
    val synthesizedSeconds: Double = 0.0,
    /** The run's listening-time budget ([PregenBudget.maxSeconds]); null →
     * unbounded, so [percent] falls back to the whole-book denominator. */
    val budgetSeconds: Double? = null,
    /**
     * Why the run ended, set by [OfflinePregen.run] before it returns; null
     * only while the run is in flight (progress events before the terminal).
     * The worker maps failure terminals to WorkManager failure deliberately
     * instead of collapsing every partial run into success (CR-1).
     */
    val terminal: PregenTerminal? = null,
) {
    val processed: Int get() = passagesSynthesized + passagesCached + failures

    /** 0→100 over the requested slice for bounded runs (the 30-min ask fills
     * as those 30 min generate — never a whole-book fraction); whole-book
     * runs keep the processed/totalPassages walk. */
    val percent: Int
        get() =
            if (budgetSeconds != null && budgetSeconds > 0) {
                (synthesizedSeconds * 100 / budgetSeconds).toInt().coerceIn(0, 100)
            } else if (totalPassages == 0) {
                100
            } else {
                (processed * 100 / totalPassages).coerceIn(0, 100)
            }
}

/** The terminal reason an [OfflinePregen] run stopped. */
enum class PregenTerminal {
    /** Walked every chapter to the end — the whole book is processed. */
    Completed,

    /** A finite budget (passages, chapters or wall-clock) ran out. */
    BudgetExhausted,

    /** The disk tier is full; a put would only evict another entry. */
    CacheSaturated,

    /** The caller's [OfflinePregen.shouldContinue] turned false (playback yield). */
    Yielded,

    /** Synthesis reported [SynthesisOutcome.Unavailable]; packs will not heal within the run. */
    Unavailable,

    /** [OfflinePregen.consecutiveFailureCap] consecutive synthesis failures. */
    FailureCap,
}

/**
 * Offline chapter pre-generation (post-v1 slice, decisions #42): synthesizes
 * whole books into the [PcmPassageCache] disk tier in spine order, skipping
 * passages already cached — so a run resumes anywhere (the cache is the
 * source of truth) and budgets keep the overnight job from hogging the
 * charger forever.
 *
 * Contract:
 * - Walks chapters 0..n and passages 0..m; every passage already on disk is
 *   skipped without synthesis.
 * - A non-null [run]`startAt` begins the walk AT that passage (inclusive)
 *   instead of the spine start: the "next N minutes" bounded runs pass the
 *   current reading position so they synthesize the audio ahead of the
 *   listener rather than re-walking the book from chapter 0.
 * - Stops when the budget is exhausted ([PregenBudget]), when the cache is
 *   saturated (free space below the last synthesized passage's size — a put
 *   would only evict another entry), when [shouldContinue] turns false (the
 *   caller yields to playback), or when the caller's coroutine is cancelled
 *   (checked per passage; the engine's synthesis is cancellable per batch).
 * - A failed synthesis is counted and the walk continues — the cache never
 *   holds a placeholder — but [consecutiveFailureCap] consecutive failures
 *   stop the run (an engine meltdown must not spin). `Unavailable` stops
 *   immediately: missing packs will not heal within the run.
 * - The returned [PregenProgress.terminal] names the stopping reason, so a
 *   caller (the WorkManager worker) can distinguish a safely bounded run
 *   from an engine failure instead of reporting every partial run as success
 *   (CR-1).
 */
class OfflinePregen(
    private val cache: PcmPassageCache,
    /** Synthesizes one passage — the (chapterIndex, passageIndex) identity
     * lets a translated render share the stored display translation (the
     * `x<lang>` key's text and the audio's text are the SAME artifact). */
    private val synthesize: suspend (text: String, chapterIndex: Int, passageIndex: Int) -> SynthesisOutcome,
    private val consecutiveFailureCap: Int = 5,
    private val shouldContinue: () -> Boolean = { true },
    /** Engine whose voice/speed the run synthesizes — part of the [PregenKey] cache path. */
    private val engine: String = PregenKey.DEFAULT_ENGINE,
    /** Read-in-language target for this run's keys (decisions #114/#162) —
     * the language AND the translator that wrote it ([TranslationTarget]).
     * Null = the book's original language. */
    private val target: TranslationTarget? = null,
) {
    init {
        require(consecutiveFailureCap > 0) { "consecutiveFailureCap must be positive" }
    }

    suspend fun run(
        book: Book,
        voice: String,
        speed: Double,
        budget: PregenBudget = PregenBudget(),
        /**
         * Inclusive spine position (dense chapter/passage list indexes) to
         * begin at; null walks from the book's first passage. Bounded runs
         * pass the current reading position so they pre-generate the audio
         * ahead of the listener instead of the book's opening chapters.
         */
        startAt: Pair<Int, Int>? = null,
        onProgress: suspend (PregenProgress) -> Unit = {},
    ): PregenProgress {
        val context = currentCoroutineContext()
        var progress =
            PregenProgress(
                totalChapters = book.chapters.size,
                totalPassages = book.chapters.sumOf { it.passages.size },
                budgetSeconds = budget.maxSeconds,
            )
        var consecutiveFailures = 0
        var lastPutBytes = 0L
        // The walker stops via its hooks; the stopping reason is captured
        // here and stamped on the final progress (null = walked to the end).
        var terminal: PregenTerminal? = null

        suspend fun bump(update: PregenProgress.() -> PregenProgress) {
            progress = update(progress)
            onProgress(progress)
        }

        /** Fires one final event so the last observed progress equals the
         * returned result; defaults the terminal to [PregenTerminal.Completed]. */
        suspend fun finished(): PregenProgress {
            val final = progress.copy(terminal = terminal ?: PregenTerminal.Completed)
            onProgress(final)
            return final
        }

        PregenPlanner(book, voice, speed, engine, target).walk(
            from = startAt?.let { inclusiveFrom(book, it) },
            onChapter = { chapterIndex ->
                // Chapter-boundary gates: maxChapters and the caller's yield.
                if (budget.maxChapters?.let { progress.chaptersDone >= it } == true) {
                    terminal = PregenTerminal.BudgetExhausted
                    false
                } else if (!shouldContinue()) {
                    terminal = PregenTerminal.Yielded
                    false
                } else {
                    context.ensureActive()
                    true
                }
            },
            shouldVisit = { _, _, key ->
                if (cache.contains(key)) {
                    bump { copy(passagesCached = passagesCached + 1) }
                    consecutiveFailures = 0
                    false
                } else {
                    true
                }
            },
            onCandidate = { _, passageIndex, key ->
                context.ensureActive()
                if (!shouldContinue()) {
                    terminal = PregenTerminal.Yielded
                    false
                } else if (budget.maxSeconds?.let { progress.synthesizedSeconds >= it } == true) {
                    terminal = PregenTerminal.BudgetExhausted
                    false
                } else if (budget.maxPassages?.let { progress.processed >= it } == true) {
                    terminal = PregenTerminal.BudgetExhausted
                    false
                } else if (cache.bytesRemaining() == 0L) {
                    terminal = PregenTerminal.CacheSaturated
                    false
                } else if (lastPutBytes > 0L && cache.bytesRemaining() < lastPutBytes) {
                    terminal = PregenTerminal.CacheSaturated
                    false
                } else {
                    when (
                        val outcome =
                            synthesize(
                                book.chapters[key.chapterIndex].passages[passageIndex].text,
                                key.chapterIndex,
                                passageIndex,
                            )
                    ) {
                        is SynthesisOutcome.Audio -> {
                            cache.put(key, PregenAudio(outcome.pcm, outcome.sampleRateHz, outcome.segments))
                            // 16-bit mono PCM: bytes / (rate × 2) = listening seconds
                            // (the "next N minutes" slice counter, decisions #132/#136).
                            val seconds = outcome.pcm.size / (outcome.sampleRateHz * 2.0)
                            bump {
                                copy(
                                    passagesSynthesized = passagesSynthesized + 1,
                                    synthesizedSeconds = synthesizedSeconds + seconds,
                                )
                            }
                            consecutiveFailures = 0
                            lastPutBytes = outcome.pcm.size.toLong()
                            true
                        }
                        is SynthesisOutcome.Unavailable -> {
                            terminal = PregenTerminal.Unavailable
                            false
                        }
                        is SynthesisOutcome.Failed -> {
                            bump { copy(failures = failures + 1) }
                            consecutiveFailures++
                            if (consecutiveFailures >= consecutiveFailureCap) {
                                terminal = PregenTerminal.FailureCap
                                false
                            } else {
                                true
                            }
                        }
                    }
                }
            },
            onChapterDone = { _ -> bump { copy(chaptersDone = chaptersDone + 1) } },
        )
        return finished()
    }
}

/**
 * Maps an inclusive start passage to the planner's strictly-after
 * [PregenPlanner.walk]`from` argument — the spine position immediately before
 * [at]. Null when [at] is the book's first passage, so the walk begins at the
 * spine start. Empty chapters (dropped by the dense rebuild) are stepped over
 * the same way the planner's own iteration is.
 */
private fun inclusiveFrom(
    book: Book,
    at: Pair<Int, Int>,
): Pair<Int, Int>? {
    val (chapterIndex, passageIndex) = at
    if (passageIndex > 0) return chapterIndex to (passageIndex - 1)
    var previous = chapterIndex - 1
    while (previous >= 0) {
        val size = book.chapters[previous].passages.size
        if (size > 0) return previous to (size - 1)
        previous--
    }
    return null
}
