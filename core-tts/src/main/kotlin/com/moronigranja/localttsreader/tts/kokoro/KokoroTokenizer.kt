package com.moronigranja.localttsreader.tts.kokoro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Kokoro model context: at most this many phonemes per inference window. */
const val MAX_PHONEME_LENGTH = 510

/**
 * The packaged vocabulary (config.json `vocab`: single phoneme/character →
 * token id), the last-resort fallback to the graph's embedded metadata.
 * The model's embedded `kokoro_config` wins when present (v1.1 exports carry
 * it); this resource mirrors it (validated identical for the pinned model).
 */
object KokoroVocabulary {
    private const val RESOURCE = "/tts/kokoro/config.json"

    fun parse(json: String): Map<Char, Int> {
        val vocab =
            Json.parseToJsonElement(json).jsonObject["vocab"]
                ?: error("vocab missing from kokoro config")
        val result = HashMap<Char, Int>()
        for ((key, value) in vocab.jsonObject) {
            require(key.length == 1) { "vocab key must be a single character, was '$key'" }
            val id = value.jsonPrimitive.int
            require(id > 0) { "token ids are 1-based, saw $id for '$key'" }
            result[key[0]] = id
        }
        return result
    }

    fun resource(): Map<Char, Int> {
        val stream =
            KokoroVocabulary::class.java.getResourceAsStream(RESOURCE)
                ?: error("kokoro vocab resource missing: $RESOURCE")
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}

/**
 * Character→token-id mapping and phoneme filtering, ported from kokoro-onnx's
 * `Tokenizer`. Tokenization is per character: unknown characters are dropped
 * (they survive nowhere in the pipeline), never error-carrying.
 */
class KokoroTokenizer(
    val vocab: Map<Char, Int>,
) {
    init {
        require(vocab.isNotEmpty()) { "vocab must not be empty" }
    }

    /** Maps each [phonemes] character to its token id, dropping unknowns. */
    fun tokenize(
        phonemes: String,
        limit: Int = MAX_PHONEME_LENGTH,
    ): IntArray {
        require(limit >= 0) { "limit must be non-negative" }
        if (phonemes.length > limit) {
            throw IllegalArgumentException("text is too long, must be less than $limit phonemes")
        }
        if (phonemes.isEmpty()) return IntArray(0)
        val ids = IntArray(phonemes.length)
        var count = 0
        for (char in phonemes) {
            val id = vocab[char] ?: continue
            ids[count++] = id
        }
        return if (count == ids.size) ids else ids.copyOf(count)
    }

    /** The characters of [phonemes] that survive tokenization, aligned 1:1 with it. */
    fun known(phonemes: String): String =
        buildString(phonemes.length) {
            for (char in phonemes) if (char in vocab) append(char)
        }

    /** The reference pipeline's Tokenizer.phonemize: strip → phonemize → keep vocab → strip. */
    fun phonemize(
        phonemizer: Phonemizer,
        text: String,
        language: String,
    ): String {
        val phonemes = phonemizer.phonemize(text.trim(), language)
        return known(phonemes).trim()
    }
}
