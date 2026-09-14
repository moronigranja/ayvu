package com.moronigranja.localttsreader.tts.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The SMaLL-100 tokenizer (decisions #114 conditioning contract), built from
 * the pinned `sentencepiece.bpe.model` + the pinned HF `vocab.json`.
 *
 * Two facts pin the design:
 * - The text piece → id map is the HF `vocab.json` (128,004 entries: the four
 *   specials first, then the 128,000 SPM pieces reordered), NOT the SPM piece
 *   order — the ONNX graphs' embedding table is indexed by vocab.json ids
 *   (the spike's host-prepared inputs came from the HF tokenizer, and the
 *   export parity loop validates graphs against HF generate).
 * - Lang tokens are appended AFTER the text vocab: `__<code>__` gets
 *   `vocabSize + index` (128,004 + i), confirmed by the pinned
 *   `added_tokens.json` (`__af__` = 128004, `__pt__` = 128075, `__zu__` =
 *   128103).
 *
 * `encode(text, tgtLang)` reproduces `SMALL100Tokenizer(text, tgt_lang)`
 * exactly: `[tgtLangId, pieceIds…, eos]` truncated to 512 total.
 */
class Small100Tokenizer private constructor(
    private val spm: SentencePieceBpe,
    private val vocab: Map<String, Int>,
    private val idToPiece: Array<String>,
    private val langIndex: Map<String, Int>,
) {
    /** Special ids per the pinned model's vocab ordering. */
    val eosId: Int = requireNotNull(vocab["</s>"]) { "vocab.json missing </s>" }
    val unkId: Int = requireNotNull(vocab["<unk>"]) { "vocab.json missing <unk>" }
    val langIdOffset: Int = vocab.size

    /** The SPM piece string for a model lang code, or null when unknown. */
    fun langToken(code: String): String? = if (code in langIndex) Small100Lang.langToken(code) else null

    /** The lang-token id for a model code, or UNK when the code is unknown. */
    fun langId(code: String): Int = langIndex[code]?.let { langIdOffset + it } ?: unkId

    /**
     * Encodes [text] for the encoder graph: `[langId(tgt), pieces…, eos]`,
     * truncated to 512 total (piece budget 510).
     */
    fun encode(
        text: String,
        tgtLang: String,
    ): IntArray {
        val pieces = spm.encodeToPieces(text)
        val pieceCount = pieces.size.coerceAtMost(MAX_SEQUENCE - 2)
        val ids = IntArray(pieceCount + 2)
        ids[0] = langId(tgtLang)
        for (i in 0 until pieceCount) {
            ids[i + 1] = vocab[pieces[i]] ?: unkId
        }
        ids[pieceCount + 1] = eosId
        return ids
    }

    /** Walkable piece-level access used by the chunker's hard-split. */
    fun encodePieces(text: String): List<String> = spm.encodeToPieces(text)

    /** Piece → id (null when the piece is not in the pinned vocab). */
    fun pieceToId(piece: String): Int? = vocab[piece]

    /**
     * Decodes generated ids: strips the conditioning/special ids (lang tokens,
     * `<s>`/`<pad>`/`</s>`/`<unk>` and any out-of-table model artifacts), then
     * joins the remaining pieces (▁ → space).
     */
    fun decode(ids: IntArray): String {
        val pieces = ArrayList<String>(ids.size)
        for (id in ids) {
            if (id == eosId || id == unkId || id <= 3 || id >= langIdOffset) continue
            if (id < idToPiece.size) pieces.add(idToPiece[id])
        }
        return spm.decodePieces(pieces)
    }

    companion object {
        private const val MAX_SEQUENCE = 512

        /** Loads the tokenizer from the packed SPM model + HF vocab.json. */
        fun load(
            spmFile: File,
            vocabFile: File,
        ): Small100Tokenizer {
            val spm = SentencePieceBpe.load(spmFile)
            val root = Json.parseToJsonElement(vocabFile.readText()).jsonObject
            require(root.size == 128004) {
                "unexpected vocab.json size ${root.size} — the pack pins the alirezamsh/small100 vocab @ 8ab680e"
            }
            val pieceToId = HashMap<String, Int>(root.size)
            val idToPiece = arrayOfNulls<String>(root.size)
            for ((piece, element) in root) {
                val id = element.jsonPrimitive.content.toInt()
                pieceToId[piece] = id
                idToPiece[id] = piece
            }
            val langIndex = HashMap<String, Int>(Small100Lang.M2M_CODES.size)
            Small100Lang.M2M_CODES.forEachIndexed { i, code -> langIndex[code] = i }
            return Small100Tokenizer(spm, pieceToId, idToPiece.requireNoNulls(), langIndex)
        }
    }
}
