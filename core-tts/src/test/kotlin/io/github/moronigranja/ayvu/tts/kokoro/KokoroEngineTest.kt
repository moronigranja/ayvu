package io.github.moronigranja.ayvu.tts.kokoro

import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KokoroEngineTest {
    @TempDir
    lateinit var tempDir: File

    private val spec = EngineSpec("kokoro-82m", "Kokoro-82M", EngineTier.PRIMARY, setOf("en", "fr", "pt", "ja"))
    private val packs = listOf(pack("kokoro-model"), pack("kokoro-voices"))
    private val vocab = KokoroVocabulary.resource()
    private lateinit var voices: KokoroVoiceBank
    private lateinit var session: FakeSession
    private lateinit var phonemizer: RecordingPhonemizer

    private fun pack(id: String) =
        TtsPack(
            id = id,
            engineId = "kokoro-82m",
            kind = PackKind.MODEL,
            displayName = id,
            url = "https://example.com/$id",
            sha256Hex = "a".repeat(64),
            sizeBytes = 1,
        )

    @BeforeEach
    fun setUp() {
        voices = KokoroVoiceBank.load(writeVoicesFile())
        session = FakeSession()
        phonemizer = RecordingPhonemizer()
    }

    private fun writeVoicesFile(): File {
        val file = File(tempDir, "voices.bin")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("af_heart", "pf_dora")) {
                zip.putNextEntry(ZipEntry("$name.npy"))
                val total = KokoroVoiceBank.ROWS * KokoroVoiceBank.STYLE_DIM
                val floats = ByteArray(total * 4)
                val buffer = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until total) buffer.put((i * 0.001f) % 1.0f)
                val dict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${KokoroVoiceBank.ROWS}, 1, ${KokoroVoiceBank.STYLE_DIM}), }"
                val header = (dict + "\n").padEnd(64, ' ')
                val headerBytes = header.toByteArray(Charsets.US_ASCII)
                // numpy .npy v1: magic + version + 2-byte little-endian header length.
                val prefix =
                    byteArrayOf(
                        0x93.toByte(),
                        'N'.code.toByte(),
                        'U'.code.toByte(),
                        'M'.code.toByte(),
                        'P'.code.toByte(),
                        'Y'.code.toByte(),
                        1,
                        0,
                    ) +
                        byteArrayOf((headerBytes.size and 0xFF).toByte(), ((headerBytes.size shr 8) and 0xFF).toByte())
                zip.write(prefix + headerBytes + floats)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun engine(): KokoroEngine = KokoroEngine(spec, packs, session, voices, KokoroTokenizer(vocab), phonemizer)

    private fun audioOf(outcome: SynthesisOutcome): SynthesisOutcome.Audio = assertInstanceOf(SynthesisOutcome.Audio::class.java, outcome)

    @Test
    fun `synthesizes audio with the default voice and en-us phonemization`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ, wˈɜːld! "
            val outcome = engine().synthesize(SynthesisRequest("Hello, world!"))
            val audio = audioOf(outcome)
            assertEquals(24_000, audio.sampleRateHz)
            assertEquals(1, audio.channelCount)

            // Uniform fake audio is never trimmed (librosa semantics); the batch
            // ends with '!' but is the last batch — no pause. Length = tokens×100.
            assertTrue(audio.pcm.size % 2 == 0)
            assertEquals("en-us", phonemizer.languages.single())
            assertEquals(listOf(15), session.tokenCounts, "every phoneme character tokenized")
            assertTrue(phonemizer.texts.single().contains("Hello, world!"))
        }

    @Test
    fun `voice family selects the phonemization language`() =
        runBlocking {
            phonemizer.phonemes["pt-br"] = "olˈa! "
            audioOf(engine().synthesize(SynthesisRequest("Olá!", voice = "pf_dora")))
            assertEquals("pt-br", phonemizer.languages.single())
        }

    @Test
    fun `blank text fails without touching the session`() =
        runBlocking {
            val outcome = engine().synthesize(SynthesisRequest("   "))
            assertEquals(SynthesisOutcome.Failed("nothing to synthesize"), outcome)
            assertTrue(session.tokenCounts.isEmpty())
        }

    @Test
    fun `unknown voice fails typed`() =
        runBlocking {
            val outcome = engine().synthesize(SynthesisRequest("hi", voice = "zz_unknown"))
            val failed = assertInstanceOf(SynthesisOutcome.Failed::class.java, outcome)
            assertTrue(failed.reason.contains("unknown voice"))
            assertTrue(session.tokenCounts.isEmpty())
        }

    @Test
    fun `empty phonemes fail typed`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = ""
            val outcome = engine().synthesize(SynthesisRequest("..."))
            assertEquals(SynthesisOutcome.Failed("nothing to synthesize"), outcome)
            assertTrue(session.tokenCounts.isEmpty())
        }

    @Test
    fun `unsupported phonemization language fails typed`() =
        runBlocking {
            phonemizer.failWith = PhonemizeException("language 'pt-br' is not supported by this espeak-ng installation (available: en-us)")
            val outcome = engine().synthesize(SynthesisRequest("Olá!", voice = "pf_dora"))
            val failed = assertInstanceOf(SynthesisOutcome.Failed::class.java, outcome)
            assertTrue(failed.reason.contains("not supported"), "reason: ${failed.reason}")
        }

    @Test
    fun `sentence pauses are added between batches`() =
        runBlocking {
            // 300 a's + "! " + 300 b's splits into two batches, the first ENDING
            // with the sentence mark '!': 0.25s of silence is spliced between them
            // (the second is the last batch and gets none).
            phonemizer.phonemes["en-us"] = "a".repeat(300) + "! " + "b".repeat(300)
            val audio = audioOf(engine().synthesize(SynthesisRequest("hello")))
            assertEquals(2, session.calls.size, "two inference windows")
            val totalTokens = session.tokenCounts.sum()
            assertEquals(601, totalTokens, "batch0 = 300 a's + '!', batch1 = 300 b's")
            val expected = totalTokens * FAKE_SAMPLES_PER_TOKEN + (0.25 * 24_000).toInt()
            assertEquals(expected, audio.pcm.size / 2, "token samples + one sentence pause")
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

    /** Padding is the session's job: the engine hands over [0, *tokens, 0]. */
    @Test
    fun `session receives padded token windows`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ "
            engine().synthesize(SynthesisRequest("hello"))
            val call = session.calls.single()
            assertEquals(listOf(vocab['h'], vocab['ə'], vocab['l'], vocab['ˈ'], vocab['o'], vocab['ʊ']), call.tokens.toList())
            assertEquals(256, call.styleRow.size)
        }

    @Test
    fun `sentence anchors split the audio at marks and stay contiguous`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ wˈɜːld! bˈaɪ bˈaɪ! cˈaɪt "
            val audio = audioOf(engine().synthesize(SynthesisRequest("one two!")))
            val segments = audio.segments ?: error("segments expected on a timings graph")
            // Three sentences: before the first '!', between the two marks, after the last.
            assertEquals(3, segments.size)
            assertEquals(segments[0].endSeconds, segments[1].startSeconds, 1e-9, "gap-free adjacent spans")
            assertEquals(segments[1].endSeconds, segments[2].startSeconds, 1e-9, "gap-free adjacent spans")
            val total = audio.pcm.size / 2.0 / 24_000.0
            assertEquals(total, segments.last().endSeconds, 1e-9, "last span runs to the audio end")
            assertTrue(segments[0].endSeconds > segments[0].startSeconds + 1e-9)
            assertTrue(segments[1].endSeconds > segments[1].startSeconds + 1e-9)
        }

    @Test
    fun `sentence anchors are null without graph durations`() =
        runBlocking {
            session = FakeSession(hasTimings = false)
            phonemizer.phonemes["en-us"] = "həlˈoʊ! "
            val audio = audioOf(engine().synthesize(SynthesisRequest("hello")))
            assertEquals(null, audio.segments)
        }

    @Test
    fun `unmarked text yields a single full-length anchor`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ "
            val audio = audioOf(engine().synthesize(SynthesisRequest("hello")))
            val total = audio.pcm.size / 2.0 / 24_000.0
            // First phoneme starts after the BOS pad's duration (100 fake samples);
            // production trim removes that pad, pulling the start to ~0.
            assertEquals(listOf(SegmentAnchor(FAKE_SAMPLES_PER_TOKEN / 24_000.0, total)), audio.segments)
        }

    private class RecordingPhonemizer : Phonemizer {
        val phonemes = mutableMapOf<String, String>()
        val languages = mutableListOf<String>()
        val texts = mutableListOf<String>()
        var failWith: PhonemizeException? = null

        override fun phonemize(
            text: String,
            language: String,
        ): String {
            failWith?.let { throw it }
            texts += text
            languages += language
            return phonemes[language] ?: throw PhonemizeException("unexpected language $language")
        }

        override fun supportedLanguages(): Set<String> = phonemes.keys
    }

    private class FakeSession(
        override var hasTimings: Boolean = true,
        var failWith: RuntimeException? = null,
    ) : KokoroSession {
        val calls = mutableListOf<PaddedCall>()
        val speeds = mutableListOf<Double>()
        val tokenCounts get() = calls.map { it.tokens.size }

        override val embeddedVocab: Map<Char, Int> = emptyMap()

        override fun infer(
            tokens: IntArray,
            styleRow: FloatArray,
            speed: Double,
        ): InferResult {
            failWith?.let { throw it }
            speeds += speed
            calls += PaddedCall(tokens.copyOf(), styleRow.copyOf())
            val n = tokens.size * FAKE_SAMPLES_PER_TOKEN
            return InferResult(
                audio = FloatArray(n) { 0.25f }, // uniform: never trimmed
                duration = if (hasTimings) IntArray(tokens.size) { 10 } else null,
            )
        }

        override fun close(): Unit = throw UnsupportedOperationException("fake session must not be closed by the engine")
    }

    @Test
    fun `request speed reaches the session`() =
        runBlocking {
            phonemizer.phonemes["en-us"] = "həlˈoʊ "
            engine().synthesize(SynthesisRequest("hello", speed = 2.0))
            assertEquals(listOf(2.0), session.speeds, "speed must flow to the graph input")
        }

    @Test
    fun `streaming emits one chunk per window that reassembles to the buffered bytes`() {
        runBlocking {
            phonemizer.phonemes["en-us"] = "a".repeat(300) + "! " + "b".repeat(300)
            val engine = engine()

            val chunks = mutableListOf<ByteArray>()
            val streamed = audioOf(engine.synthesizeStreaming(SynthesisRequest("hello")) { chunk -> chunks += chunk })
            val buffered = audioOf(engine.synthesize(SynthesisRequest("hello")))

            assertEquals(2, chunks.size, "one chunk per inference window")
            val reassembled = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(reassembled, at)
                at += chunk.size
            }
            assertEquals(buffered.pcm.toList(), reassembled.toList(), "stream concatenates to the buffered PCM")
            assertEquals(buffered.segments, streamed.segments, "segments agree between paths")
        }
    }

    @Test
    fun `streaming without graph durations emits per-window chunks and null segments`() {
        runBlocking {
            session = FakeSession(hasTimings = false)
            phonemizer.phonemes["en-us"] = "a".repeat(300) + "! " + "b".repeat(300)
            val chunks = mutableListOf<ByteArray>()
            val outcome = audioOf(engine().synthesizeStreaming(SynthesisRequest("hello")) { chunk -> chunks += chunk })
            assertEquals(2, chunks.size, "two windows even without timings")
            assertEquals(null, outcome.segments)
        }
    }

    private data class PaddedCall(
        val tokens: IntArray,
        val styleRow: FloatArray,
    )

    private companion object {
        const val FAKE_SAMPLES_PER_TOKEN = 100
    }
}
