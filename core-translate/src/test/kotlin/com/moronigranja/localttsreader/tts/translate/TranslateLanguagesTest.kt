package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Voice-catalog → target-language matching (decisions #114). The regression
 * case: langCode returns mixed-case `pt-BR` while the target normalizes to
 * lowercase — the ONLY langCode with an uppercase variant, so every other
 * language matched by accident and the flagship target silently degraded to
 * the original voice (S22 device pass 2026-09-14).
 */
class TranslateLanguagesTest {
    @Test
    fun `codes over the real kokoro catalog covers the voicable targets`() {
        val langs = TranslateLanguages.codes(KokoroVoiceMetadata.all)
        assertEquals(listOf("en", "es", "fr", "hi", "it", "ja", "pt-BR", "zh"), langs)
    }

    @Test
    fun `firstVoiceFor matches a mixed-case langCode against the normalized target`() {
        val voice = TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, "pt-BR")
        assertTrue(voice in listOf("pf_dora", "pm_alex", "pm_santa"), "pt-BR must resolve its voice, was $voice")
        // The lowercase target form resolves the same voice.
        assertEquals(voice, TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, "pt-br"))
        assertEquals(voice, TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, "pt_br"))
    }

    @Test
    fun `firstVoiceFor matches the bare base of a regional code`() {
        // en is a base of en-US: a catalog with only regional en rows still
        // serves the bare code.
        val en = TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, "en")
        assertTrue(en != null && en.startsWith("af_"), "en must resolve an en-US voice, was $en")
    }

    @Test
    fun `firstVoiceFor returns null for an unvoiced target`() {
        assertNull(TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, "ko"))
        assertNull(TranslateLanguages.firstVoiceFor(KokoroVoiceMetadata.all, ""))
    }
}
