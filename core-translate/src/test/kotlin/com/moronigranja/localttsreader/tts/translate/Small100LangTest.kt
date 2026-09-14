package com.moronigranja.localttsreader.tts.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Small100LangTest {
    @Test
    fun appCodesMapToModelCodes() {
        assertEquals("pt", Small100Lang.toSmall100("pt-BR"))
        assertEquals("pt", Small100Lang.toSmall100("pt"))
        assertEquals("zh", Small100Lang.toSmall100("zh"))
        assertEquals("en", Small100Lang.toSmall100("en"))
        assertEquals("de", Small100Lang.toSmall100("DE"))
        assertEquals("fr", Small100Lang.toSmall100("fr"))
        assertNull(Small100Lang.toSmall100("xx"))
        assertNull(Small100Lang.toSmall100("pt-PT"))
        assertNull(Small100Lang.toSmall100(""))
    }

    @Test
    fun langTokensFollowThePinnedRule() {
        assertEquals("__pt__", Small100Lang.langToken("pt"))
        assertEquals("__en__", Small100Lang.langToken("en"))
    }

    @Test
    fun allModelCodesHaveTokens() {
        assertEquals(100, Small100Lang.M2M_CODES.size)
        for (code in Small100Lang.M2M_CODES) {
            assertEquals("__${code}__", Small100Lang.langToken(code))
        }
    }
}
