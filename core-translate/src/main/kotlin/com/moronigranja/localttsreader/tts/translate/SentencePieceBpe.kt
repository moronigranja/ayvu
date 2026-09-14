package com.moronigranja.localttsreader.tts.translate

import java.io.File
import java.util.PriorityQueue

/**
 * A from-scratch port of the sentencepiece encoder for the SMaLL-100
 * `sentencepiece.bpe.model` (roadmap Phase J → core-translate slice). No
 * protobuf or sentencepiece dependency: the `.model` protobuf wire format is
 * parsed directly, normalization uses the model's own precompiled charsmap
 * (double-array trie + NUL-separated replacement blob), and encoding runs the
 * BPE merge algorithm of sentencepiece's bpe_model.cc.
 *
 * The model is a **BPE** SentencePiece model (trainer_spec model_type=2), NOT
 * unigram — the piece table carries merge scores and encoding is agenda-driven
 * pair merging, not Viterbi over the lattice. `byte_fallback` is off in the
 * pinned model, so unknown characters become the `<unk>` piece via the
 * caller's vocab mapping (no `<0xXX>` pieces exist in this model; no UNUSED
 * pieces either, so the resegmentation pass of bpe_model.cc is a no-op here).
 *
 * The IDs produced by [encodeToPieces] are piece STRINGS; assigning ids is the
 * caller's job ([Small100Tokenizer] maps pieces through the pinned HF
 * `vocab.json`, whose id order differs from the SPM piece table — the model's
 * embedding table is indexed by vocab.json ids, decisions #114 parity).
 *
 * Golden parity (the JVM test) pins this port head-for-head against the
 * python sentencepiece library on the same model file.
 */
class SentencePieceBpe private constructor(
    /** mergeable pieces (NORMAL/USER_DEFINED/UNUSED) -> score; the BPE merge table. */
    private val mergeTable: Map<String, Float>,
    /** user-defined symbols: matched input prefixes freeze (never merged). */
    private val frozenSymbols: List<String>,
    /** charsmap double-array units, or null when the model has no charsmap. */
    private val units: IntArray?,
    /** charsmap replacement blob (NUL-separated UTF-8), or null. */
    private val replacementBlob: ByteArray?,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean,
    private val escapeWhitespaces: Boolean,
    private val treatWhitespaceAsSuffix: Boolean,
) {
    /** Normalizes [text] exactly as sentencepiece's Normalizer does
     * (charsmap replacements, space collapsing, dummy prefix, ▁ escaping). */
    fun normalize(text: String): String {
        val input = text.toByteArray(Charsets.UTF_8)
        if (input.isEmpty()) return ""
        val out = java.io.ByteArrayOutputStream(input.size * 3)
        val ws: ByteArray = if (escapeWhitespaces) SPACE_SYMBOL else byteArrayOf(' '.code.toByte())

        // Ignores heading spaces (remove_extra_whitespaces).
        var consumed = 0
        if (removeExtraWhitespaces) {
            while (consumed < input.size) {
                val (replacement, len) = normalizePrefix(input, consumed) ?: break
                if (!replacement.contentEquals(SPACE)) break
                consumed += len
            }
        }
        // all-chars-whitespace (or empty) short-circuits the dummy prefix.
        if (consumed >= input.size) return ""

        // add_dummy_prefix: leading ▁.
        if (!treatWhitespaceAsSuffix && addDummyPrefix) out.write(ws)

        var isPrevSpace = removeExtraWhitespaces
        while (consumed < input.size) {
            val (replacement, len) = normalizePrefix(input, consumed) ?: break
            var sp = replacement
            // Removes heading spaces when the previous piece ended with space.
            while (isPrevSpace && sp.isNotEmpty() && sp[0] == ' '.code.toByte()) {
                sp = sp.copyOfRange(1, sp.size)
            }
            if (sp.isNotEmpty()) {
                for (b in sp) {
                    if (escapeWhitespaces && b == ' '.code.toByte()) {
                        out.write(ws)
                    } else {
                        out.write(b.toInt())
                    }
                }
                isPrevSpace = sp[sp.size - 1] == ' '.code.toByte()
            }
            consumed += len
        }

        // Ignores tailing space.
        if (removeExtraWhitespaces) {
            val result = out.toByteArray()
            var end = result.size
            while (end >= ws.size && endsWith(result, end, ws)) end -= ws.size
            return String(result, 0, end, Charsets.UTF_8)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun endsWith(
        bytes: ByteArray,
        endExclusive: Int,
        suffix: ByteArray,
    ): Boolean {
        if (endExclusive < suffix.size) return false
        for (i in suffix.indices) {
            if (bytes[endExclusive - suffix.size + i] != suffix[i]) return false
        }
        return true
    }

    /**
     * The charsmap rule (or passthrough char) at [offset]: (replacement bytes,
     * consumed byte count). Null only when the input is exhausted.
     */
    private fun normalizePrefix(
        input: ByteArray,
        offset: Int,
    ): Pair<ByteArray, Int>? {
        if (offset >= input.size) return null
        var longestLength = 0
        var longestValue = 0
        if (units != null) {
            var nodePos = 0
            var unit = units[0]
            nodePos = nodePos xor offsetOf(unit)
            var i = 0
            while (offset + i < input.size) {
                val key = input[offset + i].toInt() and 0xFF
                nodePos = nodePos xor key
                unit = units[nodePos]
                if (labelOf(unit) != key) break
                nodePos = nodePos xor offsetOf(unit)
                if (hasLeafOf(unit)) {
                    if (i + 1 > longestLength) {
                        longestLength = i + 1
                        longestValue = units[nodePos] and 0x7FFFFFFF
                    }
                }
                i++
            }
        }
        if (longestLength == 0) {
            // No rule: pass the char through (malformed UTF-8 becomes U+FFFD,
            // consuming 1 byte, as sentencepiece's normalizePrefix does).
            val first = input[offset].toInt() and 0xFF
            val len =
                when {
                    first < 0x80 -> 1
                    first and 0xE0 == 0xC0 -> 2
                    first and 0xF0 == 0xE0 -> 3
                    first and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
            val chunk = input.copyOfRange(offset, (offset + len).coerceAtMost(input.size))
            val charLen = utf8CharLen(first)
            return if (charLen > 0 && offset + charLen <= input.size) {
                chunk to charLen
            } else {
                U_FFFD to 1
            }
        }
        val blob = replacementBlob ?: error("charsmap hit without replacement blob")
        var end = longestValue
        while (end < blob.size && blob[end] != 0.toByte()) end++
        return blob.copyOfRange(longestValue, end) to longestLength
    }

    private fun utf8CharLen(first: Int): Int =
        when {
            first < 0x80 -> 1
            first and 0xE0 == 0xC0 -> 2
            first and 0xF0 == 0xE0 -> 3
            first and 0xF8 == 0xF0 -> 4
            else -> -1
        }

    /**
     * Encodes [text] to SPM piece strings: normalize → char-split (frozen
     * user-defined symbols intact) → agenda-driven BPE merges → walks the
     * surviving symbol chain. Matches bpe_model.cc's `Encode` for models
     * without UNUSED pieces (this model has none → no resegmentation).
     */
    fun encodeToPieces(text: String): List<String> {
        val norm = normalize(text).toByteArray(Charsets.UTF_8)
        if (norm.isEmpty()) return emptyList()

        val starts = IntArray(norm.size)
        val ends = IntArray(norm.size)
        val prevs = IntArray(norm.size)
        val nexts = IntArray(norm.size)
        val frozen = BooleanArray(norm.size)
        val dead = BooleanArray(norm.size)

        var pos = 0
        var count = 0
        while (pos < norm.size) {
            var len = -1
            var freeze = false
            for (fp in frozenSymbols) {
                val fbytes = fp.toByteArray(Charsets.UTF_8)
                if (pos + fbytes.size <= norm.size && regionMatches(norm, pos, fbytes)) {
                    if (fbytes.size > len) len = fbytes.size
                    freeze = true
                }
            }
            var charLen = -1
            if (len < 0) {
                val first = norm[pos].toInt() and 0xFF
                charLen = utf8CharLen(first)
                len = if (charLen > 0 && pos + charLen <= norm.size) charLen else 1
            }
            starts[count] = pos
            ends[count] = pos + len
            prevs[count] = if (count == 0) -1 else count - 1
            nexts[count] = if (pos + len >= norm.size) -1 else count + 1
            frozen[count] = freeze
            pos += len
            count++
        }
        if (count == 0) return emptyList()

        val agenda =
            PriorityQueue<MergeCandidate>(16) { a, b ->
                val byScore = b.score.compareTo(a.score)
                if (byScore != 0) byScore else a.left - b.left
            }

        fun maybeAdd(
            left: Int,
            right: Int,
        ) {
            if (left == -1 || right == -1 || dead[left] || dead[right] ||
                frozen[left] || frozen[right]
            ) {
                return
            }
            val piece = String(norm, starts[left], ends[right] - starts[left], Charsets.UTF_8)
            val score = mergeTable[piece] ?: return
            agenda.add(MergeCandidate(score, left, right, ends[right] - starts[left]))
        }

        for (i in 1 until count) maybeAdd(i - 1, i)

        while (agenda.isNotEmpty()) {
            val top = agenda.poll()
            val l = top.left
            val r = top.right
            if (dead[l] || dead[r]) continue
            if ((ends[l] - starts[l]) + (ends[r] - starts[r]) != top.size) continue
            // Merge: the left symbol absorbs the right one.
            ends[l] = ends[r]
            nexts[l] = nexts[r]
            if (nexts[r] >= 0) prevs[nexts[r]] = l
            dead[r] = true
            maybeAdd(prevs[l], l)
            maybeAdd(l, nexts[l])
        }

        val out = ArrayList<String>()
        var i = 0
        while (i != -1) {
            if (!dead[i]) {
                out.add(String(norm, starts[i], ends[i] - starts[i], Charsets.UTF_8))
            }
            i = nexts[i]
        }
        return out
    }

    /**
     * Decodes piece strings exactly as sentencepiece's processor does: the FIRST
     * piece's leading ▁ is consumed (the encode-side dummy prefix comes back as a
     * bare leading space otherwise) when the model uses add_dummy_prefix /
     * remove_extra_whitespaces, then every ▁ becomes a space. Control and unknown
     * pieces must already be filtered by the caller.
     */
    fun decodePieces(pieces: List<String>): String {
        val sb = StringBuilder()
        for ((i, p) in pieces.withIndex()) {
            var piece = p
            if (i == 0 && (addDummyPrefix || removeExtraWhitespaces)) {
                if (piece.startsWith(SPACE_SYMBOL_STR)) piece = piece.substring(SPACE_SYMBOL_STR.length)
            }
            sb.append(piece.replace(SPACE_SYMBOL_STR, " "))
        }
        return sb.toString()
    }

    companion object {
        private const val SPACE_SYMBOL_STR = "\u2581"
        private val SPACE_SYMBOL = SPACE_SYMBOL_STR.toByteArray(Charsets.UTF_8)
        private val SPACE = byteArrayOf(' '.code.toByte())
        private val U_FFFD = "\uFFFD".toByteArray(Charsets.UTF_8)

        // ModelProto wire fields (sentencepiece_model.proto):
        //   repeated SentencePiece pieces = 1; TrainerSpec trainer_spec = 2;
        //   NormalizerSpec normalizer_spec = 3.
        private const val FIELD_PIECES = 1
        private const val FIELD_TRAINER_SPEC = 2
        private const val FIELD_NORMALIZER_SPEC = 3

        // TrainerSpec: model_type = 3 (UNIGRAM=1/BPE=2); byte_fallback = 35.
        private const val TS_MODEL_TYPE = 3
        private const val TS_BYTE_FALLBACK = 35
        private const val TS_TREAT_WS_AS_SUFFIX = 24

        // NormalizerSpec: precompiled_charsmap = 2; add_dummy_prefix = 3;
        // remove_extra_whitespaces = 4; escape_whitespaces = 5.
        private const val NS_CHARSMAP = 2
        private const val NS_ADD_DUMMY_PREFIX = 3
        private const val NS_REMOVE_EXTRA_WS = 4
        private const val NS_ESCAPE_WS = 5

        // SentencePiece: piece = 1; score = 2; type = 3.
        private const val SP_PIECE = 1
        private const val SP_SCORE = 2
        private const val SP_TYPE = 3

        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val TYPE_UNUSED = 5

        /** Loads and parses a sentencepiece `.model` file. */
        fun load(modelFile: File): SentencePieceBpe = load(modelFile.readBytes())

        fun load(bytes: ByteArray): SentencePieceBpe {
            var idx = 0
            var trainerSpec: ByteArray? = null
            var normalizerSpec: ByteArray? = null
            val mergeTable = HashMap<String, Float>()
            val frozenSymbols = ArrayList<String>()
            var unkCount = 0

            fun varint(): Int {
                var shift = 0
                var value = 0
                while (true) {
                    val b = bytes[idx].toInt() and 0xFF
                    idx++
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }

            while (idx < bytes.size) {
                val key = varint()
                val field = key ushr 3
                val wire = key and 7
                when (wire) {
                    0 -> varint()
                    2 -> {
                        val len = varint()
                        if (field == FIELD_PIECES) {
                            val (piece, score, type) = parsePieceRecord(bytes, idx, len)
                            // The BPE merge table = NORMAL/USER_DEFINED/UNUSED
                            // pieces (bpe_model.cc merges through `pieces_`);
                            // user-defined pieces also freeze input spans.
                            if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED || type == TYPE_UNUSED) {
                                mergeTable[piece] = score
                            }
                            if (type == TYPE_USER_DEFINED) frozenSymbols.add(piece)
                            if (type == TYPE_UNKNOWN) unkCount++
                        } else if (field == FIELD_TRAINER_SPEC) {
                            trainerSpec = bytes.copyOfRange(idx, idx + len)
                        } else if (field == FIELD_NORMALIZER_SPEC) {
                            normalizerSpec = bytes.copyOfRange(idx, idx + len)
                        }
                        idx += len
                    }
                    else -> error("unsupported protobuf wire type $wire in SPM model")
                }
            }
            check(unkCount == 1) { "SPM model must define exactly one <unk> piece" }

            var byteFallback = false
            var treatWhitespaceAsSuffix = false
            if (trainerSpec != null) {
                var i = 0

                fun tv(): Int {
                    var shift = 0
                    var value = 0
                    while (true) {
                        val b = trainerSpec!![i].toInt() and 0xFF
                        i++
                        value = value or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) return value
                        shift += 7
                    }
                }
                while (i < trainerSpec.size) {
                    val key = tv()
                    val field = key ushr 3
                    val wire = key and 7
                    when (wire) {
                        0 ->
                            when (field) {
                                TS_BYTE_FALLBACK -> byteFallback = tv() != 0
                                TS_TREAT_WS_AS_SUFFIX -> treatWhitespaceAsSuffix = tv() != 0
                                else -> tv()
                            }
                        2 -> {
                            val len = tv()
                            i += len
                        }
                        5 -> i += 4 // fixed32 (character_coverage etc.)
                        else -> error("bad trainer_spec wire $wire")
                    }
                }
            }
            check(!byteFallback) {
                "byte_fallback models are unsupported: the pinned SMaLL-100 model has byte_fallback off"
            }

            var charsmap: ByteArray? = null
            var addDummy = true
            var removeExtra = true
            var escapeWs = true
            if (normalizerSpec != null) {
                var i = 0

                fun nv(): Int {
                    var shift = 0
                    var value = 0
                    while (true) {
                        val b = normalizerSpec!![i].toInt() and 0xFF
                        i++
                        value = value or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) return value
                        shift += 7
                    }
                }
                while (i < normalizerSpec.size) {
                    val key = nv()
                    val field = key ushr 3
                    val wire = key and 7
                    when (field) {
                        NS_CHARSMAP -> {
                            require(wire == 2) { "charsmap must be length-delimited" }
                            val len = nv()
                            charsmap = normalizerSpec.copyOfRange(i, i + len)
                            i += len
                        }
                        NS_ADD_DUMMY_PREFIX -> if (wire == 0) addDummy = nv() != 0
                        NS_REMOVE_EXTRA_WS -> if (wire == 0) removeExtra = nv() != 0
                        NS_ESCAPE_WS -> if (wire == 0) escapeWs = nv() != 0
                        else ->
                            when (wire) {
                                0 -> nv()
                                2 -> {
                                    val len = nv()
                                    i += len
                                }
                                else -> error("bad normalizer_spec wire $wire")
                            }
                    }
                }
            }

            val (units, blob) = charsmap?.let { decodeCharsmap(it) } ?: (null to null)
            return SentencePieceBpe(
                mergeTable,
                frozenSymbols,
                units,
                blob,
                addDummy,
                removeExtra,
                escapeWs,
                treatWhitespaceAsSuffix,
            )
        }

        /** Parses one SentencePiece message from the model bytes at [start]. */
        private fun parsePieceRecord(
            bytes: ByteArray,
            start: Int,
            len: Int,
        ): Triple<String, Float, Int> {
            var i = start
            val end = start + len
            var piece = ""
            var score = 0.0f
            var type = TYPE_NORMAL

            fun v(): Int {
                var shift = 0
                var out = 0
                while (true) {
                    val b = bytes[i].toInt() and 0xFF
                    i++
                    out = out or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return out
                    shift += 7
                }
            }
            while (i < end) {
                val key = v()
                val field = key ushr 3
                val wire = key and 7
                when (field) {
                    SP_PIECE -> {
                        val plen = v()
                        piece = String(bytes, i, plen, Charsets.UTF_8)
                        i += plen
                    }
                    SP_SCORE -> {
                        val bits =
                            (bytes[i].toInt() and 0xFF) or
                                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[i + 3].toInt() and 0xFF) shl 24)
                        score = Float.fromBits(bits)
                        i += 4
                    }
                    SP_TYPE -> type = v()
                    else ->
                        if (wire == 0) {
                            v()
                        } else {
                            val plen = v()
                            i += plen
                        }
                }
            }
            return Triple(piece, score, type)
        }

        /**
         * Decodes the precompiled charsmap: 4-byte little-endian trie byte
         * count, then the double-array units, then the NUL-separated
         * replacement strings. (Sentencepiece v0.2.x stores the blob raw —
         * NOT zlib — and the trie values are byte offsets into it.)
         */
        private fun decodeCharsmap(charsmap: ByteArray): Pair<IntArray, ByteArray> {
            require(charsmap.size >= 4) { "charsmap too small" }
            val trieLen =
                (charsmap[0].toInt() and 0xFF) or
                    ((charsmap[1].toInt() and 0xFF) shl 8) or
                    ((charsmap[2].toInt() and 0xFF) shl 16) or
                    ((charsmap[3].toInt() and 0xFF) shl 24)
            require(trieLen % 4 == 0 && 4 + trieLen <= charsmap.size) {
                "malformed charsmap: trieLen=$trieLen of ${charsmap.size}"
            }
            val units = IntArray(trieLen / 4)
            for (i in units.indices) {
                val base = 4 + i * 4
                units[i] =
                    (charsmap[base].toInt() and 0xFF) or
                    ((charsmap[base + 1].toInt() and 0xFF) shl 8) or
                    ((charsmap[base + 2].toInt() and 0xFF) shl 16) or
                    ((charsmap[base + 3].toInt() and 0xFF) shl 24)
            }
            val blob = charsmap.copyOfRange(4 + trieLen, charsmap.size)
            return units to blob
        }

        private fun offsetOf(unit: Int): Int = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

        private fun labelOf(unit: Int): Int = unit and (0x80000000.toInt() or 0xFF)

        private fun hasLeafOf(unit: Int): Boolean = ((unit ushr 8) and 1) == 1

        private fun regionMatches(
            bytes: ByteArray,
            offset: Int,
            other: ByteArray,
        ): Boolean {
            for (i in other.indices) {
                if (bytes[offset + i] != other[i]) return false
            }
            return true
        }

        private data class MergeCandidate(
            val score: Float,
            val left: Int,
            val right: Int,
            val size: Int,
        )
    }
}
