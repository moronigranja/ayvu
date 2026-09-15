package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
}
