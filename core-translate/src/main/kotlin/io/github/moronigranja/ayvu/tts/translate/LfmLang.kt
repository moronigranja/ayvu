package io.github.moronigranja.ayvu.tts.translate

/**
 * App language code → the language NAME the LFM2.5-1.2B translator is prompted
 * with (decisions #161/#162): the read-in-language conditioning surface that
 * replaces `Small100Lang`'s M2M code mapping.
 *
 * The model is prompted in English ("Translate to Brazilian Portuguese, reply
 * only with the translation:\n\n…" — the measured gate's shape, see
 * [io.github.moronigranja.ayvu.llm.LlamaTranslator.userMessage]), so the
 * mapping is a name table, not a token rule.
 *
 * [APP_CODES] covers every language the voice catalog can voice — the same
 * trade the retired SMaLL-100 port made: `pt-BR` is the gate-measured pair
 * (chrF 67.37 on the S22); `it`/`hi` are not in the model's advertised
 * language list (`en/ar/zh/fr/de/ja/ko/es`) and ride generalization. An
 * unmapped code is never guessed — translation reports unavailable instead
 * ([TranslateAvailability]).
 *
 * Matching normalizes case and `-`/`_` (like [TranslateLanguages]): `pt_br`
 * resolves the same prompt name as `pt-BR`. A bare `pt` deliberately does NOT
 * resolve — the picker only ever offers the catalog's `pt-BR`, and the
 * untested bare code must not silently translate as European Portuguese.
 */
object LfmLang {
    /** The canonical app codes the read-in picker can offer (catalog order is
     * applied by [TranslateLanguages.codes]). */
    val APP_CODES: List<String> = listOf("en", "es", "fr", "de", "it", "ja", "hi", "zh", "pt-BR")

    private val PROMPT_NAMES: Map<String, String> =
        mapOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "ja" to "Japanese",
            "hi" to "Hindi",
            "zh" to "Chinese",
            // The measured gate's verbatim target string (lfm12_gate.py:9).
            "pt-br" to "Brazilian Portuguese",
        )

    /**
     * The prompt language name for an app code (`pt-BR` → "Brazilian
     * Portuguese"), or null when the target is not supported — never guessed.
     */
    fun toPromptLanguage(appCode: String): String? = PROMPT_NAMES[appCode.lowercase().replace('_', '-')]
}
