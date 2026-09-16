package com.moronigranja.localttsreader.player.pregen

import kotlinx.coroutines.flow.Flow

/**
 * The read-in-language TEXT service: one persisted artifact serves BOTH the
 * reader's display and the speech path ([com.moronigranja.localttsreader
 * .tts.translate.TranslatingEngine] consumes it), so the same stored text is
 * shown and spoken and a passage is translated at most once.
 *
 * Display work is progressive: [cached] seeds a chapter, [prefetch] fills
 * the current + next pages at display priority (yielding to playback and
 * pregen through [com.moronigranja.localttsreader.featureplayer.playback
 * .PlaybackActive]'s engineInUse gate), and [ready] emits each passage's
 * text as it lands. The audio path's [translate] is cache-first and joins
 * the keyed in-flight decode instead of decoding twice.
 */
interface TranslationService {
    /** Cached text only; never blocks on the LLM. */
    suspend fun cached(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String?

    /** Cache hit, or a fresh translation. Null = unavailable. */
    suspend fun translate(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String?

    /** Fire-and-forget background fill at display priority. */
    fun prefetch(
        bookId: String,
        chapter: Int,
        passages: List<Int>,
        target: TranslationTarget,
    )

    /** Emits once per passage whose text became available. */
    val ready: Flow<TranslationReady>

    /**
     * Non-blocking read of whether a decode could start right now (translate
     * pack staged + the session not stuck in a failed-open state). The
     * reader's Pending-vs-Unavailable split; does NOT arm the runtime's
     * idle-close timer.
     */
    val translatePossible: Boolean
}

/** One passage's translation became available ([TranslationService.ready]). */
data class TranslationReady(
    val bookId: String,
    val chapter: Int,
    val passage: Int,
    val target: TranslationTarget,
    val text: String,
)