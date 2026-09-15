package com.moronigranja.localttsreader.tts.kokoro

/**
 * Deterministic, ordered literal rules applied to the spoken form only
 * (roadmap G1), before phonemization. The index/match text is never touched:
 * normalization happens at the [Phonemizer] boundary, so corpus and oracle
 * checks compare the same unnormalized text.
 *
 * Two disciplines every rule must keep, from the G0 findings:
 *
 * - **Language-scoped.** The same glyph is a different word per language
 *   (`Prof.` is *professor* / *profesor* / *professeur* / *professore*), and a
 *   dictionary that expands in the wrong language is worse than no rule. The
 *   base tag of the phonemizer's language selects the rule set.
 * - **Bounded.** Each rule matches a standalone token, never a substring: the
 *   lookarounds come from the helper, so a rule can never rewrite inside a word
 *   (`500ms.` and `MS Word` survive the `Ms.` rule — the original precedent).
 *
 * Abbreviation rules CONSUME the trailing period. That is not cosmetic: espeak
 * expands `Mr.` → "mister" but keeps the period as a clause boundary, so the
 * honorific is followed by a full-stop pause (G0 `abbrev-period-pause`). Writing
 * the expansion ourselves is what removes it.
 *
 * Evidence: every entry here comes from a measured G0 row
 * (docs/g0-findings.md) — the corpus is the test input, and
 * `PronunciationNormalizerEspeakTest` asserts the render against the real
 * espeak-ng, so this table stays honest.
 */
object PronunciationNormalizer {
    /** `X ← Y` means: the standalone token matching [first] is spoken as [second]. */
    private fun rule(
        pattern: String,
        expansion: String,
    ): Pair<Regex, String> = Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])") to expansion

    /** An abbreviation's period is part of the match (see the class KDoc). */
    private fun abbrev(
        abbreviation: String,
        expansion: String,
    ): Pair<Regex, String> = rule("$abbreviation\\.", expansion)

    /**
     * The token is only an honorific when it does not sit inside a name: an
     * initial BEFORE it (`J. M. Dupont`) or a full first name (`João D. Silva`)
     * means `M.`/`D.` is a name part, not an abbreviation. Both shapes appear in
     * real text, so both are guarded.
     */
    private fun namePartAbbrev(
        abbreviation: String,
        expansion: String,
    ): Pair<Regex, String> = rule("(?<!\\p{Lu}\\.\\s)(?<!\\p{Lu}\\p{Ll}+\\s)$abbreviation\\.", expansion)

    private val en: List<Pair<Regex, String>> =
        listOf(
            // espeak-ng 1.52.0 spells "Ms." as "M S" (ˌɛmˈɛs); /mɪz/ is the
            // idiomatic form and "Miz" renders mˈɪz (host-verified).
            abbrev("Ms", "Miz"),
            abbrev("Mrs", "missus"),
            abbrev("Mr", "mister"),
            abbrev("Dr", "doctor"),
            abbrev("Prof", "professor"),
            abbrev("Rev", "reverend"),
            abbrev("Capt", "captain"),
            abbrev("Gen", "general"),
            abbrev("Sgt", "sergeant"),
            abbrev("Col", "colonel"),
            // "no." is only a number when a figure follows it: "The answer is
            // no." must survive (G0 row 0009 has "no. 3").
            rule("no\\.(?=\\s*\\d)", "number"),
            abbrev("approx", "approximately"),
            abbrev("fig", "figure"),
            abbrev("vol", "volume"),
            abbrev("Cf", "compare"),
            // Owner-resolved to the natural spoken forms (G0 resolution).
            abbrev("e\\.g", "for example"),
            abbrev("i\\.e", "that is"),
            // Deliberately NOT expanded: U.S./U.K./a.m./p.m./ET/GMT are
            // initialisms — espeak reads them as letters, which is correct.
        )

    private val es: List<Pair<Regex, String>> =
        listOf(
            abbrev("Sra", "señora"),
            abbrev("Srta", "señorita"),
            abbrev("Sr", "señor"),
            abbrev("Dra", "doctora"),
            abbrev("Dr", "doctor"),
            abbrev("Profa", "profesora"),
            // "la Prof. Salas" is a woman (G0 row 0095): the article is the only
            // in-text signal, so the feminine form is a scoped rule in front of
            // the masculine default — a bare dictionary cannot express it.
            rule("(?<=\\bla\\s)Prof\\.", "profesora"),
            abbrev("Prof", "profesor"),
            rule("EE\\.\\s*UU\\.", "Estados Unidos"),
            rule("p\\.\\s*ej\\.", "por ejemplo"),
            abbrev("pág", "página"),
            abbrev("aprox", "aproximadamente"),
            abbrev("etc", "etcétera"),
        )

    private val fr: List<Pair<Regex, String>> =
        listOf(
            namePartAbbrev("M", "monsieur"),
            rule("(?<=\\bla\\s)Prof\\.", "professeure"),
            abbrev("Prof", "professeur"),
            rule("c\\.-à-d\\.", "c'est-à-dire"),
            rule("É\\.-U\\.", "États-Unis"),
            rule("R\\.-U\\.", "Royaume-Uni"),
            rule("p\\.\\s*ex\\.", "par exemple"),
            // "8 h" is eight *hours*: the bare "h" is a unit, not a letter
            // (G0 row 0138 rendered it "huit hache"). Built WITHOUT the rule()
            // helper on purpose — the helper's leading lookbehind forbids a
            // preceding alphanumeric, and a preceding DIGIT is precisely this
            // rule's context, so it carries its own bounding instead.
            Regex("(?<=\\d)\\s*h(?![\\p{L}\\p{N}])") to " heures",
        )

    private val it: List<Pair<Regex, String>> =
        listOf(
            // The dotted forms carry an INTERNAL period and usually no trailing
            // one ("Sig.ra Dalloway", "Dott.ssa Bianchi"): the trailing period
            // is optional here, unlike the plain abbreviations below.
            rule("Sig\\.ra\\.?", "signora"),
            rule("Sig\\.na\\.?", "signorina"),
            abbrev("Sig", "signor"),
            rule("Dott\\.ssa\\.?", "dottoressa"),
            abbrev("Dott", "dottore"),
            abbrev("Avv", "avvocato"),
            rule("(?<=\\bla\\s)Prof\\.", "professoressa"),
            abbrev("Prof", "professore"),
            abbrev("pag", "pagina"),
            abbrev("ecc", "eccetera"),
            rule("p\\.\\s*es\\.", "per esempio"),
        )

    private val pt: List<Pair<Regex, String>> =
        listOf(
            abbrev("Sra", "senhora"),
            abbrev("Srta", "senhorita"),
            abbrev("Sr", "senhor"),
            abbrev("Dra", "doutora"),
            abbrev("Dr", "doutor"),
            abbrev("Profa", "professora"),
            abbrev("Prof", "professor"),
            // "D. Pedro II" is *dom* Pedro in pt-BR (G0 row 0233); the initial
            // guard keeps a name initial ("João D. Silva") untouched.
            namePartAbbrev("D", "dom"),
            rule("p\\.\\s*ex\\.", "por exemplo"),
            abbrev("pág", "página"),
            abbrev("aprox", "aproximadamente"),
            abbrev("etc", "etcetera"),
        )

    /** Rule sets by base language tag (`en-us`/`en-gb` → `en`, `pt-br` → `pt`). */
    private val byLanguage: Map<String, List<Pair<Regex, String>>> =
        mapOf(
            "en" to en,
            "es" to es,
            "fr" to fr,
            "it" to it,
            "pt" to pt,
        )

    /** The spoken form of [text] for [language]; an unknown language is untouched. */
    fun forSpeech(
        text: String,
        language: String,
    ): String {
        var out = text
        for ((re, rep) in byLanguage[language.substringBefore('-').lowercase()].orEmpty()) {
            out = re.replace(out, rep)
        }
        return out
    }
}
