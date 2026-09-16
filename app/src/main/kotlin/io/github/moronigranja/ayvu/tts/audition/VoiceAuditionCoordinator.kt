package io.github.moronigranja.ayvu.tts.audition

import io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector
import io.github.moronigranja.ayvu.featureplayer.playback.PassageOutput
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.player.AuditionStage
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PlayerPhase
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.VoicePreview
import io.github.moronigranja.ayvu.tts.piper.PiperVoicePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C2 audition coordinator (composition root): one voice sample may generate
 * or play at a time; starting another cancels the first; slow synthesis shows
 * cancellable "Generating sample…" feedback. Narration handling — capture and
 * pause when the book is playing, resume only if it was — goes through
 * [PlayerCommands] (the A5 single-writer seam), so sampling can never publish
 * stale book state. Preview audio is ephemeral by construction: played
 * straight to [PassageOutput], never written to the passage cache, progress
 * or history.
 *
 * All state transitions are [Synchronized]: the completion poll runs on the
 * app scope while stop()/preview() can arrive from the UI thread, and the
 * finish/stop path must be raced-free (a stopped audition never double-
 * resumes narration).
 *
 * Test seam: constructed directly with fakes ([EngineSelector] over a fake
 * [io.github.moronigranja.ayvu.tts.TTSEngine], a fake [PassageOutput],
 * fake [PlayerCommands], a test scope).
 */
@Singleton
class VoiceAuditionCoordinator
    @Inject
    constructor(
        private val selector: EngineSelector,
        private val output: PassageOutput,
        private val commands: PlayerCommands,
        private val appScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val settings: AppSettings,
    ) : VoiceAudition {
        private val _state = MutableStateFlow(AuditionUiState())
        override val state: StateFlow<AuditionUiState> = _state.asStateFlow()

        private var job: Job? = null
        private var pollJob: Job? = null
        private var capturedWasPlaying = false
        private var finishing = false

        @Synchronized
        override fun preview(voice: String) {
            val current = _state.value
            if (current.voice == voice &&
                (current.stage == AuditionStage.Generating || current.stage == AuditionStage.Playing)
            ) {
                return // already auditioning this voice
            }
            // Supersede the previous audition WITHOUT resuming narration — the
            // book stays paused for the new sample.
            cancelCurrent(resumeNarration = false)

            val phase = PlaybackStateHolder.state.value.phase
            capturedWasPlaying = phase == PlayerPhase.PLAYING || phase == PlayerPhase.LOADING
            if (capturedWasPlaying) commands.pause()

            _state.value = AuditionUiState(voice, AuditionStage.Generating)
            job =
                appScope.launch {
                    try {
                        // Engine resolution (the FIRST call cold-opens the
                        // Kokoro model, ~30s+ on weak devices) must NOT run on
                        // the main thread — it happens off-main here so the UI
                        // stays responsive and shows cancellable
                        // "Generating sample…" while the model loads.
                        // Phrase lookup follows the ACTIVE engine's catalog:
                        // Kokoro families first, then the Piper voices
                        // (D4 #154 addendum) — unknown names stay null and
                        // the audition fails typed below. The previewed id
                        // resolves through the #144 availability shape and
                        // PAIRS with the engine (engineFor): Piper serves
                        // exactly one voice per instance, so previewing a
                        // second Piper voice opens its instance instead of
                        // failing typed on the global one (decisions #144).
                        val resolved = selector.resolveVoice(voice)
                        val phrase = VoicePreview.phraseFor(voice) ?: PiperVoicePreview.phraseFor(voice)
                        val engine = withContext(ioDispatcher) { selector.engineFor(resolved) }
                        if (phrase == null || engine == null) {
                            finishAudition()
                            _state.value =
                                AuditionUiState(
                                    voice,
                                    AuditionStage.Failed(
                                        if (engine == null) {
                                            "Speech assets missing — download to preview"
                                        } else {
                                            "Voice unavailable for preview"
                                        },
                                    ),
                                )
                            return@launch
                        }
                        // RTF probe (item 8, D2): every Preview contributes a
                        // wall/audio sample — a ~1-2 s sample alone never
                        // crosses the 10 s gate (#93), so it only ever
                        // ACCUMULATES toward the verdict.
                        val startedAt = System.currentTimeMillis()
                        val outcome = engine.synthesize(SynthesisRequest(phrase, resolved))
                        if (outcome is SynthesisOutcome.Audio) {
                            val wallMs = System.currentTimeMillis() - startedAt
                            val audioMs = outcome.pcm.size * 1000L / (outcome.sampleRateHz * 2L) // mono 16-bit
                            settings.setRtfSample(wallMs, audioMs)
                        }
                        when (outcome) {
                            is SynthesisOutcome.Audio -> {
                                _state.value = AuditionUiState(voice, AuditionStage.Playing)
                                output.play(outcome.pcm, outcome.sampleRateHz, 1.0)
                                val frames = outcome.pcm.size / 2 // mono 16-bit
                                pollJob =
                                    appScope.launch {
                                        while (isActive) {
                                            if (output.positionSamples >= frames) {
                                                finishAudition()
                                                break
                                            }
                                            delay(100)
                                        }
                                    }
                            }
                            is SynthesisOutcome.Unavailable ->
                                failAudition("Speech assets missing — download to preview")
                            is SynthesisOutcome.Failed -> failAudition(outcome.reason ?: "Synthesis failed")
                            else -> failAudition("No audio produced")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failAudition(e.message ?: "Preview failed")
                    }
                }
        }

        @Synchronized
        override fun stop() {
            cancelCurrent(resumeNarration = true)
            _state.value = AuditionUiState()
        }

        @Synchronized
        private fun cancelCurrent(resumeNarration: Boolean) {
            job?.cancel()
            job = null
            pollJob?.cancel()
            pollJob = null
            output.stop()
            finishing = false
            if (resumeNarration && capturedWasPlaying) commands.resume()
            capturedWasPlaying = false
        }

        /** Sample played to the end (or stopped) — narration comes back only
         * if the audition had captured it mid-play. */
        @Synchronized
        private fun finishAudition() {
            if (finishing) return
            finishing = true
            val resume = capturedWasPlaying
            capturedWasPlaying = false
            job?.cancel()
            job = null
            pollJob?.cancel()
            pollJob = null
            output.stop()
            _state.value = AuditionUiState()
            if (resume) commands.resume()
        }

        @Synchronized
        private fun failAudition(reason: String) {
            val voice = _state.value.voice
            val resume = capturedWasPlaying
            capturedWasPlaying = false
            job?.cancel()
            job = null
            pollJob?.cancel()
            pollJob = null
            _state.value = AuditionUiState(voice, AuditionStage.Failed(reason))
            if (resume) commands.resume()
        }
    }
