package com.moronigranja.localttsreader.tts.setup

import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.piper.PiperPacks
import com.moronigranja.localttsreader.tts.piper.PiperVoices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The engine-aware required-pack table the setup gate, the download plan and
 * the voice readiness all share (D4, 2026-09-13). */
class SetupEnginePacksTest {
    @Test
    fun `kokoro requires its three packs`() {
        assertEquals(
            listOf(KokoroPacks.model.id, KokoroPacks.voices.id, KokoroPacks.espeak.id),
            SetupEnginePacks.requiredIds(DefaultEngines.kokoro.id, "af_heart"),
        )
    }

    @Test
    fun `piper requires the resolved voice packs plus espeak`() {
        // The saved voice is a served Piper id → its own model + config.
        assertEquals(
            PiperPacks.forVoice(PiperVoices.LESSAC).map { it.id } + SetupEnginePacks.ESPEAK_PACK_ID,
            SetupEnginePacks.requiredIds(DefaultEngines.piper.id, PiperVoices.LESSAC),
        )
        assertEquals(
            PiperPacks.forVoice(PiperVoices.THORSTEN).map { it.id } + SetupEnginePacks.ESPEAK_PACK_ID,
            SetupEnginePacks.requiredIds(DefaultEngines.piper.id, PiperVoices.THORSTEN),
        )
    }

    @Test
    fun `piper with an unserved voice falls back to the default voice packs`() {
        // A saved Kokoro voice under piper (decisions #144 availability shape)
        // falls back to Piper's default — the same rule EngineSelector uses.
        assertEquals(
            PiperPacks.forVoice(PiperVoices.LESSAC).map { it.id } + SetupEnginePacks.ESPEAK_PACK_ID,
            SetupEnginePacks.requiredIds(DefaultEngines.piper.id, "af_heart"),
        )
    }

    @Test
    fun `system and unknown engines require no packs`() {
        assertEquals(emptyList<String>(), SetupEnginePacks.requiredIds("system-tts", "af_heart"))
        assertEquals(emptyList<String>(), SetupEnginePacks.requiredIds("unknown-engine", "af_heart"))
    }
}
