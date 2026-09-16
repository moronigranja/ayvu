package io.github.moronigranja.ayvu.tts.kokoro

/**
 * Static voice metadata for the kokoro v1.0 voices pack (C1.3): the 54 names
 * are presentation data — the roster lives only inside the downloaded
 * `voices-v1.0.bin` ([KokoroVoiceBank]), so "choose a voice before download"
 * needs a static table. The roster below was read from the pinned pack
 * artifact itself (the pack is the contract; metadata is presentation):
 * `unzip -l voices-v1.0.bin` yields exactly these 54 `.npy` members, and this
 * table is cross-checked against a loaded pack in tests.
 *
 * Prefix families → language/gender, mirroring [KokoroEngine.VOICE_LANGUAGES]
 * (the espeak-ng phonemization map): af/am en-US, bf/bm en-GB, ef/em es,
 * ff/fm fr, if/im it, pf/pm pt-BR, jf/jm ja, zf/zm zh, hf/hm hi. v1.0 has no
 * `fm_` voices — only `ff_siwis` — so the table carries the families the pack
 * actually ships.
 */
data class KokoroVoiceMeta(
    val name: String,
    val language: String,
    val gender: String,
    /** Presentation label — capitalized voice name plus the upstream trait
     * emoji where one exists ("Heart ❤️", "Bella 🔥", "Nicole 🎧"). */
    val displayName: String = "",
    /** Upstream overall data-grade (hexgrad/Kokoro-82M VOICES.md) — A…F+.
     * Null for the ungraded es/pt families (thin training data, no grade). */
    val grade: String? = null,
)

object KokoroVoiceMetadata {
    /** Voice prefix family → (display language, gender). Declared FIRST:
     * object init runs top-down and [all]'s init calls [meta], which reads
     * this map — a later declaration would be null at that point. */
    private val FAMILY_TO_LANGUAGE: Map<String, Pair<String, String>> =
        mapOf(
            "af" to ("English (US)" to "Female"),
            "am" to ("English (US)" to "Male"),
            "bf" to ("English (UK)" to "Female"),
            "bm" to ("English (UK)" to "Male"),
            "ef" to ("Spanish" to "Female"),
            "em" to ("Spanish" to "Male"),
            "ff" to ("French" to "Female"),
            "hf" to ("Hindi" to "Female"),
            "hm" to ("Hindi" to "Male"),
            "if" to ("Italian" to "Female"),
            "im" to ("Italian" to "Male"),
            "jf" to ("Japanese" to "Female"),
            "jm" to ("Japanese" to "Male"),
            "pf" to ("Portuguese (Brazil)" to "Female"),
            "pm" to ("Portuguese (Brazil)" to "Male"),
            "zf" to ("Chinese" to "Female"),
            "zm" to ("Chinese" to "Male"),
        )

    /** Per-voice presentation extras, pinned from hexgrad/Kokoro-82M's
     * VOICES.md (HF, 2026-09): the personal trait emoji (the voice's
     * nickname glyph — ❤️ Heart, 🔥 Bella, 🎧 Nicole) and the overall
     * data-grade the upstream table assigns (A…F+; the es/pt families ship
     * no grade). Pure presentation — the pack roster remains the contract,
     * cross-checked by tests. Declared before [all]: `all`'s init calls
     * [meta], which reads this map. */
    private val EXTRAS: Map<String, Pair<String, String?>> =
        mapOf(
            "af_heart" to ("❤️" to "A"),
            "af_bella" to ("🔥" to "A-"),
            "af_nicole" to ("🎧" to "B-"),
            "af_alloy" to ("" to "C"),
            "af_aoede" to ("" to "C+"),
            "af_jessica" to ("" to "D"),
            "af_kore" to ("" to "C+"),
            "af_nova" to ("" to "C"),
            "af_river" to ("" to "D"),
            "af_sarah" to ("" to "C+"),
            "af_sky" to ("" to "C-"),
            "am_adam" to ("" to "F+"),
            "am_echo" to ("" to "D"),
            "am_eric" to ("" to "D"),
            "am_fenrir" to ("" to "C+"),
            "am_liam" to ("" to "D"),
            "am_michael" to ("" to "C+"),
            "am_onyx" to ("" to "D"),
            "am_puck" to ("" to "C+"),
            "am_santa" to ("" to "D-"),
            "bf_alice" to ("" to "D"),
            "bf_emma" to ("" to "B-"),
            "bf_isabella" to ("" to "C"),
            "bf_lily" to ("" to "D"),
            "bm_daniel" to ("" to "D"),
            "bm_fable" to ("" to "C"),
            "bm_george" to ("" to "C"),
            "bm_lewis" to ("" to "D+"),
            "ff_siwis" to ("" to "B-"),
            "jf_alpha" to ("" to "C+"),
            "jf_gongitsune" to ("" to "C"),
            "jf_nezumi" to ("" to "C-"),
            "jf_tebukuro" to ("" to "C"),
            "jm_kumo" to ("" to "C-"),
            "hf_alpha" to ("" to "C"),
            "hf_beta" to ("" to "C"),
            "hm_omega" to ("" to "C"),
            "hm_psi" to ("" to "C"),
            "if_sara" to ("" to "C"),
            "im_nicola" to ("" to "C"),
            "zf_xiaobei" to ("" to "D"),
            "zf_xiaoni" to ("" to "D"),
            "zf_xiaoxiao" to ("" to "D"),
            "zf_xiaoyi" to ("" to "D"),
            "zm_yunjian" to ("" to "D"),
            "zm_yunxi" to ("" to "D"),
            "zm_yunxia" to ("" to "D"),
            "zm_yunyang" to ("" to "D"),
            // The es/pt families: upstream ships no data-grade for them.
            "ef_dora" to ("" to null),
            "em_alex" to ("" to null),
            "em_santa" to ("" to null),
            "pf_dora" to ("" to null),
            "pm_alex" to ("" to null),
            "pm_santa" to ("" to null),
        )

    /** All 54 v1.0 voices, language-grouped (declaration order). */
    val all: List<KokoroVoiceMeta> =
        listOf(
            // en-US female
            meta("af_alloy"),
            meta("af_aoede"),
            meta("af_bella"),
            meta("af_heart"),
            meta("af_jessica"),
            meta("af_kore"),
            meta("af_nicole"),
            meta("af_nova"),
            meta("af_river"),
            meta("af_sarah"),
            meta("af_sky"),
            // en-US male
            meta("am_adam"),
            meta("am_echo"),
            meta("am_eric"),
            meta("am_fenrir"),
            meta("am_liam"),
            meta("am_michael"),
            meta("am_onyx"),
            meta("am_puck"),
            meta("am_santa"),
            // en-GB female
            meta("bf_alice"),
            meta("bf_emma"),
            meta("bf_isabella"),
            meta("bf_lily"),
            // en-GB male
            meta("bm_daniel"),
            meta("bm_fable"),
            meta("bm_george"),
            meta("bm_lewis"),
            // es female
            meta("ef_dora"),
            // es male
            meta("em_alex"),
            meta("em_santa"),
            // fr female
            meta("ff_siwis"),
            // hi female
            meta("hf_alpha"),
            meta("hf_beta"),
            // hi male
            meta("hm_omega"),
            meta("hm_psi"),
            // it female
            meta("if_sara"),
            // it male
            meta("im_nicola"),
            // ja female
            meta("jf_alpha"),
            meta("jf_gongitsune"),
            meta("jf_nezumi"),
            meta("jf_tebukuro"),
            // ja male
            meta("jm_kumo"),
            // pt-BR female
            meta("pf_dora"),
            // pt-BR male
            meta("pm_alex"),
            meta("pm_santa"),
            // zh female
            meta("zf_xiaobei"),
            meta("zf_xiaoni"),
            meta("zf_xiaoxiao"),
            meta("zf_xiaoyi"),
            // zh male
            meta("zm_yunjian"),
            meta("zm_yunxi"),
            meta("zm_yunxia"),
            meta("zm_yunyang"),
        ).also {
            require(it.size == 54) { "kokoro v1.0 ships 54 voices, table has ${it.size}" }
            require(it.map(KokoroVoiceMeta::name).distinct().size == it.size) {
                "voice names must be unique in the metadata table"
            }
        }

    /** Names the metadata table carries but the pack roster lacks — surfaced
     * in tests (fixture pack), never required at app runtime. */
    fun missingFrom(packNames: Set<String>): Set<String> = all.map(KokoroVoiceMeta::name).toSet() - packNames

    private fun meta(name: String): KokoroVoiceMeta {
        val family = name.substringBefore('_')
        val (language, gender) =
            FAMILY_TO_LANGUAGE[family]
                ?: error("voice $name has no known prefix family")
        val (emoji, grade) =
            EXTRAS[name]
                ?: error("voice $name has no presentation extras pinned")
        val pretty = name.substringAfter('_').replaceFirstChar { it.uppercase() }
        val displayName = if (emoji.isEmpty()) pretty else "$pretty $emoji"
        return KokoroVoiceMeta(name, language, gender, displayName, grade)
    }
}
