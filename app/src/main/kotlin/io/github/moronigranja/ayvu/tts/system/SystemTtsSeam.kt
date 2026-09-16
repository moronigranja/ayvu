package io.github.moronigranja.ayvu.tts.system

/**
 * C1.5/decisions #102: the seam between the degraded [SystemTtsEngine] and
 * the Android TextToSpeech runtime. core-tts stays pure JVM; this interface
 * + the app-side [AndroidSystemTtsSeam] keep engine logic testable with a
 * fake (no Robolectric), and a device-lottery codec quirk can be swapped
 * behind it without touching the engine contract.
 */
sealed interface TtsSynthesis {
    /** 16-bit little-endian PCM, already downmixed to mono. */
    data class Audio(
        val pcm: ByteArray,
        val sampleRateHz: Int,
    ) : TtsSynthesis

    /** The device engine is missing, failed to init, or lacks the language. */
    data object Unavailable : TtsSynthesis
}

interface SystemTtsSeam {
    /** Languages actually available on the device, ISO 639-1 codes (BCP-47
     * language subtags, "en"/"es"/"ja"…). Never throws; empty when the
     * device engine is unavailable. */
    fun availableLanguages(): Set<String>

    /**
     * Synthesizes [text] (in [language] when non-null, else the device
     * default) to mono 16-bit PCM. Never throws — [TtsSynthesis.Unavailable]
     * on init failure, missing language, or synthesis errors.
     */
    suspend fun synthesizeToPcm(
        text: String,
        language: String?,
    ): TtsSynthesis
}
