package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.tts.SegmentAnchor

/**
 * One pre-synthesized passage: PCM plus what the read-along reader needs.
 * Engine-agnostic by construction — the player converts `SynthesisOutcome`
 * into this (decisions #35).
 */
data class PregenAudio(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val segments: List<SegmentAnchor>? = null,
)

/**
 * Identity of one synthesized passage: book, spine indexes, engine, voice,
 * speed (and the read-in-language dimension, decisions #114). The [toString]
 * form is the disk-cache path — book id is a content hash (decisions #11),
 * the engine/voice/speed slug mirrors the post-v1 PCM cache keying (engine +
 * voice + speed + passage, #31/#34), and it round-trips through [parse].
 *
 * Path layout (v2, decisions #54; v2a for translation; v2b for the translator
 * identity, decisions #161/#162):
 * `<bookId>/<engine>/<voice>/<speed>/c<ch>p<passage>`,
 * `<bookId>/<engine>/<voice>/<speed>/x<lang>/c<ch>p<passage>` and
 * `<bookId>/<engine>/<voice>/<speed>/x<lang>/t<translator>/c<ch>p<passage>`.
 * The engine segment sits directly under the `bookId` subtree — the
 * delete/usage unit (decisions #11) — ahead of the voice slug, so the same
 * voice name can never collide across engines on disk. [parse] also accepts
 * the pre-engine v1 layout `<bookId>/<voice>/<speed>/c<ch>p<passage>` and
 * reads it as [DEFAULT_ENGINE]: entries written before the engine dimension
 * stay addressable and are never treated as disk artifacts (CR-4 deletes
 * unparseable paths, so the legacy form must keep parsing).
 */
data class PregenKey(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val voice: String,
    val speed: Double,
    val engine: String = DEFAULT_ENGINE,
    /** Read-in-language target app code when this audio is translated text;
     * null = the book's original language. The disk path inserts an `x<lang>`
     * segment, so translated and original audio can never collide — including
     * the target-voice-equals-book-voice toggle case (decisions #114). */
    val translateLang: String? = null,
    /** WHO translated the text ([LFM_TRANSLATOR] today), or null when
     * [translateLang] is null — the plan's `t<translator>` segment. Different
     * engines produce different Portuguese for the same passage, so a run must
     * never treat another engine's audio as a cache hit (decisions #161). An
     * untranslated key is unchanged; [parse] reads a 6-segment (v2a) key as
     * [SMALL100_TRANSLATOR], the only engine that could have written it. */
    val translator: String? = null,
) {
    override fun toString(): String {
        val middle = "$engine/$voice/${formatSpeed(speed)}"
        val langSegment = translateLang?.let { "x$it" } ?: ""
        // The translator segment only qualifies a language segment: without a
        // target there is no translation to attribute.
        val translatorSegment = if (langSegment.isNotEmpty()) translator?.let { "t$it" } ?: "" else ""
        val translated = "$langSegment/$translatorSegment".trim('/')
        return if (translated.isEmpty()) {
            "$bookId/$middle/c$chapterIndex" + "p$passageIndex"
        } else {
            "$bookId/$middle/$translated/c$chapterIndex" + "p$passageIndex"
        }
    }

    companion object {
        /**
         * The engine pre-generation writes with today (decisions #54); legacy
         * cache paths without an engine segment parse as this.
         */
        const val DEFAULT_ENGINE = "kokoro"

        /** The read-in-language translator writing today (decisions #162) —
         * the `t<translator>` cache-key dimension's value. */
        const val LFM_TRANSLATOR = "lfm12b"

        /** The translator 6-segment (v2a) keys were written by: the retired
         * SMaLL-100 runtime. Parsed, never written — its audio must never be
         * served for an LFM request (decisions #161). */
        const val SMALL100_TRANSLATOR = "small100"

        /**
         * Parses the [toString] form, or the legacy pre-engine form
         * `<bookId>/<voice>/<speed>/c<ch>p<passage>` as [DEFAULT_ENGINE];
         * null on malformed input.
         */
        fun parse(path: String): PregenKey? {
            val parts = path.split('/')
            // V2b: <bookId>/<engine>/<voice>/<speed>/x<lang>/t<translator>/c<ch>p<passage> (7 segments)
            // V2a: <bookId>/<engine>/<voice>/<speed>/x<lang>/c<ch>p<passage> (6 segments)
            // V2:  <bookId>/<engine>/<voice>/<speed>/c<ch>p<passage> (5 segments)
            // V1:  <bookId>/<voice>/<speed>/c<ch>p<passage> (4 segments, engine = kokoro)
            if (parts.size !in 4..7) return null
            val bookId = parts[0]
            if (bookId.isEmpty()) return null
            // The spine is `c<ch>p<passage>`; strip the `c` before splitting so
            // the chapter segment stays numeric (same contract as the v1 parse).
            val spine = parts.last()
            if (!spine.startsWith("c")) return null
            val (c, p) = spine.substring(1).split('p', limit = 2).takeIf { it.size == 2 } ?: return null
            val legacy = parts.size == 4
            val engine = if (legacy) DEFAULT_ENGINE else parts[1]
            val voice = if (legacy) parts[1] else parts[2]
            val speed =
                (if (legacy) parts[2] else parts[3]).replace('_', '.').toDoubleOrNull() ?: return null
            val translateLang =
                if (parts.size >= 6) {
                    val segment = parts[4]
                    if (!segment.startsWith("x")) return null
                    segment.substring(1).takeIf { it.isNotBlank() } ?: return null
                } else {
                    null
                }
            // A 6-segment key predates the translator dimension, so SMaLL-100 is
            // the only engine that can have written it — that default is what
            // keeps an LFM run from reading small-100-era audio as a cache hit.
            val translator =
                when {
                    parts.size == 7 -> {
                        val segment = parts[5]
                        if (!segment.startsWith("t")) return null
                        segment.substring(1).takeIf { it.isNotBlank() } ?: return null
                    }
                    parts.size == 6 -> SMALL100_TRANSLATOR
                    else -> null
                }
            return PregenKey(
                bookId,
                c.toIntOrNull() ?: return null,
                p.toIntOrNull() ?: return null,
                voice,
                speed,
                engine,
                translateLang,
                translator,
            )
        }

        /** The on-disk speed slug (`1` for 1.0, `1_5` for 1.5); the cache
         * derives the legacy v1 folder name from it ([PcmPassageCache]). */
        internal fun formatSpeed(speed: Double): String =
            if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString().replace('.', '_')
    }
}
