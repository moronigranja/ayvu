package io.github.moronigranja.ayvu.ocr

import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * The pinned tess-two language packs (S1): **legacy** `tessdata` 3.04.00
 * models. tess-two 9.1.0's native tesseract is a pre-LSTM 4.0.0-beta build —
 * `tessdata_fast` 4.0.0 LSTM models were verified to fail `init` on-device
 * (2026-08-26), while legacy models initialize and recognize cleanly. One
 * `TtsPack` per language, so the v1 settings UI reuses the exact pack
 * machinery (explicit, resumable, SHA-verified downloads; decision #7).
 *
 * SHA-256s are the real artifacts, produced by downloading each once on the
 * host during S1 (never fabricated; the registry hard-requires them):
 *
 * | lang | size (B) | sha256 (prefix) |
 * |------|----------|-----------------|
 * | eng  | 21876550 | c0515c9f… |
 * | spa  | 15953087 | f2398599… |
 * | fra  | 14044118 | 86afb23a… |
 * | deu  | 13367187 | cb7eb42a… |
 * | por  | 12914622 | 089fb419… |
 * | ita  | 14210569 | 5a4e6e82… |
 *
 * A language's `id` is its tessdata name, and the staging adapter (feature-ocr)
 * copies the verified artifact from the pack cache to the tess-two data
 * directory (`<dataPath>/tessdata/<lang>.traineddata`) on first use.
 */
object TrainedDataPacks {
    const val ENGINE_ID = "tess-two"

    private const val RELEASE = "3.04.00"
    private const val BASE = "https://github.com/tesseract-ocr/tessdata/raw/$RELEASE"

    val eng = pack("eng", "English", 21_876_550L, "c0515c9f1e0c79e1069fcc05c2b2f6a6841fb5e1082d695db160333c1154f06d")
    val spa = pack("spa", "Spanish", 15_953_087L, "f23985996bbcfe2b57864ccb082783c1c74c87429f04411a04a6ba4d3da2efda")
    val fra = pack("fra", "French", 14_044_118L, "86afb23ad146467f263e8ade56fd3951b1cc28f8c4eebc34f993d3c02d88a7ab")
    val deu = pack("deu", "German", 13_367_187L, "cb7eb42a7e972cec7ef904fe81825d7b547c46df684c814fdb11a930b13bca3a")
    val por = pack("por", "Portuguese", 12_914_622L, "089fb419cd7bd135236244dd9a4b8a42dfe2ee97d97b481efdd7b92c9c6324a0")
    val ita = pack("ita", "Italian", 14_210_569L, "5a4e6e826e021d04f3494c2bd74ed1af5977b67fdedceb3c9aa30ff6c7a4b3d3")

    val all: List<TtsPack> = listOf(eng, spa, fra, deu, por, ita)

    /** Engine metadata for the registry/UI ("tess-two", the six installed languages). */
    val spec: EngineSpec =
        EngineSpec(
            id = ENGINE_ID,
            displayName = "Tesseract OCR (tess-two)",
            tier = io.github.moronigranja.ayvu.tts.EngineTier.PRIMARY,
            languages = all.map { it.id }.toSet(),
        )

    private fun pack(
        lang: String,
        displayName: String,
        sizeBytes: Long,
        sha256Hex: String,
    ) = TtsPack(
        id = lang,
        engineId = ENGINE_ID,
        kind = PackKind.LANGUAGE,
        displayName = displayName,
        description = "tessdata_fast 4.0.0 ($lang) — the OCR language pack.",
        url = "$BASE/$lang.traineddata",
        sha256Hex = sha256Hex,
        sizeBytes = sizeBytes,
        version = "1",
    )
}
