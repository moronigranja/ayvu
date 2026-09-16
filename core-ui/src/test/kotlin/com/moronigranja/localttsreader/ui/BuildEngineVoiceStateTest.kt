package com.moronigranja.localttsreader.ui

import com.moronigranja.localttsreader.player.AuditionStage
import com.moronigranja.localttsreader.player.AuditionUiState
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared engine+voice picker-state builder (decisions #102.4 + #166
 * follow-up): exactly one row carries the selection, favorites are
 * independent of selection, the persistent summary names the saved voice, a
 * saved voice absent from the catalog renders as unavailable, per-voice pack
 * readiness/size flow from the required-pack table, and the engine list is
 * the fixed Kokoro/Piper/system order.
 */
class BuildEngineVoiceStateTest {
    private val voice = "af_heart"
    private val other = "af_bella"

    @Test
    fun `exactly one row is selected and the summary names it`() {
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                readyFor = { true },
                bytesFor = { 0L },
                audition = AuditionUiState(),
            )
        assertEquals("Selected voice: Heart ❤️ ($voice)", state.summary)
        assertTrue(state.rows.none { it.name != voice && it.selected })
        assertTrue(state.rows.first { it.name == voice }.selected)
        assertTrue(state.ready)
    }

    @Test
    fun `favorite is independent of selection`() {
        // other voice is the favorite, but voice is selected — the star and
        // the selection indicator must not imply each other.
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = setOf(other),
                readyFor = { true },
                bytesFor = { 0L },
                audition = AuditionUiState(),
            )
        val row = state.rows.first { it.name == other }
        assertTrue(row.favorite)
        assertTrue(!row.selected)
        val selected = state.rows.first { it.name == voice }
        assertTrue(!selected.favorite)
        assertTrue(selected.selected)
    }

    @Test
    fun `saved voice absent from catalog is surfaced as unavailable`() {
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = "zz_ghost",
                favorites = emptySet(),
                readyFor = { true },
                bytesFor = { 0L },
                audition = AuditionUiState(),
            )
        assertEquals("zz_ghost", state.unavailableSavedVoice)
        assertEquals("", state.summary)
        assertTrue(state.rows.none { it.selected })
    }

    @Test
    fun `pack readiness and size flow into the rows`() {
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                readyFor = { it != voice },
                bytesFor = { if (it == voice) 63_201_294L else 0L },
                audition = AuditionUiState(),
            )
        val row = state.rows.first { it.name == voice }
        assertFalse(row.ready)
        assertEquals(63_201_294L, row.bytes)
        assertTrue(state.rows.first { it.name == other }.ready)
        assertEquals(0L, state.rows.first { it.name == other }.bytes)
        assertFalse(state.ready) // the SELECTED voice is unready
    }

    @Test
    fun `engine list is the fixed Kokoro Piper system order`() {
        val options = engineOptions(DefaultEngines.kokoro.id) { true }
        assertEquals(
            listOf(DefaultEngines.kokoro.id, DefaultEngines.piper.id, "system-tts"),
            options.map { it.id },
        )
        assertEquals("Kokoro-82M", options[0].displayName)
        assertEquals("Piper", options[1].displayName)
        assertEquals("Device voice (system)", options[2].displayName)
        assertTrue(options.all { it.ready })
        val unready = engineOptions(DefaultEngines.piper.id) { false }
        assertTrue(unready.none { it.ready })
    }

    @Test
    fun `audition stage maps onto the matching voice row only`() {
        val audition = AuditionUiState(voice, AuditionStage.Generating)
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                readyFor = { true },
                bytesFor = { 0L },
                audition = audition,
            )
        assertEquals(VoicePreviewUi.Generating, state.rows.first { it.name == voice }.preview)
        assertTrue(state.rows.first { it.name == other }.preview is VoicePreviewUi.Idle)
    }

    @Test
    fun `presentation fields flow from the metadata table`() {
        val state =
            buildEngineVoiceState(
                engineId = DefaultEngines.kokoro.id,
                engines = emptyList(),
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                readyFor = { true },
                bytesFor = { 0L },
                audition = AuditionUiState(),
            )
        val heart = state.rows.first { it.name == "af_heart" }
        assertEquals("Heart ❤️", heart.displayName)
        assertEquals("A", heart.grade)
        // The es/pt families ship no upstream grade — no invented one.
        assertTrue(state.rows.filter { it.language == "Spanish" }.all { it.grade == null })
        val unemojied = state.rows.first { it.name == "am_adam" }
        assertEquals("Adam", unemojied.displayName)
        assertEquals("F+", unemojied.grade)
    }
}
