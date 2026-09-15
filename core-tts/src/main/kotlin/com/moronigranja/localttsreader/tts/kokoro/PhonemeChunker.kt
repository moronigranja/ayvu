package com.moronigranja.localttsreader.tts.kokoro

/**
 * Splits a phoneme string into inference batches and reports the pause a
 * batch end should be followed by — a direct port of kokoro-onnx's `chunker`
 * (`_atoms` / `_pack` / `split_phonemes` / `pause_after`).
 *
 * The model context is fixed at [MAX_PHONEME_LENGTH] phonemes, so long inputs
 * are cut at the least disruptive boundary: sentence, clause, word, and only
 * as a last resort mid-word. Batches are then packed to fill evenly: a short
 * tail batch is spoken at a different rate and loudness than its neighbours,
 * so the smallest limit that needs no extra pass over the text is used.
 */
object PhonemeChunker {
    private const val SENTENCE_MARKS = ".!?…"
    private const val CLAUSE_MARKS = ",;:"

    // Least to most disruptive place to cut. Punctuation stays with the text
    // before it, which is how the model was trained.
    private val boundaries =
        listOf(
            Regex("(?<=[.!?…])\\s+"),
            Regex("(?<=[,;:])\\s+"),
            Regex("\\s+"),
        )

    /** Seconds of silence a batch ending with [phonemes] should be followed by. */
    fun pauseAfter(
        phonemes: String,
        sentence: Double,
        clause: Double,
    ): Double {
        val mark = phonemes.trimEnd().lastOrNull()
        return when (mark) {
            null -> 0.0
            in SENTENCE_MARKS -> sentence
            in CLAUSE_MARKS -> clause
            else -> 0.0
        }
    }

    /** Splits [phonemes] into balanced batches of at most [maxLength] phonemes. */
    fun split(
        phonemes: String,
        maxLength: Int = MAX_PHONEME_LENGTH,
    ): List<String> {
        val atoms = mutableListOf<String>()
        collectAtoms(phonemes.trim(), maxLength, 0, atoms)
        if (atoms.isEmpty()) return emptyList()
        if (atoms.size == 1) return atoms

        val lengths = atoms.map { it.length }.toIntArray()
        val fewest = pack(lengths, maxLength).size

        var low = lengths.max()
        var high = maxLength
        while (low < high) {
            val middle = (low + high) / 2
            if (pack(lengths, middle).size <= fewest) {
                high = middle
            } else {
                low = middle + 1
            }
        }

        // The pack ranges are `start until index` — end-exclusive like the
        // reference's (start, index) tuples; subList needs the exclusive end.
        return pack(lengths, low).map { range -> atoms.subList(range.first, range.last + 1).joinToString(" ") }
    }

    private fun collectAtoms(
        phonemes: String,
        maxLength: Int,
        level: Int,
        out: MutableList<String>,
    ) {
        if (phonemes.isEmpty()) return
        if (phonemes.length <= maxLength) {
            out += phonemes
            return
        }
        for (index in level until boundaries.size) {
            val pieces = boundaries[index].split(phonemes)
            if (pieces.size > 1) {
                for (piece in pieces) {
                    collectAtoms(piece.trim(), maxLength, index + 1, out)
                }
                return
            }
        }
        // A single unbroken run longer than the context: slice it rather than
        // dropping the tail.
        for (start in 0 until phonemes.length step maxLength) {
            val end = minOf(start + maxLength, phonemes.length)
            out += phonemes.substring(start, end)
        }
    }

    /** Groups consecutive atoms into batches within [limit], as index ranges. */
    private fun pack(
        lengths: IntArray,
        limit: Int,
    ): List<IntRange> {
        val batches = mutableListOf<IntRange>()
        var start = 0
        var size = 0
        for (index in lengths.indices) {
            val candidate = if (index == start) lengths[index] else size + 1 + lengths[index]
            if (candidate > limit && index > start) {
                batches += start until index
                start = index
                size = lengths[index]
            } else {
                size = candidate
            }
        }
        if (lengths.isNotEmpty()) batches += start until lengths.size
        return batches
    }
}
