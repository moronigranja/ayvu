package io.github.moronigranja.ayvu.tts.setup

import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.PackState
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.piper.PiperEngine
import io.github.moronigranja.ayvu.tts.piper.PiperPacks
import io.github.moronigranja.ayvu.tts.piper.PiperVoices

/**
 * The pack ids the ACTIVE engine needs to be usable — the engine-aware
 * replacement for the setup flow's hardcoded Kokoro list (D4 made the engine
 * a real choice, decisions #154; K2 derived the settings rows the same way,
 * decisions #156). The setup gate, the setup screen's download plan and the
 * voice-selector readiness all share this one table so "what must be ready"
 * follows the selected engine, never a fixed Kokoro assumption:
 *
 * - kokoro-82m: its three packs (model, voices, espeak-ng).
 * - piper-v1: the resolved voice's model + config, plus the shared espeak-ng
 *   bundle both open-weight engines phonemize through (decisions #32/#154).
 * - system-tts / unknown: nothing — the degraded device voice needs no
 *   download (the opted-in path is derived separately in [SetupState]).
 *
 * A saved voice the engine does not expose (decisions #144 availability shape)
 * falls back to that engine's default voice, matching
 * [io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector].
 */
object SetupEnginePacks {
    const val ESPEAK_PACK_ID: String = "espeak-ng"

    val kokoroIds: List<String> =
        listOf(KokoroPacks.model.id, KokoroPacks.voices.id, KokoroPacks.espeak.id)

    /** The pack ids the [engineId] engine must have Ready to synthesize. */
    fun requiredIds(
        engineId: String,
        voice: String,
    ): List<String> =
        when (engineId) {
            DefaultEngines.piper.id ->
                PiperPacks
                    .forVoice(if (voice in PiperVoices.all) voice else PiperEngine.DEFAULT_VOICE)
                    .map { it.id } + ESPEAK_PACK_ID
            DefaultEngines.kokoro.id -> kokoroIds
            // system-tts (and any unknown engine) registers no download
            // requirement — the degraded voice path.
            else -> emptyList()
        }

    /** True when every pack [requiredIds] names is Ready on disk. */
    fun readyFor(
        engineId: String,
        voice: String,
        packs: List<PackState>,
    ): Boolean = requiredIds(engineId, voice).all { id -> packs.firstOrNull { it.pack.id == id }?.status == PackStatus.Ready }

    /** Total required-pack size for the voice (0 when no packs are needed). */
    fun bytesFor(
        engineId: String,
        voice: String,
        packs: List<PackState>,
    ): Long = requiredIds(engineId, voice).sumOf { id -> packs.firstOrNull { it.pack.id == id }?.pack?.sizeBytes ?: 0L }
}
