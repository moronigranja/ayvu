package io.github.moronigranja.ayvu.tts.kokoro

import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * The pinned pack descriptors for Kokoro-82M — the first real descriptors in
 * the app (T2). Both point at thewh1teagle/kokoro-onnx **model-files-v1.1**
 * release (the one the reference README uses), and the SHA-256s are of the
 * exact downloaded artifacts, produced by downloading each once during T2 —
 * verified against the upstream sizes below (never fabricated):
 *
 * - kokoro-model:  kokoro-v1.0.onnx  (325,505,369 B), fp32, en export,
 *   float speed input + duration output + embedded vocab — the canonical
 *   reference artifact. int8/fp16 variants and the v1.1-zh model exist but
 *   are not pinned: the V3 device pass picks the shipping precision.
 * - kokoro-voices: voices-v1.0.bin   (28,214,398 B), 54 voices across
 *   en-US/en-GB, fr, es, it, pt-BR, ja, zh, hi. German and Korean have no
 *   v1.0 voices, so the engine does not advertise them.
 *
 * A descriptor change (new artifact, re-export) is a version bump + re-pin:
 * download once, hash, commit.
 */
object KokoroPacks {
    private const val RELEASE = "model-files-v1.1"
    private const val BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/$RELEASE"

    val model =
        TtsPack(
            id = "kokoro-model",
            engineId = "kokoro-82m",
            kind = PackKind.MODEL,
            displayName = "Kokoro-82M model (v1.0, fp32)",
            description = "Kokoro-82M ONNX export (fp32, English).",
            url = "$BASE/kokoro-v1.0.onnx",
            sha256Hex = "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
            sizeBytes = 325_505_369,
            version = "1",
        )

    val voices =
        TtsPack(
            id = "kokoro-voices",
            engineId = "kokoro-82m",
            kind = PackKind.VOICE,
            displayName = "Kokoro v1.0 voices (54)",
            description = "54 voices: en-US, en-GB, fr-FR, es-ES, it-IT, pt-BR, ja, zh, hi.",
            url = "$BASE/voices-v1.0.bin",
            sha256Hex = "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d",
            sizeBytes = 28_214_398,
            version = "1",
        )

    val espeak =
        TtsPack(
            id = "espeak-ng",
            engineId = "kokoro-82m",
            kind = PackKind.MODEL,
            displayName = "espeak-ng bundle (G2P)",
            description = "arm64 libespeak-ng.so + espeak-ng-data (1.52.0) — the Kokoro phonemizer.",
            url = "https://github.com/moronigranja/ayvu/releases/download/espeak-ng-1.52.0/espeak-ng-android-1.52.0.zip",
            sha256Hex = "6b2edca73af83dac42bfeb421a84f36810cbe7602b76237807356a4bd84898fa",
            sizeBytes = 9_857_162,
            version = "1",
        )

    val all: List<TtsPack> = listOf(model, voices, espeak)
}
