package io.github.moronigranja.ayvu.tts.translate

import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack

/**
 * One read-in-language engine option (decisions #114/#161/#162, second option
 * #182): everything that is specific to a translate model, in one place — the
 * pack that carries its GGUF, where that GGUF is staged, and the id that names
 * it in every cache key.
 *
 * [id] is the translator IDENTITY: it is the `t<id>` segment of the pregen
 * cache paths (core-player's `PregenKey`) and the translator column of the
 * stored-translation rows (`TranslationStore`), so two engines can never serve
 * each other's audio or text. The value must match a constant in core-player's
 * `PregenKey` — the two modules are siblings and cannot reference each other,
 * so the pairing is asserted in `TranslateEngineVocabularyTest`
 * (feature-player, which sees both).
 *
 * The registry shapes ([spec], [pack]) follow the TTS packs' convention:
 * `spec.id == pack.engineId`, and the engine is a registry-only pseudo-engine
 * — never selectable as a TTS engine (the selector's switch stays closed on
 * every one of these ids).
 */
data class TranslateEngine(
    /** Translator identity: the `t<id>` cache segment + the store's translator column. */
    val id: String,
    /** Registry-layout spec (`id == pack.engineId`); never a selectable TTS engine. */
    val spec: EngineSpec,
    /** The downloadable GGUF bundle. */
    val pack: TtsPack,
    /** Staging root under the app files dir (`files/<bundleDirName>`). */
    val bundleDirName: String,
    /** The GGUF's file name inside the staged bundle (the upstream name, kept verbatim). */
    val modelFileName: String,
    /** Short picker label ("Faster" / "Better quality"). */
    val label: String,
    /** One line for the picker row: what the choice costs and buys. */
    val summary: String,
) {
    /** Human download-size label for the picker ("~731 MB" / "~1.7 GB"),
     * derived from [pack] so the copy can never drift from the descriptor.
     * Locale-fixed: the app's copy is English and a comma decimal separator
     * would read as a thousands separator. */
    val sizeLabel: String
        get() =
            if (pack.sizeBytes < 1_000_000_000L) {
                "~${pack.sizeBytes / 1_000_000} MB"
            } else {
                "~${String.format(java.util.Locale.US, "%.1f", pack.sizeBytes / 1_000_000_000.0)} GB"
            }
}

/**
 * The read-in-language engine registry: the shipped LFM2.5-1.2B pack (the
 * default, decisions #161/#162) and the LFM2.5-2.6B-Base option (decisions
 * #182 — measured on the S22 at chrF 70.44, +3.1 over the shipped model, for
 * ~1.5x the per-sentence time and a 1.67 GB pack).
 *
 * Both bundles are hosted on this repo's releases (same convention as the
 * espeak-ng zip and the shipped translate pack), STORED — a GGUF compresses to
 * nothing — with the model's licence text inside the archive.
 *
 * Artifacts:
 * - `translate-lfm12b-q4-v1.zip` (730,895,330 B, sha256 `a701827a…`) carrying
 *   `LFM2.5-1.2B-Instruct-Q4_K_M.gguf` (730,895,168 B, sha256 `b1b3de11…`) from
 *   `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` @ `6767265158422fb8a19c62ceb45f16f05363615b`
 *   — byte-identical to the artifact measured on the S22 (chrF 67.37, 22.3 tok/s,
 *   2.7 s/passage; decisions #161).
 * - `translate-lfm26b-base-q4-v1.zip` (1,674,466,090 B, sha256 `e299b921…`, one
 *   STORED entry per file: the GGUF, `LICENSE`, `SOURCE-OFFER.txt`) carrying
 *   `LFM2.5-2.6B-Base.Q4_K_M.gguf` (1,674,454,080 B, sha256 `bff5a730…`) from
 *   `mradermacher/LFM2.5-2.6B-Base-GGUF` (quant of `LiquidAI/LFM2.5-2.6B-Base`) —
 *   the artifact measured on the S22 (chrF 70.44 idle and under playback,
 *   4.95/7.79 s per sentence; decisions #181/#182).
 *
 * Licences: both models are LFM Open License v1.0 (`general.license.name =
 * lfm1.0` in the GGUF), which is what the release archives carry alongside the
 * model; the terms (including the revenue threshold) apply to the model, not to
 * Ayvu's own GPL-3.0 code.
 */
object TranslatePacks {
    /** The shipped default: LFM2.5-1.2B-Instruct Q4_K_M (decisions #161/#162). */
    val shipped =
        TranslateEngine(
            id = "lfm12b",
            spec =
                EngineSpec(
                    id = "translate-lfm12b",
                    displayName = "LFM2.5-1.2B (read-in-language)",
                    tier = EngineTier.FALLBACK,
                    languages = LfmLang.APP_CODES.toSet(),
                ),
            pack =
                TtsPack(
                    id = "translate-lfm12b-q4-v1",
                    engineId = "translate-lfm12b",
                    kind = PackKind.MODEL,
                    displayName = "LFM2.5-1.2B translate model (Q4_K_M)",
                    description =
                        "LFM2.5-1.2B-Instruct (LFM Open License v1.0) Q4_K_M GGUF, ~730 MB. " +
                            "The shipped read-in-language engine (decisions #161).",
                    url = "https://github.com/moronigranja/ayvu/releases/download/translate-lfm12b-v1/translate-lfm12b-q4-v1.zip",
                    sha256Hex = "a701827af5f5b40687d1b7e6d90e475d6ad88d0a912361a84d61370b3ba9442f",
                    sizeBytes = 730_895_330,
                    version = "1",
                ),
            bundleDirName = "translate-lfm",
            modelFileName = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            label = "Faster",
            summary = "~730 MB. Translates in about a third of the time of the larger model.",
        )

    /**
     * The better-reading option: LFM2.5-2.6B-Base Q4_K_M (decisions #182).
     * Driven through the model's own chat template — the app renders every
     * prompt through the GGUF's template, which is the form that was measured.
     */
    val better =
        TranslateEngine(
            id = "lfm26b",
            spec =
                EngineSpec(
                    id = "translate-lfm26b",
                    displayName = "LFM2.5-2.6B-Base (read-in-language)",
                    tier = EngineTier.FALLBACK,
                    languages = LfmLang.APP_CODES.toSet(),
                ),
            pack =
                TtsPack(
                    id = "translate-lfm26b-base-q4-v1",
                    engineId = "translate-lfm26b",
                    kind = PackKind.MODEL,
                    displayName = "LFM2.5-2.6B-Base translate model (Q4_K_M)",
                    description =
                        "LFM2.5-2.6B-Base (LFM Open License v1.0) Q4_K_M GGUF, ~1.7 GB. " +
                            "Better-reading read-in-language engine (decisions #182).",
                    url = "https://github.com/moronigranja/ayvu/releases/download/translate-lfm26b-base-v1/translate-lfm26b-base-q4-v1.zip",
                    sha256Hex = "e299b921f2eacc5ca7e3aede5c220fb9a7bea73272ce549ae5023ea7a48f52f4",
                    sizeBytes = 1_674_466_090,
                    version = "1",
                ),
            bundleDirName = "translate-lfm26b",
            modelFileName = "LFM2.5-2.6B-Base.Q4_K_M.gguf",
            label = "Better quality",
            summary = "~1.7 GB. Reads better on real prose (measured chrF 70.44 vs 67.37) and takes about 1.5x the time.",
        )

    /** Every option, shipped first (the picker's order and the tie-break). */
    val all: List<TranslateEngine> = listOf(shipped, better)

    /** The engine an untouched install (or an unrecognized stored value) uses. */
    const val DEFAULT_ID = "lfm12b"

    /** Every option's pack, for the pack registry (one descriptor per engine). */
    val packs: List<TtsPack> = all.map { it.pack }

    /** Registry-layout engine IDs — the read-in-language packs' identity in the
     * pack registry and the Speech subscreen's row filter. */
    val engineIds: Set<String> = all.map { it.spec.id }.toSet()

    /** The option with [id], or the default when it is absent/unknown — a
     * stored id from a removed build can never break playback. */
    fun byId(id: String?): TranslateEngine = all.firstOrNull { it.id == id } ?: shipped

    /** The option owning [packId], or null (a non-translate pack id). */
    fun byPackId(packId: String): TranslateEngine? = all.firstOrNull { it.pack.id == packId }
}
