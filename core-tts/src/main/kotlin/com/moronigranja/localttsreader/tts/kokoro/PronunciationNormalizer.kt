package com.moronigranja.localttsreader.tts.kokoro

/**
 * Deterministic, ordered literal rules applied to the spoken form only
 * (roadmap G1), before phonemization. The index/match text is never touched:
 * normalization happens at the [Phonemizer] boundary, so corpus and oracle
 * checks compare the same unnormalized text.
 *
 * Three disciplines every rule keeps, all of them earned by a G0 finding
 * (docs/g0-findings.md):
 *
 * - **Language-scoped.** The same glyph is a different word per language
 *   (`Prof.` is *professor* / *profesor* / *professeur* / *professore*; `m` is
 *   *meters* / *metros* / *mètres* / *metri*), and expanding in the wrong
 *   language is worse than leaving the token alone. The base tag of the
 *   phonemizer's language selects the rule set.
 * - **Bounded.** [rule] wraps every literal pattern in lookarounds, so a rule
 *   can never rewrite inside a word (`500ms.` and `MS Word` survive the `Ms.`
 *   rule — the original precedent). Rules whose context is a *preceding figure*
 *   carry their own bounding instead ([unit], the French `8 h`, the Portuguese
 *   `9h45`): a unit follows a quantity, and requiring the digit is what keeps a
 *   name initial (`W. Somerset Maugham`) safe from the watt rule.
 * - **The spoken word survives; only punctuation goes.** Abbreviations consume
 *   their trailing period — espeak expands `Mr.` → "mister" but keeps the
 *   period as a clause boundary, so the honorific was followed by a full-stop
 *   pause (`abbrev-period-pause`). Digit separators become the spoken word
 *   ("point" / "coma" / "vírgula" / "virgule") or vanish (thousands), because
 *   the separator's *punctuation role* is what inserts pauses inside numbers
 *   (`decimal-period-pause`, `decimal-comma-pause`).
 *
 * Evidence: every entry comes from a measured G0 row, the corpus is the test
 * input, and `PronunciationNormalizerEspeakTest` asserts the render against the
 * real espeak-ng — so a wrong expansion word fails the suite even when the pure
 * transform looks right.
 */
object PronunciationNormalizer {
    /** One ordered rule: a pattern and what the match becomes. */
    private typealias Rule = Pair<Regex, (MatchResult) -> String>

    /** A literal expansion of a standalone token. */
    private fun rule(
        pattern: String,
        expansion: String,
    ): Rule = Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])") to { expansion }

    /** An abbreviation's period is part of the match (see the class KDoc). */
    private fun abbrev(
        abbreviation: String,
        expansion: String,
    ): Rule = rule("$abbreviation\\.", expansion)

    /**
     * A measurement symbol after a figure: the optional space between figure and
     * symbol is consumed as part of the match (so `8 m` and `8m` both become
     * "8 meters", never "8  meters"), and `(?<=\d)` is the whole guard — which
     * is why this does not go through [rule]: a preceding digit is exactly what
     * a unit follows, and what a name initial never has.
     */
    private fun unit(
        symbol: String,
        expansion: String,
    ): Rule = Regex("(?<=\\d)[ \\u00A0]?${Regex.escape(symbol)}(?![\\p{L}\\p{N}])") to { expansion }

    /** A pattern whose replacement needs the matched groups (digit separators). */
    private fun transform(
        pattern: String,
        replacement: (MatchResult) -> String,
    ): Rule = Regex(pattern) to replacement

    /**
     * The token is only an honorific when it does not sit inside a name: an
     * initial BEFORE it (`J. M. Dupont`) or a full first name (`João D. Silva`)
     * means `M.`/`D.` is a name part, not an abbreviation. Both shapes appear in
     * real text, so both are guarded.
     */
    private fun namePartAbbrev(
        abbreviation: String,
        expansion: String,
    ): Rule = rule("(?<!\\p{Lu}\\.\\s)(?<!\\p{Lu}\\p{Ll}+\\s)$abbreviation\\.", expansion)

    /**
     * Digit separators, applied FIRST (they restructure the number the other
     * rules then see). `en` uses the point as its decimal and the comma as its
     * thousands group; `es`/`it`/`pt` invert that; `fr` groups with spaces,
     * which espeak already reads correctly.
     *
     * The decimal becomes the spoken word, the thousands separator disappears:
     * both keep espeak from treating the separator as clause punctuation, which
     * is the confirmed defect (`0.5%` → "zero. five per cent", `99.5` →
     * "ninety-nine. five", `2,5` → "dois vírgula [pause] cinco",
     * `1.234,56` → "uno. doscientos…"). es 0104 reads the *identical* input
     * correctly, which is why this is a deterministic rule and not a patch.
     */
    private val enSeparators: List<Rule> =
        listOf(transform("(\\d)\\.(\\d)") { m -> "${m.groupValues[1]} point ${m.groupValues[2]}" })

    private fun commaDecimalSeparators(spokenSeparator: String): List<Rule> =
        listOf(
            // Thousands first: "1.234,56" → "1234,56", then the decimal word.
            transform("(\\d)\\.(\\d)") { m -> "${m.groupValues[1]}${m.groupValues[2]}" },
            transform("(\\d),(\\d)") { m -> "${m.groupValues[1]} $spokenSeparator ${m.groupValues[2]}" },
        )

    private val en: List<Rule> =
        enSeparators +
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
                // "no." is only a number when a figure follows it: "The answer
                // is no." must survive (G0 row 0009 has "no. 3").
                rule("no\\.(?=\\s*\\d)", "number"),
                abbrev("approx", "approximately"),
                abbrev("fig", "figure"),
                abbrev("vol", "volume"),
                abbrev("Cf", "compare"),
                // Owner-resolved to the natural spoken forms (G0 resolution).
                abbrev("e\\.g", "for example"),
                abbrev("i\\.e", "that is"),
                // Units (G0 rows 0019-0021, 0064-0066): espeak spells the symbols.
                unit("mph", " miles per hour"),
                unit("°F", " degrees Fahrenheit"),
                unit("°C", " degrees Celsius"),
                unit("kg", " kilograms"),
                unit("ft", " feet"),
                unit("lb", " pounds"),
                unit("oz", " ounces"),
                unit("mi", " miles"),
                unit("st", " stone"),
                unit("W", " watts"),
                unit("L", " liters"),
                unit("m", " meters"),
                // Deliberately NOT expanded: U.S./U.K./a.m./p.m./ET/GMT are
                // initialisms — espeak reads them as letters, which is correct.
            )

    private val es: List<Rule> =
        commaDecimalSeparators("coma") +
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
                // Units (G0 rows 0107-0109): es expands metric multi-letter
                // symbols (kg, km) but spells the rest as letters ("eme", "ele",
                // "ese-te", "ele-be", "eme-pe-hache").
                unit("km/h", " kilómetros por hora"),
                unit("mph", " millas por hora"),
                unit("km", " kilómetros"),
                unit("kg", " kilogramos"),
                unit("ft", " pies"),
                unit("mi", " millas"),
                unit("lb", " libras"),
                unit("oz", " onzas"),
                unit("st", " piedras"),
                unit("°C", " grados Celsius"),
                unit("°F", " grados Fahrenheit"),
                unit("W", " vatios"),
                unit("L", " litros"),
                unit("l", " litros"),
                unit("m", " metros"),
            )

    private val fr: List<Rule> =
        commaDecimalSeparators("virgule") +
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
                Regex("(?<=\\d)\\s*h(?![\\p{L}\\p{N}])") to { " heures" },
                // Units (G0 rows 0148-0150): kg/km expand correctly; m/l and the
                // rate form do not.
                unit("km/h", " kilomètres par heure"),
                unit("m", " mètres"),
                unit("l", " litres"),
                unit("°C", " degrés Celsius"),
                unit("°F", " degrés Fahrenheit"),
            )

    private val it: List<Rule> =
        commaDecimalSeparators("virgola") +
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
                // Units (G0 rows 0189-0191): every symbol is spelled, kg and km
                // included ("kappa-gi", "kappa-emme").
                unit("km/h", " chilometri all'ora"),
                unit("mph", " miglia all'ora"),
                unit("km", " chilometri"),
                unit("kg", " chilogrammi"),
                unit("°C", " gradi Celsius"),
                unit("°F", " gradi Fahrenheit"),
                unit("W", " watt"),
                unit("L", " litri"),
                unit("l", " litri"),
                unit("m", " metri"),
            )

    private val pt: List<Rule> =
        commaDecimalSeparators("vírgula") +
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
                // pt-BR writes clock time without a separator: "9h45" is nine
                // *and* forty-five (G0 row 0222 read it "nove agá quarenta e
                // cinco"), and a bare "8h" is eight *hours* (row 0220).
                Regex("(?<=\\d)h(?=\\d)") to { " e " },
                Regex("(?<=\\d)h(?![\\p{L}\\p{N}])") to { " horas" },
                // Units (G0 rows 0230-0232): every symbol is spelled ("ka-ge",
                // "ka-eme", "agá").
                unit("km/h", " quilômetros por hora"),
                unit("mph", " milhas por hora"),
                unit("km", " quilômetros"),
                unit("kg", " quilogramas"),
                unit("°C", " graus Celsius"),
                unit("°F", " graus Fahrenheit"),
                unit("W", " watts"),
                unit("L", " litros"),
                unit("l", " litros"),
                unit("m", " metros"),
            )

    /** Rule sets by base language tag (`en-us`/`en-gb` → `en`, `pt-br` → `pt`). */
    private val byLanguage: Map<String, List<Rule>> =
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
            out = re.replace(out) { m -> rep(m) }
        }
        return out
    }
}
