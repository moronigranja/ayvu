package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta

/**
 * Voice-catalog → target-language surface (decisions #114): which canonical
 * APP language codes a set of voice metadata rows can voice, and which voice
 * speaks a target. One shared implementation behind the reader/library
 * pickers and the [com.moronigranja.localttsreader.featureplayer.playback
 * .EngineSelector] resolution — the catalogs (KokoroVoiceMetadata /
 * PiperVoiceMetadata) are core-tts presentation rows; this maps their display
 * language strings to the codes the app stores per book (`pt-BR`, `zh`, …).
 *
 * Matching normalizes `-`/`_`/case and treats `pt-BR` as matching `pt_BR` AND
 * the bare base `pt`; deterministic catalog order.
 */
object TranslateLanguages {
    /** Metadata display language → canonical app code; null = not voicable. */
    fun langCode(display: String): String? =
        when (display.lowercase()) {
            "english (us)", "english (uk)" -> "en"
            "spanish", "spanish (es)" -> "es"
            "french" -> "fr"
            "german" -> "de"
            "italian" -> "it"
            "japanese" -> "ja"
            "hindi" -> "hi"
            "chinese" -> "zh"
            "portuguese (brazil)", "portuguese (br)" -> "pt-BR"
            else -> null
        }

    /** The canonical codes a catalog can voice (catalog order, deduplicated),
     * restricted to languages the translator can be prompted for. */
    fun codes(metas: List<KokoroVoiceMeta>): List<String> {
        val seen = LinkedHashSet<String>()
        for (meta in metas) {
            val code = langCode(meta.language) ?: continue
            if (code !in seen && LfmLang.toPromptLanguage(code) != null) seen.add(code)
        }
        return seen.toList()
    }

    /** The first catalog voice whose language matches [target], or null. */
    fun firstVoiceFor(
        metas: List<KokoroVoiceMeta>,
        target: String,
    ): String? {
        val normalized = normalize(target)
        return metas
            .firstOrNull { meta ->
                val code = langCode(meta.language)?.let(::normalize) ?: return@firstOrNull false
                code == normalized ||
                    (normalized.count { it == '-' } == 0 && code.substringBefore('-') == normalized)
            }?.name
    }

    /** Every catalog name whose language matches [target] (same normalize/base-code rule as [firstVoiceFor]). */
    fun voicesFor(
        metas: List<KokoroVoiceMeta>,
        target: String,
    ): List<String> {
        val normalized = normalize(target)
        return metas
            .filter { meta ->
                val code = langCode(meta.language)?.let(::normalize) ?: return@filter false
                code == normalized ||
                    (normalized.count { it == '-' } == 0 && code.substringBefore('-') == normalized)
            }
            .map { it.name }
    }

    /** [preferred] when it is a voice of [target], else the catalog's first — null when none matches. */
    fun resolvedVoiceFor(
        metas: List<KokoroVoiceMeta>,
        target: String,
        preferred: String?,
    ): String? = preferred?.takeIf { it in voicesFor(metas, target) } ?: firstVoiceFor(metas, target)

    private fun normalize(code: String): String = code.lowercase().replace('_', '-')
}
