package com.moronigranja.localttsreader.tts.kokoro

/**
 * Deterministic, ordered literal rules applied to the spoken form only
 * (roadmap G1), before phonemization. The index/match text is never touched:
 * normalization happens at the [Phonemizer] boundary, so corpus and oracle
 * checks compare the same unnormalized text.
 */
object PronunciationNormalizer {
    /** Ordered literal rules applied to the spoken form only (roadmap G1). */
    private val rules: List<Pair<Regex, String>> =
        listOf(
            // espeak-ng 1.52.0 spells the honorific "Ms." as "M S" (ˌɛmˈɛs);
            // the idiomatic spoken form is /mɪz/ and "Miz" renders mˈɪz in that
            // same library (host-verified). Case-sensitive M, requires the period,
            // bounded by non-alphanumerics so units ("500ms.") and "MS Word"
            // (no period) are never rewritten.
            Regex("(?<![\\p{L}\\p{N}])Ms\\.(?![\\p{L}\\p{N}])") to "Miz",
        )

    fun forSpeech(text: String): String {
        var out = text
        for ((re, rep) in rules) out = re.replace(out, rep)
        return out
    }
}
