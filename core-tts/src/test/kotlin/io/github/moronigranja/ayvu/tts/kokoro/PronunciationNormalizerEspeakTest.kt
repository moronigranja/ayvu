package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Host-verified ground truth: espeak-ng 1.52.0 pronounces "Ms." as ˌɛmˈɛs
 * ("M S") while "Miz" renders mˈɪz. The decorator must move the spoken form
 * to the idiomatic mɪz. Skipped when espeak-ng is not installed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PronunciationNormalizerEspeakTest {
    private lateinit var phonemizer: EspeakPhonemizer

    @BeforeAll
    fun setUp() {
        phonemizer =
            try {
                EspeakPhonemizer.load()
            } catch (e: Throwable) {
                assumeTrue(false, "espeak-ng not available: ${e.message}")
                throw e
            }
        assumeTrue("en-us" in phonemizer.supportedLanguages(), "espeak-ng voices missing")
    }

    @Test
    fun `Ms is spoken as miz, not spelled out M S`() {
        val normalized = NormalizingPhonemizer(phonemizer)
        assertEquals(
            phonemizer.phonemize("Miz Dalloway said.", "en-us"),
            normalized.phonemize("Ms. Dalloway said.", "en-us"),
        )
        assertNotEquals(
            phonemizer.phonemize("Ms. Dalloway said.", "en-us"),
            normalized.phonemize("Ms. Dalloway said.", "en-us"),
        )
    }

    /**
     * The G1 abbreviation class, verified through the REAL espeak-ng: the
     * normalized render of a corpus row must equal the render of its expected
     * spoken form. This is what keeps the dictionary honest — a wrong expansion
     * word fails here even when the pure transform looks right.
     *
     * Rows are the G0 corpus entries (docs/g0-findings.md); a language whose
     * voice is missing is skipped by the shared assumption.
     */
    @Test
    fun `every language's honorific row renders as its spoken form`() {
        val normalized = NormalizingPhonemizer(phonemizer)
        val cases =
            listOf(
                "en-us" to
                    (
                        "Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon." to
                            "Miz Dalloway, doctor Watson, and professor Higgins arrived at noon."
                    ),
                "en-us" to
                    (
                        "Rev. King, Capt. Ahab, and Gen. Lee signed the letter." to
                            "reverend King, captain Ahab, and general Lee signed the letter."
                    ),
                "es" to
                    (
                        "Sra. Delgado, Dr. Vidal y Srta. Rivas llegaron al mediodía." to
                            "señora Delgado, doctor Vidal y señorita Rivas llegaron al mediodía."
                    ),
                "es" to
                    (
                        "El Sr. Ortega preguntó a la Prof. Salas por el Dr. García." to
                            "El señor Ortega preguntó a la profesora Salas por el doctor García."
                    ),
                "fr-fr" to
                    (
                        "M. Higgins et le Prof. Lefèvre arrivèrent à midi." to
                            "monsieur Higgins et le professeur Lefèvre arrivèrent à midi."
                    ),
                "it" to
                    (
                        "Sig.ra Dalloway, Dott. Watson e Prof. Higgins arrivarono." to
                            "signora Dalloway, dottore Watson e professore Higgins arrivarono."
                    ),
                "it" to
                    (
                        "Il Sig. Verdi chiese alla Dott.ssa Bianchi notizie dell'Avv. Rossi." to
                            "Il signor Verdi chiese alla dottoressa Bianchi notizie dell'avvocato Rossi."
                    ),
                "pt-br" to
                    (
                        "Sra. Dalloway, Dr. Watson e Prof. Higgins chegaram ao meio-dia." to
                            "senhora Dalloway, doutor Watson e professor Higgins chegaram ao meio-dia."
                    ),
                "pt-br" to
                    (
                        "O Sr. Oliveira perguntou à Profa. Costa pelo Dr. Barbosa." to
                            "O senhor Oliveira perguntou à professora Costa pelo doutor Barbosa."
                    ),
                // Units and digit separators (G0 measurement/number rows).
                "en-us" to
                    (
                        "The room was 12 ft by 8 m; it weighed 2.5 kg and held 3 L." to
                            "The room was 12 feet by 8 meters; it weighed 2 point 5 kilograms and held 3 liters."
                    ),
                "en-gb" to
                    (
                        "He scored 99.5 per cent and lost 8 of 9 frames." to
                            "He scored 99 point 5 per cent and lost 8 of 9 frames."
                    ),
                "es" to
                    (
                        "La sala medía 12 pies por 8 m; pesaba 2,5 kg y cabían 3 l." to
                            "La sala medía 12 pies por 8 metros; pesaba 2 coma 5 kilogramos y cabían 3 litros."
                    ),
                "fr-fr" to
                    (
                        "Roulez 5 km au nord à 90 km/h par 36 °C à l'ombre." to
                            "Roulez 5 km au nord à 90 kilomètres par heure par 36 degrés Celsius à l'ombre."
                    ),
                "it" to
                    (
                        "La stanza era 12 piedi per 8 m; pesava 2,5 kg e conteneva 3 l." to
                            "La stanza era 12 piedi per 8 metri; pesava 2 virgola 5 chilogrammi e conteneva 3 litri."
                    ),
                "pt-br" to
                    (
                        "A sala tinha 12 pés por 8 m; pesava 2,5 kg e cabiam 3 l." to
                            "A sala tinha 12 pés por 8 metros; pesava 2 vírgula 5 quilogramas e cabiam 3 litros."
                    ),
                // Signs, ranges, footnotes, decades (G0 number/date/footnote rows).
                "es" to
                    (
                        "La temperatura bajó a −5 grados y subió 2,5 grados hacia las 9:45." to
                            "La temperatura bajó a menos 5 grados y subió 2 coma 5 grados hacia las 9:45."
                    ),
                "fr-fr" to
                    (
                        "La température est tombée à −5 degrés, puis montée de 2,5 degrés." to
                            "La température est tombée à moins 5 degrés, puis montée de 2 virgule 5 degrés."
                    ),
                "pt-br" to
                    (
                        "A temperatura caiu a −5 graus e subiu 2,5 graus por volta das 9h45." to
                            "A temperatura caiu a menos 5 graus e subiu 2 vírgula 5 graus por volta das 9 e 45."
                    ),
                "en-gb" to
                    (
                        "The match ended 2-1 before 76,212 fans; a 4th straight win." to
                            "The match ended 2 to 1 before 76,212 fans; a 4th straight win."
                    ),
                "en-us" to
                    (
                        "The 1800s, the '90s, and 2024 C.E. were all busy." to
                            "The eighteen hundreds, the nineties, and 2024 C.E. were all busy."
                    ),
                "en-us" to
                    (
                        "The claim is disputed.² Other scholars disagree.³" to
                            "The claim is disputed. Other scholars disagree."
                    ),
                // Dates: the corpus already measured the render of each row's
                // spelled-out half, so these expectations are the corpus's own
                // reference audio, not an interpretation of it.
                "en-us" to
                    (
                        "On 3/4/2024, or March 4th 2024, they met; it was the 21st of May." to
                            "On March 4th, 2024, or March 4th 2024, they met; it was the 21st of May."
                    ),
                "en-gb" to
                    (
                        "On 3/4/2024, or 4 March 2024, they met; it was the 21st of May." to
                            "On 4 March 2024, or 4 March 2024, they met; it was the 21st of May."
                    ),
                "es" to
                    (
                        "El 3/4/2024, o el 4 de marzo de 2024, se vieron; era el 21 de mayo." to
                            "El 4 de marzo de 2024, o el 4 de marzo de 2024, se vieron; era el 21 de mayo."
                    ),
                "fr-fr" to
                    (
                        "Le 3/4/2024, ou le 4 mars 2024, ils se sont vus ; c'était le 21 mai." to
                            "Le 4 mars 2024, ou le 4 mars 2024, ils se sont vus ; c'était le 21 mai."
                    ),
                "it" to
                    (
                        "Il 3/4/2024, ovvero il 4 marzo 2024, si incontrarono; era il 21 maggio." to
                            "Il 4 marzo 2024, ovvero il 4 marzo 2024, si incontrarono; era il 21 maggio."
                    ),
                "pt-br" to
                    (
                        "Em 03/04/2024, ou 4 de março de 2024, se encontraram; era 21 de maio." to
                            "Em 4 de março de 2024, ou 4 de março de 2024, se encontraram; era 21 de maio."
                    ),
            )

        for ((language, pair) in cases) {
            val (raw, expectedSpoken) = pair
            if (language !in phonemizer.supportedLanguages()) continue
            assertEquals(
                phonemizer.phonemize(expectedSpoken, language),
                normalized.phonemize(raw, language),
                "$language: ${raw.take(40)}… must render as its spoken form",
            )
        }
    }

    /**
     * The roman-numeral defect WAS espeak's label, so the check is its absence:
     * the numeral never reaches the engine as a glyph, so it cannot be prefixed
     * with "roman"/"romain". Asserted before AND after, so the test would notice
     * if the upstream behaviour changed and the rule became a no-op.
     */
    @Test
    fun `the roman label is absent from the render in en and fr`() {
        val normalized = NormalizingPhonemizer(phonemizer)
        val cases =
            listOf(
                Triple("en-us", "Chapter IV begins.", "ɹˌoʊmən"),
                Triple("en-us", "King Henry VIII ruled.", "ɹˌoʊmən"),
                Triple("fr-fr", "Le chapitre IV commence.", "ʁomˈɛ̃"),
            )
        for ((language, text, label) in cases) {
            if (language !in phonemizer.supportedLanguages()) continue
            assertTrue(
                phonemizer.phonemize(text, language).contains(label),
                "$language: before the rule the label is present ($label)",
            )
            assertTrue(
                !normalized.phonemize(text, language).contains(label),
                "$language: the normalized render must carry no label — was " +
                    "\"${normalized.phonemize(text, language)}\"",
            )
        }
    }
}
