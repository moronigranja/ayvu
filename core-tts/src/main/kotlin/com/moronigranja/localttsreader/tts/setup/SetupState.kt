package com.moronigranja.localttsreader.tts.setup

/**
 * Durable facts the first-run setup derives from (C1, decisions #102). Every
 * fact is already persisted or on-disk truth — there is deliberately NO
 * "onboarding done" flag (C3 contract): a cold start on a library with books
 * and ready packs never shows the flow, whatever the user did before.
 *
 * - [requiredPacksReady]: the three Kokoro packs (model, voices, espeak-ng)
 *   are all [com.moronigranja.localttsreader.tts.PackStatus.Ready].
 * - [espeakStaged]: the espeak bundle is extracted under `files/espeak/`
 *   ([EspeakStager] readiness — the engine's actual gate).
 * - [voiceSelected]: the persisted voice differs from the shipped default. NOT
 *   a durable onboarding signal by itself: the default counts as "unselected"
 *   only while the library is empty and packs are missing (see SetupGate).
 * - [bookCount]: library rows (0 on a clean install).
 * - [systemTtsOptedIn]: the degraded zero-download fallback was chosen
 *   (persisted `tts_engine = "system-tts"`, decisions #102).
 */
data class SetupFacts(
    val requiredPacksReady: Boolean,
    val espeakStaged: Boolean,
    val voiceSelected: Boolean,
    val bookCount: Int,
    val systemTtsOptedIn: Boolean,
)

/**
 * The ordered presentation steps (C1). The wizard walks
 * [SetupState.derive] one step at a time (PRIVACY → DOWNLOAD_PACKS →
 * CHOOSE_VOICE → IMPORT_BOOK on the full plan);
 * re-derivation after each fact change picks up exactly where the flow stands
 * (packs completing shrinks the list; a finished import lands on [COMPLETE]).
 */
enum class StepKind {
    PRIVACY,
    CHOOSE_VOICE,
    DOWNLOAD_PACKS,
    IMPORT_BOOK,
    COMPLETE,

    /** Terminal for the opted-in degraded path (decisions #102): reading the
     * device voice until Kokoro packs install later. Never a screen. */
    DEGRADED_READY,
}

/**
 * Pure derivation of the setup steps from [SetupFacts] (C1.2) — the gate, the
 * setup screen and the tests all share one table, so the UI stays mechanical.
 *
 * Rules (decisions #102):
 * - Everything ready + a book → `[COMPLETE]` — the flow never shows, whatever
 *   the voice/engine choice.
 * - Opted-in degraded: no download requirement — `[PRIVACY, CHOOSE_VOICE,
 *   IMPORT_BOOK]`; with a book already present the flow is done in degraded
 *   mode (`[DEGRADED_READY]`, terminal) until Kokoro packs later install.
 * - Packs ready + no books → `[IMPORT_BOOK]` (re-entered later too: every
 *   step stays reachable from Settings after onboarding).
 * - Packs missing → the full plan, import appended once downloads complete.
 */
object SetupState {
    fun derive(facts: SetupFacts): List<StepKind> {
        val packsDone = facts.requiredPacksReady && facts.espeakStaged
        return when {
            packsDone && facts.bookCount > 0 -> listOf(StepKind.COMPLETE)
            // Packs done but no voice chosen yet — keep CHOOSE_VOICE in the
            // plan (after the download, per the C1 reversal) so the language/
            // voice step is reachable on the download-completion path instead
            // of being skipped straight to import.
            packsDone && !facts.voiceSelected -> listOf(StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK)
            // Ready packs win over the opted-in path: a user who installed an
            // engine later (or re-entered with packs present) sees only the
            // import step, whatever the engine choice (C3: durable facts).
            packsDone -> listOf(StepKind.IMPORT_BOOK)
            facts.systemTtsOptedIn && facts.bookCount > 0 -> listOf(StepKind.DEGRADED_READY)
            facts.systemTtsOptedIn -> listOf(StepKind.PRIVACY, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK)
            // C1 reversal (decisions): voice selection moves AFTER the packs
            // download — Preview needs the engine open, and the packs are a
            // single 28 MB bundle, so choosing before Ready bought nothing.
            else -> listOf(StepKind.PRIVACY, StepKind.DOWNLOAD_PACKS, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK)
        }
    }

    /** The flow is over when the derived head is a terminal step. */
    fun isTerminal(steps: List<StepKind>): Boolean =
        when (steps.firstOrNull()) {
            StepKind.COMPLETE, StepKind.DEGRADED_READY -> true
            else -> false
        }
}
