package com.moronigranja.localttsreader.tts.kokoro

/**
 * [Phonemizer] decorator: rewrites the spoken text via
 * [PronunciationNormalizer.forSpeech] before delegating. Keeps pronunciation
 * fixes affect-only-the-spoken-form — the engine, index, and oracle inputs
 * never see the rewrite. The language is forwarded to the normalizer too: G1's
 * rules are language-scoped (`Prof.` expands differently in es/fr/it/pt).
 */
class NormalizingPhonemizer(
    private val delegate: Phonemizer,
) : Phonemizer {
    override fun phonemize(
        text: String,
        language: String,
    ): String = delegate.phonemize(PronunciationNormalizer.forSpeech(text, language), language)

    override fun supportedLanguages(): Set<String> = delegate.supportedLanguages()
}
