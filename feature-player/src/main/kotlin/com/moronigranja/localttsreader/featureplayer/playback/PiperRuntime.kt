package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.kokoro.EspeakPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.NormalizingPhonemizer
import com.moronigranja.localttsreader.tts.piper.PiperEngine
import com.moronigranja.localttsreader.tts.piper.PiperPacks
import com.moronigranja.localttsreader.tts.piper.PiperVoices
import dagger.hilt.android.qualifiers.ApplicationContext
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
        @Volatile private var engine: TTSEngine? = null

        @Volatile private var engineVoice: String? = null

        @Volatile private var failure: String? = null
        private var failedOpens = 0

        /** The voice this runtime serves for [stored] (see class doc). */
        fun voiceFor(stored: String): String =
            if (stored in PiperVoices.all) stored else PiperEngine.DEFAULT_VOICE

        /**
         * The ready engine for the resolved voice, or null with [failure] set.
         * A resolved-voice change discards the cached instance (one voice per
         * instance) and re-opens on the next call.
         */
        open fun engine(): TTSEngine? {
            val voice = voiceFor(settings.state.value.voice)
            engine?.takeIf { engineVoice == voice }?.let { return it }
            synchronized(this) {
                engine?.takeIf { engineVoice == voice }?.let { return it }
                if (failedOpens >= MAX_FAILED_OPEN_ATTEMPTS) return null
                val missing = missingPrerequisites(voice)
                if (missing != null) {
                    failure = missing
                    return null
                }
                return try {
                    openEngine(voice).also {
                        engine = it
                        engineVoice = voice
                        failure = null
                        failedOpens = 0
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
                phonemizer = NormalizingPhonemizer(
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
        }
    }
