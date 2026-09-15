package com.moronigranja.localttsreader.tts.kokoro

/**
 * [Phonemizer] decorator: rewrites the spoken text via
 * [PronunciationNormalizer.forSpeech] before delegating. Keeps pronunciation
 * fixes affect-only-the-spoken-form — the engine, index, and oracle inputs
 * never see the rewrite.
 */
class NormalizingPhonemizer(
    private val delegate: Phonemizer,
) : Phonemizer {
    override fun phonemize(
        text: String,
        language: String,
    ): String = delegate.phonemize(PronunciationNormalizer.forSpeech(text), language)

    override fun supportedLanguages(): Set<String> = delegate.supportedLanguages()
}
