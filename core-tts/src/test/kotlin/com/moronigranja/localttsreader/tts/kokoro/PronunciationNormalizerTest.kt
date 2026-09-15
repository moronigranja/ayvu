package com.moronigranja.localttsreader.tts.kokoro

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
        assertEquals("dom Pedro II governou", spoken("D. Pedro II governou", "pt-br"))
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
    // Scoping
    // ------------------------------------------------------------------

    @Test
    fun `a rule never fires in another language`() {
        // "Prof." is a Spanish word only in es; the same token in en keeps its
        // own expansion, and an unknown language is left completely alone.
        assertEquals("profesor Salas", spoken("Prof. Salas", "es"))
        assertEquals("professor Salas", spoken("Prof. Salas", "en-us"))
        assertEquals("Prof. Salas", spoken("Prof. Salas", "de"))
        assertEquals("Sr. Oliveira", spoken("Sr. Oliveira", "en-us"))
    }
}
