package com.moronigranja.localttsreader.locate

/**
 * Pure matching logic: how well a shared snippet is contained in a passage.
 *
 * Per snippet n-gram window (n = 4): credit 1.0 when the window appears verbatim in the
 * passage, otherwise partial credit = fraction of its 3-gram sub-windows found. The
 * final score is the mean credit over all snippet windows (recall-style, 0..1).
 * 1.0 = snippet fully contained (verbatim / contiguous chunk / truncated prefix);
 * unrelated text scores near 0.
 *
 * The 4-gram + 3-gram-fallback shape was chosen by measurement (see agents.md §8):
 * realistic OCR typo rates keep matches above the 0.6 default threshold while
 * cross-book distractors stay below ~0.05 and reordered text sits near 0.
 */
object TextMatcher {
    const val DEFAULT_NGRAM_SIZE = 4

    /**
     * Precomputed per-passage gram sets, built once at index time by [TextIndex] so
     * queries only normalize the snippet.
     */
    data class PassageGrams(
        val ngrams: Set<String>,
        val subgrams: Set<String>,
        val tokens: Set<String>,
    )

    /** Precompute what [TextIndex] stores for one already-normalized passage. */
    fun indexGrams(normalizedPassage: String): PassageGrams {
        val tokens = normalizedPassage.split(' ').filter { it.isNotEmpty() }
        return PassageGrams(
            ngrams = TextNormalizer.grams(normalizedPassage, DEFAULT_NGRAM_SIZE),
            subgrams = TextNormalizer.grams(normalizedPassage, DEFAULT_NGRAM_SIZE - 1),
            tokens = tokens.toSet(),
        )
    }

    /** Convenience: score a raw snippet against a raw passage string (normalizes both). */
    fun score(
        snippet: String,
        passage: String,
    ): Double = scoreNormalized(TextNormalizer.normalize(snippet), indexGrams(TextNormalizer.normalize(passage)))

    /**
     * Score an already-normalized snippet against precomputed passage grams.
     * Snippets shorter than 4 tokens fall back to token-set recall (weak signal, but
     * the only signal available — the caller's threshold decides whether it's enough).
     */
    fun scoreNormalized(
        snippetNormalized: String,
        passage: PassageGrams,
    ): Double {
        val snippetTokens = snippetNormalized.split(' ').filter { it.isNotEmpty() }
        if (snippetTokens.isEmpty()) return 0.0

        if (snippetTokens.size < DEFAULT_NGRAM_SIZE) {
            if (passage.tokens.isEmpty()) return 0.0
            var hits = 0
            for (t in snippetTokens) if (t in passage.tokens) hits++
            return hits.toDouble() / snippetTokens.size
        }

        var total = 0.0
        for (i in 0..snippetTokens.size - DEFAULT_NGRAM_SIZE) {
            val window = snippetTokens.subList(i, i + DEFAULT_NGRAM_SIZE)
            if (window.joinToString(" ") in passage.ngrams) {
                total += 1.0
            } else {
                // Tolerate local noise (e.g. one OCR typo) via 3-gram sub-windows.
                var found = 0
                if (window.subList(0, 3).joinToString(" ") in passage.subgrams) found++
                if (window.subList(1, 4).joinToString(" ") in passage.subgrams) found++
                total += found / 2.0
            }
        }
        return total / (snippetTokens.size - DEFAULT_NGRAM_SIZE + 1)
    }
}
