package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book

/**
 * Storage transparency (decisions #44): what pre-generating a book costs.
 *
 * Exact bytes for passages already in the cache (the cache is the source of
 * truth — [PcmPassageCache.sizeOf] is the on-disk file size); estimated
 * bytes otherwise: PCM at the engine's 16-bit mono sample rate (see
 * [sampleRateHz]; kokoro is 24 kHz, unknown engines fall back to the
 * documented safe default of 24 kHz), × the estimated speaking time —
 * [charsPerSecond] at 1.0×, scaled by [speed] (speed is part of the cache
 * key, #35; the worker pre-generates at 1.0). The rate is keyed per engine
 * so core-player does not depend on core-tts for storage math (decisions
 * #54 engine-swap prep).
 *
 * Estimates are informational: one listened hour ≈ 170 MB (24 kHz 16-bit
 * mono). The default rate (~150–180 wpm English) is a safe middle ground,
 * deliberately not a measured per-voice rate.
 */
class PregenSpaceEstimator(
    private val cache: PcmPassageCache,
    private val charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
) {
    init {
        require(charsPerSecond > 0) { "charsPerSecond must be positive" }
    }

    /**
     * [engine] answers which engine's voice/speed the estimate covers; it
     * picks both the per-engine sample rate and the cache key, so an
     * estimate for one engine never sees another engine's bytes.
     */
    fun estimate(
        book: Book,
        voice: String,
        speed: Double,
        engine: String = PregenKey.DEFAULT_ENGINE,
        /** Read-in-language target code (decisions #114); the translated audio
         * lives under its own `x<lang>` cache segment. */
        translateLang: String? = null,
    ): PregenSpaceEstimate {
        require(speed > 0) { "speed must be positive" }
        val bytesPerSecond = bytesPerSecond(engine)
        var total = 0L
        var cached = 0L
        for (chapter in book.chapters) {
            for ((passageIndex, passage) in chapter.passages.withIndex()) {
                val key = PregenKey(book.id, chapter.index, passageIndex, voice, speed, engine, translateLang)
                val exact = cache.sizeOf(key)
                if (exact != null) {
                    total += exact
                    cached += exact
                } else {
                    val seconds = passage.text.length / charsPerSecond / speed
                    total += (seconds * bytesPerSecond).toLong()
                }
            }
        }
        return PregenSpaceEstimate(total, cached)
    }

    companion object {
        /** ~150–180 wpm English at 1.0×; the exact cached part always wins. */
        const val DEFAULT_CHARS_PER_SECOND = 15.0

        /**
         * 24 kHz — the kokoro sample rate, kept in core-player so storage
         * math never imports core-tts. New engines must add their rate to
         * [SAMPLE_RATE_HZ] when they land (decisions #54 swapping prep).
         */
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000

        /** Engine id → 16-bit mono sample rate; unknown engines use the
         * documented [DEFAULT_SAMPLE_RATE_HZ] fallback. piper-v1 renders at
         * 22.05 kHz (D4, decisions #154) — the 24 kHz fallback would
         * over-estimate its space by ~9%. */
        private val SAMPLE_RATE_HZ =
            mapOf(
                PregenKey.DEFAULT_ENGINE to DEFAULT_SAMPLE_RATE_HZ,
                "piper-v1" to 22_050,
            )

        /** The 16-bit mono sample rate assumed for [engine]'s PCM. */
        fun sampleRateHz(engine: String): Int = SAMPLE_RATE_HZ[engine] ?: DEFAULT_SAMPLE_RATE_HZ

        private fun bytesPerSecond(engine: String): Long = sampleRateHz(engine) * 2L
    }
}

/** [totalBytes] = cached-exact + estimated; [cachedBytes] = the exact part only. */
data class PregenSpaceEstimate(
    val totalBytes: Long,
    val cachedBytes: Long,
)
