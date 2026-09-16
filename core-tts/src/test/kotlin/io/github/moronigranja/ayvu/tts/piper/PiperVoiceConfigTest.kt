package io.github.moronigranja.ayvu.tts.piper

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PiperVoiceConfigTest {
    /**
     * Real en_US-lessac-medium map values, recorded from official piper-tts
     * (`PiperVoice.phonemes_to_ids` on the pinned artifact, decisions #154) —
     * the chars the verified sample sentence uses, plus the combining tilde
     * the NFD decomposition exposes.
     */
    private val lessacMap =
        mapOf(
            'h' to listOf(20),
            'ə' to listOf(59),
            'l' to listOf(24),
            'ˈ' to listOf(120),
            'o' to listOf(27),
            'ʊ' to listOf(100),
            ',' to listOf(8),
            ' ' to listOf(3),
            'w' to listOf(35),
            'ɜ' to listOf(62),
            'ː' to listOf(122),
            'd' to listOf(17),
            '!' to listOf(4),
            '̃' to listOf(141),
        )

    @Test
    fun `parses the voice json fields`() {
        val config = PiperVoiceConfig.parse(VOICE_JSON)
        assertEquals(22_050, config.sampleRateHz)
        assertEquals(0.667f, config.noiseScale)
        assertEquals(1.0f, config.lengthScale)
        assertEquals(0.8f, config.noiseW)
        assertEquals("en-us", config.espeakVoice)
        assertEquals(listOf(96), config.phonemeIdMap['ʃ'])
        assertEquals(listOf(1), config.phonemeIdMap['^'], "BOS framing id")
        assertEquals(listOf(0), config.phonemeIdMap['_'], "PAD framing id")
        assertEquals(listOf(2), config.phonemeIdMap['$'], "EOS framing id")
    }

    @Test
    fun `missing config sections fail typed`() {
        assertThrows(IllegalStateException::class.java) { PiperVoiceConfig.parse("""{"audio": {}}""") }
    }

    @Test
    fun `multi codepoint id map keys fail typed`() {
        assertThrows(IllegalArgumentException::class.java) {
            PiperVoiceConfig.parse(
                """{"audio":{"sample_rate":22050},"inference":{},"espeak":{"voice":"en-us"},"phoneme_id_map":{"ab":[1]}}""",
            )
        }
    }

    @Test
    fun `phoneme ids match the official piper framing recorded on the pinned voice`() {
        val config = PiperVoiceConfig(22_050, 0.667f, 1.0f, 0.8f, "en-us", lessacMap)
        // Recorded from official piper-tts PiperVoice for the same phoneme
        // string on en_US-lessac-medium @ rhasspy/piper-voices 1162a917
        // (decisions #154): BOS=1 + PAD=0 open, (id, 0) per phoneme, EOS=2.
        assertArrayEquals(
            intArrayOf(1, 0, 20, 0, 59, 0, 24, 0, 120, 0, 27, 0, 100, 0, 8, 0, 3, 0, 35, 0, 120, 0, 62, 0, 122, 0, 24, 0, 17, 0, 4, 0, 2),
            config.phonemeIds("həlˈoʊ, wˈɜːld!"),
        )
    }

    @Test
    fun `precomposed phonemes decompose before mapping like official piper`() {
        val config = PiperVoiceConfig(22_050, 0.667f, 1.0f, 0.8f, "en-us", lessacMap)
        // õ (U+00F5) NFD-decomposes to o + U+0303 combining tilde (id 141 in
        // the real lessac map) — the official pipeline decomposes before mapping.
        assertArrayEquals(intArrayOf(1, 0, 27, 0, 141, 0, 2), config.phonemeIds("õ"))
    }

    @Test
    fun `codepoints missing from the map are skipped like official piper`() {
        val config = PiperVoiceConfig(22_050, 0.667f, 1.0f, 0.8f, "en-us", lessacMap)
        // 'x' is not in the map: official piper skips it with a warning (the
        // engine surfaces a framing-only sequence as "nothing to synthesize").
        assertArrayEquals(intArrayOf(1, 0, 20, 0, 2), config.phonemeIds("xh"))
    }

    @Test
    fun `speed divides the VITS length scale inside the contract bounds`() {
        val config = PiperVoiceConfig(22_050, 0.667f, 1.0f, 0.8f, "en-us", lessacMap)
        assertEquals(1.0f, config.scales(1.0)[1], "speed 1.0 stays the measured json value")
        assertEquals(2.0f, config.scales(0.1)[1], "below the contract floor clamps to 0.5x speed (durations double)")
        assertEquals(0.667f, config.scales(2.0)[0])
        assertEquals(0.8f, config.scales(2.0)[2], "noise scales are untouched by speed")
    }

    private companion object {
        /** lessac-shaped config — the pinned artifact's fields, trimmed. */
        val VOICE_JSON =
            """
            {
              "audio": {"sample_rate": 22050, "quality": "medium"},
              "espeak": {"voice": "en-us"},
              "language": {"code": "en_US", "family": "en", "region": "US"},
              "dataset": "lessac",
              "inference": {"noise_scale": 0.667, "length_scale": 1, "noise_w": 0.8},
              "phoneme_type": "espeak",
              "phoneme_id_map": {
                "\u0283": [96],
                "^": [1],
                "_": [0],
                "$": [2]
              }
            }
            """.trimIndent()
    }
}
