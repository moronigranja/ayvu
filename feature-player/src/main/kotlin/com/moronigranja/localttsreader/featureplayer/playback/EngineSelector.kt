package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.TTSEngine
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * C1.5/decisions #102, extended by the D4 selection wiring (#154 addendum):
 * the playback engine seam. PlaybackService and PregenWorker keep one neutral
 * entry point; the persisted `tts_engine` setting picks between
 * - the zero-download [SystemTtsEngine] (bound app-side under
 *   `@Named("system_tts")`, realized lazily so an open-weight session never
 *   touches the device TTS) — the degraded device voice,
 * - the [PiperRuntime] engine (D4 small tier, downloaded packs),
 * - else the ready [KokoroRuntime] engine.
 *
 * The selected engine changes only via [AppSettings] (the Settings screen's
 * "Speech engine" row / setup opt-in), and the service re-reads it on every
 * play/resume — no restart needed. Selection is explicit: nothing here
 * auto-switches engines (decisions #154 — Piper is never auto-selected).
 */
@Singleton
class EngineSelector
    @Inject
    constructor(
        private val runtime: KokoroRuntime,
        private val piper: PiperRuntime,
        @Named("system_tts") private val systemTts: Lazy<TTSEngine>,
        private val settings: AppSettings,
    ) {
        private val selected: String
            get() = settings.state.value.ttsEngine

        /** True when the degraded system voice is selected (drives the
         * PlayerCard's "Device voice" pill via PlaybackUiState.degraded).
         * Piper is a PRIMARY engine class, not degraded (#154). */
        val isDegraded: Boolean
            get() = selected == SettingsStore.SYSTEM_TTS_ENGINE

        /** The active engine, or null when its prerequisites are missing. */
        fun engine(): TTSEngine? =
            when {
                isDegraded -> systemTts.get()
                selected == SettingsStore.PIPER_ENGINE -> piper.engine()
                else -> runtime.engine()
            }

        /**
         * The active engine opened to serve the RESOLVED [voice] — the
         * result of [resolveVoice]/[effectiveVoice], never a raw stored
         * id — or null when its prerequisites are missing. Piper's
         * one-voice-per-instance contract re-points the runtime when the
         * resolved voice differs from the global one (a per-book override,
         * decisions #144); Kokoro and the system voice serve any voice.
         */
        fun engineFor(voice: String): TTSEngine? =
            when {
                isDegraded -> systemTts.get()
                selected == SettingsStore.PIPER_ENGINE -> piper.engineFor(voice)
                else -> runtime.engine()
            }

        /**
         * The voice the ACTIVE engine serves for [bookId] (decisions #144
         * item 5): the book's per-book override when one is stored, else the
         * global default — run through [resolveVoice]'s availability shape,
         * so playback, coverage keys and pre-generation always name a voice
         * the engine actually serves (an override the engine does not expose
         * falls back to the engine's default; the sheet marks the row
         * unavailable). Changing the global default neither clears nor
         * rewrites overrides, so this reads the mirror per call.
         */
        fun effectiveVoice(bookId: String): String =
            resolveVoice(settings.state.value.bookVoices[bookId] ?: settings.state.value.voice)

        /**
         * The voice id the ACTIVE engine serves for the stored global voice
         * (decisions #144 availability shape): engine-exposed ids pass
         * through, anything else falls back to that engine's default voice —
         * playback and cache keys always name a voice the engine actually
         * serves (a Kokoro name must never reach the one-voice-per-instance
         * Piper session, which fails typed on unknown voices).
         */
        fun resolveVoice(stored: String): String =
            if (selected == SettingsStore.PIPER_ENGINE) piper.voiceFor(stored) else stored

        /** Missing-prerequisite reason for the non-degraded engine; null in
         * degraded mode (system synthesis failures surface per passage). */
        val failureReason: String?
            get() =
                when {
                    isDegraded -> null
                    selected == SettingsStore.PIPER_ENGINE -> piper.failureReason
                    else -> runtime.failureReason
                }
    }
