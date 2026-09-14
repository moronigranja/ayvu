package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.translate.Sm100Translator
import com.moronigranja.localttsreader.tts.translate.Small100Tokenizer
import com.moronigranja.localttsreader.tts.translate.TranslatePackStager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-in-language runtime (decisions #114): the app-scoped SMaLL-100
 * translator over the downloaded + staged translate pack — the
 * [KokoroRuntime]/[PiperRuntime] shape with the HiBreak co-residency guard.
 *
 * The ~1.06 GB PSS leg (three int8 graphs) must never sit resident during
 * original-language listening: opening is lazy, and every [translator()]
 * touch re-arms a 60 s idle timer that closes all three sessions after the
 * last translate call. With translation OFF no call ever reaches
 * [translator()] (the selector's resolve() only wraps when a target exists),
 * so the leg is never opened at all. There is no programmatic memory cap on
 * the platform — this timer IS the guard (plus the per-book opt-in and the
 * degrade-to-original failure path, #101).
 *
 * Open retries mirror PiperRuntime (QW3): a prerequisite-missing failure is
 * re-checked per call; a genuine open failure is capped at
 * [MAX_FAILED_OPEN_ATTEMPTS] per process until an idle close resets the
 * window.
 */
@Singleton
class TranslateRuntime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
    ) {
        @Volatile private var translator: Sm100Translator? = null

        @Volatile private var failure: String? = null
        private var failedOpens = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var closeJob: Job? = null

        /**
         * The ready translator, or null with [failureReason] set. Re-arms the
         * idle-close timer on every call — resolved per synthesize, so the
         * leg stays resident exactly while translation is being used.
         */
        fun translator(): Sm100Translator? {
            translator?.let {
                armIdleClose()
                return it
            }
            synchronized(this) {
                translator?.let {
                    armIdleClose()
                    return it
                }
                if (failedOpens >= MAX_FAILED_OPEN_ATTEMPTS) return null
                val missing = missingPrerequisites()
                if (missing != null) {
                    failure = missing
                    return null
                }
                return try {
                    openTranslator().also {
                        translator = it
                        failure = null
                        failedOpens = 0
                        armIdleClose()
                    }
                } catch (e: Throwable) {
                    failedOpens++
                    failure = e.message ?: "translate session open failed"
                    android.util.Log.w("TranslateRuntime", "open failed: $failure", e)
                    null
                }
            }
        }

        /** Missing-prerequisite message, or null when every file is present. */
        private fun missingPrerequisites(): String? =
            if (TranslatePackStager.isStaged(context.filesDir)) {
                null
            } else {
                "translation pack not ready — download it in Speech settings"
            }

        /** Opens the translator over the staged bundle; the tokenizer needs
         * the SPM + vocab, the sessions the three int8 graphs. */
        private fun openTranslator(): Sm100Translator {
            val bundle = TranslatePackStager.bundleDir(context.filesDir)
            val tokenizer =
                Small100Tokenizer.load(
                    File(bundle, "sentencepiece.bpe.model"),
                    File(bundle, "vocab.json"),
                )
            val threads = settings.state.value.ttsThreads
            return Sm100Translator.open(bundle, tokenizer) { it.setIntraOpNumThreads(threads) }
        }

        private fun armIdleClose() {
            closeJob?.cancel()
            closeJob =
                scope.launch {
                    delay(IDLE_CLOSE_MS)
                    synchronized(this@TranslateRuntime) {
                        translator?.close()
                        translator = null
                        closeJob = null
                        failedOpens = 0
                        android.util.Log.d("TranslateRuntime", "idle close after $IDLE_CLOSE_MS ms")
                    }
                }
        }

        val failureReason: String? get() = failure

        companion object {
            /** Per-process retry cap for genuine open failures (corrupt pack). */
            const val MAX_FAILED_OPEN_ATTEMPTS = 3

            /** Sessions close 60 s after the last translate call — the
             * HiBreak co-residency guard: ~1.06 GB PSS is never resident
             * during original-language listening. */
            const val IDLE_CLOSE_MS = 60_000L
        }
    }
