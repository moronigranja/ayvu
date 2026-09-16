package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * C2: every Kokoro voice has a fixed language-appropriate audition phrase, so
 * the Preview button always has text; an unknown/foreign name degrades to
 * null (the coordinator fails typed, never silently).
 */
class VoicePreviewTest {
    @Test
    fun `every shipped voice has an audition phrase`() {
        for (voice in KokoroVoiceMetadata.all) {
            assertNotNull("no preview phrase for $voice", VoicePreview.phraseFor(voice.name))
        }
    }

    @Test
    fun `phrase follows the voice prefix family`() {
        assertEquals(VoicePreview.phraseFor("af_alloy"), VoicePreview.phraseFor("af_heart"))
        assertEquals(VoicePreview.phraseFor("if_sara"), VoicePreview.phraseFor("im_nicola"))
    }

    @Test
    fun `unknown voice has no phrase`() {
        assertNull(VoicePreview.phraseFor("unknown_voice"))
    }
}
