package io.github.moronigranja.ayvu.tts

import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.piper.PiperPacks

/**
 * The known engines (hard-facts engine table + decisions #21), metadata only.
 *
 * Pack descriptors are pinned from the actual artifacts — each SHA-256/URL
 * was produced by downloading the artifact once during the engine slice (T2),
 * never fabricated. The registry tracks download state per descriptor; the
 * settings UI renders the engine list and surfaces the download action.
 */
object DefaultEngines {
    val cosyVoice3: EngineSpec =
        EngineSpec(
            id = "cosyvoice3-0.5b",
            displayName = "Fun-CosyVoice3-0.5B",
            tier = EngineTier.FALLBACK, // gated: CPU RTF 14.7–17.5 on the S22 Ultra (decisions #21)
            languages = setOf("zh", "en", "fr", "es", "ja", "ko", "it", "ru", "de"), // hard-facts: 9 languages
        )

    val kokoro: EngineSpec =
        EngineSpec(
            id = "kokoro-82m",
            displayName = "Kokoro-82M",
            tier = EngineTier.PRIMARY, // v1 primary (decisions #21)
            // The languages the pinned v1.0 voice pack actually serves (T2): the
            // 54 voices cover en-US/en-GB, fr, es, it, pt-BR, ja, zh, hi — the
            // release pack ships no German or Korean voices.
            languages = setOf("en", "fr", "es", "it", "pt", "ja", "zh", "hi"),
        )

    /**
     * D4 small tier (decisions #99 + addenda): Piper adopted after the owner's
     * listening pass passed the quality gate (2026-09-13). PRIMARY is the
     * engine-class, not a fallback claim: Piper is a genuine open-weight
     * narration engine — measured realtime on the HiBreak (RTF 0.566–0.575 on
     * ORT-android 1.29) and quality-gated PASS — and covers German, which the
     * Kokoro v1.0 pack does not serve. It is never auto-selected: the
     * playback seam chooses engines explicitly (no auto-fallback).
     */
    val piper: EngineSpec =
        EngineSpec(
            id = "piper-v1",
            displayName = "Piper",
            tier = EngineTier.PRIMARY,
            // The languages the pinned voices serve (rhasspy/piper-voices
            // @ 1162a917): en_US-lessac-medium, de_DE-thorsten-high and the
            // 2026-09-13 pins es_ES-davefx-medium / it_IT-serena-medium /
            // pt_BR-faber-medium. German is Piper-only at v1 — Kokoro ships no
            // German voices; Korean (ko_KR-kss-medium) stays unpinned on its
            // CC-BY-NC license until the owner clears it.
            languages = setOf("en", "de", "es", "it", "pt"),
        )

    val descriptors: List<EngineDescriptor> =
        listOf(
            EngineDescriptor(kokoro, KokoroPacks.all),
            EngineDescriptor(piper, PiperPacks.all),
            // CosyVoice3 stays metadata-only until its pack is pinned (gated on the
            // DiT acceleration finding; decisions #21/#23); reproducibility
            // manifest: docs/cosyvoice3-pack.md.
            EngineDescriptor(cosyVoice3, emptyList()),
        )
}
