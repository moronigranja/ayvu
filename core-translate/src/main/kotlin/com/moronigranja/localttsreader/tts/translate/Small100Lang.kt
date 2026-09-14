package com.moronigranja.localttsreader.tts.translate

/**
 * SMaLL-100 language-code surface: the FAIRSEQ m2m100 code list + the
 * app-code → model-code mapping (decisions #114 conditioning contract).
 *
 * The M2M/SMaLL family conditions on the TARGET language by PREPENDING its
 * lang token (`__<code>__`, id = vocab size + order index) to the source and
 * appending `</s>`; the decoder starts on `</s>` with NO forced bos. The code
 * list and token rule are pinned from `tokenization_small100.py` at
 * `alirezamsh/small100` @ `8ab680e26a596d2e3d2d2d17ae0f68df1037328c` (the
 * pack's manifest revision): `SMALL100Tokenizer` maps `lang_code_to_token =
 * "__{code}__"` over exactly this ordered list, ids assigned in order after
 * the text vocab.
 */
object Small100Lang {
    /**
     * The FAIRSEQ_LANGUAGE_CODES["m2m100"] list of the pinned tokenizer, in
     * order — index i gets id `vocabSize + i` (vocabSize = 128004 for the
     * pinned model, so `__af__` = 128004 … `__zu__` = 128103).
     */
    val M2M_CODES: List<String> =
        listOf(
            "af",
            "am",
            "ar",
            "ast",
            "az",
            "ba",
            "be",
            "bg",
            "bn",
            "br",
            "bs",
            "ca",
            "ceb",
            "cs",
            "cy",
            "da",
            "de",
            "el",
            "en",
            "es",
            "et",
            "fa",
            "ff",
            "fi",
            "fr",
            "fy",
            "ga",
            "gd",
            "gl",
            "gu",
            "ha",
            "he",
            "hi",
            "hr",
            "ht",
            "hu",
            "hy",
            "id",
            "ig",
            "ilo",
            "is",
            "it",
            "ja",
            "jv",
            "ka",
            "kk",
            "km",
            "kn",
            "ko",
            "lb",
            "lg",
            "ln",
            "lo",
            "lt",
            "lv",
            "mg",
            "mk",
            "ml",
            "mn",
            "mr",
            "ms",
            "my",
            "ne",
            "nl",
            "no",
            "ns",
            "oc",
            "or",
            "pa",
            "pl",
            "ps",
            "pt",
            "ro",
            "ru",
            "sd",
            "si",
            "sk",
            "sl",
            "so",
            "sq",
            "sr",
            "ss",
            "su",
            "sv",
            "sw",
            "ta",
            "th",
            "tl",
            "tn",
            "tr",
            "uk",
            "ur",
            "uz",
            "vi",
            "wo",
            "xh",
            "yi",
            "yo",
            "zh",
            "zu",
        )

    /** The lang token string for a model code (`pt` → `__pt__`). */
    fun langToken(code: String): String = "__${code}__"

    /**
     * App language code (BCP-47-ish, as stored in book metadata / engine
     * voice catalogs) → SMaLL-100 code: `pt-BR` → `pt`, `zh` → `zh`,
     * everything else lowercase identity, anything unmapped → null
     * (translation unavailable — never guessed).
     */
    fun toSmall100(appCode: String): String? {
        val code = appCode.lowercase()
        if (code == "pt-br" || code == "pt_br") return "pt"
        return if (code in M2M_CODES) code else null
    }
}
