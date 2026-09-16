package io.github.moronigranja.ayvu.persistence

import io.github.moronigranja.ayvu.player.DisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings mirror every UI/playback surface reads (V1): one immutable
 * [Snapshot] in a StateFlow, updated on every write and on [reload] (app
 * start, share entry, service command). Consumers observe the flow — the
 * theme radio reflects a change the moment it lands — while the hot paths
 * read `state.value.<field>` with no database and no polling.
 *
 * Pure JVM: Hilt annotations only (core-persistence has no Android deps).
 */
@Singleton
class AppSettings
    @Inject
    constructor(
        private val store: SettingsStore,
    ) {
        data class Snapshot(
            val threshold: Double = SettingsStore.DEFAULT_MATCH_THRESHOLD,
            val voice: String = SettingsStore.DEFAULT_VOICE,
            val favorites: List<String> = emptyList(),
            val theme: ThemeMode = ThemeMode.SYSTEM,
            val ocrLanguages: List<String> = listOf(SettingsStore.DEFAULT_OCR_LANGUAGE),
            /** The speech engine id (C1.5/decisions #102): kokoro-82m default,
             * system-tts degraded fallback. */
            val ttsEngine: String = SettingsStore.DEFAULT_TTS_ENGINE,
            /** Linear playback gain multiplier (1.0 = unity, > 1.0 amplifies). */
            val playbackGain: Float = SettingsStore.DEFAULT_PLAYBACK_GAIN,
            /** ORT intra-op thread count for Kokoro synthesis (decisions #137):
             * fewer cores generating = a snappier phone while pre-generating;
             * more = faster generation. Applied on the next engine open. */
            val ttsThreads: Int = SettingsStore.DEFAULT_TTS_THREADS,
            /** Realtime-capability tri-state (item 8, D2): `true` = the engine
             * generates ≥ as fast as it plays (wall ≤ audio over ≥ 10 s of
             * rendered audio), `false` = slower, `null` = unmeasured (fewer than
             * 10 s of audio samples accumulated; today's behavior keeps). */
            val realtimeCapable: Boolean? = null,
            /** Per-book voice overrides (decisions #144, Phase K item 5):
             * bookId → the voice id that book plays instead of [voice]. The
             * mirror of `book.voice.<bookId>` rows; absent = no override. */
            val bookVoices: Map<String, String> = emptyMap(),
            /** Per-book read-in-language targets (decisions #114): bookId → the
             * target APP language code. The mirror of `book.translate.<bookId>`
             * rows; absent = read in the book's original language. */
            val bookTranslate: Map<String, String> = emptyMap(),
            /** Per-book DISPLAY languages (read-in-language display): bookId →
             * the app language code whose translation the reader SHOWS. The
             * mirror of `book.display.<bookId>` rows; absent = show the
             * original language. */
            val bookDisplays: Map<String, String> = emptyMap(),
            /** The reader's display MODE (a reading STYLE, global): interleaved
             * default, translated-only shows the translation alone. */
            val displayMode: DisplayMode = DisplayMode.INTERLEAVED,
            /** The global target-language voice for read-in-language speech;
             * null = automatic (the catalog's first voice for the book's
             * target language). */
            val translateVoice: String? = null,
        )

        private val _state = MutableStateFlow(Snapshot())
        val state: StateFlow<Snapshot> = _state.asStateFlow()

        suspend fun reload() {
            _state.value =
                Snapshot(
                    threshold = store.matchThreshold(),
                    voice = store.voice(),
                    favorites = store.favoriteVoices(),
                    theme = store.themeMode(),
                    ocrLanguages = store.ocrLanguages(),
                    ttsEngine = store.ttsEngine(),
                    playbackGain = store.playbackGain(),
                    ttsThreads = store.ttsThreads(),
                    bookVoices = store.bookVoices(),
                    bookTranslate = store.bookTranslates(),
                    bookDisplays = store.bookDisplays(),
                    displayMode = store.displayMode(),
                    translateVoice = store.translateVoice(),
                    realtimeCapable = deriveRtf(store.rtfWallMs(), store.rtfAudioMs()),
                )
        }

        suspend fun setVoice(value: String) {
            store.setVoice(value)
            _state.value = _state.value.copy(voice = value)
        }

        /** The per-book override for [bookId] (decisions #144) — a non-suspend
         * read for the playback hot path; resolution (override ?: global) lives
         * at the choke point. */
        fun bookVoice(bookId: String): String? = _state.value.bookVoices[bookId]

        /** Writes or clears [bookId]'s override (null = "use default") and
         * mirrors it. The caller re-dispatches the voice change so the service
         * rebuilds under the new effective voice. */
        suspend fun setBookVoice(
            bookId: String,
            voice: String?,
        ) {
            store.setBookVoice(bookId, voice)
            _state.value =
                if (voice == null) {
                    _state.value.copy(bookVoices = _state.value.bookVoices - bookId)
                } else {
                    _state.value.copy(bookVoices = _state.value.bookVoices + (bookId to voice))
                }
        }

        /** The read-in-language target for [bookId] (decisions #114) — a
         * non-suspend read for the playback hot path; resolution lives in
         * [io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector]. */
        fun bookTranslate(bookId: String): String? = _state.value.bookTranslate[bookId]

        /** Writes or clears [bookId]'s translate target (null = "Off") and
         * mirrors it. The caller re-dispatches so the service rebuilds the book
         * under the new target (same changeVoice path). */
        suspend fun setBookTranslate(
            bookId: String,
            targetLang: String?,
        ) {
            store.setBookTranslate(bookId, targetLang)
            _state.value =
                if (targetLang == null) {
                    _state.value.copy(bookTranslate = _state.value.bookTranslate - bookId)
                } else {
                    _state.value.copy(bookTranslate = _state.value.bookTranslate + (bookId to targetLang))
                }
        }

        suspend fun setThemeMode(value: ThemeMode) {
            store.setThemeMode(value)
            _state.value = _state.value.copy(theme = value)
        }

        /** The DISPLAY language for [bookId] (read-in-language display) — a
         * non-suspend read for the reader's projection. */
        fun bookDisplay(bookId: String): String? = _state.value.bookDisplays[bookId]

        /** Writes or clears [bookId]'s display language and mirrors it. The
         * store normalizes the speech target (a mismatched speech language is
         * cleared — the display and speech name ONE translation), so the
         * mirror must re-read both keys from the store, not copy locally. */
        suspend fun setBookDisplay(
            bookId: String,
            lang: String?,
        ) {
            store.setBookDisplay(bookId, lang)
            // The store NORMALIZES the speech target on a non-null display
            // write (a mismatched speech language is cleared) — mirror the
            // post-normalization state read back from the store, so the mirror
            // never disagrees with truth (the caller rebuilds the session only
            // when the speech target actually changed).
            val speechAfter = store.bookTranslate(bookId)
            _state.value =
                if (lang == null) {
                    _state.value.copy(bookDisplays = _state.value.bookDisplays - bookId)
                } else {
                    _state.value.copy(
                        bookDisplays = _state.value.bookDisplays + (bookId to lang),
                        bookTranslate =
                            if (speechAfter == null) {
                                _state.value.bookTranslate - bookId
                            } else {
                                _state.value.bookTranslate + (bookId to speechAfter)
                            },
                    )
                }
        }

        /** The reader's display mode (global reading style). */
        fun displayMode(): DisplayMode = _state.value.displayMode

        suspend fun setDisplayMode(value: DisplayMode) {
            store.setDisplayMode(value)
            _state.value = _state.value.copy(displayMode = value)
        }

        /** The global target-language voice for read-in-language speech (null
         * = automatic) — a non-suspend read for the playback hot path. */
        fun translateVoice(): String? = _state.value.translateVoice

        suspend fun setTranslateVoice(value: String?) {
            store.setTranslateVoice(value)
            _state.value = _state.value.copy(translateVoice = value?.takeIf { it.isNotBlank() })
        }

        suspend fun setMatchThreshold(value: Double) {
            store.setMatchThreshold(value)
            _state.value = _state.value.copy(threshold = value)
        }

        suspend fun setOcrLanguages(value: List<String>) {
            store.setOcrLanguages(value)
            _state.value = _state.value.copy(ocrLanguages = value)
        }

        suspend fun setFavoriteVoices(value: List<String>) {
            store.setFavoriteVoices(value)
            _state.value = _state.value.copy(favorites = value)
        }

        suspend fun toggleFavorite(voiceName: String) {
            val current = _state.value.favorites
            val next = if (voiceName in current) current - voiceName else current + voiceName
            setFavoriteVoices(next)
        }

        suspend fun setTtsEngine(value: String) {
            store.setTtsEngine(value)
            _state.value = _state.value.copy(ttsEngine = value)
        }

        suspend fun setPlaybackGain(value: Float) {
            store.setPlaybackGain(value)
            _state.value = _state.value.copy(playbackGain = value)
        }

        suspend fun setTtsThreads(value: Int) {
            store.setTtsThreads(value)
            _state.value =
                _state.value.copy(ttsThreads = value.coerceIn(SettingsStore.MIN_TTS_THREADS, SettingsStore.MAX_TTS_THREADS))
        }

        /** Records one synthesis sample (item 8): ACCUMULATES wall and audio
         * into the persisted pair — every Preview and live passage contributes,
         * and the tri-state flips the moment the ≥ 10 s gate is crossed. */
        suspend fun setRtfSample(
            wallMs: Long,
            audioMs: Long,
        ) {
            store.putRtf(store.rtfWallMs() + wallMs, store.rtfAudioMs() + audioMs)
            _state.value = _state.value.copy(realtimeCapable = deriveRtf(store.rtfWallMs(), store.rtfAudioMs()))
        }

        /** The derivation table (null under the 10 s gate, true ≤ 1.0 realtime,
         * false slower). */
        private fun deriveRtf(
            wallMs: Long,
            audioMs: Long,
        ): Boolean? =
            when {
                // #93: short probes overstate RTF — no verdict below 10 s of audio.
                audioMs < RTF_MIN_AUDIO_MS -> null
                wallMs <= 0L -> null
                wallMs <= audioMs -> true
                else -> false
            }

        private companion object {
            /** The realtime gate (item 8/#93): verdicts need ≥ 10 s of rendered
             * audio, else the tri-state stays unmeasured. */
            const val RTF_MIN_AUDIO_MS = 10_000L
        }
    }
