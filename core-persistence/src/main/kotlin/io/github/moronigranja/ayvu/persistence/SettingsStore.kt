package io.github.moronigranja.ayvu.persistence

import io.github.moronigranja.ayvu.player.DisplayMode

/**
 * Typed accessors over the generic `settings` table (V1). Unknown or corrupt
 * stored values fall back to defaults — a missing row and a bad value are
 * equivalent, and both are user-recoverable from the settings UI.
 *
 * Regular (non-suspend) accessors are for the playback hot path: the app
 * supplies a cached [io.github.moronigranja.ayvu.featuresettings
 * .AppSettings] singleton that mirrors these keys in memory; these direct
 * calls are the source of truth underneath it.
 */
class SettingsStore(
    private val settingsDao: SettingsDao,
) {
    /** Recall floor for share matches (decisions #3), default 0.6. */
    suspend fun matchThreshold(): Double = settingsDao.get(KEY_MATCH_THRESHOLD)?.toDoubleOrNull() ?: DEFAULT_MATCH_THRESHOLD

    suspend fun setMatchThreshold(value: Double) {
        require(value in 0.0..1.0) { "match threshold must be within 0..1, was $value" }
        settingsDao.put(SettingEntity(KEY_MATCH_THRESHOLD, value.toString()))
    }

    /** The Kokoro voice played by default (V1 voice picker), default af_heart. */
    suspend fun voice(): String = settingsDao.get(KEY_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

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

    /**
     * Per-book voice override (decisions #144, Phase K item 5): one generic
     * row `book.voice.<bookId>` in this same table — no Room migration, and
     * it rides the existing backup archive (snapshot dumps every row raw;
     * the merge's "restored keys overwrite local" precedence applies) and is
     * dropped with the book ([RoomLibraryStore.delete]). Absent key = no
     * override; resolution is `override(bookId) ?: [voice]` at the playback
     * choke point. An explicit override beats the global default, and
     * changing the global default neither clears nor rewrites overrides.
     */
    suspend fun bookVoice(bookId: String): String? = settingsDao.get(bookVoiceKey(bookId))?.takeIf { it.isNotBlank() }

    /** Writes or clears [bookId]'s override (null clears — the sheet's
     * explicit "use default"). */
    suspend fun setBookVoice(
        bookId: String,
        voice: String?,
    ) {
        require(bookId.isNotBlank()) { "book id must not be blank" }
        val key = bookVoiceKey(bookId)
        if (voice == null) {
            settingsDao.delete(key)
        } else {
            require(voice.isNotBlank()) { "voice must not be blank" }
            settingsDao.put(SettingEntity(key, voice))
        }
    }

    /** Every stored override (bookId → voice id) — the mirror's reload read.
     * The table is tiny, so this is one scan of [SettingsDao.all]. */
    suspend fun bookVoices(): Map<String, String> =
        settingsDao
            .all()
            .filter { it.key.startsWith(KEY_BOOK_VOICE_PREFIX) && it.value.isNotBlank() }
            .associate { it.key.removePrefix(KEY_BOOK_VOICE_PREFIX) to it.value }

    /**
     * Per-book read-in-language target (decisions #114): one generic row
     * `book.translate.<bookId>` holding the target APP language code (e.g.
     * `pt-BR`), mirroring the `book.voice.` precedent exactly — same table,
     * no migration, rides the existing backup archive raw and is dropped with
     * the book. Absent key = read in the book's original language; resolution
     * lives in [io.github.moronigranja.ayvu.featureplayer.playback
     * .EngineSelector.resolve].
     */
    suspend fun bookTranslate(bookId: String): String? = settingsDao.get(bookTranslateKey(bookId))?.takeIf { it.isNotBlank() }

    /** Writes or clears [bookId]'s translate target (null clears — "Off"). */
    suspend fun setBookTranslate(
        bookId: String,
        targetLang: String?,
    ) {
        require(bookId.isNotBlank()) { "book id must not be blank" }
        val key = bookTranslateKey(bookId)
        if (targetLang == null) {
            settingsDao.delete(key)
        } else {
            require(targetLang.isNotBlank()) { "target language must not be blank" }
            settingsDao.put(SettingEntity(key, targetLang))
        }
    }

    /** Every stored translate target (bookId → app language code) — the
     * mirror's reload read (one scan, same as [bookVoices]). */
    suspend fun bookTranslates(): Map<String, String> =
        settingsDao
            .all()
            .filter { it.key.startsWith(KEY_BOOK_TRANSLATE_PREFIX) && it.value.isNotBlank() }
            .associate { it.key.removePrefix(KEY_BOOK_TRANSLATE_PREFIX) to it.value }

    /**
     * Per-book DISPLAY language (read-in-language display): one generic row
     * `book.display.<bookId>` holding the app language code whose translation
     * the reader SHOWS — the same table ride-along as `book.translate.`, no
     * migration, rides the backup archive raw and is dropped with the book.
     * Absent key = show the original language.
     *
     * Speech constraint (a passage is translated AT MOST once): with a display
     * language set, the spoken language is either the original (Off) or that
     * same translation — the write normalizes a mismatched speech target away
     * ([setBookDisplay] clears `book.translate.`), so a stored mismatch can
     * only exist if written directly; the playback choke point degrades it.
     */
    suspend fun bookDisplay(bookId: String): String? = settingsDao.get(bookDisplayKey(bookId))?.takeIf { it.isNotBlank() }

    /** Writes or clears [bookId]'s display language. A non-null [lang]
     * normalizes the speech target: when the stored speech target differs,
     * it is cleared in the same call (Off) — the display and the speech must
     * never name two different translations of one passage. Clearing the
     * display leaves the speech target alone. */
    suspend fun setBookDisplay(
        bookId: String,
        lang: String?,
    ) {
        require(bookId.isNotBlank()) { "book id must not be blank" }
        val key = bookDisplayKey(bookId)
        if (lang == null) {
            settingsDao.delete(key)
        } else {
            require(lang.isNotBlank()) { "display language must not be blank" }
            settingsDao.put(SettingEntity(key, lang))
            // At most one translation per passage: a speech target other than
            // the display's would be a second LLM pass + a second render.
            val speech = settingsDao.get(bookTranslateKey(bookId))?.takeIf { it.isNotBlank() }
            if (speech != null && speech != lang) settingsDao.delete(bookTranslateKey(bookId))
        }
    }

    /** Every stored display language (bookId → app language code) — the
     * mirror's reload read (one scan, same as [bookVoices]). */
    suspend fun bookDisplays(): Map<String, String> =
        settingsDao
            .all()
            .filter { it.key.startsWith(KEY_BOOK_DISPLAY_PREFIX) && it.value.isNotBlank() }
            .associate { it.key.removePrefix(KEY_BOOK_DISPLAY_PREFIX) to it.value }

    /** The reader's display mode — a reading STYLE, global (interleaved
     * default; translated-only shows the translation alone). */
    suspend fun displayMode(): DisplayMode = DisplayMode.from(settingsDao.get(KEY_DISPLAY_MODE))

    suspend fun setDisplayMode(value: DisplayMode) {
        settingsDao.put(SettingEntity(KEY_DISPLAY_MODE, value.key))
    }

    /** The global target-language voice for read-in-language speech; absent =
     * automatic (the catalog's first voice for the book's target language). */
    suspend fun translateVoice(): String? = settingsDao.get(KEY_TRANSLATE_VOICE)?.takeIf { it.isNotBlank() }

    suspend fun setTranslateVoice(value: String?) {
        if (value.isNullOrBlank()) {
            settingsDao.delete(KEY_TRANSLATE_VOICE)
        } else {
            settingsDao.put(SettingEntity(KEY_TRANSLATE_VOICE, value))
        }
    }

    /** UI theme: system / light / dark (V1). */
    suspend fun themeMode(): ThemeMode = ThemeMode.from(settingsDao.get(KEY_THEME_MODE))

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
    suspend fun ttsEngine(): String = settingsDao.get(KEY_TTS_ENGINE)?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_ENGINE

    suspend fun setTtsEngine(value: String) {
        require(value.isNotBlank()) { "tts engine must not be blank" }
        settingsDao.put(SettingEntity(KEY_TTS_ENGINE, value))
    }

    /**
     * The read-in-language translate engine (the second-engine option,
     * decisions #182): one engine id from the translate engine registry
     * (`core-translate`'s `TranslatePacks`), naming the staged pack the
     * translator opens AND the `t<id>` cache segment every translation of the
     * book is keyed under.
     *
     * Default = the shipped LFM2.5-1.2B, so an untouched install behaves
     * exactly as before. Unknown values fall back to the default — a stored id
     * from a removed option can never leave a book unable to translate (the
     * same rule [ThemeMode] follows).
     */
    suspend fun translateEngine(): String = settingsDao.get(KEY_TRANSLATE_ENGINE)?.takeIf { it.isNotBlank() } ?: DEFAULT_TRANSLATE_ENGINE

    suspend fun setTranslateEngine(value: String) {
        require(value.isNotBlank()) { "translate engine must not be blank" }
        settingsDao.put(SettingEntity(KEY_TRANSLATE_ENGINE, value))
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
    suspend fun playbackGain(): Float = settingsDao.get(KEY_PLAYBACK_GAIN)?.toFloatOrNull() ?: DEFAULT_PLAYBACK_GAIN

    suspend fun setPlaybackGain(value: Float) {
        require(value in PLAYBACK_GAIN_MIN..PLAYBACK_GAIN_MAX) {
            "playback gain must be within ${PLAYBACK_GAIN_MIN}..${PLAYBACK_GAIN_MAX}, was $value"
        }
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

        /** The read-in-language engine id (decisions #182). The value space is
         * [io.github.moronigranja.ayvu.tts.translate.TranslatePacks]'s engine
         * ids; the default is the shipped LFM2.5-1.2B. */
        const val DEFAULT_TRANSLATE_ENGINE = "lfm12b"
        const val KEY_TRANSLATE_ENGINE = "translate_engine"

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

        /** Per-book voice override keys (decisions #144, Phase K item 5):
         * `<prefix><bookId>`, one row per overridden book in this same
         * generic table. */
        const val KEY_BOOK_VOICE_PREFIX = "book.voice."

        fun bookVoiceKey(bookId: String): String = KEY_BOOK_VOICE_PREFIX + bookId

        /** Per-book read-in-language keys (decisions #114): `<prefix><bookId>`
         * → the target app language code, same generic-table ride-along as
         * [KEY_BOOK_VOICE_PREFIX]. */
        const val KEY_BOOK_TRANSLATE_PREFIX = "book.translate."

        fun bookTranslateKey(bookId: String): String = KEY_BOOK_TRANSLATE_PREFIX + bookId

        /** Per-book display-language keys (read-in-language display):
         * `<prefix><bookId>` → the target app language code whose translation
         * the reader shows, same generic-table ride-along as
         * [KEY_BOOK_TRANSLATE_PREFIX]. */
        const val KEY_BOOK_DISPLAY_PREFIX = "book.display."

        fun bookDisplayKey(bookId: String): String = KEY_BOOK_DISPLAY_PREFIX + bookId

        /** The reader's display MODE (a reading style — global, not per book):
         * interleaved default; translated_only shows the translation alone. */
        const val KEY_DISPLAY_MODE = "display_mode"

        /** The global target-language voice for read-in-language speech; absent =
         * automatic (the catalog's first voice for the book's target language). */
        const val KEY_TRANSLATE_VOICE = "translate_voice"
    }
}

/** How the app picks its light/dark palette (V1 theme-follows-system). */
enum class ThemeMode(
    val key: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun from(raw: String?): ThemeMode = entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}
