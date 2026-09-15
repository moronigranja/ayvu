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

    /** A pattern whose replacement needs the matched groups (separators, decades, signs). */
    private fun transform(
        pattern: String,
        replacement: (MatchResult) -> String,
    ): Rule = Regex(pattern) to replacement

    /** Language-independent rules (script furniture, not words). */
    private val common: List<Rule> =
        listOf(
            // Footnote reference markers are page furniture, not text: strip the
            // superscript digits and the bracketed form (G0
            // `footnote-reference-marker-read-aloud`, confirmed across all six
            // Latin-script languages). The dagger/cross forms are already silent
            // upstream and need no rule.
            transform("[¹²³⁴⁵⁶⁷⁸⁹⁰]+") { "" },
            transform("\\[\\d{1,3}]") { "" },
        )

    /** Century words for the decade rules; an unmapped century is left alone. */
    private val centuryWords =
        mapOf(
            "17" to "seventeen",
            "18" to "eighteen",
            "19" to "nineteen",
            "20" to "twenty",
            "21" to "twenty-one",
        )

    /** Tens words used by "1910s" → "nineteen-tens" and "'90s" → "nineties". */
    private val tensWords =
        listOf("tens", "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties", "nineties")

    private fun tensWord(digit: String): String? = digit.toIntOrNull()?.takeIf { it in 1..9 }?.let { tensWords[it - 1] }

    /**
     * The decade rules (G0 `decade-trailing-s-read-literally`): "1800s" is the
     * eighteen hundreds, "1910s" the nineteen-tens, "the '90s" the nineties.
     * Unmapped centuries return the original text untouched rather than a guess.
     */
    private fun englishDecades(): List<Rule> =
        listOf(
            transform("\\b(\\d{2})00s\\b") { m ->
                centuryWords[m.groupValues[1]]?.let { "$it hundreds" } ?: m.value
            },
            transform("\\b(\\d{2})(\\d)0s\\b") { m ->
                val century = centuryWords[m.groupValues[1]]
                val tens = tensWord(m.groupValues[2])
                if (century != null && tens != null) "$century-$tens" else m.value
            },
            transform("'(\\d{2})s\\b") { m ->
                // "'90s" → "nineties": the TENS digit is the first of the pair,
                // and the apostrophe goes with it.
                tensWord(m.groupValues[1].take(1)) ?: m.value
            },
        )

    /**
     * The minus sign (G0 `negative-sign-english-injection`): espeak-ng injects an
     * ENGLISH "minus" into es/fr and drops the sign entirely in pt. The sign is
     * only a negative when it is not a range hyphen (a preceding figure) and
     * introduces a figure; the following space is consumed so the spoken word and
     * the number stay separate tokens.
     */
    private fun minus(spoken: String): Rule = transform("(?<![\\d\\p{L}])\\s?[-−]\\s?(?=\\d)") { "$spoken " }

    /**
     * The value of a roman numeral, or null when the glyphs are not one. Used to
     * REPLACE the numeral with its digits before phonemization: espeak-ng prepends
     * a literal "roman"/"romain" to every numeral it sees (G0
     * `roman-numeral-read-with-label`), and it garbles lowercase forms in some
     * voices, so the labels disappear when the numeral never reaches it.
     */
    private fun romanValue(roman: String): Int? {
        val digits = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        val values = roman.lowercase().map { digits[it] ?: return null }
        if (values.isEmpty() || values.size > 7) return null
        var total = 0
        values.forEachIndexed { i, value ->
            if (i + 1 < values.size && value < values[i + 1]) total -= value else total += value
        }
        return total.takeIf { it in 1..3999 }
    }

    /** Structural words after which a roman numeral counts rather than names. */
    private val structuralContext =
        "(?:chapter|subchapter|part|section|subsection|appendix|table|figure|act|scene|book|volume|" +
            "line|paragraph|page|chap\\.|capítulo|capitolo|sección|sezione|seção|chapitre|paragraphe)"

    /**
     * The roman-numeral label class, scoped to en/fr where it was confirmed (es/it/pt
     * read plain numbers and are left alone; their accepted-as-is lowercase garble
     * stays accepted).
     */
    private fun romanNumerals(): List<Rule> =
        listOf(
            // A structural word disambiguates, so a single glyph is safe here
            // ("Chapter I" → "Chapter 1"); the keyword is kept verbatim.
            transform("(?i)\\b($structuralContext)\\s+([IVXLCDM]{1,7})\\b") { m ->
                romanValue(m.groupValues[2])?.let { "${m.groupValues[1]} $it" } ?: m.value
            },
            // After a name, TWO or more glyphs are required: the English pronoun
            // "I" and a stray "V"/"X" must never become numbers ("So I went"
            // survives), and the lookbehind is bounded for ICU.
            transform("(?<=\\b[A-Z][a-z]{1,20} )([IVXLCDM]{2,7})\\b") { m ->
                romanValue(m.groupValues[1])?.toString() ?: m.value
            },
            // The lowercase garble in a structural context ("subsection iii").
            transform("(?i)\\b($structuralContext)\\s+([ivxlcdm]{2,7})\\b") { m ->
                romanValue(m.groupValues[2])?.let { "${m.groupValues[1]} $it" } ?: m.value
            },
        )

    /** Regnal ordinals: the owner's I–X boundary, pt and es alike. */
    private val ordinalFeminine =
        mapOf(
            "es" to listOf("primera", "segunda", "tercera", "cuarta", "quinta", "sexta", "séptima", "octava", "novena", "décima"),
            "pt" to listOf("primeira", "segunda", "terceira", "quarta", "quinta", "sexta", "sétima", "oitava", "nona", "décima"),
        )

    private val ordinalMasculine =
        mapOf(
            "es" to listOf("primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo", "noveno", "décimo"),
            "pt" to listOf("primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono", "décimo"),
        )

    /**
     * Grammatical gender of the regnal names the corpus carries. A literal
     * dictionary cannot know that "Isabel II" is *segunda* while "Pedro II" is
     * *segundo*; masculine is the documented default for an unknown name.
     */
    private val feminineRegnalNames =
        setOf("isabel", "isabela", "elizabeth", "elisabet", "elisabetta", "maria", "ana", "beatriz", "catarina", "leonor")

    /** `I`–`X` after a person name reads as an ordinal (owner ruling, 2026-09-15). */
    private fun regnalOrdinals(language: String): Rule =
        transform("\\b([A-Z][\\p{Ll}]{1,20}) (I|II|III|IV|V|VI|VII|VIII|IX|X)\\b") { m ->
            val name = m.groupValues[1]
            val value = romanValue(m.groupValues[2]) ?: return@transform m.value
            val table =
                if (name.lowercase() in feminineRegnalNames) ordinalFeminine[language] else ordinalMasculine[language]
            table?.getOrNull(value - 1)?.let { "$name $it" } ?: m.value
        }

    /** Currency symbol → (plural, singular) name for English amounts. */
    private val englishCurrencies =
        mapOf(
            "$" to ("dollars" to "dollar"),
            "£" to ("pounds" to "pound"),
            "€" to ("euros" to "euro"),
            "¥" to ("yen" to "yen"),
            "₹" to ("rupees" to "rupee"),
        )

    /**
     * English money (G0 `currency-amount-misread`): the symbol precedes the amount
     * in text and espeak reads it that way — "yen ten thousand" instead of "ten
     * thousand yen" — and the cents as "point five six" instead of "and fifty-six
     * cents". Rewriting the pair fixes the order, and a ".00" tail is dropped
     * rather than spoken.
     */
    private fun englishCurrency(): List<Rule> =
        listOf(
            transform("([$£€¥₹])(\\d[\\d,]*\\d|\\d)(?:[.](\\d{2}))?") { m ->
                val amount = m.groupValues[2]
                val (plural, singular) = englishCurrencies.getValue(m.groupValues[1])
                val unit = if (amount == "1") singular else plural
                val cents = m.groupValues[3].trimStart('0')
                if (cents.isEmpty()) "$amount $unit" else "$amount $unit and $cents cents"
            },
            transform("(\\d+)¢") { m -> "${m.groupValues[1]} cents" },
            transform("(\\d+)p\\b") { m -> "${m.groupValues[1]} pence" },
        )

    /**
     * pt-BR money (G0 `pt-br-currency-real-read-with-dollar`): `R$` expanded to
     * "real dólar", and the owner's ruling is "deveria ser 'reais' somente" — the
     * amount, then the currency in the right number. A ",00" tail disappears.
     */
    private fun portugueseCurrency(): Rule =
        transform("R\\$\\s?(\\d[\\d.]*)(?:,(\\d{2}))?") { m ->
            val amount = m.groupValues[1]
            val unit = if (amount.replace(".", "") == "1") "real" else "reais"
            val cents = m.groupValues[2].trimStart('0')
            if (cents.isEmpty()) "$amount $unit" else "$amount $unit e $cents centavos"
        }

    /**
     * The token is only an honorific when it does not sit inside a name: an
     * initial BEFORE it (`J. M. Dupont`) or a full first name (`João D. Silva`)
     * means `M.`/`D.` is a name part, not an abbreviation. Both shapes appear in
     * real text, so both are guarded.
     *
     * The first-name guard is BOUNDED (`{1,20}`) on purpose: these rules execute
     * on Android's ICU regex engine, which rejects a lookbehind of unbounded
     * length — `\p{Ll}+` compiles on the host JVM and throws
     * `PatternSyntaxException` on the device (found by the S22 listening harness,
     * 2026-09-15). [patternSources] plus the suite's ICU-safety test keep every
     * rule inside that constraint.
     */
    private fun namePartAbbrev(
        abbreviation: String,
        expansion: String,
    ): Rule = rule("(?<!\\p{Lu}\\.\\s)(?<!\\p{Lu}\\p{Ll}{1,20}\\s)$abbreviation\\.", expansion)

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
        englishCurrency() +
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
                // A hyphen between figures is a range or a score, not punctuation:
                // espeak says "dash" (G0 `hyphen-read-as-dash` — "2-1" → "two dash
                // one", "lines 8-19" → "eight dash nineteen"). "to" is the owner's
                // call for English.
                transform("(?<=\\d)\\s?-\\s?(?=\\d)") { " to " },
                // Decades (G0 `decade-trailing-s-read-literally`).
                *englishDecades().toTypedArray(),
                // Roman numerals (G0 `roman-numeral-read-with-label`).
                *romanNumerals().toTypedArray(),
                // Deliberately NOT expanded: U.S./U.K./a.m./p.m./ET/GMT are
                // initialisms — espeak reads them as letters, which is correct.
                // (The minus sign needs no rule in en: espeak already says "minus".)
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
                minus("menos"),
                regnalOrdinals("es"),
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
                minus("moins"),
                *romanNumerals().toTypedArray(),
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
        listOf(portugueseCurrency()) +
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
                // Regnal numerals: I–X read ordinally in pt-BR too ("Pedro
                // segundo", "Isabel segunda" — owner ruling, 2026-09-15).
                regnalOrdinals("pt"),
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
                minus("menos"),
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
        for ((re, rep) in common) out = re.replace(out) { m -> rep(m) }
        for ((re, rep) in byLanguage[language.substringBefore('-').lowercase()].orEmpty()) {
            out = re.replace(out) { m -> rep(m) }
        }
        return out
    }

    /**
     * Test seam: every rule's source pattern. The suite scans these for the
     * ICU-safety constraint (no unbounded lookbehind) — an Android-only failure
     * mode that a host test cannot otherwise observe, since `java.util.regex`
     * on the JVM accepts what ICU rejects.
     */
    internal val patternSources: List<String>
        get() = byLanguage.values.flatten().map { it.first.pattern }
}
