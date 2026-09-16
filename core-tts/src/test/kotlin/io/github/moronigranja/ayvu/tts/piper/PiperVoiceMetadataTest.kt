package io.github.moronigranja.ayvu.tts.piper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D4 selection wiring (decisions #154 addendum): the piper voice catalog is
 * presentation data — the roster it presents must be exactly the voice ids
 * the engine instances actually serve, and every served voice needs an
 * audition phrase (unknown names fail typed, never a silent fallback). */
class PiperVoiceMetadataTest {
    @Test
    fun `the piper catalog names are exactly the served voices`() {
        assertEquals(PiperVoices.all, PiperVoiceMetadata.all.map { it.name })
    }

    @Test
    fun `every piper voice has an audition phrase - unknown names degrade to null`() {
        for (voice in PiperVoices.all) {
            assertTrue(PiperVoicePreview.phraseFor(voice)?.isNotBlank() == true, "phrase missing for $voice")
        }
        assertNull(PiperVoicePreview.phraseFor("af_heart"), "a Kokoro name is not a Piper voice")
        assertNull(PiperVoicePreview.phraseFor("en_US-not-a-voice"))
    }
}
