package com.moronigranja.localttsreader.tts.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The read-in-language target surface (decisions #161/#162): app code → the
 * language name the model is prompted with. An unmapped code must return null
 * — a guess would silently translate into the wrong language (the picker's
 * degrade row reports "language <x> not supported" instead).
 */
class LfmLangTest {
    @Test
    fun appCodesMapToPromptNames() {
        assertEquals("English", LfmLang.toPromptLanguage("en"))
        assertEquals("Spanish", LfmLang.toPromptLanguage("es"))
        assertEquals("French", LfmLang.toPromptLanguage("fr"))
        assertEquals("German", LfmLang.toPromptLanguage("DE"))
        assertEquals("Italian", LfmLang.toPromptLanguage("it"))
        assertEquals("Japanese", LfmLang.toPromptLanguage("ja"))
        assertEquals("Hindi", LfmLang.toPromptLanguage("hi"))
        assertEquals("Chinese", LfmLang.toPromptLanguage("zh"))
    }

    @Test
    fun brazilianPortugueseIsTheMeasuredGateString() {
        // Verbatim from lfm12_gate.py — the only target the 67.37 chrF number
        // was measured against; changing it invalidates that claim.
        assertEquals("Brazilian Portuguese", LfmLang.toPromptLanguage("pt-BR"))
        assertEquals("Brazilian Portuguese", LfmLang.toPromptLanguage("pt_BR"))
        assertEquals("Brazilian Portuguese", LfmLang.toPromptLanguage("PT-br"))
    }

    @Test
    fun unmappedTargetsAreNeverGuessed() {
        assertNull(LfmLang.toPromptLanguage("pt")) // bare pt: not the measured pair
        assertNull(LfmLang.toPromptLanguage("pt-PT"))
        assertNull(LfmLang.toPromptLanguage("ko")) // model supports it, the app offers no voice
        assertNull(LfmLang.toPromptLanguage("xx"))
        assertNull(LfmLang.toPromptLanguage(""))
    }

    @Test
    fun everyOfferedAppCodeResolves() {
        for (code in LfmLang.APP_CODES) {
            assertEquals(true, LfmLang.toPromptLanguage(code) != null, "$code must resolve")
        }
        assertEquals(9, LfmLang.APP_CODES.size)
    }
}
