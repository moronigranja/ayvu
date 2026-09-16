package io.github.moronigranja.ayvu.tts.kokoro

/**
 * C2: one fixed, language-appropriate audition phrase per Kokoro voice
 * family — short (~1 second spoken) so a preview hears the voice without
 * holding the engine. The language follows [KokoroVoiceMetadata]'s prefix
 * families (the same map the roster table uses), so every shipped voice has
 * a phrase and an unknown name degrades to null (the audition surfaces a
 * typed failure, never a silent fallback).
 */
object VoicePreview {
    private val PHRASES: Map<String, String> =
        mapOf(
            "af" to "The quick brown fox jumps over the lazy dog.",
            "am" to "The quick brown fox jumps over the lazy dog.",
            "bf" to "The quick brown fox jumps over the lazy dog.",
            "bm" to "The quick brown fox jumps over the lazy dog.",
            "ef" to "El veloz zorro marrón salta sobre el perro perezoso.",
            "em" to "El veloz zorro marrón salta sobre el perro perezoso.",
            "ff" to "Le renard brun rapide saute par-dessus le chien paresseux.",
            "hf" to "तेज़ भूरी लोमड़ी आलसी कुत्ते के ऊपर कूदती है।",
            "hm" to "तेज़ भूरी लोमड़ी आलसी कुत्ते के ऊपर कूदती है।",
            "if" to "La rapida volpe marrone salta sopra il cane pigro.",
            "im" to "La rapida volpe marrone salta sopra il cane pigro.",
            "jf" to "素早い茶色の狐が怠けた犬を飛び越えます。",
            "jm" to "素早い茶色の狐が怠けた犬を飛び越えます。",
            "pf" to "A rápida raposa marrom salta sobre o cão preguiçoso.",
            "pm" to "A rápida raposa marrom salta sobre o cão preguiçoso.",
            "zf" to "敏捷的棕色狐狸跳过了懒狗。",
            "zm" to "敏捷的棕色狐狸跳过了懒狗。",
        )

    /** The fixed phrase for [voice] (by prefix family), or null when the
     * name is not a known Kokoro voice. */
    fun phraseFor(voice: String): String? = PHRASES[voice.substringBefore('_')]
}
