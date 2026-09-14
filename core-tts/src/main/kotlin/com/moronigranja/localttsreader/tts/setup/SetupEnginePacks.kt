package com.moronigranja.localttsreader.tts.setup

import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.piper.PiperEngine
import com.moronigranja.localttsreader.tts.piper.PiperPacks
import com.moronigranja.localttsreader.tts.piper.PiperVoices

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
 * [com.moronigranja.localttsreader.featureplayer.playback.EngineSelector].
 */
object SetupEnginePacks {
    const val ESPEAK_PACK_ID: String = "espeak-ng"

    val kokoroIds: List<String> =
        listOf(KokoroPacks.model.id, KokoroPacks.voices.id, KokoroPacks.espeak.id)

    /** The pack ids the [engineId] engine must have Ready to synthesize. */
    fun requiredIds(engineId: String, voice: String): List<String> =
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
}
