package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PronunciationNormalizerTest {
    @Test
    fun `honorific Ms with period is rewritten to Miz`() {
        assertEquals(
            "Miz Dalloway went to the store.",
            PronunciationNormalizer.forSpeech("Ms. Dalloway went to the store."),
        )
    }

    @Test
    fun `Ms at the string start is rewritten`() {
        assertEquals("Miz is a title", PronunciationNormalizer.forSpeech("Ms. is a title"))
    }

    @Test
    fun `Ms at the string end is rewritten`() {
        assertEquals("…and Miz", PronunciationNormalizer.forSpeech("…and Ms."))
    }

    @Test
    fun `Ms after a number and space is rewritten`() {
        assertEquals("1250 Miz Jones", PronunciationNormalizer.forSpeech("1250 Ms. Jones"))
    }

    @Test
    fun `every Ms in a sentence is rewritten`() {
        assertEquals(
            "Miz Dalloway, Miz Ramsay.",
            PronunciationNormalizer.forSpeech("Ms. Dalloway, Ms. Ramsay."),
        )
    }

    @Test
    fun `units with ms period are untouched`() {
        assertEquals("500ms. latency", PronunciationNormalizer.forSpeech("500ms. latency"))
    }

    @Test
    fun `MS without a period is untouched`() {
        assertEquals("MS Word", PronunciationNormalizer.forSpeech("MS Word"))
    }

    @Test
    fun `Mrs is untouched`() {
        assertEquals("Mrs. Smith", PronunciationNormalizer.forSpeech("Mrs. Smith"))
    }

    @Test
    fun `lowercase ms is untouched`() {
        assertEquals("ms. lowercase", PronunciationNormalizer.forSpeech("ms. lowercase"))
    }
}
