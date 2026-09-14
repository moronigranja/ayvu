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
        displayName = "SMaLL-100 (read-in-language)",
        tier = EngineTier.FALLBACK,
        languages = Small100Lang.M2M_CODES.toSet(),
    )

/**
 * The pinned pack descriptor for the read-in-language translator: the
 * SMaLL-100 dynamic-int8 bundle (decisions #114 — one 916 MB all-language
 * pack, output-side only) published on this repo's releases (same hosting
 * convention as the espeak-ng zip, `KokoroPacks.espeak`).
 *
 * Artifact: `translate-small100-int8-v1.zip` (921,820,987 B, sha256
 * `de57cc19…`) — `{encoder,decoder,decoder_with_past}_model.onnx` (int8,
 * hashes cross-checked against `m/nmt/manifest.json` — `0bec8395…`,
 * `7e713802…`, `4e44b128…` — which pins `alirezamsh/small100` @
 * `8ab680e26a596d2e3d2d2d17ae0f68df1037328c`) + `sentencepiece.bpe.model`
 * (re-downloaded from HF at that revision: same bytes, `d8f7c76e…`) +
 * `vocab.json` (the HF piece→id table — the graphs' embedding index; the
 * tokenizer requires it, committed as its id source).
 *
 * `engineId = "translate-small100"` is a registry-layout id only — the
 * descriptor is NOT in [com.moronigranja.localttsreader.tts.DefaultEngines]
 * and the engine selector's switch stays closed: no TTS engine is ever
 * selected with this id (the pack row lives in the Speech subscreen).
 * License: SMaLL-100 weights are MIT; SentencePiece is Apache-2.0.
 */
object TranslatePacks {
    /** Registry-layout engine id for the translate pack (not a selectable TTS engine). */
    const val PACK_ENGINE_ID = "translate-small100"

    private const val RELEASE = "translate-small100-v1"
    private const val BASE = "https://github.com/moronigranja/local-tts-reader/releases/download/$RELEASE"

    val pack =
        TtsPack(
            id = "translate-small100-int8-v1",
            engineId = "translate-small100",
            kind = PackKind.MODEL,
            displayName = "SMaLL-100 translate model (int8)",
            description = "SMaLL-100 (MIT) dynamic-int8 all-language pack, ~916 MB. Read-in-language engine (decisions #114 chr-F trade).",
            url = "$BASE/translate-small100-int8-v1.zip",
            sha256Hex = "de57cc191612ae2c6fe529f20271fae795153fbed283721653f862a3450ef881",
            sizeBytes = 921_820_987,
            version = "1",
        )

    val all: List<TtsPack> = listOf(pack)
}
