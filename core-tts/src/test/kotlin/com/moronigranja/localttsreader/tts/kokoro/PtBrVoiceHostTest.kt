package com.moronigranja.localttsreader.tts.kokoro

import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * pt-BR spot test (user request, 2026-08-26): the pinned pf_/pm_ voices must
 * synthesize real Portuguese through the pt-br espeak-ng G2P — the
 * voice→language map (KokoroEngine.VOICE_LANGUAGES) routes `pf_`/`pm_` to
 * pt-br phonemization; this asserts the whole chain on the real model.
 *
 * Requires the pack cache (~/.cache/local-tts-reader/packs) and a host
 * espeak-ng lib symlinked at ~/.cache/local-tts-reader/host-espeak/ (the
 * harness layout the floor probe used).
 */
class PtBrVoiceHostTest {
    private val home = System.getProperty("user.home")

    private fun engine(): KokoroEngine {
        val cache = PackCache(File(home, ".cache/local-tts-reader/packs"))
        val model = cache.targetFile(KokoroPacks.model)
        val voices = cache.targetFile(KokoroPacks.voices)
        val espeakLib = File(home, ".cache/local-tts-reader/host-espeak/libespeak-ng.so")
        // Real-model test: skipped on CI runners (no pack cache) instead of
        // failing; the on-device PtVoiceE2eTest covers the same path there.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            model.isFile && voices.isFile && espeakLib.isFile,
            "kokoro packs + host espeak-ng staged at ~/.cache/local-tts-reader",
        )
        val phonemizer = EspeakPhonemizer(espeakLib.absolutePath, File("/usr/share/espeak-ng-data").absolutePath)
        return KokoroEngine.open(
            spec = DefaultEngines.kokoro,
            packs = KokoroPacks.all,
            modelFile = model,
            voicesFile = voices,
            phonemizer = phonemizer,
        )
    }

    @Test
    fun ptBrVoiceSynthesizesPortuguese() =
        runBlocking {
            val engine = engine()
            val ptText = "O rato roeu a roupa do rei de Roma. "
            val outcome = engine.synthesize(SynthesisRequest(ptText, "pf_dora", speed = 1.0))
            val audio = outcome as? SynthesisOutcome.Audio ?: error("expected Audio, was $outcome")
            val seconds = audio.pcm.size / 2.0 / audio.sampleRateHz
            assertTrue(seconds in 1.0..12.0, "duration sane ($seconds s)")
            assertEquals(24_000, audio.sampleRateHz)
            assertTrue(audio.segments?.isNotEmpty() == true, "sentence anchors for the read-along")
            // The gender pair exists too.
            val male = engine.synthesize(SynthesisRequest("O rato roeu a roupa do rei de Roma. ", "pm_alex", speed = 1.0))
            assertTrue(male is SynthesisOutcome.Audio, "pm_alex synthesized, was $male")
            val maleAudio = male as? SynthesisOutcome.Audio ?: error("pm_alex failed, was $male")
            assertTrue(maleAudio.pcm.isNotEmpty())
        }

    @Test
    fun wrongFamilyPrefixIsRejectedTyped() =
        runBlocking {
            val engine = engine()
            val outcome = engine.synthesize(SynthesisRequest("hello", "pf_dora_typo", speed = 1.0))
            assertTrue(outcome is SynthesisOutcome.Failed, "was $outcome")
            val failed = outcome as? SynthesisOutcome.Failed ?: error("was $outcome")
            assertTrue(failed.reason.contains("unknown voice"), "was: ${failed.reason}")
        }
}
