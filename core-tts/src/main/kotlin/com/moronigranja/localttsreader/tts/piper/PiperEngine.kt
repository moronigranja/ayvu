package com.moronigranja.localttsreader.tts.piper

import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.kokoro.EspeakPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.NormalizingPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.PhonemizeException
import com.moronigranja.localttsreader.tts.kokoro.Phonemizer
import com.moronigranja.localttsreader.tts.pcm16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Piper (rhasspy/piper-voices VITS) as a [TTSEngine] — the D4 small-tier
 * engine adopted after the quality gate passed (decisions #99 and its
 * 2026-09-12/13 addenda). The invocation is the proven spike one, direct ORT
 * over the stock VITS export (no sherpa): shared espeak-ng phonemization via
 * the existing [Phonemizer] seam (wrapped in [NormalizingPhonemizer] so the G1
 * rules apply) → IPA codepoints through the voice's `phoneme_id_map` (the
 * verified official piper framing, [PiperVoiceConfig.phonemeIds]) → one
 * inference → 16-bit little-endian PCM at the voice's sample rate (22050).
 *
 * One voice per instance: Piper has no speaker mixing — a voice IS a model,
 * so an instance is opened for exactly one of [PiperVoices] and a request for
 * any other voice fails typed instead of silently switching models. Multiple
 * voices = multiple instances (the pack registry tracks every voice's model +
 * config through [PiperPacks]).
 *
 * Read-along degradation (decisions #30b): the stock export exposes a single
 * audio output — no alignments, no word timestamps — so the outcome carries
 * `segments = null` (the same shape as the system voice) and read-along
 * degrades to passage level. A custom re-export could surface VITS alignments
 * later.
 *
 * Speed: [SynthesisRequest.speed] is expressed through the VITS length scale
 * (durations divide by speed); 1.0 stays the measured json values.
 *
 * Thread safety: phonemization is serialized inside the phonemizer; inference
 * is one call on a thread-safe ORT session. Synthesis is cancellable before
 * the inference starts — a running pass cannot be preempted.
 */
class PiperEngine internal constructor(
    override val spec: EngineSpec,
    override val packs: List<TtsPack>,
    /** The voice this instance serves (its model is [packs]' model). */
    val voice: String,
    private val config: PiperVoiceConfig,
    private val session: PiperSession,
    private val phonemizer: Phonemizer,
) : TTSEngine,
    AutoCloseable {
    override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
        withContext(Dispatchers.IO) {
            try {
                synthesizeCore(request, coroutineContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SynthesisOutcome.Failed(e.message ?: "synthesis failed")
            }
        }

    private suspend fun synthesizeCore(
        request: SynthesisRequest,
        context: CoroutineContext,
    ): SynthesisOutcome {
        if (request.text.isBlank()) return SynthesisOutcome.Failed("nothing to synthesize")

        val requested = request.voice ?: voice
        if (requested != voice) return SynthesisOutcome.Failed("unknown voice '$requested'")

        val phonemes =
            try {
                phonemizer.phonemize(request.text, config.espeakVoice)
            } catch (e: PhonemizeException) {
                return SynthesisOutcome.Failed(e.message ?: "phonemization failed")
            }
        if (phonemes.isEmpty()) return SynthesisOutcome.Failed("nothing to synthesize")

        // Newlines are not in the vocabulary, so collapse every whitespace run
        // into the single gap between words the id map covers (kokoro's rule;
        // decisions #99 recorded the same newline→space handling).
        val collapsed = phonemes.split(Regex("\\s+")).joinToString(" ")
        val phonemeIds = config.phonemeIds(collapsed)
        if (phonemeIds.size <= 3) return SynthesisOutcome.Failed("nothing to synthesize")

        context.ensureActive()
        val audio = session.infer(phonemeIds, config.scales(request.speed))
        if (audio.isEmpty()) return SynthesisOutcome.Failed("synthesis produced no audio")
        return SynthesisOutcome.Audio(
            pcm = pcm16(audio),
            sampleRateHz = config.sampleRateHz,
            channelCount = 1,
            segments = null, // recorded degradation: the stock export has no word timestamps (#30b)
        )
    }

    /**
     * Process-scoped, never closed in production; kept for tests/benchmarks.
     */
    override fun close() {
        session.close()
    }

    companion object {
        const val DEFAULT_VOICE: String = PiperVoices.LESSAC

        /**
         * Opens the engine on ready pack files for [voice]. Construction fails
         * fast on missing files — an engine never fabricates a fallback model.
         */
        fun open(
            spec: EngineSpec,
            packs: List<TtsPack>,
            voice: String,
            modelFile: File,
            configFile: File,
            phonemizer: Phonemizer = NormalizingPhonemizer(EspeakPhonemizer.load()),
            sessionFactory: (ai.onnxruntime.OrtSession.SessionOptions) -> Unit = {},
        ): PiperEngine {
            require(modelFile.isFile) { "model pack file not ready: $modelFile" }
            require(configFile.isFile) { "voice config file not ready: $configFile" }
            val session = OrtPiperSession.open(modelFile, sessionFactory)
            return try {
                PiperEngine(spec, packs, voice, PiperVoiceConfig.parse(configFile.readText()), session, phonemizer)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
        }
    }
}
