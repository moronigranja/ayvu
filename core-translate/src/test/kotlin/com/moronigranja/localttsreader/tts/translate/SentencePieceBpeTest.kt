package com.moronigranja.localttsreader.tts.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the SentencePieceBpe port (normalizer + BPE encode) against the
 * python sentencepiece library behavior on the committed SPM model fixture.
 * The golden-id test ([Small100TokenizerTest]) is the head-for-head pin
 * through the full HF tokenizer; these tests document the port's outer
 * contract directly.
 */
class SentencePieceBpeTest {
    @Test
    fun normalizationMatchesSentencepieceRules() {
        // NBSP -> space (then escaped to ▁), fullwidth -> ASCII, ligature -> fi.
        assertEquals("\u2581a\u2581b", spm.normalize("a\u00a0b"))
        assertEquals("\u2581ABC", spm.normalize("ＡＢＣ"))
        assertEquals("\u2581file\u25811", spm.normalize("ﬁle ①"))
        // Dummy prefix + space collapsing + trailing space strip.
        assertEquals("\u2581hello", spm.normalize("  hello  "))
        assertEquals("\u2581double\u2581space", spm.normalize("double  space"))
        // Empty input is empty (no dummy prefix).
        assertEquals("", spm.normalize(""))
    }

    @Test
    fun bpeEncodeMatchesSentencepiecePieces() {
        assertEquals(
            listOf(
                "\u2581The",
                "\u2581quick",
                "\u2581bro",
                "wn",
                "\u2581fo",
                "x",
                "\u2581jum",
                "ps",
                "\u2581over",
                "\u2581the",
                "\u2581la",
                "zy",
                "\u2581dog",
                ".",
            ),
            spm.encodeToPieces("The quick brown fox jumps over the lazy dog."),
        )
        // CJK: no merging beyond the existing two-char piece; the rest stays
        // single chars (verified identical to python sentencepiece).
        assertEquals(
            listOf("\u2581", "こ", "ん", "に", "ち", "は", "世界"),
            spm.encodeToPieces("こんにちは世界"),
        )
        // Devanagari merges per BPE, not per character.
        assertEquals(
            listOf("\u2581नम", "स्ते", "\u2581दुनिया"),
            spm.encodeToPieces("नमस्ते दुनिया"),
        )
    }

    @Test
    fun bpeIsNotNaiveLongestPrefixSegmentation() {
        // The agenda merges `ps` before `p` can attach to `▁jum`, so the
        // outcome differs from greedy longest-prefix matching (which would
        // emit `▁jump` + `s`): this asserts the encoder runs the real BPE
        // merge algorithm, not a longest-match tokenizer.
        val pieces = spm.encodeToPieces("The quick brown fox jumps over the lazy dog.")
        assertEquals(listOf("\u2581jum", "ps"), pieces.subList(6, 8))
        assertNotEquals(listOf("\u2581jump", "s"), pieces.subList(6, 8))
    }

    @Test
    fun decodePiecesReversesEscaping() {
        assertEquals(
            "Hello, world!",
            spm.decodePieces(
                listOf("\u2581Hello", ",", "\u2581world", "!"),
            ),
        )
    }

    companion object {
        private lateinit var spm: SentencePieceBpe

        @JvmStatic
        @BeforeAll
        fun loadModel() {
            val resource =
                SentencePieceBpeTest::class.java.getResource("/sentencepiece.bpe.model")
                    ?: error("missing test fixture sentencepiece.bpe.model")
            spm = SentencePieceBpe.load(File(resource.toURI()))
        }
    }
}
