package io.github.moronigranja.ayvu.tts

import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.piper.PiperPacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultEnginesTest {
    @Test
    fun `catalog ids are unique and ordered primary first`() {
        val engines = DefaultEngines.descriptors
        assertEquals(engines.map { it.spec.id }.distinct().size, engines.size, "engine ids must be unique")
        assertEquals(EngineTier.PRIMARY, engines.first().spec.tier, "v1 primary must lead the catalog")
    }

    @Test
    fun `cosyvoice3 declares its 9 hard-facts languages and sits in the fallback tier`() {
        val spec = DefaultEngines.cosyVoice3
        assertEquals(9, spec.languages.size)
        assertTrue(spec.languages.containsAll(setOf("zh", "en", "fr", "es", "ja", "ko", "it", "ru", "de")))
        assertEquals(EngineTier.FALLBACK, spec.tier, "CosyVoice3 stays behind the T3 gate (decisions #21)")
    }

    @Test
    fun `kokoro advertises exactly the languages its voice pack serves`() {
        val spec = DefaultEngines.kokoro
        assertEquals(EngineTier.PRIMARY, spec.tier)
        // The pinned voices-v1.0.bin covers 9 families but no German or
        // Korean voices exist in the release pack (T2 pinning).
        assertEquals(setOf("en", "fr", "es", "it", "pt", "ja", "zh", "hi"), spec.languages)
        assertTrue("pt" in spec.languages, "v1.0 ships pt-BR voices (hard-facts)")
        assertFalse("de" in spec.languages, "no German voices in the v1.0 pack")
        assertFalse("ko" in spec.languages, "no Korean voices in the v1.0 pack")
    }

    @Test
    fun `kokoro ships the pinned T2 pack descriptors`() {
        val kokoro = DefaultEngines.descriptors.first { it.spec.id == "kokoro-82m" }
        assertEquals(KokoroPacks.all, kokoro.packs)
        assertEquals(listOf("kokoro-model", "kokoro-voices", "espeak-ng"), kokoro.packs.map { it.id })

        val model = KokoroPacks.model
        assertTrue(model.url.startsWith("https://"))
        assertTrue(model.sha256Hex.length == 64 && model.sha256Hex.all { it in "0123456789abcdefABCDEF" })
        assertEquals(325_505_369L, model.sizeBytes, "kokoro-v1.0.onnx @ model-files-v1.1")
        assertEquals(28_214_398L, KokoroPacks.voices.sizeBytes, "voices-v1.0.bin @ model-files-v1.1")

        val espeak = KokoroPacks.espeak
        assertTrue(espeak.url.startsWith("https://"), "espeak-ng is served over HTTPS")
        assertEquals(10_144_828L, espeak.sizeBytes, "espeak-ng bundle @ moronigranja/ayvu release")
        assertEquals(64, espeak.sha256Hex.length)
    }

    @Test
    fun `cosyvoice3 still ships no pack descriptors until its slice pins artifacts`() {
        val cosy = DefaultEngines.descriptors.first { it.spec.id == "cosyvoice3-0.5b" }
        assertTrue(cosy.packs.isEmpty(), "no fake URLs/hashes may ship")
        assertTrue(cosy.spec.tier == EngineTier.FALLBACK)
    }

    @Test
    fun `piper is the adopted D4 primary-tier engine with its pinned voices`() {
        val spec = DefaultEngines.piper
        assertEquals("piper-v1", spec.id)
        assertEquals(EngineTier.PRIMARY, spec.tier, "quality gate passed + realtime measured (decisions #99 addenda)")
        // The pinned rhasspy/piper-voices voices @ 1162a917; German is
        // Piper-only at v1 (Kokoro ships no German voices). Korean stays
        // unpinned on its CC-BY-NC license (owner call).
        assertEquals(setOf("en", "de", "es", "it", "pt"), spec.languages)
        assertFalse("ko" in spec.languages, "ko_KR-kss-medium is CC-BY-NC-SA — not pinned")
    }

    @Test
    fun `piper ships the pinned D4 pack descriptors`() {
        val piper = DefaultEngines.descriptors.first { it.spec.id == "piper-v1" }
        assertEquals(PiperPacks.all, piper.packs)
        assertEquals(
            listOf(
                "piper-lessac-medium",
                "piper-lessac-medium-config",
                "piper-thorsten-high",
                "piper-thorsten-high-config",
                "piper-davefx-medium",
                "piper-davefx-medium-config",
                "piper-serena-medium",
                "piper-serena-medium-config",
                "piper-faber-medium",
                "piper-faber-medium-config",
            ),
            piper.packs.map { it.id },
        )

        val model = PiperPacks.lessacModel
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/1162a9173d0ce503555aed757976b7a9912eae4c/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            model.url,
        )
        assertEquals("5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f", model.sha256Hex)
        assertEquals(63_201_294L, model.sizeBytes, "en_US-lessac-medium.onnx @ 1162a917 (HF LFS oid)")
        assertEquals(PackKind.VOICE, PiperPacks.lessacConfig.kind, "the .onnx.json is the voice config asset")
        assertEquals(4_885L, PiperPacks.lessacConfig.sizeBytes, "the voice config travels with its model")
        assertEquals(model.id, PiperPacks.lessacConfig.companionOf, "the config is a companion of its model")

        val thorsten = PiperPacks.thorstenModel
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/resolve/1162a9173d0ce503555aed757976b7a9912eae4c/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx",
            thorsten.url,
        )
        assertEquals("9df1c43c61149ef9b39e618e2b861fbe41e1fcea9390b2dac62e8761573ea4f1", thorsten.sha256Hex)
        assertEquals(113_895_201L, thorsten.sizeBytes, "de_DE-thorsten-high.onnx @ 1162a917 (HF LFS oid)")
        assertEquals(4_875L, PiperPacks.thorstenConfig.sizeBytes)
        assertEquals(thorsten.id, PiperPacks.thorstenConfig.companionOf, "the config is a companion of its model")
    }
}
