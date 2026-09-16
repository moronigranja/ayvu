package io.github.moronigranja.ayvu.tts.kokoro

/**
 * Text → IPA phoneme string, the phonemization half of the Kokoro pipeline
 * (reference: thewh1teagle/kokoro-onnx `SpeechPipeline`, phonemizer's espeak
 * backend).
 *
 * Contract (mirrors phonemizer.phonemize(text, lang, preserve_punctuation=True,
 * with_stress=True) as consumed by kokoro-onnx):
 * - output is IPA with stress marks, punctuation preserved in place,
 * - lines (''\n''-separated) are joined with ''\n'', blank lines dropped,
 * - [language] is an espeak-ng voice language ("en-us", "en-gb", "fr-fr",
 *   "es", "it", "pt-br", "ja", "cmn", "hi", ...) — what the engine maps its
 *   voice families to.
 *
 * espeak-ng holds process-global state: implementors MUST serialize calls
 * internally and be safe for concurrent use (inference stays concurrent).
 *
 * @throws PhonemizeException when the language is not available or the native
 * call fails — callers map it to a typed failure, never a silent fallback.
 */
interface Phonemizer {
    fun phonemize(
        text: String,
        language: String,
    ): String

    /** The voice languages this installation can phonemize, as [language] codes. */
    fun supportedLanguages(): Set<String>
}

class PhonemizeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
