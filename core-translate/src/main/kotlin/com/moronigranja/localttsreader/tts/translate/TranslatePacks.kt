package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.PackKind
import com.moronigranja.localttsreader.tts.TtsPack

/**
 * The registry descriptor's spec for the translate bundle. Purely structural:
 * `EngineDescriptor` requires a spec whose `id == pack.engineId`, but this is
 * never selectable as a TTS engine — [EngineSelector]'s switch stays closed on
 * it and [TTSEngine] rendering never routes through it.
 */
val TranslateSpec =
    EngineSpec(
        id = TranslatePacks.PACK_ENGINE_ID,
        displayName = "LFM2.5-1.2B (read-in-language)",
        tier = EngineTier.FALLBACK,
        languages = LfmLang.APP_CODES.toSet(),
    )

/**
 * The pinned pack descriptor for the read-in-language translator: the
 * LFM2.5-1.2B-Instruct Q4_K_M GGUF (decisions #161/#162 — one 730 MB
 * all-language pack) published on this repo's releases (same hosting
 * convention as the espeak-ng zip, `KokoroPacks.espeak`).
 *
 * Artifact: `translate-lfm12b-q4-v1.zip` (730,895,330 B, sha256
 * `a701827a…`, STORED — the GGUF compresses to nothing) containing
 * `LFM2.5-1.2B-Instruct-Q4_K_M.gguf` (730,895,168 B, sha256 `b1b3de11…`) from
 * `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` @
 * `6767265158422fb8a19c62ceb45f16f05363615b` — byte-identical to the artifact
 * measured on the S22 (chrF 67.37 on 40 FLORES en→por, 22.3 tok/s, 2.7
 * s/passage; decisions #161).
 *
 * `engineId = "translate-lfm12b"` is a registry-layout id only — the
 * descriptor is NOT in [com.moronigranja.localttsreader.tts.DefaultEngines]
 * and the engine selector's switch stays closed: no TTS engine is ever
 * selected with this id (the pack row lives in the Speech subscreen).
 * License: LFM Open License v1.0 (`general.license.name = lfm1.0` in the
 * GGUF), free for this project's use (decisions #161).
 */
object TranslatePacks {
    /** Registry-layout engine id for the translate pack (not a selectable TTS engine). */
    const val PACK_ENGINE_ID = "translate-lfm12b"

    private const val RELEASE = "translate-lfm12b-v1"
    private const val BASE = "https://github.com/moronigranja/local-tts-reader/releases/download/$RELEASE"

    val pack =
        TtsPack(
            id = "translate-lfm12b-q4-v1",
            engineId = PACK_ENGINE_ID,
            kind = PackKind.MODEL,
            displayName = "LFM2.5-1.2B translate model (Q4_K_M)",
            description = "LFM2.5-1.2B-Instruct (LFM Open License v1.0) Q4_K_M GGUF, ~730 MB. Read-in-language engine (decisions #161).",
            url = "$BASE/translate-lfm12b-q4-v1.zip",
            sha256Hex = "a701827af5f5b40687d1b7e6d90e475d6ad88d0a912361a84d61370b3ba9442f",
            sizeBytes = 730_895_330,
            version = "1",
        )

    val all: List<TtsPack> = listOf(pack)
}
