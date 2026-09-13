package com.moronigranja.localttsreader.persistence

/**
 * Typed accessors over the generic `settings` table (V1). Unknown or corrupt
 * stored values fall back to defaults — a missing row and a bad value are
 * equivalent, and both are user-recoverable from the settings UI.
 *
 * Regular (non-suspend) accessors are for the playback hot path: the app
 * supplies a cached [com.moronigranja.localttsreader.featuresettings
 * .AppSettings] singleton that mirrors these keys in memory; these direct
 * calls are the source of truth underneath it.
 */
class SettingsStore(private val settingsDao: SettingsDao) {

    /** Recall floor for share matches (decisions #3), default 0.6. */
    suspend fun matchThreshold(): Double =
        settingsDao.get(KEY_MATCH_THRESHOLD)?.toDoubleOrNull() ?: DEFAULT_MATCH_THRESHOLD

    suspend fun setMatchThreshold(value: Double) {
        require(value in 0.0..1.0) { "match threshold must be within 0..1, was $value" }
        settingsDao.put(SettingEntity(KEY_MATCH_THRESHOLD, value.toString()))
    }

    /** The Kokoro voice played by default (V1 voice picker), default af_heart. */
    suspend fun voice(): String =
        settingsDao.get(KEY_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

    suspend fun setVoice(value: String) {
        require(value.isNotBlank()) { "voice must not be blank" }
        settingsDao.put(SettingEntity(KEY_VOICE, value))
    }

    /** The user's starred voices, in picker order (V1 favorites). */
    suspend fun favoriteVoices(): List<String> =
        settingsDao.get(KEY_FAVORITE_VOICES)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun setFavoriteVoices(value: List<String>) {
        require(value.all { it.isNotBlank() }) { "favorite voices must not be blank" }
        settingsDao.put(SettingEntity(KEY_FAVORITE_VOICES, value.joinToString("\n")))
    }

    /** UI theme: system / light / dark (V1). */
    suspend fun themeMode(): ThemeMode =
        ThemeMode.from(settingsDao.get(KEY_THEME_MODE))

    suspend fun setThemeMode(value: ThemeMode) {
        settingsDao.put(SettingEntity(KEY_THEME_MODE, value.key))
    }

    /** Installed OCR languages for the share flow (S1/S2), default English only. */
    suspend fun ocrLanguages(): List<String> =
        settingsDao.get(KEY_OCR_LANGUAGES)?.split('\n')?.filter { it.isNotBlank() }
            ?: listOf(DEFAULT_OCR_LANGUAGE)

    suspend fun setOcrLanguages(value: List<String>) {
        require(value.all { it.isNotBlank() }) { "OCR languages must not be blank" }
        settingsDao.put(SettingEntity(KEY_OCR_LANGUAGES, value.joinToString("\n")))
    }

    /** The speech engine id (C1.5, decisions #102): `kokoro-82m` (default) or
     * the zero-download `system-tts` degraded fallback. */
    suspend fun ttsEngine(): String =
        settingsDao.get(KEY_TTS_ENGINE)?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_ENGINE

    suspend fun setTtsEngine(value: String) {
        require(value.isNotBlank()) { "tts engine must not be blank" }
        settingsDao.put(SettingEntity(KEY_TTS_ENGINE, value))
    }

    /** ORT intra-op thread count for Kokoro synthesis (decisions #137): the
     * size of the ONNX thread pool that saturates a phone while generating.
     * Fewer threads leave cores free for the UI; more generate faster.
     * Clamped to supported bounds; the value applies on the next engine open. */
    suspend fun ttsThreads(): Int {
        val stored = settingsDao.get(KEY_TTS_THREADS)?.toIntOrNull()
        return stored?.coerceIn(MIN_TTS_THREADS, MAX_TTS_THREADS) ?: DEFAULT_TTS_THREADS
    }

    suspend fun setTtsThreads(value: Int) {
        settingsDao.put(SettingEntity(KEY_TTS_THREADS, value.coerceIn(MIN_TTS_THREADS, MAX_TTS_THREADS).toString()))
    }

    /** Linear playback gain applied to the generated voice (a multiplier on
     * top of the device media volume): 1.0 = unity, > 1.0 amplifies. The
     * value is clamped by the output to the platform's `AudioTrack` max. */
    suspend fun playbackGain(): Float =
        settingsDao.get(KEY_PLAYBACK_GAIN)?.toFloatOrNull() ?: DEFAULT_PLAYBACK_GAIN

    suspend fun setPlaybackGain(value: Float) {
        require(value in PLAYBACK_GAIN_MIN..PLAYBACK_GAIN_MAX) { "playback gain must be within ${PLAYBACK_GAIN_MIN}..${PLAYBACK_GAIN_MAX}, was $value" }
        settingsDao.put(SettingEntity(KEY_PLAYBACK_GAIN, value.toString()))
    }

    /** Accumulated wall-clock / audio-duration samples of the realtime probe
     * (item 8, D2): [rtfWallMs] is synthesis wall time, [rtfAudioMs] the
     * rendered audio duration. Realtime when wall <= audio over >= 10 s of
     * audio; short samples OVERSTATe RTF (#93), so the derivation ignores
     * them. Both are cumulative — every Preview and live-synthesis sample
     * contributes (0 = never measured). */
    suspend fun rtfWallMs(): Long = settingsDao.get(KEY_RTF_WALL_MS)?.toLongOrNull() ?: 0L

    suspend fun rtfAudioMs(): Long = settingsDao.get(KEY_RTF_AUDIO_MS)?.toLongOrNull() ?: 0L

    suspend fun putRtf(
        wallMs: Long,
        audioMs: Long,
    ) {
        require(wallMs >= 0 && audioMs >= 0) { "rtf samples must be non-negative, were $wallMs/$audioMs" }
        settingsDao.putAll(
            listOf(
                SettingEntity(KEY_RTF_WALL_MS, wallMs.toString()),
                SettingEntity(KEY_RTF_AUDIO_MS, audioMs.toString()),
            ),
        )
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.6
        const val DEFAULT_VOICE = "af_heart"
        const val DEFAULT_OCR_LANGUAGE = "eng"
        const val DEFAULT_TTS_ENGINE = "kokoro-82m"
        /** D4 (decisions #154/#154-addendum): the Piper small tier — the id
         * matches DefaultEngines.piper; selection is explicit, never auto. */
        const val PIPER_ENGINE = "piper-v1"
        const val SYSTEM_TTS_ENGINE = "system-tts"

        /** Playback gain bounds + default (linear multiplier, 1.0 = unity). */
        const val DEFAULT_PLAYBACK_GAIN = 1.0f
        const val PLAYBACK_GAIN_MIN = 0.5f
        const val PLAYBACK_GAIN_MAX = 2.0f

        const val KEY_MATCH_THRESHOLD = "match_threshold"
        const val KEY_VOICE = "voice"
        const val KEY_FAVORITE_VOICES = "favorite_voices"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_OCR_LANGUAGES = "ocr_languages"
        const val KEY_TTS_ENGINE = "tts_engine"

        /** ORT intra-op threads for Kokoro synthesis (decisions #137). Default 4
         * (the old hardcoded 6 saturated the S22's 8 cores — decisions #116);
         * range covers 4-core phones up to today's 8-core flagships. */
        const val DEFAULT_TTS_THREADS = 4
        const val MIN_TTS_THREADS = 1
        const val MAX_TTS_THREADS = 8
        const val KEY_TTS_THREADS = "tts_threads"
        const val KEY_PLAYBACK_GAIN = "playback_gain"
        const val KEY_RTF_WALL_MS = "rtf_wall_ms"
        const val KEY_RTF_AUDIO_MS = "rtf_audio_ms"
    }
}

/** How the app picks its light/dark palette (V1 theme-follows-system). */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(raw: String?): ThemeMode = entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}
