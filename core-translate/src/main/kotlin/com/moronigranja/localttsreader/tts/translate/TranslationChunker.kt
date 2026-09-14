package com.moronigranja.localttsreader.tts.translate

/**
 * Splits passage text into encoder-sized translation chunks (decisions #114
 * sizing: FLORES devtest passages measure 120–250 tokens, ~2 s per chunk on
 * the S22 — one chunk is the common case).
 *
 * Sentence-bounded split on `[.!?…。！？]+` runs (delimiters kept with the
 * sentence), then greedily packs sentences while the summed encoded size
 * (incl. the `[lang, …]` + `</s>` conditioning) stays ≤ [maxIds]; a single
 * sentence over the budget is hard-split at piece boundaries, each fragment
 * decoded independently (mid-word joins possible — the accepted trade for a
 * passage beyond the encoder window).
 */
class TranslationChunker(
    private val tokenizer: Small100Tokenizer,
    private val maxIds: Int = DEFAULT_MAX_IDS,
) {
    /** Splits [text] into chunks each encodable within [maxIds] ids. */
    fun chunk(
        text: String,
        tgtLang: String,
    ): List<String> {
        val sentences = splitSentences(text)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentCost = 2 // the conditioning pair [lang, eos]
        for (sentence in sentences) {
            val cost = tokenizer.encodePieces(sentence).size + 2
            if (cost > maxIds) {
                // A sentence alone over budget: hard-split its pieces.
                flush(current, currentCost, chunks)
                chunks.addAll(hardSplit(sentence, tgtLang))
                current.setLength(0)
                currentCost = 2
                continue
            }
            if (currentCost + cost - 2 > maxIds) {
                flush(current, currentCost, chunks)
                current.setLength(0)
                currentCost = 2
            }
            current.append(sentence)
            currentCost += cost
        }
        flush(current, currentCost, chunks)
        return chunks
    }

    private fun flush(
        buffer: StringBuilder,
        cost: Int,
        out: MutableList<String>,
    ) {
        if (cost > 2) out.add(buffer.toString())
    }

    /** Splits a single over-budget sentence into ≤ maxIds-id fragments. */
    private fun hardSplit(
        sentence: String,
        tgtLang: String,
    ): List<String> {
        val pieces = tokenizer.encodePieces(sentence)
        val budget = maxIds - 2
        val out = ArrayList<String>()
        var offset = 0
        while (offset < pieces.size) {
            val group = pieces.subList(offset, (offset + budget).coerceAtMost(pieces.size))
            // Rebuild [lang, group…, eos] and decode the group pieces back.
            // (There is no trailing eos for the last fragment either — decode
            // strips it — but keeping the shape uniform is simpler.)
            val ids = IntArray(group.size + 2)
            ids[0] = tokenizer.langId(tgtLang)
            for (j in group.indices) ids[j + 1] = tokenizer.pieceToId(group[j]) ?: tokenizer.unkId
            ids[ids.size - 1] = tokenizer.eosId
            out.add(tokenizer.decode(ids))
            offset += group.size
        }
        return out
    }

    companion object {
        /** Encoder budget: 512 max, 450 leaves headroom for conditioning + eos. */
        const val DEFAULT_MAX_IDS = 450

        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…', '。', '！', '？')

        /** Splits on `[.!?…。！？]+` runs, keeping the delimiters attached. */
        internal fun splitSentences(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                sb.append(c)
                if (c in SENTENCE_ENDERS) {
                    i++
                    while (i < text.length && text[i] in SENTENCE_ENDERS) {
                        sb.append(text[i])
                        i++
                    }
                    if (sb.isNotBlank()) out.add(sb.toString())
                    sb.setLength(0)
                    continue
                }
                i++
            }
            if (sb.isNotBlank()) out.add(sb.toString())
            return out
        }
    }
}
