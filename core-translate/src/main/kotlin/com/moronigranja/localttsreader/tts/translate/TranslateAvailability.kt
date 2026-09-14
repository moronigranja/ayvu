package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta

/**
 * Why a book's read-in target is not being rendered — the pure decision both
 * the reader sheet (feature-player's [com.moronigranja.localttsreader
 * .featureplayer.playback.EngineSelector]) and the library dialog
 * (feature-library) surface as the picker's feedback row. A silent degrade
 * reads as "broken" (S22 2026-09-14: the user's pick changed nothing and
 * nothing said why).
 */
object TranslateAvailability {
    /**
     * Human reason [target] is not rendering, or null when the translation is
     * in force (or [target] is null — Off). [catalog] is the active engine's
     * voice metadata; [voiceServable] answers whether the resolved target
     * voice can actually synthesize (Piper: its pack is downloaded; Kokoro:
     * always true — one model + voices pack serves the whole catalog);
     * [translatorReady]/[translatorFailure] mirror the runtime's open state.
     */
    fun degradeReason(
        target: String?,
        catalog: List<KokoroVoiceMeta>,
        voiceServable: (String) -> Boolean,
        translatorReady: Boolean,
        translatorFailure: String?,
    ): String? {
        target ?: return null
        if (Small100Lang.toSmall100(target) == null) return "language $target not supported"
        val voice =
            TranslateLanguages.firstVoiceFor(catalog, target)
                ?: return "no $target voice for the active engine"
        if (!voiceServable(voice)) return "the $target voice pack is not downloaded"
        if (!translatorReady) {
            return translatorFailure?.let { "translator unavailable: $it" } ?: "translator not ready"
        }
        return null
    }
}
