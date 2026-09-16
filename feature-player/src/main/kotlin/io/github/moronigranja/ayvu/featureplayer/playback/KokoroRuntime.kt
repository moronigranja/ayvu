package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.kokoro.EspeakPhonemizer
import io.github.moronigranja.ayvu.tts.kokoro.KokoroEngine
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.kokoro.NormalizingPhonemizer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped Kokoro engine, opened lazily (decisions #25/#32): model + voices
 * from the pack cache (runtime downloads, decision #7 — staged or downloaded
 * before first play), espeak-ng from the staged Android bundle (lib + data
 * under `files/espeak/`, decision #32 — the adb-staging script is in build.md
 * until V1 wires the pack download).
 *
 * Open retries (QW3): a prerequisite-missing failure is transient — a first
 * play before the async pack staging lands re-checks on the next call and
 * opens once the files exist. A genuine open failure (files present, open
 * threw — e.g. a corrupt model) is capped at [MAX_FAILED_OPEN_ATTEMPTS] per
 * process so play taps cannot hot-loop the 325 MB graph open.
 */
@Singleton
open class KokoroRuntime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
    ) {
        @Volatile private var engine: TTSEngine? = null

        @Volatile private var failure: String? = null
        private var failedOpens = 0

        /**
         * The ready engine, or null with [failure] set. A prerequisite-missing
         * failure does not latch: the next call re-checks the files and opens
         * once they exist. Genuine open failures retry up to
         * [MAX_FAILED_OPEN_ATTEMPTS] per process, then stay terminal so a
         * corrupt model cannot hot-loop on every play tap.
         */
        open fun engine(): TTSEngine? {
            engine?.let { return it }
            synchronized(this) {
                engine?.let { return it }
                // QW3: previously a failed open latched forever (the early
                // failure?.let fast-path here and in the lock) and nothing
                // cleared it — a first play before the packs finished async
                // staging reported 'engine unavailable' until process restart.
                if (failedOpens >= MAX_FAILED_OPEN_ATTEMPTS) return null
                val missing = missingPrerequisites()
                if (missing != null) {
                    failure = missing
                    return null
                }
                return try {
                    openEngine().also {
                        engine = it
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
        protected open fun missingPrerequisites(): String? {
            val (model, voices, espeakLib, espeakData) = prerequisites()
            return when {
                !model.isFile ->
                    "Kokoro model pack not ready — download/stage it first (files/packs/kokoro-82m/kokoro-model)"
                !voices.isFile -> "Kokoro voices pack not ready"
                !(espeakLib.isFile && espeakData.isDirectory) ->
                    "espeak-ng bundle not ready — download it in Settings (Engine section)"
                else -> null
            }
        }

        /**
         * Opens the engine over present prerequisites. Throws when the files are
         * present but the open fails (corrupt model, bad espeak lib) — the
         * caller counts those against the per-process retry cap.
         */
        protected open fun openEngine(): TTSEngine {
            val (model, voices, espeakLib, espeakData) = prerequisites()
            val threads = settings.state.value.ttsThreads
            return KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = model,
                voicesFile = voices,
                phonemizer =
                    NormalizingPhonemizer(
                        EspeakPhonemizer(
                            libraryPath = espeakLib.absolutePath,
                            dataPath = espeakData.absolutePath,
                        ),
                    ),
                // Decisions #137: the ORT intra-op pool is what saturates a phone
                // during generation (6 threads on the S22's 8 cores — #116). The
                // engine's default stays the harness-baseline 6; the app caps it
                // to the user's setting so the UI/storage threads keep cores.
                // Fixed at open: the session's pool is sized once per process.
                sessionFactory = { it.setIntraOpNumThreads(threads) },
            )
        }

        private fun prerequisites(): Prerequisites {
            val cache = PackCache(context.filesDir)
            return Prerequisites(
                model = cache.targetFile(KokoroPacks.model),
                voices = cache.targetFile(KokoroPacks.voices),
                espeakLib = File(context.filesDir, "espeak/libespeak-ng.so"),
                espeakData = File(context.filesDir, "espeak/espeak-ng-data"),
            )
        }

        private data class Prerequisites(
            val model: File,
            val voices: File,
            val espeakLib: File,
            val espeakData: File,
        )

        open val failureReason: String? get() = failure

        companion object {
            /** Per-process retry cap for genuine open failures (corrupt model). */
            const val MAX_FAILED_OPEN_ATTEMPTS = 3
        }
    }
