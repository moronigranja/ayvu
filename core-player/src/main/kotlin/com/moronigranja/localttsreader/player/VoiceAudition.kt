package com.moronigranja.localttsreader.player

import kotlinx.coroutines.flow.StateFlow

/**
 * One audition's observable stage (C2). Rows map this onto their own
 * [com.moronigranja.localttsreader.ui.VoicePreviewUi]; exactly one audition
 * may be generating/playing at a time, enforced by the coordinator.
 */
sealed interface AuditionStage {
    data object Idle : AuditionStage

    data object Generating : AuditionStage

    data object Playing : AuditionStage

    data class Failed(
        val reason: String,
    ) : AuditionStage
}

data class AuditionUiState(
    val voice: String? = null,
    val stage: AuditionStage = AuditionStage.Idle,
)

/**
 * C2 voice audition contract: preview a fixed language-appropriate phrase
 * with one voice without selecting or favoriting it. One audition at a time —
 * starting another cancels the first; slow synthesis shows cancellable
 * feedback. Preview audio is ephemeral: excluded from book progress, history
 * and the passage disk cache. If narration is active, auditioning captures
 * and pauses the book playhead, plays the sample, then resumes only if
 * narration was playing beforehand (the pause/resume go through
 * [PlayerCommands], so the A5 single-writer serialization holds and sampling
 * can never publish stale book state).
 *
 * Implemented at the composition root (app) over the engine seam — features
 * observe this core contract, never the implementation.
 */
interface VoiceAudition {
    val state: StateFlow<AuditionUiState>

    /** Audits [voice]: cancels any current audition and pauses narration only
     * if it was playing. Missing engine assets fail typed, never silently. */
    fun preview(voice: String)

    /** Stops the current audition; resumes narration if a captured audition
     * had paused it. */
    fun stop()
}
