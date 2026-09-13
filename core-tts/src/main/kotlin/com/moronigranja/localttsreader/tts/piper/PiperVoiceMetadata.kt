package com.moronigranja.localttsreader.tts.piper

import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta

/**
 * Static voice metadata for the piper-v1 voices (D4 selection wiring,
 * decisions #154 addendum): presentation data for "choose a voice before
 * download" — the engine instance is the contract (its config json carries
 * the real phoneme map/scales); this table only feeds the ONE shared voice
 * selector. [KokoroVoiceMeta] is the shared presentation row shape of
 * `buildVoiceSelectorState` (renaming it is the K2 engine-agnostic-rows
 * refactor, deliberately out of scope). Gender is the rhasspy/piper-voices
 * voice-card presentation (lessac f, thorsten m); Piper has no upstream
 * grade, so [KokoroVoiceMeta.grade] stays null.
 */
object PiperVoiceMetadata {
    val all: List<KokoroVoiceMeta> =
        listOf(
            KokoroVoiceMeta(
                name = PiperVoices.LESSAC,
                language = "English (US)",
                gender = "Female",
                displayName = "Lessac",
            ),
            KokoroVoiceMeta(
                name = PiperVoices.THORSTEN,
                language = "German",
                gender = "Male",
                displayName = "Thorsten",
            ),
        )
}

/**
 * One fixed, language-appropriate audition phrase per Piper voice — the
 * [com.moronigranja.localttsreader.tts.kokoro.VoicePreview] pattern for the
 * piper ids (unknown names degrade to null; the audition surfaces a typed
 * failure, never a silent fallback).
 */
object PiperVoicePreview {
    private val PHRASES: Map<String, String> =
        mapOf(
            PiperVoices.LESSAC to "The quick brown fox jumps over the lazy dog.",
            PiperVoices.THORSTEN to "Der schnelle braune Fuchs springt über den faulen Hund.",
        )

    /** The fixed phrase for [voice], or null when the name is not a known
     * Piper voice. */
    fun phraseFor(voice: String): String? = PHRASES[voice]
}
