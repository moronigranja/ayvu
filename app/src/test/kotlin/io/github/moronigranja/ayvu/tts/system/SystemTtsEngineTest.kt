package io.github.moronigranja.ayvu.tts.system

import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C1.5 host test (fake seam, no Robolectric): the engine contract — Audio
 * outcome shape, Failed-on-unavailable naming the language, and the
 * never-throws contract.
 */
class SystemTtsEngineTest {
    @Test
    fun `audio outcome carries mono pcm and no segments`() =
        runTest {
            val engine = SystemTtsEngine(FakeSeam(TtsSynthesis.Audio(ByteArray(64), 22_050)))

            val outcome = engine.synthesize(SynthesisRequest("hello"))

            val audio = outcome as SynthesisOutcome.Audio
            assertEquals(64, audio.pcm.size)
            assertEquals(22_050, audio.sampleRateHz)
            assertEquals(1, audio.channelCount)
            assertNull(audio.segments, "degraded path has no read-along spans")
        }

    @Test
    fun `unavailable names the requested language`() =
        runTest {
            val engine = SystemTtsEngine(FakeSeam(TtsSynthesis.Unavailable))

            val outcome = engine.synthesize(SynthesisRequest("hola", voice = "ef_dora"))

            val failed = outcome as SynthesisOutcome.Failed
            assertTrue("es-ES" in failed.reason, "names es-ES: ${failed.reason}")
        }

    @Test
    fun `unavailable without a voice names the device default`() =
        runTest {
            val engine = SystemTtsEngine(FakeSeam(TtsSynthesis.Unavailable))

            val failed = engine.synthesize(SynthesisRequest("hi")) as SynthesisOutcome.Failed
            assertTrue("device default" in failed.reason, "names the default: ${failed.reason}")
        }

    @Test
    fun `empty pcm reads as failure`() =
        runTest {
            val engine = SystemTtsEngine(FakeSeam(TtsSynthesis.Audio(ByteArray(0), 8_000)))

            val failed = engine.synthesize(SynthesisRequest("x")) as SynthesisOutcome.Failed
            assertTrue("no audio" in failed.reason, "no audio named")
        }

    @Test
    fun `never throws - seam failure returns Failed`() =
        runTest {
            val engine = SystemTtsEngine(ThrowingSeam())

            val outcome = engine.synthesize(SynthesisRequest("x"))
            assertTrue(outcome is SynthesisOutcome.Failed)
        }

    @Test
    fun `a cancelled synthesis propagates instead of reading as Failed`() =
        runTest {
            // The seam's withContext resumption throws when the caller was
            // cancelled (a superseding command cancels the play loop / fill).
            // Converting that into SynthesisOutcome.Failed published the raw job
            // message as a synthesis failure and counted it toward the
            // pre-generation failure cap (owner report 2026-09-22).
            val engine = SystemTtsEngine(CancellingSeam())
            val thrown =
                try {
                    engine.synthesize(SynthesisRequest("x"))
                    null
                } catch (e: CancellationException) {
                    e
                }
            assertTrue(thrown != null, "a cancelled synthesis propagates, never a Failed outcome")
        }

    @Test
    fun `voice hint maps the kokoro family to the device language`() =
        runTest {
            val seam = FakeSeam(TtsSynthesis.Audio(ByteArray(8), 16_000))
            val engine = SystemTtsEngine(seam)

            engine.synthesize(SynthesisRequest("hi", voice = "af_heart"))
            assertEquals("en-US", seam.lastLanguage)
            engine.synthesize(SynthesisRequest("hola", voice = "pf_dora"))
            assertEquals("pt-BR", seam.lastLanguage)
            engine.synthesize(SynthesisRequest("hola"))
            assertNull(seam.lastLanguage, "null voice → device default")
        }

    @Test
    fun `spec is a fallback zero-download engine`() {
        val engine = SystemTtsEngine(FakeSeam(TtsSynthesis.Unavailable))

        assertEquals(SettingsStore.SYSTEM_TTS_ENGINE, engine.spec.id)
        assertEquals(EngineTier.FALLBACK, engine.spec.tier)
        assertTrue(engine.spec.languages.isNotEmpty())
        assertEquals(emptyList<TtsPack>(), engine.packs)
    }

    private class FakeSeam(
        private val outcome: TtsSynthesis,
    ) : SystemTtsSeam {
        var lastLanguage: String? = null

        override fun availableLanguages(): Set<String> = setOf("en", "es", "pt")

        override suspend fun synthesizeToPcm(
            text: String,
            language: String?,
        ): TtsSynthesis {
            lastLanguage = language
            return outcome
        }
    }

    private class ThrowingSeam : SystemTtsSeam {
        override fun availableLanguages(): Set<String> = setOf("en")

        override suspend fun synthesizeToPcm(
            text: String,
            language: String?,
        ): TtsSynthesis = error("boom")
    }

    /** The cancelled-caller shape: the seam's suspension resumes with a
     *  CancellationException (what `withContext` throws in production). */
    private class CancellingSeam : SystemTtsSeam {
        override fun availableLanguages(): Set<String> = setOf("en")

        override suspend fun synthesizeToPcm(
            text: String,
            language: String?,
        ): TtsSynthesis = throw CancellationException("StandaloneCoroutine was cancelled")
    }
}
