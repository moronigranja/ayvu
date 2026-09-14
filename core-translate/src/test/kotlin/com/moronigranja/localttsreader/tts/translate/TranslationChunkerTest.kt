package com.moronigranja.localttsreader.tts.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

/** Boundary behavior of the sentence-bounded, ≤ maxIds chunker. */
class TranslationChunkerTest {
    @Test
    fun delimitersStayWithTheirSentence() {
        assertEquals(
            listOf("Hello.", " World!", "¿Qué tal?"),
            split("Hello. World!¿Qué tal?"),
        )
        assertEquals(
            listOf("Итак…", " действительно."),
            split("Итак… действительно."),
        )
    }

    @Test
    fun shortPassageIsOneChunk() {
        val text = "The first sentence. The second one! And a third?"
        val chunks = chunker(450).chunk(text, "pt")
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun sentencesPackUntilBudget() {
        // Each sentence is 13 ids incl. conditioning; budget 14 fits one, not two
        // → two sentence-aligned chunks. The inter-sentence space starts the
        // second chunk (split keeps delimiters with the sentence).
        val s1 = "This is a moderately long first sentence. "
        val s2 = "This is a moderately long second sentence. "
        val chunks = chunker(14).chunk(s1 + s2, "es")
        assertEquals(2, chunks.size)
        assertEquals(
            listOf("This is a moderately long first sentence.", " This is a moderately long second sentence."),
            chunks,
        )
    }

    @Test
    fun overBudgetSentenceIsHardSplit() {
        val runOn = ("Repetition makes the encoder overflow: ").repeat(12)
        val chunks = chunker(50).chunk(runOn, "it")
        assertTrue(chunks.size >= 2, "expected a hard split, got $chunks")
        for (chunk in chunks) {
            val size = tokenizer.encode(chunk, "it").size
            assertTrue(size <= 50, "chunk of $size ids exceeds budget: ${chunk.take(30)}")
        }
    }

    private fun split(text: String) = TranslationChunker.splitSentences(text)

    private fun chunker(maxIds: Int) = TranslationChunker(tokenizer, maxIds)

    companion object {
        private lateinit var tokenizer: Small100Tokenizer

        @JvmStatic
        @BeforeAll
        fun load() {
            fun resource(name: String) =
                File(
                    TranslationChunkerTest::class.java.getResource(name)!!.toURI(),
                )
            tokenizer = Small100Tokenizer.load(resource("/sentencepiece.bpe.model"), resource("/vocab.json"))
        }
    }
}
