package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-in-language contract (decisions #29/#101): a successful
 * translation routes to the TARGET engine under the target voice; ANY
 * failure hands the DELEGATE the ORIGINAL request — a wrong-voice
 * pronunciation of untranslated text is the bug mode this kills. The two
 * engines are distinct instances: Piper is one-voice-per-instance, so the
 * translated render must never touch the original-voice instance (S22
 * 2026-09-14: it failed typed and every passage degraded to English audio,
 * cached under the x<lang> key).
 */
class TranslatingEngineTest {
    private val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en", "pt"))

    private class Engines(
        val engine: TranslatingEngine,
        val original: RecordingDelegate,
        val target: RecordingDelegate,
    )

    private fun engine(
        translator: suspend (String) -> String? = { "traduzido: $it" },
        targetVoice: String = "pt_voice",
        targetLang: String = "pt",
        targetFactory: (EngineSpec) -> RecordingDelegate = ::RecordingDelegate,
    ): Engines {
        val original = RecordingDelegate(spec)
        val target = targetFactory(spec)
        return Engines(
            TranslatingEngine(
                delegate = original,
                targetEngine = target,
                translate = translator,
                targetVoice = targetVoice,
                targetLang = targetLang,
            ),
            original,
            target,
        )
    }

    @Test
    fun successRoutesToTheTargetEngineUnderTheTargetVoice() =
        runBlocking {
            val e = engine()
            val outcome = e.engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals(0, e.original.requests.size)
            assertEquals(1, e.target.requests.size)
            assertEquals("traduzido: Hello world", e.target.lastRequest!!.text)
            assertEquals("pt_voice", e.target.lastRequest!!.voice)
        }

    @Test
    fun translatorExceptionDegradesToOriginal() =
        runBlocking {
            val e = engine(translator = { throw IllegalStateException("graph gone") })
            val outcome = e.engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("Hello world", e.original.lastRequest!!.text)
            assertEquals("en_voice", e.original.lastRequest!!.voice)
            assertEquals(0, e.target.requests.size)
        }

    @Test
    fun emptyTranslationDegradesToOriginal() =
        runBlocking {
            val e = engine(translator = { "" })
            val outcome = e.engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals("Hello world", e.original.lastRequest!!.text)
        }

    @Test
    fun blankTextPassesThroughUntouched() =
        runBlocking {
            var called = false
            val e =
                engine(translator = {
                    called = true
                    "x"
                })
            val outcome = e.engine.synthesize(SynthesisRequest("  ", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertTrue(!called, "translator must not run for blank text")
            assertEquals("  ", e.original.lastRequest!!.text)
        }

    @Test
    fun streamingGoesThroughTheTargetEngine() =
        runBlocking {
            val e = engine()
            val windows = mutableListOf<ByteArray>()
            val outcome = e.engine.synthesizeStreaming(SynthesisRequest("Hello", "en_voice")) { windows.add(it) }
            assertTrue(outcome is SynthesisOutcome.Audio, "was $outcome")
            assertEquals(0, e.original.requests.size)
            assertEquals("traduzido: Hello", e.target.lastRequest!!.text)
            val audio = outcome as SynthesisOutcome.Audio
            assertEquals(listOf(audio.pcm), windows)
        }

    @Test
    fun specAndPacksStayTheDelegates() {
        val e = engine()
        assertEquals(spec, e.engine.spec)
        assertEquals(e.original.packs, e.engine.packs)
    }

    private open class RecordingDelegate(
        override val spec: EngineSpec,
    ) : TTSEngine {
        override val packs: List<TtsPack> = emptyList()
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

    /** The translated render's synthesis fails on the TARGET engine (the
     * one-voice-per-instance bug mode): the decorator must retry with the
     * ORIGINAL request on the delegate (#29 whole-attempt). */
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

        override suspend fun synthesizeStreaming(
            request: SynthesisRequest,
            onWindow: suspend (ByteArray) -> Unit,
        ): SynthesisOutcome =
            if (request.voice == failVoice) {
                requests.add(request)
                lastRequest = request
                SynthesisOutcome.Failed("unknown voice '$failVoice'")
            } else {
                super.synthesizeStreaming(request, onWindow)
            }
    }

    @Test
    fun targetSynthesisFailureDegradesToTheOriginalEngine() =
        runBlocking {
            val e = engine(targetFactory = { FailingOnVoiceDelegate(it, "pt_voice") })
            val outcome = e.engine.synthesize(SynthesisRequest("Hello world", "en_voice"))
            assertTrue(outcome is SynthesisOutcome.Audio)
            assertEquals(1, e.target.requests.size)
            assertEquals("pt_voice", e.target.requests[0].voice)
            assertEquals(1, e.original.requests.size)
            assertEquals("Hello world", e.original.lastRequest!!.text)
            assertEquals("en_voice", e.original.lastRequest!!.voice)
        }

    @Test
    fun streamingTargetFailureBeforeFirstWindowDegradesToOriginal() =
        runBlocking {
            val e = engine(targetFactory = { FailingOnVoiceDelegate(it, "pt_voice") })
            val windows = mutableListOf<ByteArray>()
            val outcome =
                e.engine.synthesizeStreaming(SynthesisRequest("Hello world", "en_voice")) { windows.add(it) }
            assertTrue(outcome is SynthesisOutcome.Audio)
            assertEquals(1, windows.size)
            assertEquals(1, e.target.requests.size)
            assertEquals(1, e.original.requests.size)
            assertEquals("en_voice", e.original.lastRequest!!.voice)
        }
}
