package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NormalizingPhonemizerTest {
    @Test
    fun `delegates with the normalized text and language`() {
        val fake = RecordingPhonemizer()
        val normalizing = NormalizingPhonemizer(fake)
        assertEquals("phonemes(Miz X)", normalizing.phonemize("Ms. X", "en-us"))
        assertEquals(listOf("Miz X" to "en-us"), fake.calls)
    }

    @Test
    fun `supportedLanguages comes from the delegate`() {
        val fake = RecordingPhonemizer()
        assertEquals(fake.supportedLanguages(), NormalizingPhonemizer(fake).supportedLanguages())
    }

    private class RecordingPhonemizer : Phonemizer {
        val calls = mutableListOf<Pair<String, String>>()
        private val languages = setOf("en-us", "en-gb")

        override fun phonemize(
            text: String,
            language: String,
        ): String {
            calls += text to language
            return "phonemes($text)"
        }

        override fun supportedLanguages(): Set<String> = languages
    }
}
