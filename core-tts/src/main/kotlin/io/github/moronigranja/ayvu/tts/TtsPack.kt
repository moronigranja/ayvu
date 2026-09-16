package io.github.moronigranja.ayvu.tts

/**
 * A downloadable engine asset: model weights, a per-language asset, or a voice.
 *
 * Packs are **never bundled in the APK** (hard-facts: "language/voice packs are
 * downloadable, never bundled"): every asset is a runtime, explicit,
 * user-consented, resumable download, verified against [sha256Hex] and cached
 * under the pack cache root once verified (decision #7).
 *
 * Descriptors are data: an engine impl declares the packs it needs, and the
 * [PackRegistry] tracks their state. A descriptor must be fully pinned — the
 * URL and the SHA-256 of the exact artifact — before it can be shipped;
 * descriptors are never shipped with placeholder hashes (T1 decision: hashes
 * are produced by downloading the artifact once during the engine slice, T2).
 *
 * [id] must be globally unique across engines (registry key); the convention is
 * `<engineId>-<name>` (e.g. `kokoro-model`, `cosyvoice3-model`).
 */
data class TtsPack(
    val id: String,
    val engineId: String,
    val kind: PackKind,
    val displayName: String,
    val description: String = "",
    val url: String,
    val sha256Hex: String,
    val sizeBytes: Long,
    val version: String = "1",
    /**
     * The base pack this artifact accompanies ([id] of the base), or null for a
     * standalone pack. A companion travels with its base — Piper's per-voice
     * `.onnx.json` is useless without the model and the model is unusable
     * without it — so the settings surface lists only base packs and a base
     * download also requests its companions.
     */
    val companionOf: String? = null,
) {
    init {
        require(id.isNotBlank()) { "pack id must not be blank" }
        require(engineId.isNotBlank()) { "engine id must not be blank" }
        require(displayName.isNotBlank()) { "display name must not be blank" }
        require(url.startsWith("https://")) { "packs are served over HTTPS only, was: $url" }
        require(sha256Hex.length == 64 && sha256Hex.all { it in HEX_DIGITS }) {
            "sha256 must be 64 lowercase/uppercase hex digits"
        }
        require(sizeBytes > 0) { "pack size must be positive, was: $sizeBytes" }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdefABCDEF"
    }
}

/** What an asset is for: weights, a per-language asset, or a voice. */
enum class PackKind { MODEL, LANGUAGE, VOICE }
