package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.persistence.PassageDao
import com.moronigranja.localttsreader.player.TranslationStore
import com.moronigranja.localttsreader.player.pregen.TranslationReady
import com.moronigranja.localttsreader.player.pregen.TranslationService
import com.moronigranja.localttsreader.player.pregen.TranslationTarget
import com.moronigranja.localttsreader.tts.translate.LfmLang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TranslationService] over the persisted [TranslationStore] and the ONE
 * process-wide [TranslateRuntime] translator session (decisions #162: reuse
 * the existing session with its 60 s idle close — never a second open).
 *
 * Ownership:
 * - **cache lookup** through [TranslationStore] before any LLM call — a
 *   cache hit never touches the model;
 * - **keyed in-flight dedupe**: a `MutableMap<Key, Deferred<String?>>` so
 *   two callers for the same passage share one decode (today single-flight
 *   is only the session mutex, so both would queue and decode twice);
 * - **priority**: display work ([prefetch]) yields to playback and pregen —
 *   gated on [PlaybackActive.engineInUse], retried when it clears; the audio
 *   path's [translate] is playback's own work and never self-gates;
 * - **thread discipline**: decodes run on this service's [Dispatchers.Default]
 *   scope, never on the caller's (the sync cold path calls `synthesize` on
 *   the player thread — a display decode there would block player commands);
 * - **no cancellation of an in-flight decode**: the native decode loop is
 *   not interruptible (~2.7 s tail). Supersede by identity instead — a
 *   prefetch whose book/target no longer matches is dropped when it
 *   completes, never awaited by a stale caller.
 */
@Singleton
class TranslationServiceImpl
    @Inject
    constructor(
        private val store: TranslationStore,
        private val passageDao: PassageDao,
        private val runtime: TranslateRuntime,
    ) : TranslationService {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val inFlight = mutableMapOf<Key, Deferred<String?>>()
        private val _ready = MutableSharedFlow<TranslationReady>(extraBufferCapacity = 64)
        override val ready: Flow<TranslationReady> = _ready.asSharedFlow()

        private data class Key(
            val bookId: String,
            val chapter: Int,
            val passage: Int,
            val lang: String,
            val translator: String,
        )

        private fun key(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ) = Key(bookId, chapter, passage, target.lang, target.translator)

        private companion object {
            /** How long display prefetch defers to an engaged engine (the
             * engineInUse hold) before decoding anyway. A synthesis burst
             * clears within ~1-2 s; 5 s covers a full burst plus scheduling
             * without starving the reader during a long playback session. */
            const val DISPLAY_YIELD_WINDOW_MS = 5_000L
        }

        override val translatePossible: Boolean
            get() = runtime.canOpen

        override suspend fun cached(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? = store.get(bookId, chapter, passage, target)

        override suspend fun translate(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? {
            // Cache first: a stored translation never touches the model.
            store.get(bookId, chapter, passage, target)?.let { return it }
            android.util.Log.d("Translate", "translate: $bookId/$chapter/$passage lang=${target.lang} (miss)")
            val k = key(bookId, chapter, passage, target)
            // Keyed in-flight dedupe: concurrent callers (display prefetch +
            // the audio path) share ONE decode; the decode runs on the
            // service scope so the caller's thread only awaits.
            val deferred =
                synchronized(inFlight) {
                    inFlight[k] ?: scope.async { decode(bookId, chapter, passage, target) }.also { inFlight[k] = it }
                }
            return try {
                deferred.await()
            } finally {
                synchronized(inFlight) { inFlight.remove(k) }
            }
        }

        override fun prefetch(
            bookId: String,
            chapter: Int,
            passages: List<Int>,
            target: TranslationTarget,
        ) {
            if (passages.isEmpty()) return
            android.util.Log.d("Translate", "prefetch: $bookId/$chapter ${passages.size} passages lang=${target.lang} engineInUse=${PlaybackActive.engineInUse}")
            currentPrefetch = PrefetchId(bookId, chapter, target)
            scope.launch {
                // Display work yields to playback and pregen: never start a
                // decode while the engine is busy; retry when it clears.
                // BOUNDED: the service's open-prefill and a playing session
                // hold engineInUse for their WHOLE run (the pregen-worker
                // yield signal, G2) — a pure retry-until-clear would starve
                // the display of its translations for the entire session.
                // Yield for a quiet window (a synthesis burst clears within a
                // second or two); past it, decode anyway — the prefilled
                // cushion absorbs the transient CPU contention.
                val quietDeadline = System.currentTimeMillis() + DISPLAY_YIELD_WINDOW_MS
                while (
                    PlaybackActive.engineInUse &&
                    System.currentTimeMillis() < quietDeadline &&
                    currentPrefetch == PrefetchId(bookId, chapter, target)
                ) {
                    delay(500)
                }
                for (p in passages) {
                    // Superseded by identity: a newer book/chapter/target
                    // drops this fill at its next passage boundary.
                    if (currentPrefetch != PrefetchId(bookId, chapter, target)) break
                    val text = translate(bookId, chapter, p, target)
                    // The translator is gone (pack removed): no text can land
                    // and the remaining passages are Unavailable — stop early.
                    if (text == null && !translatePossible) break
                }
            }
        }

        @Volatile private var currentPrefetch: PrefetchId? = null

        private data class PrefetchId(
            val bookId: String,
            val chapter: Int,
            val target: TranslationTarget,
        )

        /** The original passage text — the passages cache is the parse truth
         * and is always present for an imported book. One per-book map cached
         * (the service is used within one book at a time). */
        @Volatile private var sourceByBook: Pair<String, Map<Pair<Int, Int>, String>>? = null

        private suspend fun sourceText(
            bookId: String,
            chapter: Int,
            passage: Int,
        ): String? {
            val cached = sourceByBook
            val map =
                if (cached != null && cached.first == bookId) {
                    cached.second
                } else {
                    passageDao.forBook(bookId).associate { (it.chapterIndex to it.passageIndex) to it.text }.also {
                        sourceByBook = bookId to it
                    }
                }
            return map[chapter to passage]
        }

        private suspend fun decode(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? {
            android.util.Log.d("Translate", "decode: $bookId/$chapter/$passage lang=${target.lang} start")
            val source =
                sourceText(bookId, chapter, passage)
                    ?: run {
                        android.util.Log.w("Translate", "decode: no source text for $bookId/$chapter/$passage")
                        return null
                    }
            val modelLang =
                LfmLang.toPromptLanguage(target.lang)
                    ?: run {
                        android.util.Log.w("Translate", "decode: no prompt language for '${target.lang}'")
                        return null
                    }
            val startedAt = System.currentTimeMillis()
            val translated =
                try {
                    // The ONE shared session: opens on demand (arming the
                    // idle close) and serializes on its mutex.
                    runtime.translate(source, modelLang)
                } catch (t: Throwable) {
                    // The #101 degrade contract: ANY failure yields null —
                    // logged so a silent dots-forever state is diagnosable.
                    android.util.Log.w("Translate", "decode failed $bookId/$chapter/$passage", t)
                    null
                }
            if (translated.isNullOrBlank()) return null
            // Store BEFORE emitting: a crash mid-fill still persists the row,
            // so a restart never re-runs the LLM for this passage.
            store.put(bookId, chapter, passage, target, translated)
            _ready.tryEmit(TranslationReady(bookId, chapter, passage, target, translated))
            android.util.Log.d(
                "Translate",
                "translate: lang=$modelLang chars=${source.length} ms=${System.currentTimeMillis() - startedAt}",
            )
            return translated
        }
    }