package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * G1 rules, spoken-form only (roadmap G1, findings in docs/g0-findings.md).
 * Pure transforms: what the normalizer produces is asserted exactly, including
 * the boundary cases that must survive — a rule that rewrites a word, an
 * initial or a unit is worse than the mispronunciation it fixes.
 *
 * The render-through-espeak half lives in [PronunciationNormalizerEspeakTest].
 */
class PronunciationNormalizerTest {
    private fun spoken(
        text: String,
        language: String,
    ) = PronunciationNormalizer.forSpeech(text, language)

    // ------------------------------------------------------------------
    // en — the honorific/abbreviation class (G0 rows 0004-0009, 0046-0054)
    // ------------------------------------------------------------------

    @Test
    fun `English honorifics are spoken in full, with the period consumed`() {
        assertEquals(
            "Miz Dalloway, doctor Watson, and professor Higgins arrived at noon.",
            spoken("Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon.", "en-us"),
        )
        assertEquals(
            "missus Danvers, mister Bean, and sergeant Pepper asked for directions.",
            spoken("Mrs. Danvers, Mr. Bean, and Sgt. Pepper asked for directions.", "en-us"),
        )
        assertEquals(
            "reverend King, captain Ahab, and general Lee signed the letter.",
            spoken("Rev. King, Capt. Ahab, and Gen. Lee signed the letter.", "en-gb"),
        )
    }

    @Test
    fun `English abbreviations expand, initialisms stay letter-read`() {
        assertEquals(
            "The U.S. and U.K. delegations met at approximately 8 a.m. ET.",
            spoken("The U.S. and U.K. delegations met at approx. 8 a.m. ET.", "en-us"),
        )
        assertEquals(
            "compare appendix B: the figure shows volume 2, number 3.",
            spoken("Cf. appendix B: the fig. shows vol. 2, no. 3.", "en-us"),
        )
        assertEquals(
            "She read for example the first chapter, that is page 5, and noted the etc.",
            spoken("She read e.g. the first chapter, i.e. page 5, and noted the etc.", "en-gb"),
        )
    }

    @Test
    fun `English boundary cases are untouched`() {
        assertEquals("500ms. latency", spoken("500ms. latency", "en-us"))
        assertEquals("MS Word", spoken("MS Word", "en-us"))
        assertEquals("ms. lowercase", spoken("ms. lowercase", "en-us"))
        assertEquals("Mrs. Smith", spoken("Mrs. Smith", "xx-unknown"))
        // "no." IS a number before a figure — and a sentence-ending "no." must
        // survive, which is the case that would silently rewrite a book.
        assertEquals("The answer is no.", spoken("The answer is no.", "en-us"))
        assertEquals("See number 5 and number 12.", spoken("See no. 5 and no. 12.", "en-us"))
    }

    // ------------------------------------------------------------------
    // es / fr / it / pt — same class, language-scoped words
    // ------------------------------------------------------------------

    @Test
    fun `Spanish honorifics expand and the article picks the feminine form`() {
        assertEquals(
            "señora Delgado, doctor Vidal y señorita Rivas llegaron al mediodía.",
            spoken("Sra. Delgado, Dr. Vidal y Srta. Rivas llegaron al mediodía.", "es"),
        )
        assertEquals(
            "El señor Ortega preguntó a la profesora Salas por el doctor García.",
            spoken("El Sr. Ortega preguntó a la Prof. Salas por el Dr. García.", "es"),
        )
        assertEquals(
            "Estados Unidos y el Reino Unido se reunieron por ejemplo a las 8.",
            spoken("EE. UU. y el Reino Unido se reunieron p. ej. a las 8.", "es"),
        )
    }

    @Test
    fun `French M expands to monsieur but a name initial does not`() {
        assertEquals("monsieur Higgins", spoken("M. Higgins", "fr-fr"))
        // "M. le maire" is *monsieur le maire* — the corpus row 0136's reading.
        assertEquals("monsieur le maire", spoken("M. le maire", "fr-fr"))
        assertEquals("le professeur Lefèvre", spoken("le Prof. Lefèvre", "fr-fr"))
        assertEquals(
            "J. M. Dupont",
            spoken("J. M. Dupont", "fr-fr"),
            "a single initial before the token means a name, not an honorific",
        )
        assertEquals("8 heures du matin", spoken("8 h du matin", "fr-fr"))
    }

    @Test
    fun `Italian honorifics expand including the dotted feminine forms`() {
        assertEquals(
            "signora Dalloway, dottore Watson e professore Higgins arrivarono a mezzogiorno.",
            spoken("Sig.ra Dalloway, Dott. Watson e Prof. Higgins arrivarono a mezzogiorno.", "it"),
        )
        assertEquals(
            "Il signor Verdi chiese alla dottoressa Bianchi notizie dell'avvocato Rossi.",
            spoken("Il Sig. Verdi chiese alla Dott.ssa Bianchi notizie dell'Avv. Rossi.", "it"),
        )
    }

    @Test
    fun `Portuguese honorifics expand, senhor included, with the initial guard`() {
        assertEquals(
            "senhora Dalloway, doutor Watson e professor Higgins chegaram ao meio-dia.",
            spoken("Sra. Dalloway, Dr. Watson e Prof. Higgins chegaram ao meio-dia.", "pt-br"),
        )
        assertEquals(
            "O senhor Oliveira perguntou à professora Costa pelo doutor Barbosa.",
            spoken("O Sr. Oliveira perguntou à Profa. Costa pelo Dr. Barbosa.", "pt-br"),
        )
        assertEquals("dom Pedro segundo governou", spoken("D. Pedro II governou", "pt-br"), "honorific + regnal compose")
        assertEquals("João D. Silva", spoken("João D. Silva", "pt-br"))
    }

    // ------------------------------------------------------------------
    // Units and digit separators (G0 measurement/number rows)
    // ------------------------------------------------------------------

    @Test
    fun `English units expand and the decimal point becomes the spoken word`() {
        assertEquals(
            "The room was 12 feet by 8 meters; it weighed 2 point 5 kilograms and held 3 liters.",
            spoken("The room was 12 ft by 8 m; it weighed 2.5 kg and held 3 L.", "en-us"),
        )
        assertEquals("0 point 5% is half", spoken("0.5% is half", "en-us"))
        assertEquals("99 point 5 per cent", spoken("99.5 per cent", "en-gb"))
        // The reading is unchanged for a fraction espeak already handled — the
        // rule removes the punctuation role, it does not add the word.
        assertEquals("3 point 14159 is pi", spoken("3.14159 is pi", "en-us"))
    }

    @Test
    fun `an ordinal suffix is never a unit`() {
        // "st" (stone) must not bite the ordinal suffix the date rule produces.
        assertEquals("the 21st of May", spoken("the 21st of May", "en-us"))
        assertEquals("her 1st novel", spoken("her 1st novel", "en-us"))
        // …while the spaced unit still expands.
        assertEquals("weighed 8 stone 5 pounds", spoken("weighed 8 st 5 lb", "en-us"))
    }

    @Test
    fun `a unit never fires without a figure`() {
        // "W." is a name initial here, not watts: the unit rules require a
        // preceding digit, which is what keeps them safe in prose.
        assertEquals("W. Somerset Maugham wrote it.", spoken("W. Somerset Maugham wrote it.", "en-us"))
        assertEquals("the m in the word", spoken("the m in the word", "en-us"))
    }

    @Test
    fun `Spanish units expand, thousands drop and the decimal comma is spoken`() {
        assertEquals(
            "La sala medía 12 pies por 8 metros; pesaba 2 coma 5 kilogramos y cabían 3 litros.",
            spoken("La sala medía 12 pies por 8 m; pesaba 2,5 kg y cabían 3 l.", "es"),
        )
        assertEquals("Costaba 1234 coma 56 $", spoken("Costaba 1.234,56 $", "es"))
    }

    @Test
    fun `French units expand including the rate form`() {
        assertEquals(
            "Roulez 5 km au nord à 90 kilomètres par heure par 36 degrés Celsius à l'ombre.",
            spoken("Roulez 5 km au nord à 90 km/h par 36 °C à l'ombre.", "fr-fr"),
        )
    }

    @Test
    fun `Italian units expand and the decimal comma is spoken`() {
        assertEquals(
            "La stanza era 12 piedi per 8 metri; pesava 2 virgola 5 chilogrammi e conteneva 3 litri.",
            spoken("La stanza era 12 piedi per 8 m; pesava 2,5 kg e conteneva 3 l.", "it"),
        )
    }

    @Test
    fun `Portuguese units expand and the clock form loses its letter`() {
        assertEquals(
            "A sala tinha 12 pés por 8 metros; pesava 2 vírgula 5 quilogramas e cabiam 3 litros.",
            spoken("A sala tinha 12 pés por 8 m; pesava 2,5 kg e cabiam 3 l.", "pt-br"),
        )
        assertEquals(
            "por volta das 9 e 45",
            spoken("por volta das 9h45", "pt-br"),
        )
        assertEquals("às 8 horas da manhã", spoken("às 8h da manhã", "pt-br"))
    }

    // ------------------------------------------------------------------
    // Signs, hyphen ranges, footnote markers, decades (G0 number/date/footnote)
    // ------------------------------------------------------------------

    @Test
    fun `the minus sign gets a native word instead of the English loanword`() {
        assertEquals(
            "La temperatura bajó a menos 5 grados",
            spoken("La temperatura bajó a −5 grados", "es"),
        )
        assertEquals(
            "La température est tombée à moins 5 degrés",
            spoken("La température est tombée à −5 degrés", "fr-fr"),
        )
        // pt dropped the sign entirely before this rule.
        assertEquals("A temperatura caiu a menos 5 graus", spoken("A temperatura caiu a −5 graus", "pt-br"))
        // English already says "minus", and a range hyphen is not a minus.
        assertEquals("hit -40 degrees", spoken("hit -40 degrees", "en-us"))
        assertEquals("3 to 1", spoken("3 to 1", "es"))
    }

    @Test
    fun `an English hyphen between figures becomes the range word`() {
        assertEquals(
            "The match ended 2 to 1 before 76,212 fans.",
            spoken("The match ended 2-1 before 76,212 fans.", "en-gb"),
        )
        assertEquals("lines 8 to 19", spoken("lines 8-19", "en-us"))
        // A hyphen inside a word is not a range.
        assertEquals("a well-known writer", spoken("a well-known writer", "en-us"))
    }

    @Test
    fun `footnote reference markers are stripped in every language`() {
        assertEquals(
            "The claim is disputed. Other scholars disagree.",
            spoken("The claim is disputed.² Other scholars disagree.³", "en-us"),
        )
        // "note 1" is content; only the bracketed marker is furniture.
        assertEquals("See note 1 for details.", spoken("See note 1 for details.[3]", "en-us"))
        assertEquals("Como se ve en el texto.", spoken("Como se ve en el texto.¹", "es"))
        assertEquals("veja o texto.", spoken("veja o texto.²", "pt-br"))
    }

    @Test
    fun `decades are named instead of read with a dangling s`() {
        assertEquals(
            "The eighteen hundreds, the nineties, and 2024 C.E.",
            spoken("The 1800s, the '90s, and 2024 C.E.", "en-us"),
        )
        assertEquals("the nineteen-tens", spoken("the 1910s", "en-gb"))
        assertEquals("the twenty hundreds", spoken("the 2000s", "en-us"))
        // An unmapped century is left alone rather than guessed.
        assertEquals("the 1500s", spoken("the 1500s", "en-us"))
    }

    // ------------------------------------------------------------------
    // Roman numerals and currencies (G0 roman-numeral/currency rows)
    // ------------------------------------------------------------------

    @Test
    fun `roman numerals lose espeak's "roman" label in English and French`() {
        assertEquals(
            "Chapter 4 begins at line 42; King Henry 8 ruled.",
            spoken("Chapter IV begins at line xlii; King Henry VIII ruled.", "en-us"),
        )
        assertEquals(
            "Louis 14 met Elizabeth 2; Pope Pius 12 watched.",
            spoken("Louis XIV met Elizabeth II; Pope Pius XII watched.", "en-us"),
        )
        assertEquals(
            "Le chapitre 4 commence ; Louis 14 a régné.",
            spoken("Le chapitre IV commence ; Louis XIV a régné.", "fr-fr"),
        )
    }

    @Test
    fun `the English pronoun I and stray letters are never turned into numbers`() {
        // The name branch requires two or more glyphs precisely for this.
        assertEquals("So I went home.", spoken("So I went home.", "en-us"))
        assertEquals("She met Mr V.", spoken("She met Mr V.", "en-us"))
        assertEquals("a mix of X and Y", spoken("a mix of X and Y", "en-us"))
    }

    @Test
    fun `Spanish and Portuguese regnal numerals read ordinally up to ten`() {
        assertEquals("Isabel segunda", spoken("Isabel II", "es"))
        assertEquals("el rey Felipe sexto", spoken("el rey Felipe VI", "es"))
        // XI and above stay cardinal — the owner's boundary.
        assertEquals("Luis XIV", spoken("Luis XIV", "es"))
        assertEquals("dom Pedro segundo governou", spoken("D. Pedro II governou", "pt-br"), "honorific + regnal compose")
        assertEquals("Isabel segunda", spoken("Isabel II", "pt-br"))
        assertEquals("Luís XIV", spoken("Luís XIV", "pt-br"))
        // es/it keep their own conventions for structural contexts (no rule).
        assertEquals("capítulo IV", spoken("capítulo IV", "es"))
    }

    @Test
    fun `English money is spoken with the currency after the amount`() {
        assertEquals(
            "It cost 1,234 dollars and 56 cents, or 99 euros and 95 cents, or 50 pounds.",
            spoken("It cost $1,234.56, or €99.95, or £50.", "en-us"),
        )
        assertEquals(
            "Prices rose from 10,000 yen to 12,500 yen, and 750 rupees became 900 rupees.",
            spoken("Prices rose from ¥10,000 to ¥12,500, and ₹750 became ₹900.", "en-us"),
        )
        // A ".00" tail is dropped, not spoken as "point zero zero".
        assertEquals("on a 45 dollars bill", spoken("on a $45.00 bill", "en-us"))
        assertEquals("saved 20 cents", spoken("saved 20¢", "en-us"))
        assertEquals("cost 45 pence", spoken("cost 45p", "en-gb"))
    }

    @Test
    fun `Portuguese R$ is spoken as reais, never as real dolar`() {
        assertEquals("Gorjeta sobre 45 reais", spoken("Gorjeta sobre R$ 45,00", "pt-br"))
        assertEquals("1 real", spoken("R$ 1,00", "pt-br"), "the digit keeps espeak's own \"um\"")
        // The thousands separator is stripped by the rule that follows, so the
        // amount reaches the phonemizer as plain digits (read as words by espeak).
        assertEquals(
            "subiu de 1234 reais e 56 centavos para 1400 reais",
            spoken("subiu de R$ 1.234,56 para R$ 1.400,00", "pt-br"),
        )
    }

    // ------------------------------------------------------------------
    // Dates (G0 date-slash-read-aloud)
    // ------------------------------------------------------------------

    @Test
    fun `slash-dates are spoken as dates in every locale`() {
        // Each expectation is the corpus row's own spelled-out half: the corpus
        // already measured the render of these exact strings, so matching them is
        // matching the target audio, not a guess.
        assertEquals(
            "On March 4th, 2024, or March 4th 2024, they met; it was the 21st of May.",
            spoken("On 3/4/2024, or March 4th 2024, they met; it was the 21st of May.", "en-us"),
        )
        assertEquals(
            "On 4 March 2024, or 4 March 2024, they met; it was the 21st of May.",
            spoken("On 3/4/2024, or 4 March 2024, they met; it was the 21st of May.", "en-gb"),
        )
        assertEquals(
            "El 4 de marzo de 2024, o el 4 de marzo de 2024, se vieron; era el 21 de mayo.",
            spoken("El 3/4/2024, o el 4 de marzo de 2024, se vieron; era el 21 de mayo.", "es"),
        )
        assertEquals(
            "Le 4 mars 2024, ou le 4 mars 2024, ils se sont vus ; c'était le 21 mai.",
            spoken("Le 3/4/2024, ou le 4 mars 2024, ils se sont vus ; c'était le 21 mai.", "fr-fr"),
        )
        assertEquals(
            "Il 4 marzo 2024, ovvero il 4 marzo 2024, si incontrarono; era il 21 maggio.",
            spoken("Il 3/4/2024, ovvero il 4 marzo 2024, si incontrarono; era il 21 maggio.", "it"),
        )
        assertEquals(
            "Em 4 de março de 2024, ou 4 de março de 2024, se encontraram; era 21 de maio.",
            spoken("Em 03/04/2024, ou 4 de março de 2024, se encontraram; era 21 de maio.", "pt-br"),
        )
    }

    @Test
    fun `the day-month form needs a date preposition`() {
        // Corpus rows 0103/0143/0185/0226: "el 25/12" is a date…
        assertEquals("el 25 de diciembre", spoken("el 25/12", "es"))
        assertEquals("le 21 mai", spoken("le 21/5", "fr-fr"))
        assertEquals("il 25 dicembre", spoken("il 25/12", "it"))
        assertEquals("em 25 de dezembro", spoken("em 25/12", "pt-br"))
        // …and a bare pair with no date word is never a date (it is a fraction,
        // see the fraction tests below).
        assertEquals("add three fourths of a cup", spoken("add 3/4 cup", "en-us"))
        assertEquals("won 2/1", spoken("won 2/1", "en-us"))
        assertEquals("una fracción tres cuartos", spoken("una fracción 3/4", "es"))
    }

    @Test
    fun `a figure that cannot be a month settles the order`() {
        // The corpus writes slash-dates month-first, and 25 cannot be a month, so
        // the swap is arithmetic rather than convention.
        assertEquals("el 25 de diciembre", spoken("el 25/12", "es"))
        assertEquals("il 25 dicembre", spoken("il 25/12", "it"))
        // A month-name-free impossibility stays untouched.
        assertEquals("el 32/13", spoken("el 32/13", "es"))
    }

    @Test
    fun `a four-digit year needs no context word`() {
        assertEquals("By June 7th, 2000, the edition was due.", spoken("By 06/07/2000, the edition was due.", "en-us"))
        assertEquals("By 7 June 2000, the edition was due.", spoken("By 06/07/2000, the edition was due.", "en-gb"))
    }

    // ------------------------------------------------------------------
    // Scoping
    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // Fractions (owner-reported 2026-09-15)
    // ------------------------------------------------------------------

    @Test
    fun `fractions are spoken as fractions, never spelled as a slash`() {
        // The owner's own example, verbatim.
        assertEquals(
            "Add three fourths of a cup of sugar.",
            spoken("Add 3/4 cup of sugar.", "en-us"),
        )
        assertEquals("half a cup", spoken("1/2 cup", "en-us"))
        assertEquals("two thirds of a liter", spoken("2/3 L", "en-us"))
        assertEquals("three fourths", spoken("3/4", "en-us"))
        // A plural measure noun becomes singular after "of a".
        assertEquals("five eighths of a mile", spoken("5/8 miles", "en-us"))
    }

    @Test
    fun `an improper pair stays a ratio or a score`() {
        assertEquals("win 2/1", spoken("win 2/1", "en-us"))
        assertEquals("a 5/2 favourite", spoken("a 5/2 favourite", "en-us"))
        assertEquals("odds 3/3", spoken("odds 3/3", "en-us"))
    }

    @Test
    fun `a pair that already carries the partitive keeps it`() {
        // One "of", not "of a of a".
        assertEquals("three fourths of a cup", spoken("3/4 of a cup", "en-us"))
        assertEquals("tres cuartos de la poblacion", spoken("3/4 de la poblacion", "es"))
    }

    @Test
    fun `fractions are spoken in the Romance languages with invariant halves`() {
        assertEquals("tres cuartos de taza", spoken("3/4 taza", "es"))
        assertEquals("la mitad de taza", spoken("1/2 taza", "es"))
        assertEquals("trois quarts de tasse", spoken("3/4 tasse", "fr-fr"))
        assertEquals("tre quarti di tazza", spoken("3/4 tazza", "it"))
        assertEquals("tr\u00eas quartos de x\u00edcara", spoken("3/4 x\u00edcara", "pt-br"))
    }

    @Test
    fun `a fraction is never read as a date or a path`() {
        // The date list still owns the slash-dates, in every locale.
        assertEquals("On March 4th, 2024.", spoken("On 3/4/2024.", "en-us"))
        assertEquals("el 4 de marzo de 2024", spoken("el 3/4/2024", "es"))
        // A slash inside a path-like token is not a fraction.
        assertEquals("see docs/3/4 notes", spoken("see docs/3/4 notes", "en-us"))
    }

    // ------------------------------------------------------------------
    // Clause-boundary pauses (inserted punctuation; owner-approved mechanism)
    // ------------------------------------------------------------------

    @Test
    fun `a closing quote before its attribution becomes a sentence break`() {
        // The comma is there but reads too short (269 ms against a period's 429
        // measured on the shipped espeak). Both comma conventions are covered: the
        // English comma-inside form and the es/fr/it/pt comma-outside form.
        assertEquals(
            "\"Mind the gap.\" he said. \"Mind it... every single time.\"",
            spoken("\"Mind the gap,\" he said. \"Mind it... every single time.\"", "en-us"),
        )
        assertEquals("«Cuidado.» dijo él.", spoken("«Cuidado», dijo él.", "es"))
        assertEquals("« Attention. » dit-il.", spoken("« Attention », dit-il.", "fr-fr"))
        assertEquals("«Attento.» disse lui.", spoken("«Attento», disse lui.", "it"))
        assertEquals("\"Cuidado.\" disse ele.", spoken("\"Cuidado\", disse ele.", "pt-br"))
        assertEquals("\u201cCuidado.\u201d dijo \u00e9l.", spoken("\u201cCuidado,\u201d dijo \u00e9l.", "es"))
        assertEquals("\u201cCuidado.\u201d dijo \u00e9l.", spoken("\u201cCuidado\u201d, dijo \u00e9l.", "es"))
    }

    @Test
    fun `an opening quote after a comma is never touched`() {
        // The ASCII quote is ambiguous by itself, so the rule matches the whole
        // quoted span: "he said, "X."" has no comma before its closing quote.
        assertEquals("he said, \"Mind the gap.\"", spoken("he said, \"Mind the gap.\"", "en-us"))
    }

    @Test
    fun `a short question takes a beat before the next clause`() {
        assertEquals(
            "One. Two... three? — No — four. Four, four, four.",
            spoken("One. Two... three? No — four. Four, four, four.", "en-us"),
        )
        assertEquals("Uno. Dos... ¿tres? — No — cuatro.", spoken("Uno. Dos... ¿tres? No — cuatro.", "es"))
        // A quoted, multi-word question keeps its attribution untouched.
        assertEquals("\"Will you?\" she replied.", spoken("\"Will you?\" she replied.", "en-us"))
    }

    @Test
    fun `a heading's colon becomes a sentence break before its title`() {
        assertEquals("CHAPTER ONE. An Unexpected Party", spoken("CHAPTER ONE: An Unexpected Party", "en-us"))
        assertEquals("PART TWO. The Road Goes Ever On", spoken("PART TWO — The Road Goes Ever On", "en-us"))
        assertEquals("CAPÍTULO UNO. Una fiesta inesperada", spoken("CAPÍTULO UNO: Una fiesta inesperada", "es"))
        assertEquals("Annexe A. Guide de prononciation", spoken("Annexe A : Guide de prononciation", "fr-fr"))
        assertEquals("PARTE SEGUNDA. A estrada continua", spoken("PARTE SEGUNDA — A estrada continua", "pt-br"))
    }

    @Test
    fun `a colon inside a sentence keeps its meaning`() {
        // A heading is title-shaped: every word before the mark starts uppercase,
        // and the line opens with a structural word. Everything else is prose.
        assertEquals(
            "Compare appendix B: the figure shows volume 2, number 3.",
            spoken("Compare appendix B: the figure shows volume 2, number 3.", "en-us"),
        )
        assertEquals(
            "compare appendix B: the figure shows volume 2, number 3.",
            spoken("compare appendix B: the figure shows volume 2, number 3.", "en-us"),
        )
        assertEquals("He said: I will be there.", spoken("He said: I will be there.", "en-us"))
    }

    @Test
    fun `a rule never fires in another language`() {
        // "Prof." is a Spanish word only in es; the same token in en keeps its
        // own expansion, and an unknown language is left completely alone.
        assertEquals("profesor Salas", spoken("Prof. Salas", "es"))
        assertEquals("professor Salas", spoken("Prof. Salas", "en-us"))
        assertEquals("Prof. Salas", spoken("Prof. Salas", "de"))
        assertEquals("Sr. Oliveira", spoken("Sr. Oliveira", "en-us"))
    }

    /**
     * Android's regex engine (ICU) rejects a LOOKBEHIND of unbounded length —
     * `(?<!\p{Lu}\p{Ll}+\s)` compiles on the host JVM and throws
     * `PatternSyntaxException` the moment the class loads on the device, which is
     * how the S22 listening harness found it (2026-09-15). A host test cannot
     * observe that directly, so this asserts the property that makes it safe:
     * every lookbehind in every rule is bounded.
     */
    @Test
    fun `no rule uses an unbounded lookbehind, which Android's engine rejects`() {
        val patterns = PronunciationNormalizer.patternSources
        assertEquals(true, patterns.isNotEmpty(), "the rule set should not be empty")

        val offenders =
            patterns.filter { pattern ->
                var i = 0
                var found = false
                while (i < pattern.length) {
                    val start = pattern.indexOf("(?<", i)
                    if (start < 0) break
                    val isLookbehind = pattern.startsWith("(?<!", start) || pattern.startsWith("(?<=", start)
                    val close = pattern.indexOf(')', start)
                    if (!isLookbehind || close < 0) {
                        i = start + 3
                        continue
                    }
                    val body = pattern.substring(start, close)
                    if (body.contains("+") || body.contains("*") || Regex("\\{\\d+,}").containsMatchIn(body)) {
                        found = true
                    }
                    i = close
                }
                found
            }

        assertEquals(
            emptyList<String>(),
            offenders,
            "unbounded lookbehind (ICU rejects these on Android): $offenders",
        )
    }
}
