package io.github.moronigranja.ayvu.tts.kokoro

import ai.onnxruntime.OrtSession
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import io.github.moronigranja.ayvu.tts.pcm16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kokoro-82M as a [TTSEngine] — a faithful JVM port of the reference
 * thewh1teagle/kokoro-onnx `SpeechPipeline` (batch synthesis path):
 *
 * text → espeak-ng phonemes (stress + preserved punctuation, vocab-filtered)
 * → balanced ≤510-phoneme windows → per-window inference ([0, tokens, 0]
 * padded, style row for the window length) → librosa trim of each window →
 * batch-end pauses after sentence/clause marks → timing-aware pause topping-up
 * (when the graph reports durations, as the pinned v1.1 export does) →
 * 16-bit little-endian PCM @ 24000 Hz mono.
 *
 * Packs: the engine requires its model and voices packs ready; the registry is
 * the readiness gate and construction fails fast on missing files — an engine
 * never fabricates a fallback model.
 *
 * Voice → language: the v1.0 voice pack names encode their family
 * (af_/am_ = American English, pf_/pm_ = Brazilian Portuguese, ...) and the
 * family picks the espeak phonemization language, so a voice can never be
 * spoken through a wrong G2P. A null [SynthesisRequest.voice] falls back to
 * the default en-US voice (af_heart).
 *
 * Thread safety: phonemization is serialized inside the phonemizer (espeak
 * global state); inference runs on the ORT session (thread-safe). Synthesis is
 * cancellable at window granularity — a running window cannot be preempted.
 */
class KokoroEngine internal constructor(
    override val spec: EngineSpec,
    override val packs: List<TtsPack>,
    private val session: KokoroSession,
    private val voices: KokoroVoiceBank,
    private val tokenizer: KokoroTokenizer,
    private val phonemizer: Phonemizer,
) : TTSEngine,
    AutoCloseable {
    override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
        withContext(Dispatchers.IO) {
            try {
                synthesizeCore(request, coroutineContext, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SynthesisOutcome.Failed(e.message ?: "synthesis failed")
            }
        }

    override suspend fun synthesizeStreaming(
        request: SynthesisRequest,
        onWindow: suspend (ByteArray) -> Unit,
    ): SynthesisOutcome =
        withContext(Dispatchers.IO) {
            try {
                synthesizeCore(request, coroutineContext, onWindow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SynthesisOutcome.Failed(e.message ?: "synthesis failed")
            }
        }

    /**
     * The single synthesis path (decisions #138): one window loop, and when
     * [sink] is non-null each window's final PCM16 is handed to it the moment
     * the window completes — the first audio is available long before the
     * passage finishes. [synthesize] is the collector (sink = null) and
     * [synthesizeStreaming] feeds a consumer; both return the identical
     * whole-passage outcome, so a streaming consumer can never diverge from
     * the buffered contract.
     *
     * Pause top-up ([KokoroTimings.insertPauses]) now runs per window over
     * the window's own loudness floor instead of once over the merged
     * passage. A boundary mark is already padded by [PhonemeChunker.pauseAfter]
     * (and the model's natural gap), so it rarely top-ups; an intra-window
     * mark's quiet run is fully inside its window. The change is cosmetic
     * (pause lengths, bounded by the 0.15 s reach) and logged here — the
     * on-device output is run-to-run nondeterministic at the sample level
     * regardless (decisions #116).
     */
    private suspend fun synthesizeCore(
        request: SynthesisRequest,
        context: CoroutineContext,
        sink: (suspend (ByteArray) -> Unit)?,
    ): SynthesisOutcome {
        if (request.text.isBlank()) return SynthesisOutcome.Failed("nothing to synthesize")

        val voiceName = request.voice ?: DEFAULT_VOICE
        val language =
            VOICE_LANGUAGES[voiceName.substringBefore('_')]
                ?: return SynthesisOutcome.Failed("unknown voice '$voiceName'")

        val phonemes =
            try {
                tokenizer.phonemize(phonemizer, request.text, language)
            } catch (e: PhonemizeException) {
                return SynthesisOutcome.Failed(e.message ?: "phonemization failed")
            }
        if (phonemes.isEmpty()) return SynthesisOutcome.Failed("nothing to synthesize")

        // Newlines are not in the vocabulary, so collapse every whitespace run
        // into the single gap between words the model expects (reference _prepare).
        val collapsed = phonemes.split(Regex("\\s+")).joinToString(" ")
        val batches = PhonemeChunker.split(collapsed)
        if (batches.isEmpty()) return SynthesisOutcome.Failed("nothing to synthesize")

        val parts = ArrayList<FloatArray>(batches.size)
        val spoken = ArrayList<Timing>()
        var offset = 0L
        for ((index, batch) in batches.withIndex()) {
            context.ensureActive()

            val tokens =
                try {
                    tokenizer.tokenize(batch)
                } catch (e: IllegalArgumentException) {
                    return SynthesisOutcome.Failed(e.message ?: "text too long")
                }
            if (tokens.isEmpty()) continue

            val styleRow =
                voices.styleFor(voiceName, tokens.size)
                    ?: return SynthesisOutcome.Failed("unknown voice '$voiceName'")
            val result = session.infer(tokens, styleRow, request.speed)

            // Drop the leading pad boundary so index i is where phoneme i starts.
            var edges: IntArray? = null
            if (result.duration != null) {
                edges =
                    KokoroTimings
                        .tokenEdges(result.duration, result.audio.size)
                        .copyOfRange(1, result.duration.size + 1)
            }

            // Trim leading/trailing silence of each window: the model pads
            // ~2s at a text start, ~20ms at joins; the pause the punctuation
            // asks for is added back after trimming.
            val (trimmed, head) = AudioTrim.trim(result.audio)
            val audio: FloatArray =
                if (edges != null) {
                    val shifted = IntArray(edges.size) { i -> (edges[i] - head.first).coerceIn(0, trimmed.size) }
                    edges = shifted
                    trimmed
                } else {
                    trimmed
                }

            var batchAudio = audio
            if (index < batches.lastIndex) {
                val pause = PhonemeChunker.pauseAfter(batch, SENTENCE_PAUSE, CLAUSE_PAUSE)
                if (pause > 0.0) {
                    val padded = FloatArray(audio.size + (pause * SAMPLE_RATE).toInt())
                    audio.copyInto(padded)
                    batchAudio = padded
                }
            }

            // Pause top-up + timing accumulation happen per window, so the
            // window is final the moment its inference lands.
            var finalAudio = batchAudio
            if (session.hasTimings && edges != null) {
                val windowTimings = KokoroTimings.timings(tokenizer.known(batch), edges, SAMPLE_RATE)
                val (withPauses, shifted) =
                    KokoroTimings.insertPauses(batchAudio, windowTimings, SAMPLE_RATE, SENTENCE_PAUSE, CLAUSE_PAUSE)
                finalAudio = withPauses
                val startSeconds = offset / SAMPLE_RATE.toDouble()
                spoken += shifted.map { Timing(it.phoneme, it.start + startSeconds, it.end + startSeconds) }
            }
            parts += finalAudio
            offset += finalAudio.size
            sink?.invoke(pcm16(finalAudio))
        }

        val merged = concat(parts)
        val segments: List<SegmentAnchor>? =
            if (session.hasTimings && spoken.isNotEmpty()) {
                // Boundaries are the shifted timings, so they stay exact in the
                // final audio regardless of pause insertion (decisions #31).
                KokoroTimings.sentenceSegments(spoken, merged.size / SAMPLE_RATE.toDouble())
            } else {
                null
            }
        return SynthesisOutcome.Audio(pcm16(merged), SAMPLE_RATE, 1, segments)
    }

    /**
     * Process-scoped, never closed in production; kept for tests/benchmarks.
     */
    override fun close() {
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 24_000

        // reference create() defaults
        private const val SENTENCE_PAUSE = 0.25
        private const val CLAUSE_PAUSE = 0.1
        private const val DEFAULT_VOICE = "af_heart"

        /**
         * Voice family → espeak-ng phonemization language, per the v1.0 voice
         * pack (VOICES.md families). German and Korean are not served by this
         * pack at all, so the engine spec advertises only what voices exist.
         */
        val VOICE_LANGUAGES: Map<String, String> =
            mapOf(
                "af" to "en-us",
                "am" to "en-us",
                "bf" to "en-gb",
                "bm" to "en-gb",
                "ef" to "es",
                "em" to "es",
                "ff" to "fr-fr",
                "hf" to "hi",
                "hm" to "hi",
                "if" to "it",
                "im" to "it",
                "jf" to "ja",
                "jm" to "ja",
                "pf" to "pt-br",
                "pm" to "pt-br",
                "zf" to "cmn",
                "zm" to "cmn",
            )

        /**
         * Opens the engine on ready pack files. The graph's embedded vocab
         * wins over the packaged resource; espeak-ng may be located by system
         * loader or explicitly.
         */
        fun open(
            spec: EngineSpec,
            packs: List<TtsPack>,
            modelFile: File,
            voicesFile: File,
            phonemizer: Phonemizer = NormalizingPhonemizer(EspeakPhonemizer.load()),
            progress: (String) -> Unit = {},
            sessionFactory: (OrtSession.SessionOptions) -> Unit = {},
            pinnedWindowLength: Int? = null,
        ): KokoroEngine {
            require(modelFile.isFile) { "model pack file not ready: $modelFile" }
            require(voicesFile.isFile) { "voices pack file not ready: $voicesFile" }
            val session = OrtKokoroSession.open(modelFile, sessionFactory, pinnedWindowLength)
            progress("session")
            return try {
                val vocab = session.embeddedVocab.ifEmpty { KokoroVocabulary.resource() }
                progress("vocab")
                val voices = KokoroVoiceBank.load(voicesFile)
                progress("voices")
                KokoroEngine(
                    spec,
                    packs,
                    session,
                    voices,
                    KokoroTokenizer(vocab),
                    phonemizer,
                )
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }

        private fun concat(parts: List<FloatArray>): FloatArray {
            val total = parts.sumOf { it.size }
            val joined = FloatArray(total)
            var offset = 0
            for (part in parts) {
                part.copyInto(joined, offset)
                offset += part.size
            }
            return joined
        }
    }
}
