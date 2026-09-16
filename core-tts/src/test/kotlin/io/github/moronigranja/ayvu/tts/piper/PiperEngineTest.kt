package io.github.moronigranja.ayvu.tts.piper

import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.PhonemizeException
import io.github.moronigranja.ayvu.tts.kokoro.Phonemizer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PiperEngineTest {
    private val spec = EngineSpec("piper-v1", "Piper", EngineTier.PRIMARY, setOf("en", "de"))
    private lateinit var session: FakeSession
    private lateinit var phonemizer: RecordingPhonemizer

    private fun engine(): PiperEngine =
        PiperEngine(
            spec,
            PiperPacks.forVoice(PiperVoices.LESSAC),
            PiperVoices.LESSAC,
            PiperVoiceConfig(22_050, 0.667f, 1.0f, 0.8f, "en-us", lessacMap),
            session,
            phonemizer,
        )

    @BeforeEach
    fun setUp() {
        session = FakeSession()
        phonemizer = RecordingPhonemizer()
    }

    private fun audioOf(outcome: SynthesisOutcome): SynthesisOutcome.Audio = assertInstanceOf(SynthesisOutcome.Audio::class.java, outcome)

    @Test
    fun `synthesizes mono pcm at the voice sample rate with no segments`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ, wˈɜːld! "
            val outcome = engine().synthesize(SynthesisRequest("Hello, world!"))
            val audio = assertInstanceOf(SynthesisOutcome.Audio::class.java, outcome)
            assertEquals(22_050, audio.sampleRateHz, "the voice json sample rate")
            assertEquals(1, audio.channelCount)
            assertNull(audio.segments, "read-along degrades to passage level: stock export has no word timestamps (#30b)")
            assertTrue(audio.pcm.size % 2 == 0)
            assertEquals(FAKE_SAMPLES * 2, audio.pcm.size, "16-bit mono PCM, one byte pair per float sample")
            assertEquals("en-us", phonemizer.languages.single())

            // The verified official piper framing opens BOS+PAD and closes EOS;
            // the trailing phonemizer space maps to the gap id (3).
            assertEquals(1, session.ids.first())
            assertEquals(0, session.ids[1])
            assertEquals(2, session.ids.last())
        }

    @Test
    fun `packs are the voice's pinned model and config`() {
        val engine = engine()
        assertEquals(listOf(PiperPacks.lessacModel, PiperPacks.lessacConfig), engine.packs)
        assertTrue(engine.packs.all { it.engineId == "piper-v1" })
        assertTrue(
            engine.packs.all { it.url.startsWith("https://huggingface.co/rhasspy/piper-voices/resolve/") },
            "packs are served over HTTPS from the pinned rhasspy/piper-voices revision",
        )
        assertTrue(engine.packs.all { it.sha256Hex.length == 64 })
    }

    @Test
    fun `blank text fails without touching the session`() =
        runBlocking {
            val outcome = engine().synthesize(SynthesisRequest("   "))
            assertEquals(SynthesisOutcome.Failed("nothing to synthesize"), outcome)
            assertTrue(session.calls.isEmpty())
        }

    @Test
    fun `a voice the instance does not serve fails typed`() =
        runBlocking {
            val outcome = engine().synthesize(SynthesisRequest("hallo", voice = PiperVoices.THORSTEN))
            val failed = assertInstanceOf(SynthesisOutcome.Failed::class.java, outcome)
            assertTrue(failed.reason.contains("unknown voice"), "reason: ${failed.reason}")
            assertTrue(session.calls.isEmpty(), "one model per instance: no silent model switch")
        }

    @Test
    fun `empty phonemes fail typed`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = ""
            val outcome = engine().synthesize(SynthesisRequest("..."))
            assertEquals(SynthesisOutcome.Failed("nothing to synthesize"), outcome)
            assertTrue(session.calls.isEmpty())
        }

    @Test
    fun `unmapped-only phonemes fail instead of synthesizing silence`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "xyz"
            val outcome = engine().synthesize(SynthesisRequest("..."))
            assertEquals(SynthesisOutcome.Failed("nothing to synthesize"), outcome)
            assertTrue(session.calls.isEmpty())
        }

    @Test
    fun `unsupported phonemization language fails typed`() =
        runBlocking {
            phonemizer.failWith = PhonemizeException("language 'de' is not supported by this espeak-ng installation (available: en-us)")
            val outcome = engine().synthesize(SynthesisRequest("Hallo"))
            val failed = assertInstanceOf(SynthesisOutcome.Failed::class.java, outcome)
            assertTrue(failed.reason.contains("not supported"), "reason: ${failed.reason}")
        }

    @Test
    fun `failures in the session map to Failed`() =
        runBlocking {
            session.failWith = RuntimeException("broken session")
            phonemizer.phonemes["en-us"] = "həlˈoʊ "
            val outcome = engine().synthesize(SynthesisRequest("hello"))
            val failed = assertInstanceOf(SynthesisOutcome.Failed::class.java, outcome)
            assertTrue(failed.reason.contains("broken session"))
        }

    @Test
    fun `request speed divides the VITS length scale`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ "
            engine().synthesize(SynthesisRequest("hello", speed = 2.0))
            assertEquals(0.5f, session.scales[1], "durations divide by speed")
            assertEquals(0.667f, session.scales[0], "noise scale untouched")
            assertEquals(0.8f, session.scales[2], "noise_w untouched")
        }

    @Test
    fun `streaming emits the whole passage once and matches the buffered outcome`() {
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ, wˈɜːld! "
            val engine = engine()

            val chunks = mutableListOf<ByteArray>()
            val streamed = audioOf(engine.synthesizeStreaming(SynthesisRequest("hello")) { chunk -> chunks += chunk })
            val buffered = audioOf(engine.synthesize(SynthesisRequest("hello")))

            assertEquals(1, chunks.size, "one pass over one VITS graph: a single emit")
            assertEquals(buffered.pcm.toList(), chunks.single().toList(), "stream chunk equals the buffered PCM")
            assertEquals(buffered.segments, streamed.segments)
        }
    }

    private class RecordingPhonemizer : Phonemizer {
        val phonemes = mutableMapOf<String, String>()
        val languages = mutableListOf<String>()
        var failWith: PhonemizeException? = null

        override fun phonemize(
            text: String,
            language: String,
        ): String {
            failWith?.let { throw it }
            languages += language
            return phonemes[language] ?: throw PhonemizeException("unexpected language $language")
        }

        override fun supportedLanguages(): Set<String> = phonemes.keys
    }

    private class FakeSession : PiperSession {
        val calls = mutableListOf<Call>()
        val ids: IntArray get() = calls.single().ids
        val scales get() = calls.single().scales

        var failWith: RuntimeException? = null

        override fun infer(
            phonemeIds: IntArray,
            scales: FloatArray,
        ): FloatArray {
            failWith?.let { throw it }
            calls += Call(phonemeIds.copyOf(), scales.copyOf())
            return FloatArray(FAKE_SAMPLES) { 0.25f } // uniform: finite, non-empty
        }

        override fun close(): Unit = throw UnsupportedOperationException("fake session must not be closed by the engine")
    }

    private data class Call(
        val ids: IntArray,
        val scales: FloatArray,
    )

    private companion object {
        const val FAKE_SAMPLES = 100

        /** Real lessac map values, recorded from official piper-tts (decisions #154). */
        val lessacMap =
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
            )
    }
}
