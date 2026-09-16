package io.github.moronigranja.ayvu.tts.setup

import io.github.moronigranja.ayvu.tts.setup.SetupFacts
import io.github.moronigranja.ayvu.tts.setup.SetupState
import io.github.moronigranja.ayvu.tts.setup.StepKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** C1.2: one test per derivation rule — the gate and the setup screen share
 * this table, so the rules are locked here. */
class SetupStateTest {
    private val nothing =
        SetupFacts(
            requiredPacksReady = false,
            espeakStaged = false,
            voiceSelected = false,
            bookCount = 0,
            systemTtsOptedIn = false,
        )

    @Test
    fun `empty facts open with the full plan`() {
        val steps = SetupState.derive(nothing)
        // C1 reversal: voice selection follows the packs download (its
        // Preview needs the engine open) instead of preceding it.
        assertEquals(
            listOf(StepKind.PRIVACY, StepKind.DOWNLOAD_PACKS, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK),
            steps,
        )
        assertFalse(SetupState.isTerminal(steps))
    }

    @Test
    fun `packs ready with no books and no voice shows voice then import`() {
        val steps =
            SetupState.derive(
                nothing.copy(requiredPacksReady = true, espeakStaged = true),
            )
        // C1 reversal keeps CHOOSE_VOICE after the download — a user who never
        // picked a voice lands there, not straight on import.
        assertEquals(listOf(StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK), steps)
        assertFalse(SetupState.isTerminal(steps))
    }

    @Test
    fun `packs ready with no books and a voice chosen shows import only`() {
        val steps =
            SetupState.derive(
                nothing.copy(requiredPacksReady = true, espeakStaged = true, voiceSelected = true),
            )
        assertEquals(listOf(StepKind.IMPORT_BOOK), steps)
        assertFalse(SetupState.isTerminal(steps))
    }

    @Test
    fun `everything ready with a book is complete`() {
        val steps =
            SetupState.derive(
                nothing.copy(requiredPacksReady = true, espeakStaged = true, bookCount = 1),
            )
        assertEquals(listOf(StepKind.COMPLETE), steps)
        assertTrue(SetupState.isTerminal(steps))
    }

    @Test
    fun `opted in with packs missing drops the download requirement`() {
        val steps = SetupState.derive(nothing.copy(systemTtsOptedIn = true))
        assertEquals(
            listOf(StepKind.PRIVACY, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK),
            steps,
        )
        assertFalse(SetupState.isTerminal(steps))
    }

    @Test
    fun `opted in with a book and missing packs is degraded-ready terminal`() {
        val steps =
            SetupState.derive(
                nothing.copy(systemTtsOptedIn = true, bookCount = 2),
            )
        assertEquals(listOf(StepKind.DEGRADED_READY), steps)
        assertTrue(SetupState.isTerminal(steps))
    }

    @Test
    fun `complete wins over the opted-in degraded path`() {
        val steps =
            SetupState.derive(
                nothing.copy(
                    requiredPacksReady = true,
                    espeakStaged = true,
                    bookCount = 1,
                    systemTtsOptedIn = true,
                ),
            )
        assertEquals(listOf(StepKind.COMPLETE), steps)
    }

    @Test
    fun `opted in with packs later installed re-enters at voice then import`() {
        // Ready packs win over the degraded path (C3): a user who installed an
        // engine after opting in re-enters at voice + import, not the full plan.
        val steps =
            SetupState.derive(
                nothing.copy(
                    requiredPacksReady = true,
                    espeakStaged = true,
                    bookCount = 0,
                    systemTtsOptedIn = true,
                ),
            )
        assertEquals(listOf(StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK), steps)
    }

    @Test
    fun `espeak not staged keeps the plan open even with packs ready`() {
        // The engine's real gate is EspeakStager.isStaged — a verified-but-
        // unstaged bundle must not read as "done" (settings self-heals it).
        val steps =
            SetupState.derive(
                nothing.copy(requiredPacksReady = true, espeakStaged = false, bookCount = 1),
            )
        assertEquals(
            listOf(StepKind.PRIVACY, StepKind.DOWNLOAD_PACKS, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK),
            steps,
        )
    }

    @Test
    fun `voice never chosen does not gate the steps`() {
        // A chosen voice is only recorded on an explicit pick; the default
        // counts as unselected only while empty + packs missing. Outside that
        // window derive is driven by packs/books alone.
        val chosen = nothing.copy(voiceSelected = true) // af_heart vs bm_george
        assertEquals(SetupState.derive(nothing), SetupState.derive(chosen))
    }
}
