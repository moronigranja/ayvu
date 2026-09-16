package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.player.pregen.TranslationReady
import com.moronigranja.localttsreader.player.pregen.TranslationService
import com.moronigranja.localttsreader.player.pregen.TranslationTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Host-test stand-in for [TranslationService]: cache-first with a
 * per-key store, counts decode attempts, and never touches the LLM. The
 * selector/engine tests only need the seam to exist (the audio path must
 * never fail because the display service is absent); service-behavior tests
 * use the real [TranslationServiceImpl] against an in-memory Room database.
 */
class FakeTranslationService : TranslationService {
    /** Fresh-decode attempts (cache misses) — the "at most once" counter. */
    var decodeCount = 0
        private set

    override val translatePossible: Boolean = true

    private val rows = mutableMapOf<String, String>()

    override val ready: Flow<TranslationReady> = MutableSharedFlow<TranslationReady>().asSharedFlow()

    override suspend fun cached(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String? = rows[key(bookId, chapter, passage, target)]

    override suspend fun translate(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String? {
        cached(bookId, chapter, passage, target)?.let { return it }
        decodeCount++
        val text = "translated-${target.lang}-$passage"
        rows[key(bookId, chapter, passage, target)] = text
        return text
    }

    override fun prefetch(
        bookId: String,
        chapter: Int,
        passages: List<Int>,
        target: TranslationTarget,
    ) = Unit

    private fun key(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ) = "$bookId/$chapter/$passage/${target.lang}/${target.translator}"
}