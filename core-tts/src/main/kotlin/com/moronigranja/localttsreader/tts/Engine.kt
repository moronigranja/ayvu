package com.moronigranja.localttsreader.tts

/**
 * Engine selection tier (hard-facts engine table). V1: Kokoro-82M is the
 * primary; CosyVoice3-0.5B sits in the fallback tier behind the T3 gate
 * (decisions #21) until a DiT acceleration path changes the measurement.
 */
enum class EngineTier { PRIMARY, FALLBACK }

/**
 * Static metadata for one speech engine. [languages] are ISO 639-1 / BCP-47-ish
 * codes (e.g. "en", "pt") — the exact per-engine vocabulary is pinned with the
 * engine impl (T2); the catalog here is the hard-facts table verbatim.
 */
data class EngineSpec(
    val id: String,
    val displayName: String,
    val tier: EngineTier,
    val languages: Set<String>,
) {
    init {
        require(id.isNotBlank()) { "engine id must not be blank" }
        require(displayName.isNotBlank()) { "display name must not be blank" }
        require(languages.isNotEmpty()) { "engine must declare at least one language" }
    }
}

/**
 * The registry's unit of composition: one engine's metadata plus the packs its
 * impl requires, in declaration order.
 */
data class EngineDescriptor(
    val spec: EngineSpec,
    val packs: List<TtsPack>,
) {
    init {
        require(packs.all { it.engineId == spec.id }) {
            "pack ${packs.firstOrNull { it.engineId != spec.id }?.id} does not belong to engine ${spec.id}"
        }
    }
}

/**
 * One sentence's span in the synthesized audio, in seconds from the start of
 * the PCM. Spans are contiguous: sentence *i* runs from its first phoneme's
 * start to the next sentence's first phoneme's start, and the last sentence
 * runs to the end of the audio — the trailing pause after a mark belongs to
 * the sentence that produced it, so a read-along highlight can interpolate
 * position without silence scanning (decisions #31).
 */
data class SegmentAnchor(
    val startSeconds: Double,
    val endSeconds: Double,
)

/**
 * Cross-cutting engine contract (modules.md: "design against `TTSEngine`").
 * Every engine — Kokoro, CosyVoice3, any tier — is an implementor; swapping
 * engines is a wiring change, not a call-site change.
 *
 * T1 defines the contract; the Kokoro impl lands in T2. Implementors:
 * - declare [packs] (registry tracks their state) and fail synthesis with
 *   [SynthesisOutcome.Unavailable] when a required pack is not ready — never
 *   a silent fallback (conventions, definition of done),
 * - keep model I/O cancellable and off the main thread.
 *
 * [SynthesisRequest] is deliberately minimal (text + optional voice); engine
 * capabilities like speed/emotion instruct are engine-level settings, not
 * per-request fields, until the player needs them.
 */
interface TTSEngine {
    val spec: EngineSpec
    val packs: List<TtsPack>

    /** Synthesizes [request] into audio. Cancellable; expects a ready pack set. */
    suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome

    /**
     * Streaming variant of [synthesize] (decisions #138): hands each piece of
     * the result to [onWindow] the moment it is final — PCM16 bytes, in play
     * order — and returns the same whole-passage [SynthesisOutcome.Audio].
     * The default buffers the whole passage first (one emit), the behavior
     * every non-streaming engine and test fake already has; engines whose
     * pipeline is naturally windowed override it to emit early.
     */
    suspend fun synthesizeStreaming(
        request: SynthesisRequest,
        onWindow: suspend (ByteArray) -> Unit = {},
    ): SynthesisOutcome {
        val outcome = synthesize(request)
        if (outcome is SynthesisOutcome.Audio) onWindow(outcome.pcm)
        return outcome
    }
}

data class SynthesisRequest(
    val text: String,
    val voice: String? = null,
    /**
     * Playback speed — the model's `speed` input (Kokoro exports: float 0.5–2.0,
     * integer exports clamp to ≥ 1, decisions #28). Default 1.0. Positions and
     * sentence anchors stay in book-time (1.0×): a speed-`s` render occupies
     * ≈ half the samples at s = 2.0, so sample→time conversions scale by
     * speed on the player side (decisions #33).
     */
    val speed: Double = 1.0,
    /**
     * Passage identity the request synthesizes — the read-in-language KEY
     * dimension: a translated render must name its passage so the audio path
     * shares the stored display translation (one artifact, at most one decode
     * per passage). Set by the playback/pregen call sites; -1 = no identity
     * (preview/probe syntheses), which degrades a translated render to the
     * original text + voice ([TranslatingEngine]). Engines ignore both.
     */
    val chapterIndex: Int = -1,
    val passageIndex: Int = -1,
)

sealed interface SynthesisOutcome {
    /**
     * Signed 16-bit little-endian PCM. [segments] carries sentence anchors
     * (decisions #31): non-null when the graph reports durations and the
     * engine can place them — null for engines/tiers without a duration
     * output (the CosyVoice3 fallback tier), which degrades the read-along
     * to no per-sentence highlight rather than an estimated one.
     */
    data class Audio(
        val pcm: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int = 1,
        val segments: List<SegmentAnchor>? = null,
    ) : SynthesisOutcome

    /** A required pack is not downloaded; the caller should surface the download action. */
    data object Unavailable : SynthesisOutcome

    data class Failed(
        val reason: String,
    ) : SynthesisOutcome
}
