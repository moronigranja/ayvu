package com.moronigranja.localttsreader.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroTokenizerTest {
    private val vocab = KokoroVocabulary.resource()

    @Test
    fun `packaged vocab matches the pinned model contract`() {
        // Pinned against the v1.1 export's embedded kokoro_config (T2) —
        // 114 single-char keys, 1-based ids, punctuation and stress marks.
        assertEquals(114, vocab.size)
        assertTrue(vocab.values.all { it > 0 })
        assertEquals(vocab.size, vocab.values.toSet().size, "ids must be unique")
        assertEquals(4, vocab['.'])
        assertEquals(3, vocab[','])
        assertEquals(16, vocab[' '])
        assertTrue('ˈ' in vocab)
        assertTrue('ː' in vocab)
    }

    @Test
    fun `tokenize maps known characters and drops unknowns`() {
        val tokenizer = KokoroTokenizer(vocab)
        // h, ə, l, ˈ, o, ʊ are all in the vocab; a control char is not.
        val ids = tokenizer.tokenize("həlˈoʊ")
        assertEquals(listOf(vocab['h'], vocab['ə'], vocab['l'], vocab['ˈ'], vocab['o'], vocab['ʊ']), ids.toList())
        val partial = tokenizer.tokenize("h\u0007\u0007əl")
        assertEquals(listOf(vocab['h'], vocab['ə'], vocab['l']), partial.toList())
    }

    @Test
    fun `tokenize enforces the context limit`() {
        val tokenizer = KokoroTokenizer(vocab)
        tokenizer.tokenize("a".repeat(510))
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer.tokenize("a".repeat(511))
        }
    }

    @Test
    fun `known keeps exactly the surviving characters`() {
        val tokenizer = KokoroTokenizer(vocab)
        val phonemes = "həlˈoʊ \u0007\u0007 wˈɜːld"
        assertEquals("həlˈoʊ  wˈɜːld", tokenizer.known(phonemes))
    }

    @Test
    fun `phonemize filters through the vocab and strips`() {
        val tokenizer = KokoroTokenizer(vocab)
        val fake = FakePhonemizer("həlˈoʊ, wˈɜːld! \n\nsecond lˈaɪn. ")
        assertEquals(
            "həlˈoʊ, wˈɜːld! second lˈaɪn.",
            tokenizer.phonemize(fake, text = "  Hello, world!\n\nSecond line.  ", language = "en-us"),
        )
    }

    private class FakePhonemizer(
        private val result: String,
    ) : Phonemizer {
        override fun phonemize(
            text: String,
            language: String,
        ): String = result

        override fun supportedLanguages(): Set<String> = setOf("en-us")
    }
}
