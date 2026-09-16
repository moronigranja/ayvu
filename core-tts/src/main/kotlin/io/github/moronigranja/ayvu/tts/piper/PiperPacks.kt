package io.github.moronigranja.ayvu.tts.piper

import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * The pinned voice ids of the piper-v1 engine: the rhasspy/piper-voices
 * artifact stem. One voice = one VITS model (Piper has no speaker mixing), so
 * an engine instance is opened for exactly one of these.
 */
object PiperVoices {
    const val LESSAC = "en_US-lessac-medium"
    const val THORSTEN = "de_DE-thorsten-high"
    const val ES_ES_DAVEFX = "es_ES-davefx-medium"
    const val IT_IT_SERENA = "it_IT-serena-medium"
    const val PT_BR_FABER = "pt_BR-faber-medium"

    val all: List<String> =
        listOf(
            LESSAC,
            THORSTEN,
            ES_ES_DAVEFX,
            IT_IT_SERENA,
            PT_BR_FABER,
        )
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
 * - the 2026-09-13 language pins (same revision): es_ES-davefx-medium
 *   (CC0), it_IT-serena-medium (CC-BY-4.0), pt_BR-faber-medium (CC0),
 *   all ~63 MB @ 22050 Hz. Korean stays unpinned — the only ko voice
 *   (`ko_KR-kss-medium`) is CC-BY-NC-SA-4.0, outside the permissive pack
 *   policy (#149 excludes CC-BY-NC; owner call before it ships).
 * - each `*-config`: the voice's `.onnx.json` — phoneme_id_map, inference
 *   scales, sample rate, espeak voice — the data the engine needs besides the
 *   weights. Small git blobs, hashed from the downloaded artifacts. Each is a
 *   [TtsPack.companionOf] its model: the settings surface lists the model
 *   rows only and a model download fetches its config with it.
 *
 * A descriptor change (new artifact, re-export) is a version bump + re-pin:
 * download once, hash, commit (same flow as [io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks]).
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
            companionOf = lessacModel.id,
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
            companionOf = thorstenModel.id,
        )

    val davefxModel =
        TtsPack(
            id = "piper-davefx-medium",
            engineId = "piper-v1",
            kind = PackKind.MODEL,
            displayName = "Piper voice: es_ES davefx (medium)",
            description = "VITS ONNX export (22050 Hz, 63 MB). Spanish (es-ES).",
            url = "$BASE/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx",
            sha256Hex = "6658b03b1a6c316ee4c265a9896abc1393353c2d9e1bca7d66c2c442e222a917",
            sizeBytes = 63_201_294,
            version = "1",
        )

    val davefxConfig =
        TtsPack(
            id = "piper-davefx-medium-config",
            engineId = "piper-v1",
            kind = PackKind.VOICE,
            displayName = "Piper voice config: es_ES davefx",
            description = "Voice config from the same artifact: phoneme_id_map, inference scales, espeak voice (es).",
            url = "$BASE/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json",
            sha256Hex = "0e0dda87c732f6f38771ff274a6380d9252f327dca77aa2963d5fbdf9ec54842",
            sizeBytes = 4_817,
            version = "1",
            companionOf = davefxModel.id,
        )

    val serenaModel =
        TtsPack(
            id = "piper-serena-medium",
            engineId = "piper-v1",
            kind = PackKind.MODEL,
            displayName = "Piper voice: it_IT serena (medium)",
            description = "VITS ONNX export (22050 Hz, 64 MB). Italian (it-IT).",
            url = "$BASE/it/it_IT/serena/medium/it_IT-serena-medium.onnx",
            sha256Hex = "3f1493311db17fec0e95cdf3c92f82b5006159b7743e057a749187469dfa7cf0",
            sizeBytes = 63_511_037,
            version = "1",
        )

    val serenaConfig =
        TtsPack(
            id = "piper-serena-medium-config",
            engineId = "piper-v1",
            kind = PackKind.VOICE,
            displayName = "Piper voice config: it_IT serena",
            description = "Voice config from the same artifact: phoneme_id_map, inference scales, espeak voice (it).",
            url = "$BASE/it/it_IT/serena/medium/it_IT-serena-medium.onnx.json",
            sha256Hex = "0c5ecb9a9f574f1363993df8f641dd3ed433cd940045e137809e25651057c550",
            sizeBytes = 4_959,
            version = "1",
            companionOf = serenaModel.id,
        )

    val faberModel =
        TtsPack(
            id = "piper-faber-medium",
            engineId = "piper-v1",
            kind = PackKind.MODEL,
            displayName = "Piper voice: pt_BR faber (medium)",
            description = "VITS ONNX export (22050 Hz, 63 MB). Brazilian Portuguese (pt-BR).",
            url = "$BASE/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx",
            sha256Hex = "858555e3a064209c57088fe6bd70c4c3dc54d03eaa00c45d5ecaf43a33f95aa7",
            sizeBytes = 63_201_294,
            version = "1",
        )

    val faberConfig =
        TtsPack(
            id = "piper-faber-medium-config",
            engineId = "piper-v1",
            kind = PackKind.VOICE,
            displayName = "Piper voice config: pt_BR faber",
            description = "Voice config from the same artifact: phoneme_id_map, inference scales, espeak voice (pt-br).",
            url = "$BASE/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json",
            sha256Hex = "7e694de195ae3fc36dd732c445eb04fb49b649854893cb5506b978f0d50a1d6f",
            sizeBytes = 4_855,
            version = "1",
            companionOf = faberModel.id,
        )

    val all: List<TtsPack> =
        listOf(
            lessacModel,
            lessacConfig,
            thorstenModel,
            thorstenConfig,
            davefxModel,
            davefxConfig,
            serenaModel,
            serenaConfig,
            faberModel,
            faberConfig,
        )

    /** The packs one voice's engine instance requires (its model + config).
     * Korean (`ko_KR-kss-medium`, the only ko voice) is NOT pinned yet — it
     * is CC-BY-NC-SA-4.0, outside the permissive pack policy (decisions #149
     * excludes CC-BY-NC; the owner decides before it ships). */
    fun forVoice(voice: String): List<TtsPack> =
        when (voice) {
            PiperVoices.LESSAC -> listOf(lessacModel, lessacConfig)
            PiperVoices.THORSTEN -> listOf(thorstenModel, thorstenConfig)
            PiperVoices.ES_ES_DAVEFX -> listOf(davefxModel, davefxConfig)
            PiperVoices.IT_IT_SERENA -> listOf(serenaModel, serenaConfig)
            PiperVoices.PT_BR_FABER -> listOf(faberModel, faberConfig)
            else -> emptyList()
        }
}
