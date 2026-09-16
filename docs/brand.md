# Brand — Ayvu

The app's public identity: name, origin story, tagline, icon, and the poem plan.
Decisions on it land in decisions.md (#43); this doc holds the living material.

## Name (locked 2026-08-26, owner)

**Ayvu** — Guaraní for *speech / word / language*. *Ayvu Rapyta* ("the foundation
of human speech") is the cosmogonic poem of the Mbyá Guaraní, recorded by Curt
Nimuendajú in 1914 (public domain). The thesis matches the name: the app turns
written books into speech.

- Launcher label wired (`app_name = Ayvu`, verified building); `applicationId`
  and Kotlin `namespace` are both `io.github.moronigranja.ayvu`
  (decisions #128/#169).
- **Origin story:** a Brazilian dev naming the app for the speech of the land —
  the Mbyá Guaraní live in southern Brazil. The name *is* the brand.
- **Pronunciation:** Guaraní `y` is a close central vowel (IPA /ɨ/). Practical
  readings: en "AY-voo", pt "a-í-vu". The ambiguity is inherent to borrowing the
  word — accepted, not a defect.
- **Acknowledgment:** the Guaraní source of the name goes in the app's about text
  and the store listing (courtesy for a borrowed indigenous word).

### Why not the others (all Play-verified 2026-08-26)

| Name | Verdict |
|---|---|
| Narra | **Taken** — AI Video Narrator, Narra Messenger, NarraNarra, plus an AI book-translation reader the owner found |
| Relato | Clean — runner-up; descriptive (pt/es "tale"), not branded |
| Fala | Fala Voice AI + Fala Mike (BR education) occupy it |
| Voz | "voice" *is* the TTS category; weak as a mark |
| Conto | Banking — "conto" = account in it/pt |
| Escuta | Hearing-amplifier category noise; hard for en speakers |
| Prosa | BR English-learning app, a marketplace, two writing apps |
| Leitor | "reader" is the genre |
| Crônica | Several Crónica apps + Chronic-* illness-app noise |
| Legato | Music apps (Practice Journal, School of Music) |
| Aloud | The read-aloud genre + "Aloud AI" (PDF-to-audiobook) |

Play status at lock: no exact-name app; **"Guarani Ayvu"** (PopulisTech, 3.6★)
exists in the same cultural space — small, not a competitor, but proof the name
is already associated with Guaraní content; stay in that space with respect.

## Tagline (pending decision; README carries the en primary since 2026-08-27)

- en — *"Ayvu — your books, in voice."* (in README) / *"Ayvu — the foundation of speech."*
- pt — *"Ayvu. Da palavra, voz."* / *"Ayvu — seus livros, em voz alta."*

Store title for discovery: "Ayvu — Offline Audiobook Reader" (keywords:
audiobook / reader / epub / offline).

## Icon — PICKED: leaf + sound-arcs (owner's trace, 2026-08-27)

Owner re-traced the concept as a bespoke SVG and it won on craft — organic
S-midrib, curled stem, natural arc ends. **Ink-palette mark:** three ¾-arcs
open on the right — teal `#1FA8C5` outer, two amber `#E8A33D` inner — with the
amber almond leaf (S-midrib, curled stem) sealing the opening, tip up-right,
and the amber dot as traced (off-center left of the arc system — the owner
reviewed a re-centered variant and preferred the as-traced dot), all on ink
`#1B2430`. Dropped: the wordmark (dies below 64px — the name lives in the
label / store listing).

Production (delivered, verified via docker-lane `:app:assembleDebug` green +
`aapt dump badging`):
- Adaptive icon (`mipmap-anydpi-v26/ic_launcher{,_round}.xml`): vector
  background (ink rect) + `drawable/ic_launcher_foreground.xml` — glyph scaled
  ×0.50, mark measures 60.1% of the 108dp layer (66dp safe circle holds it with
  margin) — + Android-13 `drawable/ic_launcher_monochrome.xml` (alpha steps:
  leaf+dot 1.0, inner arcs 0.67, outer arc 0.43). `minSdk = 26` ⇒ no legacy
  PNG densities needed.
- Geometry as traced, untouched; the arc's extra sliver gap at 285–290° kept —
  reads organically.
- Canonical source: `docs/assets/ayvu-icon-master.svg` (== production
  geometry); res vectors are generated from it — regenerate, don't hand-edit.
- Store listing still needs its own 512px icon render (Play requirement).

### Draft history (superseded by the pick above)

Timeline — all concepts below are pre-pick drafts:

- Open-book family (2026-08-26): A/E/C concepts; **C1** (five-bar gutter
  equalizer, common baseline, symmetric envelope) was the favorite; C2 (bars on
  the pages) flagged for spine crowding.
- Owner asked for **book-free** marks. D-series:
  - **D1** — folded document + over-ear headphones (owner's reference);
    production-hardened: filled cups (outline washed out at 48px), thicker text
    lines, fold kept clear of the headband. Safe/classic.
  - **D2** — flowering head (speech growing from the crown). **DROPPED:**
    inspection read it as the Headspace/wellness cliché, the head read as a
    pawn, bars detached from the crown.
  - **D3** — speech bloom: a bloom whose petals are audio bars, growing from the
    land. v1 fan read as a **hand** after a taper pass (thumb + fingers);
    **v2** = symmetric 7-bar waveform envelope (8/16/24/30/24/16/8) on a
    hill/stem/leaves — inspection: "plant/flower with a waveform-as-bloom
    crown, bilaterally symmetric, ship-ready." The meaningful book-free mark.
  - **D4** — text→voice (Gemini's "liquid wave"): three cream text-line bars
    whose rounded right tips flow into amber waves — the product loop itself
    (import text → hear voice). Zero category collision, mono-friendly,
    distinct. NOT landed: four blind-vector iterations (v1 two separate
    motifs · v2 hard seam · v3 waves hanging low · v4 rounded tips + tangent
    waves — inspection aborted, unverified). The morph seam is the crux;
    needs a designer's hand or careful geometry.
  - **Radar/rings** (owner's second reference) — concentric solid/dashed rings
    on a cream-peach gradient: rated 7/10 standalone, 3/10 for this brand
    (reads location/weather radar; no story; gradient dies in adaptive +
    monochrome). Rejected.
- **Gemini directions evaluated (2026-08-26):** vocal tree ≈ D3 (independent
  convergence); wind/breath-of-life gust → beautiful, line-art washes out at
  48px — better as splash/brand art than launcher mark; macaw/toucan beak →
  skip (language-learning-app collision, palette break); synesthesia
  brushstroke → skip (monochrome killer, reads "art app"); woven-thread
  indigenous pattern → skip for launcher (noise at 24dp, cultural-pattern
  care), good future in-app motif.
- Palette: ink `#1B2430`, paper `#F5EFE0`, amber `#E8A33D`; teal `#1FA8C5`
  joins as the icon's outer-arc accent.

## Ayvu Rapyta content plan (idea)

Pre-load opening cantos of the poem as a first-run sample book and/or store
screenshots showing it read aloud. Rules: **public-domain source only**
(Nimuendajú's 1914 transcription) — NOT the SciELO 2025 translation
(copyrighted). Bundled sample content is fine (decision #7 bans bundled
*engine assets*, not content) — confirm against the "classics bundle
out-of-scope" note before picking the slice.

## Pending

Final tagline · INPI/trademark pass · store-listing copy (incl. the 512px
icon render) · about-text acknowledgment.