package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-in-language degrade contract (decisions #29/#101): only a
 * successful translation swaps text+voice; ANY failure hands the delegate the
 * ORIGINAL request — a wrong-voice pronunciation of untranslated text is the
 * bug mode this kills.
 */
class TranslatingEngineTest {
    private val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en", "pt"))

    private fun engine(
        translator: suspend (String) -> String? = { "traduzido: $it" },
        targetVoice: String = "pt_voice",
        targetLang: String = "pt",
        delegateFactory: (EngineSpec) -> RecordingDelegate = ::RecordingDelegate,
    ): Pair<TranslatingEngine, RecordingDelegate> {
        val delegate = delegateFactory(spec)
        return TranslatingEngine(delegate, translator, targetVoice, targetLang) to delegate
    }

    @Test
    fun successSwapsTextAndVoice() =
        runBlocking {
            val (engine, delegate) = engine()
            val outcome = engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("traduzido: Hello world", delegate.lastRequest!!.text)
            assertEquals("pt_voice", delegate.lastRequest!!.voice)
        }

    @Test
    fun translatorExceptionDegradesToOriginal() =
        runBlocking {
            val (engine, delegate) =
                engine(translator = { throw IllegalStateException("graph gone") })
            val outcome = engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("Hello world", delegate.lastRequest!!.text)
            assertEquals("en_voice", delegate.lastRequest!!.voice)
        }

    @Test
    fun emptyTranslationDegradesToOriginal() =
        runBlocking {
            val (engine, delegate) = engine(translator = { "" })
            val outcome = engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("Hello world", delegate.lastRequest!!.text)
        }

    @Test
    fun blankTextPassesThroughUntouched() =
        runBlocking {
            var called = false
            val (engine, delegate) =
                engine(translator = {
                    called = true
                    "x"
                })
            val outcome = engine.synthesize(SynthesisRequest("  ", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertTrue(!called, "translator must not run for blank text")
            assertEquals("  ", delegate.lastRequest!!.text)
        }

    @Test
    fun streamingGoesThroughTheSamePath() =
        runBlocking {
            val (engine, delegate) = engine()
            val windows = mutableListOf<ByteArray>()
            val outcome = engine.synthesizeStreaming(SynthesisRequest("Hello", "en_voice")) { windows.add(it) }
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("traduzido: Hello", delegate.lastRequest!!.text)
            val audio = outcome as SynthesisOutcome.Audio
            assertEquals(listOf(audio.pcm), windows)
        }

    @Test
    fun specAndPacksStayTheDelegates() {
        val (engine, delegate) = engine()
        assertEquals(spec, engine.spec)
        assertEquals(delegate.packs, engine.packs)
    }

    private open class RecordingDelegate(
        override val spec: EngineSpec,
    ) : TTSEngine {
        override val packs: List<com.moronigranja.localttsreader.tts.TtsPack> = emptyList()
        var lastRequest: SynthesisRequest? = null
        val requests = mutableListOf<SynthesisRequest>()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            requests.add(request)
            lastRequest = request
            return SynthesisOutcome.Audio(
                pcm = request.text.encodeToByteArray(),
                sampleRateHz = 16_000,
            )
        }
    }

    /** The translated render's synthesis fails (target voice pack missing):
     * the decorator must retry with the ORIGINAL request (#29 whole-attempt). */
    private class FailingOnVoiceDelegate(
        spec: EngineSpec,
        private val failVoice: String,
    ) : RecordingDelegate(spec) {
        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            if (request.voice == failVoice) {
                requests.add(request)
                lastRequest = request
                SynthesisOutcome.Failed("unknown voice '$failVoice'")
            } else {
                super.synthesize(request)
            }
    }

    @Test
    fun delegateSynthesisFailureOfTheTranslatedRenderDegradesToOriginal() =
        runBlocking {
            val (engine, delegate) =
                engine(delegateFactory = { FailingOnVoiceDelegate(it, "pt_voice") })
            val outcome = engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio)
            assertEquals(2, delegate.requests.size)
            assertEquals("pt_voice", delegate.requests[0].voice)
            assertEquals("Hello world", delegate.requests[1].text)
            assertEquals("en_voice", delegate.requests[1].voice)
        }

    @Test
    fun streamingDelegateFailureBeforeFirstWindowDegradesToOriginal() =
        runBlocking {
            val (engine, delegate) =
                engine(delegateFactory = { FailingOnVoiceDelegate(it, "pt_voice") })
            val windows = mutableListOf<ByteArray>()
            val outcome =
                engine.synthesizeStreaming(SynthesisRequest("Hello world", "en_voice")) { windows.add(it) }
            assertTrue(outcome is SynthesisOutcome.Audio)
            assertEquals(1, windows.size)
            assertEquals(2, delegate.requests.size)
            assertEquals("en_voice", delegate.requests[1].voice)
        }
}
