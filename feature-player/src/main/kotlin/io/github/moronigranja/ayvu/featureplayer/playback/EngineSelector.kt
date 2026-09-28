package io.github.moronigranja.ayvu.featureplayer.playback

import dagger.Lazy
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.pregen.TranslationService
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMetadata
import io.github.moronigranja.ayvu.tts.piper.PiperVoiceMetadata
import io.github.moronigranja.ayvu.tts.translate.LfmLang
import io.github.moronigranja.ayvu.tts.translate.TranslateLanguages
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.tts.translate.TranslatingEngine
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
        private val translate: TranslateRuntime,
        @Named("system_tts") private val systemTts: Lazy<TTSEngine>,
        private val settings: AppSettings,
        private val translationService: TranslationService,
    ) {
        /** The engine id in force for [bookId] — the book's override
         * (`book.engine.<bookId>`) when one is stored, else the global
         * `tts_engine`. */
        fun engineIdFor(bookId: String?): String = bookId?.let { settings.state.value.bookEngines[it] } ?: settings.state.value.ttsEngine

        private fun isPiper(bookId: String?): Boolean = engineIdFor(bookId) == SettingsStore.PIPER_ENGINE

        /** True when [bookId]'s engine is the degraded system voice (drives the
         * PlayerCard's "Device voice" pill via PlaybackUiState.degraded).
         * Piper is a PRIMARY engine class, not degraded (#154). */
        fun isDegraded(bookId: String?): Boolean = engineIdFor(bookId) == SettingsStore.SYSTEM_TTS_ENGINE

        /** The active engine for [bookId] (null = the global engine), or null
         * when its prerequisites are missing. */
        fun engine(bookId: String? = null): TTSEngine? =
            when {
                isDegraded(bookId) -> systemTts.get()
                isPiper(bookId) -> piper.engine()
                else -> runtime.engine()
            }

        /**
         * The active engine for [bookId] opened to serve the RESOLVED [voice] —
         * the result of [resolveVoice]/[effectiveVoice], never a raw stored
         * id — or null when its prerequisites are missing. Piper's
         * one-voice-per-instance contract re-points the runtime when the
         * resolved voice differs from the global one (a per-book override,
         * decisions #144); Kokoro and the system voice serve any voice.
         */
        fun engineFor(
            voice: String,
            bookId: String? = null,
        ): TTSEngine? =
            when {
                isDegraded(bookId) -> systemTts.get()
                isPiper(bookId) -> piper.engineFor(voice)
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
            resolveVoice(settings.state.value.bookVoices[bookId] ?: settings.state.value.voice, bookId)

        /**
         * The voice id the ACTIVE engine serves for the stored [stored] voice
         * (decisions #144 availability shape): engine-exposed ids pass
         * through, anything else falls back to that engine's default voice —
         * playback and cache keys always name a voice the engine actually
         * serves (a Kokoro name must never reach the one-voice-per-instance
         * Piper session, which fails typed on unknown voices). [bookId] picks
         * the book's engine override; null = the global engine.
         */
        fun resolveVoice(
            stored: String,
            bookId: String? = null,
        ): String = if (isPiper(bookId)) piper.voiceFor(stored) else stored

        /** Missing-prerequisite reason for [bookId]'s non-degraded engine; null
         * in degraded mode (system synthesis failures surface per passage). */
        fun failureReason(bookId: String?): String? =
            when {
                isDegraded(bookId) -> null
                isPiper(bookId) -> piper.failureReason
                else -> runtime.failureReason
            }

        // ---- read-in-language (decisions #114) ----

        /**
         * The ACTIVE read-in-language translate engine id (decisions #182) —
         * the global `translate_engine` setting resolved through the registry,
         * so an unknown/stale stored id names the engine the runtime actually
         * opens ([TranslateRuntime]'s `activeEngine`). THE translator identity
         * of every translation: the `t<translator>` cache-key segment and the
         * stored rows' translator column ([TranslationTarget]), so one engine's
         * audio or text can never be served for another engine's request. The
         * single source every construction site reads (the reader's display
         * seed/prefetch, the speech render, both pre-generation paths).
         */
        val translateEngineId: String
            get() = TranslatePacks.byId(settings.state.value.translateEngine).id

        /**
         * The book's in-force SPEECH target (app language code), or null when
         * it reads in the original language. THE single resolution choke
         * point of the speech-vs-display constraint: a passage is translated
         * at most once — with a display language set, the spoken language is
         * either the original (Off) or that SAME translation, so a stored
         * mismatch (speech != display) degrades to original audio rather than
         * starting a second translation. [translateLangInUse],
         * [translateDegradeReason] and [resolve] all read through here.
         */
        fun translateTarget(bookId: String): String? {
            val speech = settings.bookTranslate(bookId)
            val display = settings.bookDisplay(bookId)
            // At most one translation per passage: with a display language set,
            // the spoken language is either the original (Off) or that same
            // translation.
            return if (display != null && speech != display) null else speech
        }

        /**
         * The translate lang the resolved engine actually renders for [bookId]
         * — null = original language. The CACHE-KEY dimension: it must name
         * the language really synthesized, not the raw setting — an English
         * render keyed under `x<lang>` would poison the cache for the real
         * translation once the pack arrives. Null whenever resolve() did not
         * decorate (no target, no servable target voice, no ready translator).
         */
        fun translateLangInUse(bookId: String?): String? = (resolve(bookId).first as? TranslatingEngine)?.targetLang

        /**
         * Why [bookId]'s read-in target is NOT being rendered (null = the
         * translation is in force, or no target is set). The Read-in picker's
         * feedback row — a silent degrade left the user staring at original
         * audio with no explanation (S22 2026-09-14).
         */
        fun translateDegradeReason(bookId: String?): String? =
            io.github.moronigranja.ayvu.tts.translate.TranslateAvailability.degradeReason(
                target = bookId?.let { translateTarget(it) },
                catalog = activeCatalog(bookId),
                voiceServable = { voice ->
                    !isPiper(bookId) || piper.voicePackReady(voice)
                },
                translatorReady = translate.translator() != null,
                translatorFailure = translate.failureReason,
            )

        /**
         * The first voice of the ACTIVE engine catalog whose language matches
         * [target] (normalized `-`/`_`/case; `pt-BR` matches `pt_BR` and the
         * bare base `pt`). Deterministic catalog order. The read-in-language
         * [io.github.moronigranja.ayvu.tts.translate.TranslateEngine] rows are
         * registry-only pseudo-engines, never selectable as TTS engines — this
         * only reads the selectable engines' voice rows.
         */
        fun bestVoiceFor(
            target: String,
            bookId: String? = null,
        ): String? = TranslateLanguages.firstVoiceFor(activeCatalog(bookId), target)

        /** The ACTIVE engine's voice metadata catalog (empty for the degraded
         * voice). */
        fun voiceCatalog(bookId: String? = null): List<io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMeta> = activeCatalog(bookId)

        /** The stored target-language voice, validated against the book's
         * in-force target and the active engine's catalog; null = automatic. */
        fun translateVoice(bookId: String): String? =
            storedTranslateVoice(bookId) ?: translateTarget(bookId)?.let { bestVoiceFor(it, bookId) }

        /** The voice the book's audio is CONFIGURED under — the cache-key
         * voice. The request voice (the original's, see [resolve]) never
         * changes; an EXPLICIT target voice must name the render in the key
         * (the auto-picked one is deterministic from the language, hence
         * byte-identical to today — no cache invalidation for users who
         * never pick one). */
        fun renderVoice(bookId: String): String = storedTranslateVoice(bookId) ?: effectiveVoice(bookId)

        private fun storedTranslateVoice(bookId: String): String? =
            translateTarget(bookId)?.let { lang ->
                settings.translateVoice()?.takeIf { it in TranslateLanguages.voicesFor(activeCatalog(bookId), lang) }
            }

        /** The canonical target app codes the ACTIVE engine can voice (the
         * "Read in" picker rows; catalog order, deduplicated), restricted to
         * languages the translator can be prompted for. */
        fun availableTranslateLanguages(bookId: String? = null): List<String> = TranslateLanguages.codes(activeCatalog(bookId))

        /**
         * The single playback resolution point: engine + voice for [bookId].
         * engine = [engineFor] of the effective voice, wrapped in a
         * [TranslatingEngine] when the book has a translate target AND the
         * active engine has a voice for it AND the ACTIVE translate engine's
         * pack is ready — any missing link falls back to the plain engine
         * (never a half-translated render). voice = the effective voice,
         * unchanged: the decorator swaps the synthesis voice internally, so
         * callers and cache keys keep the original voice semantics.
         */
        fun resolve(bookId: String?): Pair<TTSEngine?, String> {
            val voice = if (bookId == null) resolveVoice(settings.state.value.voice) else effectiveVoice(bookId)
            val base = engineFor(voice, bookId) ?: return null to voice
            val target = bookId?.let { translateTarget(it) }
            val modelLang = target?.let { LfmLang.toPromptLanguage(it) }
            val targetVoice =
                target?.let {
                    TranslateLanguages.resolvedVoiceFor(activeCatalog(bookId), it, settings.translateVoice())
                }
            // The resolved target voice must actually be servable: Piper is
            // one-voice-per-instance over downloaded packs, so a catalog name
            // whose pack is missing fails synthesis — the decorated attempt
            // would never produce audio (S22 device pass 2026-09-14). Kokoro
            // serves its whole catalog from one model + voices pack.
            val voiceServable =
                targetVoice != null &&
                    (!isPiper(bookId) || piper.voicePackReady(targetVoice))
            // The translator session is touched ONLY when a target is in
            // force: `translator()` opens the ~1.6 GB LLM and arms the idle
            // timer, so the original-language path must never call it (the
            // degrade log below is non-arming — canOpen — for the same
            // reason: a missing precondition is diagnosable without lodging
            // the model). Resolve runs on EVERY synthesis, cold and
            // warm — an unconditional touch would keep the leg resident
            // while reading untranslated books.
            val decorated =
                if (
                    target != null &&
                    modelLang != null &&
                    targetVoice != null &&
                    voiceServable &&
                    translate.translator() != null
                ) {
                    // The translator identity is captured WITH the target: a
                    // mid-render engine switch must not key this request's text
                    // under the other engine (the queue's own key was built from
                    // the same read).
                    val translator = translateEngineId
                    // The translated render needs an instance that SERVES the
                    // target voice: Piper is one-voice-per-instance, so the
                    // base (original-voice) instance fails typed on the
                    // swapped request and every passage degraded to the
                    // original audio — cached under the x<lang> key (S22
                    // 2026-09-14). Kokoro serves its whole catalog from one
                    // instance, so engineFor returns the same engine.
                    val targetEngine = engineFor(targetVoice, bookId) ?: return base to voice
                    TranslatingEngine(
                        delegate = base,
                        targetEngine = targetEngine,
                        // The audio path shares the SAME stored artifact as the
                        // reader's display: the service is cache-first, joins
                        // the keyed in-flight decode, and stores before returning
                        // — speech never causes a second translation of a passage
                        // the display already rendered (at most once).
                        translate = { text, chapterIndex, passageIndex ->
                            val id = bookId
                            if (id == null) {
                                null
                            } else {
                                translationService.translate(
                                    id,
                                    chapterIndex,
                                    passageIndex,
                                    TranslationTarget(target, translator),
                                )
                            }
                        },
                        targetVoice = targetVoice,
                        targetLang = target,
                    )
                } else {
                    if (target != null) {
                        android.util.Log.w(
                            "Translate",
                            "degrade: target=$target modelLang=$modelLang " +
                                "targetVoice=$targetVoice translatorOpenable=${translate.canOpen} " +
                                "(${translate.failureReason ?: "open otherwise"})",
                        )
                        if (targetVoice == null) {
                            android.util.Log.w(
                                "Translate",
                                "catalog: selected=${engineIdFor(bookId)} degraded=${isDegraded(bookId)} size=" +
                                    "${activeCatalog(bookId).size} langs=" +
                                    activeCatalog(bookId)
                                        .map { it.language }
                                        .joinToString(",")
                                        .take(200),
                            )
                        }
                    } else {
                        android.util.Log.d("Translate", "no target for book $bookId")
                    }
                    base
                }
            return decorated to voice
        }

        /** The [bookId] engine's voice metadata catalog (empty for the degraded
         * system voice — no target languages). */
        private fun activeCatalog(bookId: String?): List<io.github.moronigranja.ayvu.tts.kokoro.KokoroVoiceMeta> =
            when {
                isDegraded(bookId) -> emptyList()
                isPiper(bookId) -> PiperVoiceMetadata.all
                else -> KokoroVoiceMetadata.all
            }
    }
