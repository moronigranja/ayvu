package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.llm.LlamaTranslator
import com.moronigranja.localttsreader.persistence.AppSettings
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
 * Read-in-language runtime (decisions #114/#161/#162): the app-scoped
 * LFM2.5-1.2B translator over the downloaded + staged translate pack — the
 * [KokoroRuntime]/[PiperRuntime] shape with the device co-residency guard.
 *
 * The ~1.6 GB RSS leg (730 MB GGUF + KV cache + runtime) must never sit
 * resident during original-language listening: opening is lazy, and every
 * [translator()] touch re-arms a 60 s idle timer that frees the model and its
 * context after the last translate call. With translation OFF no call ever
 * reaches [translator()] (the selector's resolve() only wraps when a target
 * exists), so the leg is never opened at all. There is no programmatic memory
 * cap on the platform — this timer IS the guard (plus the per-book opt-in and
 * the degrade-to-original failure path, #101).
 *
 * Open retries mirror PiperRuntime (QW3): a prerequisite-missing failure is
 * re-checked per call; a genuine open failure is capped at
 * [MAX_FAILED_OPEN_ATTEMPTS] per process until an idle close resets the window.
 */
@Singleton
open class TranslateRuntime
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
    ) {
        @Volatile private var translator: LlamaTranslator? = null

        @Volatile private var failure: String? = null
        private var failedOpens = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var closeJob: Job? = null

        /**
         * The ready translator, or null with [failureReason] set. Re-arms the
         * idle-close timer on every call — resolved per synthesize, so the
         * leg stays resident exactly while translation is being used.
         */
        fun translator(): LlamaTranslator? {
            ensureRetiredArtifactsRemoved()
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

        /**
         * Opens the translator over the staged bundle. The native side picks
         * the best arm64 CPU kernel variant for this device from
         * [Context.getApplicationInfo]`.nativeLibraryDir` (the packaged ggml
         * backend modules, decisions #162).
         */
        private fun openTranslator(): LlamaTranslator =
            LlamaTranslator.open(
                File(TranslatePackStager.bundleDir(context.filesDir), TranslatePackStager.MODEL_FILE),
                settings.state.value.ttsThreads,
                context.applicationInfo.nativeLibraryDir,
            )

        /**
         * Deletes the retired SMaLL-100 artifacts (decisions #162, clean
         * cutover): the staged `files/translate-small100/` bundle (~916 MB)
         * and its verified zip under `files/packs/translate-small100/`.
         * Upgraded installs would otherwise keep ~1.8 GB of dead weight — the
         * pack cache has no registry-reconciliation pass of its own, so the
         * delete is explicit and idempotent.
         */
        private fun deleteRetiredSmall100Artifacts() {
            val staged = File(context.filesDir, "translate-small100")
            val cached = File(context.filesDir, "packs/translate-small100")
            if (!staged.exists() && !cached.exists()) return
            val stagedOk = staged.deleteRecursively()
            val cachedOk = cached.deleteRecursively()
            android.util.Log.i(
                "TranslateRuntime",
                "removed retired SMaLL-100 artifacts (staged=$stagedOk cached=$cachedOk)",
            )
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

        @Volatile private var retiredArtifactsRemoved = false

        private fun ensureRetiredArtifactsRemoved() {
            if (retiredArtifactsRemoved) return
            synchronized(this) {
                if (retiredArtifactsRemoved) return
                deleteRetiredSmall100Artifacts()
                retiredArtifactsRemoved = true
            }
        }

        val failureReason: String? get() = failure

        /** Non-arming availability read: the pack is staged and the session is
         * not stuck in a failed-open state — a decode could start right now.
         * Unlike [translator()] this never opens the model nor re-arms the
         * idle-close timer (the reader's Pending-vs-Unavailable split must not
         * keep the ~1.6 GB leg resident during original-language reading). */
        open val canOpen: Boolean
            get() = TranslatePackStager.isStaged(context.filesDir) && failure == null

        /**
         * Translates [text] into [promptLanguage] through the shared session —
         * opens the session on demand (arming the idle close) and serializes
         * on the session mutex (the LLM is single-flight). Null when the
         * session cannot open or the result is blank. OPEN for host tests: a
         * fake subclass counts calls without ever opening the model (the
         * KokoroRuntime engine()/failureReason seam pattern).
         */
        open suspend fun translate(
            text: String,
            promptLanguage: String,
        ): String? = translator()?.translate(text, promptLanguage)?.takeIf { it.isNotBlank() }

        companion object {
            /** Per-process retry cap for genuine open failures (corrupt pack). */
            const val MAX_FAILED_OPEN_ATTEMPTS = 3

            /** Session closes 60 s after the last translate call — the device
             * co-residency guard: the ~1.6 GB RSS leg (measured llama-server
             * RSS 1.63 GB) is never resident during original-language
             * listening. */
            const val IDLE_CLOSE_MS = 60_000L
        }
    }
