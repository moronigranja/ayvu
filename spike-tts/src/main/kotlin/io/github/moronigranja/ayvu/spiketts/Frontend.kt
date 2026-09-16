package io.github.moronigranja.ayvu.spiketts

import java.util.regex.Pattern

/**
 * Text front-end for CosyVoice3 (port of sokuji's frontend.py).
 *
 * Japanese kana G2P (pyopenjtalk) is deliberately NOT ported — the spike
 * synthesizes English only, and the JA path is out of scope for the RTF
 * measurement (production core-tts must solve G2P separately).
 */
internal object Frontend {
    const val ENDOFPROMPT_ID = 151646
    const val ZERO_SHOT_PREFIX = "You are a helpful assistant."

    private val KANA_RE = Regex("[\u3040-\u30ff]")
    private val CJK_RE = Regex("[\u4e00-\u9fff]")

    /** Chinese (kanji present, no kana): ASCII -> full-width punctuation pass. */
    fun normalizeText(text: String): String {
        val t = text.trim()
        if (KANA_RE.containsMatchIn(t)) return t // JA: no G2P in the spike port
        if (CJK_RE.containsMatchIn(t)) {
            return t.replace(".", "。").replace("?", "？").replace("!", "！")
        }
        return t
    }

    fun buildPromptTextIds(
        tok: Bpe.Tokenizer,
        transcript: String,
    ): IntArray {
        val prefix = tok.encode(ZERO_SHOT_PREFIX)
        val ref = tok.encode(normalizeText(transcript))
        return IntArray(prefix.size + 1 + ref.size) { i ->
            when {
                i < prefix.size -> prefix[i]
                i == prefix.size -> ENDOFPROMPT_ID
                else -> ref[i - prefix.size - 1]
            }
        }
    }

    fun encodeTtsText(
        tok: Bpe.Tokenizer,
        text: String,
    ): IntArray = tok.encode(normalizeText(text))
}
