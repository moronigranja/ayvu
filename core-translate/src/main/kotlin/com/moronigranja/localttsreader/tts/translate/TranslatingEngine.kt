package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack

/**
 * Read-in-language decorator (per-book, decisions #114/#101): everything the
 * delegate does except synthesis — [spec] and [packs] stay the delegate's, so
 * engine selection and the pack registry are completely unaffected — while
 * [synthesize]/[synthesizeStreaming] translate [SynthesisRequest.text] into
 * [targetLang] and hand the delegate the result under [targetVoice].
 *
 * Degrade contract (decisions #29, promoted #101): ANY translation failure
 * (exception, empty result, unset voice/lang) falls through to the delegate
 * with the ORIGINAL text and ORIGINAL voice — a wrong-voice pronunciation of
 * untranslated text is the bug mode this kills. Only a successful translation
 * swaps the voice. Blank text passes through untouched.
 *
 * Segment anchors derive from the translated audio; the reader text,
 * bookmarks and matching stay original-language — the highlight drift is
 * recorded degradation (#101), not a defect.
 */
class TranslatingEngine(
    /** The engine serving the ORIGINAL voice — every degrade path lands here. */
    private val delegate: TTSEngine,
    /**
     * The engine serving [targetVoice]. Distinct from [delegate] for
     * one-voice-per-instance engines (Piper): the delegate is opened for the
     * book's original voice and fails typed on any other — routing the
     * translated render to it degraded every passage to the original
     * English audio AND cached it under the x<lang> key (S22 2026-09-14).
     */
    private val targetEngine: TTSEngine,
    private val translate: suspend (String) -> String?,
    private val targetVoice: String,
    /** The in-force target lang — the cache-key dimension ([translateLangInUse]):
     * keys must name the language actually rendered, never the raw setting. */
    val targetLang: String,
) : TTSEngine by delegate {
    override val spec: EngineSpec = delegate.spec

    override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
        val orchestrated = orchestrate(request)
        if (orchestrated === request) return delegate.synthesize(request)
        val outcome = targetEngine.synthesize(orchestrated)
        // The translated render itself failed — the #29 contract covers the
        // WHOLE translated attempt: retry with the original text and voice
        // so playback never dies.
        return if (outcome is SynthesisOutcome.Failed) delegate.synthesize(request) else outcome
    }

    override suspend fun synthesizeStreaming(
        request: SynthesisRequest,
        onWindow: suspend (ByteArray) -> Unit,
    ): SynthesisOutcome {
        val orchestrated = orchestrate(request)
        if (orchestrated === request) return delegate.synthesizeStreaming(request, onWindow)
        var emitted = false
        val outcome =
            targetEngine.synthesizeStreaming(orchestrated) { bytes ->
                emitted = true
                onWindow(bytes)
            }
        // Mid-stream failure after windows were published cannot be retried
        // (the consumer already holds translated bytes) — surface as-is; a
        // failure before the first window degrades to the original render.
        return if (outcome is SynthesisOutcome.Failed && !emitted) {
            delegate.synthesizeStreaming(request, onWindow)
        } else {
            outcome
        }
    }

    private suspend fun orchestrate(request: SynthesisRequest): SynthesisRequest {
        if (request.text.isBlank()) return request
        val translated =
            try {
                translate(request.text)
            } catch (t: Throwable) {
                null
            } ?: return request
        if (translated.isBlank()) return request
        return request.copy(text = translated, voice = targetVoice)
    }
}
