package com.moronigranja.localttsreader.tts.piper

import com.moronigranja.localttsreader.tts.PackKind
import com.moronigranja.localttsreader.tts.TtsPack

/**
 * The pinned voice ids of the piper-v1 engine: the rhasspy/piper-voices
 * artifact stem. One voice = one VITS model (Piper has no speaker mixing), so
 * an engine instance is opened for exactly one of these.
 */
object PiperVoices {
    const val LESSAC = "en_US-lessac-medium"
    const val THORSTEN = "de_DE-thorsten-high"

    val all: List<String> = listOf(LESSAC, THORSTEN)
}

/**
 * The pinned pack descriptors for Piper — D4 adoption (decisions #99 and its
 * 2026-09-12/13 addenda; quality gate passed 2026-09-13). One model + one
 * voice config per voice, both from rhasspy/piper-voices at the pinned
 * revision `1162a9173d0ce503555aed757976b7a9912eae4c`:
 *
 * - lessac model: en_US-lessac-medium.onnx (63,201,294 B) — the measured D4
 *   leg (HiBreak RTF 0.566–0.575 on ORT-android 1.29). sha256 cross-checked
 *   against the HF LFS oid and the staged spike copy byte-for-byte.
 * - thorsten model: de_DE-thorsten-high.onnx (113,895,201 B) — German (the
 *   Kokoro v1.0 pack ships no German voices).
 * - each `*-config`: the voice's `.onnx.json` — phoneme_id_map, inference
 *   scales, sample rate, espeak voice — the data the engine needs besides the
 *   weights. Small git blobs, hashed from the downloaded artifacts.
 *
 * A descriptor change (new artifact, re-export) is a version bump + re-pin:
 * download once, hash, commit (same flow as [com.moronigranja.localttsreader.tts.kokoro.KokoroPacks]).
 */
object PiperPacks {
    private const val REVISION = "1162a9173d0ce503555aed757976b7a9912eae4c"
    private const val BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/$REVISION"

    val lessacModel =
        TtsPack(
            id = "piper-lessac-medium",
            engineId = "piper-v1",
            kind = PackKind.MODEL,
            displayName = "Piper voice: en_US lessac (medium)",
            description = "VITS ONNX export (22050 Hz, 63 MB). The measured D4 leg.",
            url = "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            sha256Hex = "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f",
            sizeBytes = 63_201_294,
            version = "1",
        )

    val lessacConfig =
        TtsPack(
            id = "piper-lessac-medium-config",
            engineId = "piper-v1",
            kind = PackKind.VOICE,
            displayName = "Piper voice config: en_US lessac",
            description = "Voice config: phoneme_id_map, inference scales, espeak voice (en-us).",
            url = "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
            sha256Hex = "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0",
            sizeBytes = 4_885,
            version = "1",
        )

    val thorstenModel =
        TtsPack(
            id = "piper-thorsten-high",
            engineId = "piper-v1",
            kind = PackKind.MODEL,
            displayName = "Piper voice: de_DE thorsten (high)",
            description = "VITS ONNX export (22050 Hz, 114 MB). German — Kokoro v1.0 ships no German voices.",
            url = "$BASE/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx",
            sha256Hex = "9df1c43c61149ef9b39e618e2b861fbe41e1fcea9390b2dac62e8761573ea4f1",
            sizeBytes = 113_895_201,
            version = "1",
        )

    val thorstenConfig =
        TtsPack(
            id = "piper-thorsten-high-config",
            engineId = "piper-v1",
            kind = PackKind.VOICE,
            displayName = "Piper voice config: de_DE thorsten",
            description = "Voice config from the same artifact: phoneme_id_map, inference scales, espeak voice (de).",
            url = "$BASE/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx.json",
            sha256Hex = "6de734444e4c3f9e33b7ebe2746dbc19b71e85f613e79c65acf623200b99a76a",
            sizeBytes = 4_875,
            version = "1",
        )

    val all: List<TtsPack> = listOf(lessacModel, lessacConfig, thorstenModel, thorstenConfig)

    /** The packs one voice's engine instance requires (its model + config). */
    fun forVoice(voice: String): List<TtsPack> =
        when (voice) {
            PiperVoices.LESSAC -> listOf(lessacModel, lessacConfig)
            PiperVoices.THORSTEN -> listOf(thorstenModel, thorstenConfig)
            else -> emptyList()
        }
}