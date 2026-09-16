package io.github.moronigranja.ayvu.tts.system

import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * C1.5/decisions #102: the zero-download degraded fallback — reads with the
 * device's own TextToSpeech engine instead of Kokoro. Ships as an explicit
 * opt-in inside first-run setup (never auto-selected); the recorded
 * degradation is passage-level read-along (`segments = null` — same as the
 * Piper small tier, decisions #99) and no per-request speed (the system
 * engine's rate is a device setting; positions stay book-time because audio
 * length maps 1:1 to produced samples).
 *
 * `packs = emptyList()`: zero download, no registry requirement. On init
 * failure or a missing language it fails with a named
 * [SynthesisOutcome.Failed] — never a silent fallback to another engine.
 */
class SystemTtsEngine(
    private val seam: SystemTtsSeam,
) : TTSEngine {
    override val spec: EngineSpec =
        EngineSpec(
            id = SettingsStore.SYSTEM_TTS_ENGINE,
            displayName = "Device voice (system)",
            tier = EngineTier.FALLBACK,
            // Queried from the device at construction; ISO 639-1 codes, "en" as
            // the floor so the spec never reads empty.
            languages = seam.availableLanguages().ifEmpty { setOf("en") },
        )

    override val packs: List<TtsPack> = emptyList()

    override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
        val language = voiceLanguage(request.voice)
        return try {
            when (val result = seam.synthesizeToPcm(request.text, language)) {
                is TtsSynthesis.Audio ->
                    if (result.pcm.isEmpty()) {
                        SynthesisOutcome.Failed("device voice produced no audio")
                    } else {
                        SynthesisOutcome.Audio(
                            pcm = result.pcm,
                            sampleRateHz = result.sampleRateHz,
                            channelCount = 1,
                            segments = null, // recorded degradation: no read-along spans
                        )
                    }
                TtsSynthesis.Unavailable ->
                    SynthesisOutcome.Failed("device voice unavailable: ${language ?: "device default"}")
            }
        } catch (t: Throwable) {
            SynthesisOutcome.Failed(t.message ?: "device voice failed")
        }
    }

    /** Kokoro voice hint → BCP-47 for the device engine; unknown families
     * (and null) fall back to the device default language. */
    private fun voiceLanguage(voice: String?): String? {
        if (voice == null) return null
        return VOICE_FAMILY_LANGUAGES[voice.substringBefore('_')]
    }

    companion object {
        /** Mirrors [io.github.moronigranja.ayvu.tts.kokoro.KokoroEngine.Companion.VOICE_LANGUAGES]
         * (the v1.0 prefix families) in BCP-47 for the system engine. */
        val VOICE_FAMILY_LANGUAGES: Map<String, String> =
            mapOf(
                "af" to "en-US",
                "am" to "en-US",
                "bf" to "en-GB",
                "bm" to "en-GB",
                "ef" to "es-ES",
                "em" to "es-ES",
                "ff" to "fr-FR",
                "hf" to "hi-IN",
                "hm" to "hi-IN",
                "if" to "it-IT",
                "im" to "it-IT",
                "jf" to "ja-JP",
                "jm" to "ja-JP",
                "pf" to "pt-BR",
                "pm" to "pt-BR",
                "zf" to "zh-CN",
                "zm" to "zh-CN",
            )
    }
}
