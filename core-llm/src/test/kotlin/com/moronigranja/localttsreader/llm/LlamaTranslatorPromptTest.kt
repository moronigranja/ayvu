package com.moronigranja.localttsreader.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [LlamaTranslator.userMessage]'s prompt shape, pinned on the JVM. The wrapper
 * never composes prompts at the call site, so this string IS the model contract:
 * verbatim the message the measured gate sent (`docs/prints/beam-spike/
 * lfm12_gate.py:8-11`) with the target language name substituted, and the same
 * shape `LfmLang` documents for the read-in picker.
 *
 * Pure by construction — no model file, no [LlamaTranslator.open], therefore no
 * `libayvu_llm`: these assertions need nothing but the Kotlin function.
 */
class LlamaTranslatorPromptTest {
    @Test
    fun `the measured Brazilian Portuguese prompt shape`() {
        assertEquals(
            "Translate to Brazilian Portuguese, reply only with the translation:\n\nIt's a fine day to walk to the river.",
            LlamaTranslator.userMessage("It's a fine day to walk to the river.", "Brazilian Portuguese"),
        )
    }

    @Test
    fun `the target language name is substituted`() {
        assertEquals(
            "Translate to Japanese, reply only with the translation:\n\nGood morning.",
            LlamaTranslator.userMessage("Good morning.", "Japanese"),
        )
        assertEquals(
            "Translate to English, reply only with the translation:\n\nGood morning.",
            LlamaTranslator.userMessage("Good morning.", "English"),
        )
    }

    @Test
    fun `the passage is embedded verbatim`() {
        val passage = "— Isn't it? \"Yes,\" said Anna; «peut-être»; 100% — done."

        assertEquals(
            "Translate to Brazilian Portuguese, reply only with the translation:\n\n$passage",
            LlamaTranslator.userMessage(passage, "Brazilian Portuguese"),
        )
    }

    @Test
    fun `an empty or blank passage renders an empty message body`() {
        assertEquals(
            "Translate to Spanish, reply only with the translation:\n\n",
            LlamaTranslator.userMessage("", "Spanish"),
        )
        assertEquals(
            "Translate to Spanish, reply only with the translation:\n\n   \n ",
            LlamaTranslator.userMessage("   \n ", "Spanish"),
        )
    }
}
