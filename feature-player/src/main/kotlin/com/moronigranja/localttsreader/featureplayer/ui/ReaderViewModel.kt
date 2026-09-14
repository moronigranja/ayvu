package com.moronigranja.localttsreader.featureplayer.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.featureplayer.playback.EngineSelector
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.AuditionUiState
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerCommands
import com.moronigranja.localttsreader.player.VoiceAudition
import com.moronigranja.localttsreader.player.VoicePackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackState
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import com.moronigranja.localttsreader.tts.piper.PiperEngine
import com.moronigranja.localttsreader.tts.piper.PiperPacks
import com.moronigranja.localttsreader.tts.piper.PiperVoiceMetadata
import com.moronigranja.localttsreader.tts.piper.PiperVoices
import com.moronigranja.localttsreader.tts.translate.TranslatePackStager
import com.moronigranja.localttsreader.tts.translate.TranslatePacks
import com.moronigranja.localttsreader.ui.ReadInLanguageUiState
import com.moronigranja.localttsreader.ui.VoiceSelectorUiState
import com.moronigranja.localttsreader.ui.buildVoiceSelectorState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reader-side commands to the [PlaybackService]; state is read from the
 * service-published [PlaybackStateHolder] (the service is the single writer).
 *
 * C2: the reader voice sheet reuses the shared [VoiceAudition] coordinator
 * and [buildVoiceSelectorState] builder; selecting a voice persists it AND
 * rebuilds the active book under it at the same playhead via [changeVoice]
 * (A5 single-writer — stale synthesis can never publish).
 */
@HiltViewModel
class ReaderViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: AppSettings,
        private val audition: VoiceAudition,
        private val selector: EngineSelector,
        private val registry: PackRegistry,
        private val download: VoicePackDownloader,
    ) : ViewModel(),
        PlayerCommands {
        val state: StateFlow<PlaybackUiState> = PlaybackStateHolder.state

        /** C2: the shared selector state for the reader's voice sheet — the
         * catalog follows the selected engine (D4 #154 addendum): piper rows
         * with the resolved voice's pack readiness under `piper-v1`, the
         * Kokoro catalog otherwise. A saved voice the active engine does not
         * expose degrades to the builder's unavailable row (decisions #144
         * availability shape). */
        val voiceSelector: StateFlow<VoiceSelectorUiState> =
            combine(settings.state, audition.state, registry.packs) { prefs, aud, packs ->
                voiceSelector(packs, prefs, aud)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                voiceSelector(
                    registry.packs.value,
                    settings.state.value,
                    audition.state.value,
                    ready = false,
                ),
            )

        private fun voiceSelector(
            packs: List<PackState>,
            prefs: AppSettings.Snapshot,
            audition: AuditionUiState,
            ready: Boolean = readyFor(prefs, packs),
        ): VoiceSelectorUiState {
            val piperSelected = prefs.ttsEngine == SettingsStore.PIPER_ENGINE
            return buildVoiceSelectorState(
                voices = if (piperSelected) PiperVoiceMetadata.all else KokoroVoiceMetadata.all,
                selectedVoice = prefs.voice,
                favorites = prefs.favorites.toSet(),
                ready = ready,
                audition = audition,
            )
        }

        /** Pack readiness of the SELECTED engine: Kokoro's three packs, or
         * the resolved Piper voice's model + config plus the shared espeak
         * bundle both engines phonemize through. */
        private fun readyFor(
            prefs: AppSettings.Snapshot,
            packs: List<PackState>,
        ): Boolean {
            val ids =
                if (prefs.ttsEngine == SettingsStore.PIPER_ENGINE) {
                    PiperPacks
                        .forVoice(
                            if (prefs.voice in PiperVoices.all) prefs.voice else PiperEngine.DEFAULT_VOICE,
                        ).map { it.id } + ESPEAK_PACK_ID
                } else {
                    listOf(KokoroPacks.model.id, KokoroPacks.voices.id, KokoroPacks.espeak.id)
                }
            return ids.all { id -> packs.firstOrNull { it.pack.id == id }?.status == PackStatus.Ready }
        }

        /** The book this reader shows — [resume] carries it so a machine-less
         * service (STOP's post-stop fill self-stopped it) can rebuild and
         * resume from the persisted playhead instead of dead-ending the
         * play button. */
        private var openedBookId: String? = null

        // ---- read-in-language (decisions #114) ----

        private val translateProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)

        /** The voice-sheet "Read in" section state: the active book's target,
         * the target languages the active engine can voice, and pack
         * readiness (selection is disabled until the pack is staged). */
        val translateState: StateFlow<ReadInLanguageUiState> =
            combine(settings.state, registry.packs, translateProgress, PlaybackStateHolder.state) {
                prefs,
                packs,
                progress,
                playback,
                ->
                ReadInLanguageUiState(
                    bookId = playback.bookId,
                    target = playback.bookId?.let { prefs.bookTranslate[it] },
                    languages = selector.availableTranslateLanguages(),
                    packDownloaded =
                        packs.any { it.pack.id == TranslatePacks.pack.id && it.status == PackStatus.Ready } &&
                            TranslatePackStager.isStaged(context.filesDir),
                    downloadProgress = progress,
                    degradeReason = selector.translateDegradeReason(playback.bookId),
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ReadInLanguageUiState(),
            )

        /**
         * Persists the book's translate target (null = Off) and rebuilds the
         * active book at the same playhead through the [changeVoice] path —
         * same single-writer rule as voice selection: stale audio can never
         * publish. The service re-reads the setting on the rebuild, so the
         * queue + cache keys pick up the `x<lang>` dimension.
         */
        fun setTranslateTarget(target: String?) {
            // The published state lags a fresh open (the service publishes
            // after the queue builds) — fall back to the VM's opened book
            // instead of silently dropping the selection (S22 2026-09-14:
            // the user's pick vanished with zero feedback).
            val bookId = PlaybackStateHolder.state.value.bookId ?: openedBookId ?: return
            val current = settings.bookTranslate(bookId)
            if (target != current) {
                viewModelScope.launch { settings.setBookTranslate(bookId, target) }
                changeVoice(settings.state.value.voice)
            }
        }

        /** Inline download for the translation pack row (per-book, never a
         * global gate): explicit, resumable, verified (decision #7); the
         * settings surface stages the bundle after Ready. */
        fun downloadTranslatePack() {
            viewModelScope.launch {
                translateProgress.value = 0f
                registry
                    .download(TranslatePacks.pack.id) { done, total ->
                        translateProgress.value = (done.toDouble() / total).toFloat()
                    }.also { translateProgress.value = null }
            }
        }

        /** C2: select a voice AND rebuild the active book under it at the same
         * playhead (persist via [settings], supersede stale synthesis via
         * [changeVoice]). */
        fun selectVoice(voice: String) {
            val current = settings.state.value.voice
            if (voice != current) {
                viewModelScope.launch { settings.setVoice(voice) }
                changeVoice(voice)
            }
        }

        /** The star toggles favorite state only — selection is the row tap. */
        fun toggleFavorite(voice: String) {
            viewModelScope.launch { settings.toggleFavorite(voice) }
        }

        fun previewVoice(voice: String) = audition.preview(voice)

        fun stopPreview() = audition.stop()

        /** C2: explicit voice-pack download action while Kokoro packs are
         * missing — routed to the composition root (A6). */
        fun downloadVoicePacks() = download.requestDownload()

        /** Opens a book in the reader WITHOUT starting playback (decisions #52). */
        fun open(bookId: String) {
            openedBookId = bookId
            command(PlaybackService.ACTION_OPEN, bookId)
        }

        override fun play(bookId: String) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY, bookId)
        }

        override fun playAt(
            bookId: String,
            chapterIndex: Int,
            passageIndex: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapterIndex, passageIndex)
        }

        /** C2: the persisted voice was already switched by the selector — this
         * rebuilds the active book under it, preserving the playhead (A5). */
        override fun changeVoice(voice: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_CHANGE_VOICE)
                        .putExtra(PlaybackService.EXTRA_VOICE, voice),
                )
            }
        }

        fun playPosition(
            bookId: String,
            chapter: Int,
            passage: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapter, passage)
        }

        /** Positions the reader at (chapter, passage) WITHOUT starting
         * playback — the chapter selector and bookmark jumps use this instead
         * of [playPosition] (open ≠ auto-play, decisions #52). */
        fun openPosition(
            bookId: String,
            chapter: Int,
            passage: Int,
        ) {
            openedBookId = bookId
            command(PlaybackService.ACTION_OPEN_POSITION, bookId, chapter, passage)
        }

        fun openChapter(
            bookId: String,
            direction: Int,
        ) = command(PlaybackService.ACTION_OPEN_CHAPTER, bookId, direction = direction)

        fun skipForward() = command(PlaybackService.ACTION_SKIP_FORWARD)

        fun skipBackward() = command(PlaybackService.ACTION_SKIP_BACKWARD)

        fun undo() = command(PlaybackService.ACTION_UNDO)

        override fun stop() = command(PlaybackService.ACTION_STOP)

        fun cycleSleep() = command(PlaybackService.ACTION_SLEEP)

        fun bookmark() = command(PlaybackService.ACTION_BOOKMARK)

        override fun resume() = command(PlaybackService.ACTION_RESUME, openedBookId)

        override fun pause() = command(PlaybackService.ACTION_PAUSE)

        override fun seekForward() = command(PlaybackService.ACTION_SEEK_FORWARD)

        override fun seekBackward() = command(PlaybackService.ACTION_SEEK_BACKWARD)

        private fun command(
            action: String,
            bookId: String? = null,
            chapter: Int = 0,
            passage: Int = 0,
            direction: Int = 0,
        ) {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            if (bookId != null) intent.putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
            if (action == PlaybackService.ACTION_PLAY_POSITION || action == PlaybackService.ACTION_OPEN_POSITION) {
                intent.putExtra(PlaybackService.EXTRA_CHAPTER, chapter)
                intent.putExtra(PlaybackService.EXTRA_PASSAGE, passage)
            }
            if (action == PlaybackService.ACTION_OPEN_CHAPTER) {
                intent.putExtra(PlaybackService.EXTRA_DIRECTION, direction)
            }
            runCatching { context.startForegroundService(intent) }
        }

        private companion object {
            /** The shared espeak-ng bundle both open-weight engines phonemize
             * through (Kokoro and Piper; the descriptor id is stable). */
            const val ESPEAK_PACK_ID = "espeak-ng"
        }
    }
