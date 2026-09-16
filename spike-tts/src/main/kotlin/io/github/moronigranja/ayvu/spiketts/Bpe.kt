package io.github.moronigranja.ayvu.spiketts

import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Qwen2 byte-level BPE rebuilt from vocab.json + merges.txt, mirroring
 * sokuji's qwen_tokenizer.py (tokenizers library) structure: Split
 * pre-tokenizer with Qwen's regex, ByteLevel encoding, no prefix space.
 * Only plain-text encoding is needed here (add_special_tokens=False is the
 * only mode used; <|endofprompt|> is spliced in raw by Frontend).
 */
internal object Bpe {
    const val QWEN2_SPLIT =
        """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""

    class Tokenizer(
        val vocab: Map<String, Int>,
        val ranks: Map<String, Int>,
        val byteEncoder: IntArray, // byte -> char code (bytes not visible map to 256+)
        val splitRegex: Pattern,
    ) {
        fun encode(text: String): IntArray {
            val matcher = splitRegex.matcher(text)
            val out = ArrayList<Int>(text.length)
            while (matcher.find()) {
                val chunk = matcher.group()
                val bytes = chunk.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder(bytes.size)
                for (b in bytes) sb.append(byteEncoder[b.toInt() and 0xFF].toInt().toChar())
                val token = sb.toString()
                if (vocab.containsKey(token)) {
                    out.add(vocab[token]!!)
                } else {
                    for (piece in bpe(token)) {
                        val id =
                            vocab[piece]
                                ?: // byte-fallback off; Qwen2 vocab covers all symbols post-merge
                                throw IllegalStateException("BPE produced unknown token '$piece'")
                        out.add(id)
                    }
                }
            }
            return out.toIntArray()
        }

        private fun bpe(token: String): List<String> {
            val symbols = token.map { it.toString() }.toMutableList()
            while (symbols.size > 1) {
                var bestRank: Int? = null
                var bestPair: Pair<String, String>? = null
                for (i in 0 until symbols.size - 1) {
                    val rank = ranks["${symbols[i]} ${symbols[i + 1]}"]
                    if (rank != null && (bestRank == null || rank < bestRank)) {
                        bestRank = rank
                        bestPair = symbols[i] to symbols[i + 1]
                    }
                }
                if (bestRank == null) break
                val merged = bestPair!!.first + bestPair.second
                val next = ArrayList<String>(symbols.size)
                var i = 0
                while (i < symbols.size) {
                    if (i < symbols.size - 1 &&
                        symbols[i] == bestPair.first && symbols[i + 1] == bestPair.second
                    ) {
                        next.add(merged)
                        i += 2
                    } else {
                        next.add(symbols[i])
                        i += 1
                    }
                }
                symbols.clear()
                symbols.addAll(next)
            }
            return symbols
        }
    }

    private fun buildByteEncoder(): IntArray {
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in 0xA1..0xAC) bs.add(b)
        for (b in 0xAE..0xFF) bs.add(b)
        val encoder = IntArray(256)
        var n = 0
        for (b in 0..255) {
            if (bs.contains(b)) {
                encoder[b] = b
            } else {
                encoder[b] = 256 + n
                n++
            }
        }
        return encoder
    }

    fun load(modelDir: File): Tokenizer {
        val vocabJson = File(modelDir, "vocab.json").readText()
        val vocab = HashMap<String, Int>(160000)
        val obj = JSONObject(vocabJson)
        for (k in obj.keys()) vocab[k] = obj.getInt(k)
        val merges =
            File(modelDir, "merges.txt")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
        val ranks = HashMap<String, Int>(merges.size)
        merges.forEachIndexed { i, line -> ranks[line.trim()] = i }
        return Tokenizer(vocab, ranks, buildByteEncoder(), Pattern.compile(QWEN2_SPLIT))
    }
}
