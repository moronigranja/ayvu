package com.moronigranja.localttsreader.locate

import java.text.Normalizer

/**
 * Text normalization + n-gram extraction backing all matching.
 *
 * Normalized form: NFKD (accents decomposed) → combining marks dropped → apostrophes
 * and quote-like characters dropped entirely → lowercase → keep only [a-z0-9] and
 * whitespace (all other punctuation → space) → collapse whitespace runs to one space →
 * trim. Making matching insensitive to case, smart quotes, punctuation, OCR noise, and
 * accent representation ("café" → "cafe" on both sides).
 *
 * Apostrophes are stripped rather than turned into spaces so that "It's" → "its" and
 * OCR text "its" (apostrophe dropped by the engine) normalize identically; other
 * punctuation becomes a word separator ("re-enter" → "re enter").
 */
object TextNormalizer {
    private val COMBINING_MARKS = Regex("\\p{M}")
    private val APOSTROPHES = Regex("[''\u2018\u2019\u02BC\u201A\u201B]")
    private val NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        s = COMBINING_MARKS.replace(s, "")
        s = APOSTROPHES.replace(s, "")
        s = s.lowercase()
        s = NON_ALNUM.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()
        return s
    }

    /**
     * Word n-grams of an already-normalized string. Texts shorter than [n] tokens return
     * their token set (unigram fallback). Empty input → empty set.
     */
    fun grams(
        normalized: String,
        n: Int,
    ): Set<String> {
        require(n >= 1) { "n-gram size must be >= 1" }
        if (normalized.isEmpty()) return emptySet()
        val tokens = normalized.split(' ')
        if (tokens.size < n) return tokens.toSet()
        return buildSet {
            for (i in 0..tokens.size - n) {
                add(tokens.subList(i, i + n).joinToString(" "))
            }
        }
    }
}
