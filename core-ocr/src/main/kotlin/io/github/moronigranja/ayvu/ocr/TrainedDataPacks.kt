package io.github.moronigranja.ayvu.ocr

import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * The pinned OCR language packs (S1, re-pinned 2026-09-20): **`tessdata_fast`
 * tag `4.1.0` LSTM models** for the Tesseract 5.5.1 engine the app now binds
 * through Tesseract4Android (decisions #186).
 *
 * History: 0.1.x shipped tess-two 9.1.0, a pre-LSTM Tesseract build, so the
 * packs had to be the legacy `tessdata` 3.04.00 artifacts and OCR accuracy was
 * capped far below what current models give (register row 14, decisions #36).
 * `tessdata_fast` 4.0.0 LSTM models were verified to fail `init` on that
 * binding on-device; on Tesseract 5 they are the only shape that loads. The
 * retired generation's ids live in [RETIRED_ENGINE_ID]/[RETIRED_RELEASE] so
 * the stager can reclaim its bytes on upgrade.
 *
 * One `TtsPack` per language, so the v1 settings UI reuses the exact pack
 * machinery (explicit, resumable, SHA-verified downloads; decision #7).
 *
 * SHA-256s are the real artifacts, produced by downloading each once on the
 * host (2026-09-20; never fabricated — the registry hard-requires them):
 *
 * | lang | size (B) | sha256 (prefix) |
 * |------|----------|-----------------|
 * | eng  |  4113088 | 7d4322bd… |
 * | spa  |  2294433 | 6f2e04d0… |
 * | fra  |  1130365 | ced03756… |
 * | deu  |  1525436 | 19d219bb… |
 * | por  |  1982756 | c4932b93… |
 * | ita  |  2701314 | b8f89e1e… |
 *
 * A language's `id` is its tessdata name, and the staging adapter (feature-ocr)
 * copies the verified artifact into the engine's data path
 * (`<dataPath>/tessdata/<lang>.traineddata`).
 */
object TrainedDataPacks {
    /** The OCR engine id (pack-cache namespace + registry key). */
    const val ENGINE_ID = "tesseract"

    /** Upstream `tessdata_fast` tag the packs are pinned to. */
    const val RELEASE = "4.1.0"

    /** Pack tier — also the first segment of the staged-data directory, so a
     *  tier or release change can never leave a stale model where the engine
     *  will read it ([TessDataStager.tesseractDataPath]). */
    const val TIER = "fast"

    /** The retired pre-LSTM generation (shipped through 0.1.3) — reclaimed on
     *  upgrade: `packs/tess-two/` in the pack cache and the legacy
     *  `files/tesseract/tessdata/` staging directory. */
    const val RETIRED_ENGINE_ID = "tess-two"

    const val RETIRED_RELEASE = "3.04.00"

    private const val BASE = "https://github.com/tesseract-ocr/tessdata_fast/raw/$RELEASE"

    val eng = pack("eng", "English", 4_113_088L, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    val spa = pack("spa", "Spanish", 2_294_433L, "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464")
    val fra = pack("fra", "French", 1_130_365L, "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca")
    val deu = pack("deu", "German", 1_525_436L, "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d")
    val por = pack("por", "Portuguese", 1_982_756L, "c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb")
    val ita = pack("ita", "Italian", 2_701_314L, "b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827")

    val all: List<TtsPack> = listOf(eng, spa, fra, deu, por, ita)

    /** Engine metadata for the registry/UI ("tesseract", the six installed languages). */
    val spec: EngineSpec =
        EngineSpec(
            id = ENGINE_ID,
            displayName = "Tesseract OCR",
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
        description = "tessdata_fast $RELEASE ($lang) — the OCR language pack.",
        url = "$BASE/$lang.traineddata",
        sha256Hex = sha256Hex,
        sizeBytes = sizeBytes,
        version = "1",
    )
}
