package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.kokoro.EspeakPhonemizer
import io.github.moronigranja.ayvu.tts.kokoro.NormalizingPhonemizer
import io.github.moronigranja.ayvu.tts.piper.PiperEngine
import io.github.moronigranja.ayvu.tts.piper.PiperPacks
import io.github.moronigranja.ayvu.tts.piper.PiperVoices
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D4 selection wiring (decisions #154 addendum): the app-scoped Piper engine,
 * opened lazily over the downloaded packs — the [KokoroRuntime] pattern with
 * Piper's one-voice-per-instance contract: an instance serves exactly one
 * [PiperVoices] id (its model + `.onnx.json` config, [PiperPacks.forVoice]),
 * and a voice change re-points the runtime at a fresh instance for that voice.
 *
 * Voice resolution (decisions #144 availability shape): the stored global
 * voice passes through when the engine exposes it; anything else — a Kokoro
 * name, a stale id — falls back to the engine's default voice
 * ([PiperEngine.DEFAULT_VOICE]). The voice sheet marks such a saved voice
 * unavailable; playback never asks the engine for a voice it would fail
 * typed on.
 *
 * Model + voices share the espeak-ng staged bundle with Kokoro
 * (`files/espeak/`, decision #32) — Piper phonemizes through the same
 * [EspeakPhonemizer] seam.
 *
 * Open retries mirror [KokoroRuntime] (QW3): a prerequisite-missing failure is
 * transient and re-checked per call; a genuine open failure is capped at
 * [MAX_FAILED_OPEN_ATTEMPTS] per process. A superseded instance is NOT closed
 * on voice change — process-scoped like Kokoro's single session (closing under
 * a live caller races in-flight synthesis); at most one session per Piper
 * voice ever exists.
 */
@Singleton
open class PiperRuntime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
    ) {
        /**
         * Per-voice engine instances. The one-slot cache this replaces
         * re-pointed on every voice change — and the translate decorator
         * needs the ORIGINAL and TARGET voices alive simultaneously, so
         * resolve()'s two engineFor calls thrashed the slot: one model load
         * per passage (S22 2026-09-14, "stops to load every half sentence").
         * Bounded: each entry holds an ORT session (~tens of MB).
         */
        private val engines = java.util.concurrent.ConcurrentHashMap<String, TTSEngine>()

        @Volatile private var failure: String? = null
        private var failedOpens = 0

        /** The voice this runtime serves for [stored] (see class doc). */
        fun voiceFor(stored: String): String = if (stored in PiperVoices.all) stored else PiperEngine.DEFAULT_VOICE

        /** True when [voice]'s model + config files are on disk — the
         * pack-readiness probe for the translate resolve gate (no session
         * open; the files-check only). */
        fun voicePackReady(voice: String): Boolean = missingPrerequisites(voice) == null

        /**
         * The ready engine for the resolved global voice, or null with
         * [failure] set. Cached per voice — see [engines].
         */
        open fun engine(): TTSEngine? = engineFor(voiceFor(settings.state.value.voice))

        /**
         * The ready engine serving exactly [voice] (the #144 per-book
         * override path). Cached per voice — simultaneous voices (playback +
         * translate target) must coexist. Same open/retry semantics as the
         * single-slot cache it replaces.
         */
        open fun engineFor(voice: String): TTSEngine? {
            engines[voice]?.let { return it }
            synchronized(this) {
                engines[voice]?.let { return it }
                if (failedOpens >= MAX_FAILED_OPEN_ATTEMPTS) return null
                val missing = missingPrerequisites(voice)
                if (missing != null) {
                    failure = missing
                    return null
                }
                return try {
                    openEngine(voice).also { opened ->
                        engines[voice] = opened
                        failure = null
                        failedOpens = 0
                        while (engines.size > MAX_CACHED_ENGINES) {
                            engines.keys.firstOrNull()?.let { engines.remove(it) }
                        }
                    }
                } catch (e: Throwable) {
                    failedOpens++
                    failure = e.message ?: "engine open failed"
                    null
                }
            }
        }

        /** Missing-prerequisite message, or null when every file is present. */
        protected open fun missingPrerequisites(voice: String): String? {
            val (model, config, espeakLib, espeakData) = prerequisites(voice)
            return when {
                !model.isFile ->
                    "Piper voice model not ready — download it first (files/packs/piper-v1/)"
                !config.isFile -> "Piper voice config not ready"
                !(espeakLib.isFile && espeakData.isDirectory) ->
                    "espeak-ng bundle not ready — download it in Settings (Engine section)"
                else -> null
            }
        }

        /**
         * Opens the engine over present prerequisites. Throws when the files
         * are present but the open fails (corrupt model, bad config) — the
         * caller counts those against the per-process retry cap.
         */
        protected open fun openEngine(voice: String): TTSEngine {
            val (model, config, espeakLib, espeakData) = prerequisites(voice)
            val threads = settings.state.value.ttsThreads
            return PiperEngine.open(
                spec = DefaultEngines.piper,
                packs = PiperPacks.forVoice(voice),
                voice = voice,
                modelFile = model,
                configFile = config,
                phonemizer =
                    NormalizingPhonemizer(
                        EspeakPhonemizer(
                            libraryPath = espeakLib.absolutePath,
                            dataPath = espeakData.absolutePath,
                        ),
                    ),
                // Decisions #137: the ORT intra-op pool is what saturates a
                // phone during generation; the user's setting caps it for
                // Piper too (fixed at open, like Kokoro).
                sessionFactory = { it.setIntraOpNumThreads(threads) },
            )
        }

        private fun prerequisites(voice: String): Prerequisites {
            val cache = PackCache(context.filesDir)
            val packs = PiperPacks.forVoice(voice)
            return Prerequisites(
                model = cache.targetFile(packs[0]),
                config = cache.targetFile(packs[1]),
                espeakLib = File(context.filesDir, "espeak/libespeak-ng.so"),
                espeakData = File(context.filesDir, "espeak/espeak-ng-data"),
            )
        }

        private data class Prerequisites(
            val model: File,
            val config: File,
            val espeakLib: File,
            val espeakData: File,
        )

        open val failureReason: String? get() = failure

        companion object {
            /** Per-process retry cap for genuine open failures (corrupt model). */
            const val MAX_FAILED_OPEN_ATTEMPTS = 3

            /** Per-voice engine instances kept open (playback voice + translate
             * target must coexist; each holds an ORT session). */
            const val MAX_CACHED_ENGINES = 3
        }
    }
