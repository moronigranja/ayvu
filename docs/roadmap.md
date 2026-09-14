# Roadmap

Forward sequencing for Ayvu. Shipped work is reference-only below; the open
items are the active queue. Implementation history belongs in
[decisions.md](decisions.md); candidate features remain in [ideas.md](ideas.md)
until they are promoted here.

## Current state

v0.1.1 is release-ready but **not published**. The signed-APK pipeline, the on-device
sanity pass on the signed build and `docs/release-notes-0.1.1.md` are all done
(decisions #126, #128); the one remaining step is publishing the GitHub release — no
`v0.1.1` tag exists on the remote (see "Release readiness" below). The v1 capability
spine is complete and device-verified: import → index → local TTS playback with
read-along → share-and-resume, plus settings, OCR, offline pre-generation, storage
controls, backup & restore, and the app-wide player card. The current module and test
snapshot lives in the [README](../README.md#status).

Queue order (dependency-first): the **owner's G0 listening pass** → ~~D1~~ **done,
device-verified** (2026-09-13, #155) → **D7** cross-app performance spike
(measurement-only, decisions #148) → ~~D4~~ **adoption landed** (PiperEngine,
#154/#155 — K2 unblocked) → **K5** per-book voice plus the settings-surface defect →
**Phase H** stats → **D5** high-end engine choice with the ORT int4 reference. G1's rule
set and D5 are gated on G0; H is independent, so its position
is preference rather than dependency. This order, the release-state correction and the
D6 closure are recorded in decisions #145. Open defects and their acceptance criteria are
authoritative in [open-bugs.md](open-bugs.md).

## Planning rules

- Correctness work precedes features that build on the affected contract.
- Room is durable truth; indexes and audio caches are derived, recoverable state.
- Performance work starts with a measurement gate on physical devices; no delegate,
  quantization, or engine path ships because it is plausible on paper.
- A roadmap item is complete only after its observable acceptance scenario is run.
- No date or total-duration forecast is maintained while the stabilization scope is
  still changing.

## Shipped — reference only

The v1 spine:

| Legacy IDs | Delivered capability | Evidence |
|---|---|---|
| F1–F2 | Android/Hilt foundation and canonical domain model | README module inventory; decisions #1–#13 |
| C1–C7 | EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown import; segmentation; SAF library flow | decisions #10–#13, #29, #50 |
| P1–P2 | Room persistence, cached parses, progress, settings and launch-time index rebuild | decisions #22, #33 |
| T1–T5 | Verified packs, Kokoro, player state machine, MediaSession, read-along, bookmarks, undo, sleep timer and pre-generation | decisions #23–#35, #42 |
| S1–S3 | OCR, share receiver, match result and listen-from-here | decisions #36–#38 |
| V1–V3 | Settings, CI, S22 performance/device passes | decisions #34, #36, #39–#41, #49 |

Post-v1 phases, one line each:

| Phase | Shipped | Evidence |
|---|---|---|
| A1–A8 | Player/pregen/Room correctness repair: pregen terminal truth, live playhead persistence, single `ImportCoordinator` (Room→index), cross-process PCM LRU bootstrap, single-writer player commands + state agreement, composition root + feature boundaries, and the Room "deletion" classified as our E2E teardowns (not Samsung) | decisions #60–#66, #107 |
| B1–B4 | `AyvuTheme` tokens + shared `core-ui` set; four-surface redesign; S22 + HiBreak visual/a11y acceptance | decisions #68, #94, #95, #98 |
| C1–C3 | Guided first-run setup (PRIVACY → DOWNLOAD_PACKS → CHOOSE_VOICE → IMPORT_BOOK); one shared voice selector (Settings/reader/first-run); setup recovery re-derived from durable facts | decisions #102, #105, #106, #112, #119 |
| E0–E1 | Storage-location decision (one-shot SAF, no persistent grant); versioned SAF backup/restore with opt-in book bytes | decisions #109, #111 |
| F1–F4 | Import progress + cancel, library search, SAF folder import, external-file intake (ACTION_VIEW / book-share → one import overlay) | decisions #64, #90, #108, #117, #118 |
| I1–I2 | Book start detection (skip cover/TOC/index) + smart chapter detection in monolithic books | decisions #69, #70 |
| G2 | Paragraph context menu: long-press **Play from here** / **Copy text** | decisions #127 |
| G4 | Speed selector removed; playback pinned 1.0× | decisions #71, #109 |
| Immersive reader | Full-screen reader: overlay title + minimal player, book-wide passage indicator, follow-active-sentence, middle double-tap chrome toggle, play-from-view, stop-on-turn, keep-page | decisions #120–#122, #125 |
| TTS/player polish | Playback volume gain; manual pregen budget anchors at the reading position and is listening-time; player-card coverage-bar redo + generation notification; slice-relative pregen progress + library Stop control; configurable synthesis thread count; emit-early per-window streaming | decisions #129, #132–#138 |

Historical estimates and completed implementation specifications were removed from this
file. Git history and the decision ledger retain them.

## Measured engine and performance verdicts

These are the non-obvious measured conclusions a future re-evaluation starts from. Full
numbers live in the cited decisions.

### D2 — execution providers, precision, parallelism (decisions #67, #86, #115, #116, #139)

- **CPU-EP default stands.** Precision is fp32 only: fp16 produced a silent en-us stub
  (`max_abs_diff` 0.723); q8 failed the 0.001 gate at 0.700 *and* was slower
  (RTF 1.73–1.79 vs 1.16–1.20); int8 is non-runnable (`ConvInteger` on CPU EP).
- **Hexagon NPU (QNN EP) does not offload the fp32 Kokoro graph** (StridedSlice fails
  HTP op validation → 100% CPU, oracle diff 0). Re-open only as a battery/thermal play
  with a static-shape re-export; ANE prior art `laishere/kokoro-coreml` (17× realtime).
- **2-engine parallel pregen is slower**: serial 1.43 audio-s/s vs parallel 1.21
  (1.18×) at +76% VmHWM / +84% PSS. Window-parallel re-ask at candela granularity:
  serial wins at every config.
- **Thread count alone, W=1 (decisions #147, measured on the Fold 8):** one session,
  T=1..8 — **1 thread RTF 1.212 (slower than realtime, reproducible to ±0.004)**, 2 ≈ 0.67,
  3 ≈ 0.67, **4 = 0.575 (the knee: lowest energy per audio-hour at 1.94 Wh)**, 6 = 0.480,
  **8 = 0.611–0.633, i.e. *slower* than 6 in both sweep orders** (an 8-core phone
  oversubscribes once the OS and system threads share the cores). The default (4) is
  validated **for an unplugged, cool device only** — the same axis re-measured
  plugged/charging on this device (thermal status 3, leg `h`, decisions #151) puts the
  knee at **4 (0.781)** with 6/8 behind it and the unset all-cores setting tracking 6–8,
  i.e. the optimum is regime-dependent; the follow-up rule is *default 6, demote to 4 when
  thermal status ≥ 2 or charging*, and the slider's top end (8) stays measurably
  counter-productive in both regimes.
- RTF baselines: S22 1.16–1.20 (#86) / 0.66–0.76 (listening corpus); HiBreak 2.84–3.12;
  Fold 8 (SM-F971B) 0.42–0.66 at 6 threads, 1.21 at 1 thread (#147).

### D3 — engine comparison (decisions #93, #96)

| Engine | S22 RTF | Verdict |
|---|---|---|
| Kokoro-82M fp32 | 0.77 | shipped baseline |
| KittenTTS Nano v0.8 | 0.31 | DROP — NaN on ORT-android (ARM-wide) |
| MOSS-TTS-Nano | ~3.5 | DROP — decode-AR; lmkd kill ~2.5 GB on the HiBreak |
| CosyVoice3 0.5B | 12.5–31.1 | DiT-gated; duplicated honorific probes |

### D4 — small tier for the HiBreak (decisions #99, #110)

| Leg | HiBreak RTF | Verdict |
|---|---|---|
| Piper en_US-lessac-medium | 0.57 | KEEP — passage-level read-along only (#30b); adoption pending |
| Supertonic 3 | 3.92 | DEFER — duration introspection passes |
| Audio8 0.1B INT8 | N/A | DROP — slow-AR 5.8 s/token |

### D6 — cross-runtime spike (closed, decisions #140)

The llama.cpp question is answered, so the item leaves the active queue. No audited
llama.cpp path runs CosyVoice3-class flow TTS — no CFM/DiT/vocoder ops, no multi-GGUF
loading, and the GGUFs target an unaudited CrispASR whisper.cpp fork — so decisions #97's
one-convention rule is evidence-backed rather than assumed. The method caveat survives the
closure: GGUF vs ORT-int4 confounds runtime with quantization, so any future re-run must
state which axis it isolates. TFLite/ExecuTorch stays "gated — no tracked TTS export
ships one".

The one remaining leg was never a cross-runtime question: an ORT int4 reference against
the fp32 Kokoro baseline belongs to whichever engine D5 adopts, and is recorded there.

### Peer-app cross-check — Android readers running Kokoro (decisions #148)

Eight probes of the other Android apps that run Kokoro (Lectern, VoiceShelf, NekoSpeak,
HayaiTTS, the sherpa-onnx engine APKs, plus candela) found **no peer that synthesizes
faster than this app**, and no published phone RTF anywhere: every one runs the same
export family on the CPU EP, and VoiceShelf's only number (RTF ≈0.36 on SD 8 Elite)
matches our SM8850 fp32 measurement. Their real advantages are three, none of them
synthesis throughput:

- **int8 Kokoro ships on Android in three projects** — NekoSpeak's default 92 MB
  dynamic-QUInt8 model, sherpa's `kokoro-int8-multi-lang-v1_1` engine APK, Lectern's
  132 MB "Light" pack. Our numbers stand (HiBreak 2.621 vs 2.89 fp32; SM8850-class
  0.36/0.50 vs 0.52), and the 0.001 waveform gate is the only rejection left — the
  owner's listening A/B heard no damage. Amending that gate is an owner decision; the
  evidence for it is D7 leg A.
- **Power/thermal-aware generation** — candela caps synthesis concurrency at
  `THERMAL_STATUS_MODERATE`, pauses pre-render in battery-saver, and demotes only the
  producer thread; VoiceShelf buffer duty-cycles to let the phone rest; Lectern stops
  synthesis on pause. Nothing here reacts to thermal status, battery-saver or charge
  state — the pregen queue runs flat out to budget.
- **Core placement beyond a thread count** — Lectern's "fast cores" allocation and
  candela's core-count heuristic. Unused here: ORT thread-pool spinning controls and
  Android ADPF `PerformanceHintManager`.

Two verdicts are held open for measurement: the XNNPACK EP partitions **only 2D** convs
(Kokoro's are 1D, so our "slower" result tested a graph the EP could not claim), and
weight-only int4 (`MatMulNBits`) is claimed to have no CPU-EP kernel while our own
HiBreak probe ran a MatMulNBits graph to finite output on ORT-android 1.23.2. Both are
D7 legs.

### Phase J — offline NMT (decisions #114)

| Model | Verdict |
|---|---|
| M2M-100-418M | DEFER — fp32 fails the memory gate; int8 24–31 ms/token, lower chr-F than SMaLL-100 |
| SMaLL-100 int8 | ADOPTED for translate-then-read — one 915 MB pack, 8.9–9.9 ms/token, chr-F 51.9–63.2 |
| OPUS-MT per-pair | measured record; specialist alternative (tc-big int8 speed-disqualified; fp32 quality fallback) |

## Active work

### Phase D — playback latency and weak-device performance

#### D1 — Instant ±30-second seek horizon (implemented 2026-09-13, decisions #91/#155 — device-verified on both devices)

Landed in two halves: survive-seek (decisions #91 — `PregenQueue.ensure(from, rearm)`,
`stopEverything(stopFill = false)` on the nav paths, guarded fill restart) and the
audio-time horizon (decisions #155 — `PREFILL_LOOKAHEAD_SECONDS` 45 → 30 s shared by
the queue bound, the buffer-before-start wait, and the generation notification's
denominator). Hot-zone persistence not needed: the look-ahead is already write-through
persisted and the measured misses were the dead-owner ensure and cold sync synthesis,
not RAM churn.

Acceptance on both reference devices:

- Ten representative ±30-second seeks after normal listening resolve from
  `buffer|pregen|disk`, with zero synchronous synthesis at seek time.
- Cold first play after a process start (engine open) resolves without main-thread
  Choreographer skips attributable to playback, inside the D2 first-audio baseline.
- Queue memory remains bounded and overnight/manual pre-generation behavior is
  unchanged.
- Record latency separately on the S22 and Bigme HiBreak.

**Measured 2026-08-29 (pre-implementation baseline):** cross-boundary ±30 s seek to an
uncached passage is 79.6 s (S22) / 107.0 s (HiBreak). The 60 s dead-owner ensure wait
was fixed (decisions #78) — remaining cost was the cold target's synchronous
synthesis, now covered by the horizon + survive-seek. **Re-measured on both
devices (2026-09-13, decisions #155):** ten ±30 s seeks each — B6 29–61 ms per seek,
S22 19–31 ms, 0 synchronous-synthesis seeks and 0 Choreographer skips on either;
cold first play 236 s (B6, Kokoro RTF 2.9) / 44.8 s (S22). Rows:
`docs/prints/d4/d1-seek-{hibreak,s22}.json`. Acceptance met.

#### D7 — Cross-app performance spike (legs A–F) — decisions #148

One `spike-tts` measurement session on the S22 and the HiBreak, answering the four levers
the peer-app survey surfaced and closing the two conflicting verdicts. Measurement only —
adopting anything it finds (int8 tier, power/thermal policy, a gate amendment) is a
separate decision once the numbers exist.

- **A — int8 tier.** Dynamic-QUInt8 Kokoro (NekoSpeak's 92,361,271 B artifact) against the
  pinned fp32 oracle: RTF, energy per audio hour, PSS, cold open, plus a level-matched
  blind listening set and a perceptual score — the evidence needed to replace the 0.001
  waveform gate.
- **B — window length.** 150 / 300 / 510-token windows over the same text: throughput
  against first-audio latency. #139 swept workers×threads, never window length.
- **C — incremental output.** Per-window AudioTrack writes re-probed: does the emit-early
  seam (#138) beat whole-passage MODE_STATIC on underruns? #83's inert MODE_STREAM verdict
  predates the seam.
- **D — scheduling.** ADPF `PerformanceHintManager` hint session around the intra-op pool,
  `session.intra_op.allow_spinning=0`, and fast-core placement — RTF, energy, and
  UI-latency jitter (the complaint #137 answered with a thread slider).
- **E — duty cycle.** Continuous vs on/off generation at equal coverage: energy per audio
  hour and thermal headroom — the battery half of the owner's question. One continuous-run
  data point already exists from #147 (Fold 8, on battery, screen on): 3.3 W average over
  a 17 min sweep, battery 40% → 35%, thermal status 0 → 3 with SKIN 36 → 45 °C; the
  screen-off equivalent must not be used (unplugged + screen-off drops a non-foreground
  process into the restricted cpuset and stalls inference ~5×, #147).
- **F — verdict repair.** int4 `MatMulNBits` CPU-EP availability re-probed; XNNPACK's
  partition coverage re-checked on an H=1-reshaped static vocoder (open/partition gate
  only — a speed claim needs its own export).

Each leg runs its own fp32 control immediately before it and the session repeats the
baseline last, because #139 logged ~13% thermal drift across a long session. Energy legs
sample on battery only (a plugged leg reads as "energy not measured").

Acceptance: every leg reports RTF, energy per audio hour, PSS and thermal headroom on both
devices; leg A also produces a blind A/B set and a perceptual score against the fp32
oracle; legs B–E report their own latency/energy deltas; leg F returns a binary verdict
per claim. Nothing ships from this spike except the numbers and the gate-amendment
decision they inform.

#### D4 adoption — PiperEngine

Integrate `PiperEngine : TTSEngine` behind the existing seam, pin per-language voice
packs + hashes, and complete the es-IT/de/ko coverage check. Ships passage-level
read-along only (stock Piper export exposes no word timestamps — #30b).

German voice candidate (2026-09-11, peer probe): `de_DE-thorsten-high` — the voice
kokoro-reader's Go server runs through sherpa's VITS path (`LengthScale = 1.0`,
`NumThreads = 4`, both matching our defaults), so it is a working high-quality-tier
reference for the German gap. `en_US-lessac-medium` stays the measured D4 leg (HiBreak
  RTF 0.566–0.575 on ORT-android 1.29; #99 and its 2026-09-12 correction). The owner's
  listening pass **passed** (2026-09-13, decisions #99 addendum): the corrected renders
  are intelligible with prosody above Android's built-in TTS — the quality gate is
  cleared and adoption is the next actionable slice. Coverage check substantially
  answered at the pack level (2026-09-13, rhasspy/piper-voices @ `1162a917`, 176 voices,
  MIT): de ✓ 10 voices, es ✓ 9, it ✓ 4, pt-BR ✓ 4, ko △ 1 (`ko_KR-kss-medium`, single
  voice) — German (Kokoro's gap) is fully covered.

**Status: selection wired end-to-end (2026-09-13, decisions #154 + its addendum).**
`PiperEngine` is registered as `piper-v1` (PRIMARY) with the `en_US-lessac-medium` +
`de_DE-thorsten-high` packs pinned and downloading through the existing registry flow.
The phoneme-id framing is verified head-for-head against official piper-tts and pinned
in a JVM test. The runtime selection wiring landed (decisions #154 addendum):
`PiperRuntime` opens the engine over the downloaded packs behind `EngineSelector`'s
explicit `piper-v1` branch, the voice sheet/catalog resolves through the #144
availability shape, and #30b's segment-less read-along degrades exactly like
system-tts. **Speech subscreen (2026-09-13, the owner's Android-settings-style IA
pass):** the root pane carries a Speech entry row (current engine + a summary line);
the `SettingsPane.Speech` subpane holds engine + pack rows + generation threads + the
voice selector + playback volume; back/up mirror the OCR-languages pattern. The rest
of the root stays flat — ~40 rows in 5 sections is still one-flick territory; the
subscreen threshold is a section whose content is a multi-row picker or a list longer
than the root viewport, which only Speech (engine + packs + ~60 voice rows) crosses.
Remaining for this slice family: further voice-pack pins (es/it/pt-BR/ko) — the
passage-level-only read-along stays recorded degradation (#30b). **Engine device smoke
verified on both devices** (2026-09-13, `PiperDeviceSmokeTest`): B6 RTF 0.579 / S22
RTF 0.094, finite 22.05 kHz mono PCM, segments=null on device —
`docs/prints/d4/d4-piper-engine-smoke-*.wav`. **Engine-output listening pass:
Bigme HiBreak (B6) PASSED (2026-09-13, owner)** — intelligible and acceptable
(prosody "a little robotic" but acceptable), lessac voice recognizable, no
artifacts; one non-blocking trait noted (inter-sentence gap reads a little short —
a VITS learned-prosody trait, not a normalization defect). The S22 engine-output
listening pass is still owed.

### Phase G — narration quality

#### G0 — Narration-quality listening corpus — bounds G1

Build the listening corpus (names, honorifics, abbreviations, numbers, dates,
currencies, measurements, Roman numerals, dialogue, headings, footnotes, page
furniture, URLs/code-like text, long paragraphs, speed transitions, every advertised
language) and run it through the shipped Kokoro pipeline with the existing `spike-tts`
runner — the D3 corpus/harness infrastructure makes this mostly curation, not tooling.
Findings become a typed list of mispronunciation classes; G1's rule scope is bounded by
measured failures, not the single `Ms.` regression.

Acceptance: the corpus synthesizes end-to-end on the S22; findings recorded as typed
classes with examples; G1's built-in rule set is derived from them.

Status: corpus (377 entries, 9 languages × 15 categories) built and synthesized
end-to-end on the S22; device corpus, WAVs and measurements complete
([g0-findings.md](g0-findings.md)). Listening pass **in progress** (2026-09-13,
owner): five classes confirmed and recorded — `abbrev-not-expanded` (abbreviations
truncated or spelled letter-by-letter instead of the full word, cross-language, the
class that bounds G1's built-in set) plus its paired `abbrev-period-pause`,
`date-slash-read-aloud` (slash-dates spoken as numbers with the slash verbalized),
`measurement-unit-not-expanded` (unit symbols spelled letter-by-letter, en/it/pt
gaps; `km/h` also verbalizes the slash), and `dialogue-quote-attribution-pause` (no
pause between a closing quote and its narration attribution). ja/cmn/hi are
recorded as a non-native-ear limitation.

**Gates:** G1's built-in rule set (bounded by the typed findings) and D5's engine choice
(its quality gate is the G0 blind read, and the pt-BR blind read decides whether
SMaLL-100-class quality carries into the translation slice). The next action is human,
not code: **the owner's listening pass** over the Roman-language classes.

#### G1 — TTS pronunciation replacements — promoted from ideas

Add a deterministic, testable normalization/replacement stage before phonemization for
names, honorifics, abbreviations, pauses and intentionally skipped page furniture. The
reported `Ms.` → "M S" defect was the first regression case, already shipped
(`PronunciationNormalizer` + `NormalizingPhonemizer`, `Ms.` → `Miz`, 2026-08-29). The
remaining built-in correction set is bounded by G0's typed findings.

Start with ordered literal rules plus a small built-in correction set. Regex and user
editing require explicit limits and preview because an unbounded rule can silently
rewrite an entire book. Matching/index text remains unchanged; replacements affect TTS
output only.

Candidate rules from the peer probe (2026-09-11; still bounded by G0's typed findings
before anything lands): parenthetical pauses (`" (" → ", ("`, `" [" → ", ["`,
`") " → "), "`, `"] " → "], "`), dash pauses (`" — "` → ", — "`), and dropping standalone
page-number lines. All output-side only, exactly the `PronunciationNormalizer` contract
(`filter_page_numbers` is the shape for the last one).

#### G3 — Hardware and listening gestures — promoted from ideas

Add the narrow useful subset before building a configurable gesture editor:

- Media/headset play-pause and seek commands continue through `MediaSession`.
- Optional volume-key passage navigation is limited to the visible reader and is off by
  default; normal system volume behavior must remain the default.
- Screen-off behavior must use supported media-session commands rather than promising
  interception Android does not deliver to an inactive activity.

Configurable tap-zone maps remain in the idea pool until the fixed reader interactions
have device evidence and an accessibility review.

### Sequenced after G0 — D5 high-end engine choice

A Phase D item whose gate lives in Phase G: it does not start until G0's typed findings
and blind read exist.

#### D5 — High-end cloning: Chatterbox vs CosyVoice3 vs Pocket TTS

- Candidates: **CosyVoice3** (incumbent — 9 langs incl. es/it, zero-shot + cross-lingual
  cloning, pinned pack, measured 3.22 GB VmHWM on the S22) vs **Chatterbox Multilingual
  ONNX** (MIT, 23 langs incl. es/it/pt/de/ko, zero-shot cloning, 0.5B AR Llama backbone)
  vs **Pocket TTS** (Kyutai; MIT code, CC-BY-4.0 weights, **100M params / ~176 MB**,
  en/de/fr/it/pt/es, zero-shot cloning, native streaming — added 2026-09-11, decisions
  #149. It is the only candidate that could clone *inside* a phone's live budget instead
  of pregen-only: ~20× smaller than the other two, and already shipped on Android by
  NekoSpeak as five ORT sessions).
- Provenance gate first: pin revision + sha256 and verify output parity against the
  reference before measurement (the #86 fp16-stub lesson) — every candidate's export is
  community except Pocket TTS's *code*:
  - Chatterbox: only `onnx-community/chatterbox-multilingual-ONNX`; `textagent/…` is a
    mirror of the same export, NOT a pin candidate. Official `ResembleAI/chatterbox-turbo-ONNX`
    is English-only — fails multilingual.
  - Pocket TTS: the upstream weights (`kyutai/pocket-tts` @ `492522650173a0…`) are
    **gated**, so the app cannot fetch them token-less; use the ungated CC-BY-4.0 export
    `KevinAHM/pocket-tts-onnx` @ `58a6d00cf13d23…` (int8 + streaming + per-language
    bundles) or its `lookbe/…` mirror, and treat both as unvalidated-by-upstream. Ship a
    curated voice set only: `voice-donations/` and `voice-zero/` are CC0, `vctk/`,
    `alba-mackenna/`, `cml-tts/fr/` are CC-BY-4.0, but **`expresso/` and `ears/` are
    CC-BY-NC — excluded**, and the model repo's built-in embeddings (`cosette`, `jean`, …)
    derive from those, so a permissive-only catalog must be filtered, not inherited.
- Measurement (pregen-budget terms on the S22): per-passage wall time, peak/resident PSS
  through an AR KV-cache decode (the MOSS lesson — memory, not speed, kills weak RAM),
  and the G0 blind gate against CosyVoice3's #93 quality flag. Pocket TTS adds its own
  first question: **there is no ARM/phone RTF anywhere** (vendor is 6.33× realtime on an
  M4 using 2 cores), and its cost shape is an AR flow-LM loop + per-frame flow steps +
  a separate Mimi decode — so the `spike-tts` harness measures it as a pregen candidate
  first and a live one only if it clears realtime on the HiBreak.
- **ORT int4 reference (absorbed from the closed D6):** Kokoro-82M fp32 baseline vs the
  adopted candidate at int4, in the `spike-tts` harness — cold engine-open
  time-to-first-audio, steady-state RTF, peak/resident PSS + VmHWM, and the #67 PCM
  oracle (`max_abs_diff`) — S22 and HiBreak, same corpus/voice as D2/D3.
- Integration-cost audit: HF BPE tokenizer (new tokenization path vs espeak-ng; the
  advertised set en/es/it/pt/de needs no external normalizer, zh/ja/he do); 24 kHz output
  vs `lastSampleRateHz`; watermark off by default. Pocket TTS adds a second one: a
  **Misaki/sentencepiece G2P** (NekoSpeak's pure-Kotlin Misaki with Viterbi heteronym
  resolution) beside our espeak-ng/JNA path — #97's one-convention rule wants that
  justified, not assumed, and read-along timing is unverified (Mimi frames are 12.5 Hz).

  **First ARM datapoint measured (2026-09-11, HiBreak, decisions #153):** the ungated
  english_2026-04 export (int8 heavy graphs) runs RTF **5.54–6.87** at 2/4/6 threads
  (no meaningful scaling), PSS ~1.40 GB, cold open ~6 s, voice encoding 5.6–11.2 s —
  ~390 ms against the 80 ms/frame realtime budget, so **the live-cloning question is
  answered NO on the HiBreak and Pocket TTS is a pregen candidate**; it is also ~2×
  slower per audio-second than the Kokoro fp32 baseline on the same device (RTF
  2.84–3.12). Parity vs the host reference holds structurally (EOS frame and frame
  count match exactly at threads=4); residual latents/audio differences are int8-kernel
  ISA variation, and decoder chunking is not transparent (1-frame is the canonical
  unit). The fp32 control (2026-09-12, decisions #153) confirms the verdict is
  precision-robust: fp32 runs RTF 5.81–7.44 at PSS ~1.64 GB, so int8 stands as the
  better Pocket config on this device (~5% faster, 15% less memory). The S22/Fold leg
  and the G0 blind read remain D5's open gates.

### Phase H — TODAY reading and listening stats — LANDED (2026-09-13, decisions #157)

Use the capture, aggregation and UI design in
[post-v1-plan.md](post-v1-plan.md#slice-a-today-stats-dashboard). Store whole seconds
and round only for display so short valid sessions are not discarded.

Reading/listening capture is decided (2026-09-02, decisions #109): listening =
wall-clock while `PLAYING`; reading = **page-flip-active** reader dwell (screen-on
foreground, accrued only while the user is actively turning pages) with sub-10-second
spans dropped — not raw foreground dwell.

Landed: Room v3 `activity_seconds` (add-only migration, add-upsert accumulate,
book-removal drop), listening capture at the PlaybackService edge (D1 untouched),
flip-active reading capture in the reader, pure aggregation in core-player
(`DailyTotals`/`WeekSummary`/`Streak`) and the `TodayCard` header on the library
home. A full event/session timeline is still not built — add it later only if a
user-visible history view needs event-level data. Reading-dwell tuning (the 180 s
active-window bound chosen in #157) may revisit after device use.

### Phase K — Settings review and improvements

The settings surface has accreted without an information-architecture pass since B3.
`AppSettings.Snapshot` grew from five keys (threshold, voice, favorites, theme, OCR
languages) to nine (adding `ttsEngine`, `playbackGain`, `ttsThreads`,
`realtimeCapable`), and the screen now spans engines/packs, voice + favorites, match
threshold, OCR languages, theme, offline audio, playback volume, generation threads and
backup & restore — with no grouping beyond stack order. Review first, then land the
concrete improvements:

1. **Grouping and discoverability.** Reorganize into coherent sections (speech vs
   reading vs storage/data) with headers; review the "applies after restart" knobs
   (`ttsThreads`, `realtimeCapable`) for user-comprehensible copy, and keep the B4
   accessibility bar (TalkBack, 48 dp targets, theme) intact.
2. **Engine-agnostic pack rows.** `SettingsScreen` hardcodes `KOKORO_PACK_IDS` /
   `OCR_PACK_IDS`, and `VoiceCatalog` npz parsing is kokoro-specific. Derive pack rows
   from the registered engine's descriptors so D4's Piper (per-language packs) or D5's
   CosyVoice adds its packs without a settings-surface edit.
3. **Arbitrary pre-generation budget.** The library pre-gen dialog ships fixed presets
   (30 m / 1 h / 2 h / 3 h / whole book); the backend already accepts any
   `PregenBudget.maxTimeMs` (A1) — parse an arbitrary listening-time input, UI-only.
4. **Per-book overrides — decided (decisions #144).** Per-book speed is **deferred**:
   playback is pinned 1.0× (decisions #71), so there is no global speed base for an
   override to override, and the retained column/cache/backup model keeps the revisit
   migration-free. Per-book voice is **kept** — the reader's voice sheet currently
   mutates the global default, re-voicing every other book — and is item 5.
5. **Per-book voice override.** `effectiveVoice(bookId) = override(bookId) ?: global`,
   resolved where the active book's voice is read (playback synthesis, coverage keys,
   pre-generation input, per-book usage display). Storage is one `book.voice.<bookId>`
   key in the generic settings table — no Room migration, and it rides the existing
   backup archive and is dropped with the book. An override applies only when the active
   engine exposes that voice id; otherwise the global default plays and the selector row
   reads unavailable. Full contract: decisions #144.
6. **Settings-surface defect cleanup.** The open-bugs row "Offline-audio usage row is
   stale on return to a live Settings screen" (decisions #144) is a refresh-trigger fix on
   this phase's own surface: re-read `PregenStorage.usageByBook()` when the section
   becomes visible instead of only in `SettingsViewModel.init` and after a delete.

Acceptance: settings are grouped and navigable without losing any existing knob or its
persistence; adding an engine adds its packs without a settings-screen change; an
arbitrary pre-gen duration works alongside the presets; the per-book-override decision is
recorded (decisions #144); and a per-book voice changes only that book — it survives
restart and backup/restore, feeds pre-generation, and falls back to the global default
when the active engine lacks the voice.

Status: items 1, 3 and 4 landed (decisions #142, #144) — sections Speech / Reading &
sharing / Storage & data / Appearance, arbitrary listening-time entry in the pregen
dialog, per-book speed deferred to the #71 revisit and per-book voice kept as item 5.
Item 2 landed (decisions #156): the Speech rows and the OCR pane derive from the
registered engines' descriptors — piper-v1's rows came with D4 with no settings edit,
and the gate note is cleared. Item 5's contract landed (decisions #156): the
`book.voice.<bookId>` settings key with backup/restore ride-along, the
`EngineSelector.effectiveVoice`/`engineFor` + `PiperRuntime.engineFor` pairing seam, and
the AppSettings mirror; the player-side layer (activeVoice, reader voice sheet per-book
scope, pregen input, per-book delete drop) rides the Phase H slice's landed service/reader
hooks per the agreed sequence. Item 6 closed: the stale usage-row defect had already been
fixed in the 0.1.1 release pass (ON_RESUME refresh, `SettingsOfflineUsageTest`); the
docs (open-bugs.md, decisions #156) now record it.

## Later — strategic and dependency-gated work

| Item | Gate / reason for position |
|---|---|
| Pitch-preserving speed | WSOLA/phase-vocoder DSP and cache-key compatibility; measure CPU/battery before replacing hardware rate conversion. |
| Translate-then-read (`core-translate`) | Engine and scope already decided: SMaLL-100 int8, one 916 MB pack for all languages (decisions #114, Phase J verdict below), any advertised target language, output-side only, degrades to the original text on failure (decisions #101). Not blocked on any active phase — remaining work is the SMaLL-100 tokenizer port, on-device SentencePiece, and pack integration behind the pre-gen queue; the spike's export/parity/chr-F tooling and manifest pins are the reproduction path. The gate is appetite: the 916 MB download plus the accepted chr-F trade against the per-pair pt-BR specialist. |
| High-end cloned-voice pre-generation (engine chosen by D5) | Ships only after D5 (Active work) picks the engine and clears the G0 blind read — Chatterbox Multilingual, CosyVoice3 or Pocket TTS (added 2026-09-11, decisions #149); the incumbent is DiT-gated (decisions #21/#23) and D3-quality-flagged (duplicated honorific probes; RTF 12.5–31.1), disk-only playback. A1/A4 long satisfied. Distinct from D5 itself: that item *selects*, this row *ships*. |
| Kindle official export/API sync | External API/export contract and account UX; manual share/resume already covers the core use case. |
| Word-level highlighting | Requires a stable word/phoneme timing contract beyond current sentence anchors. |
| Auto language detection and voice routing | Needs per-language voice mappings, mixed-language policy and pack-availability UX. The manual single-book case is covered earlier by Phase K item 5 (per-book voice, decisions #144). |
| Full read/listen session history | Build only with a concrete history/export/statistics consumer. |
| Auto-delete listened audio | Eviction design first: must preserve the current playhead and every position reachable by undo — a design that does not yet exist (A4's LRU repair is not the eviction policy). |
| Habit-driven pre-generation | Stats/session evidence first; prediction may rank work but never override storage, charging or playback-yield limits. |
| Profiles, collections and book-map navigation | Valuable reader/library expansion after search, folder import and basic controls are complete. |

## Idea pool — not scheduled

RSVP speed-reading, downloadable public-domain classics, a fully configurable tap-zone
editor, and speculative multi-engine parallelism remain in [ideas.md](ideas.md). They
have no dependency that warrants placing them ahead of stabilization, data safety or
the promoted library/narration work.

## Further reviews — recorded, not scheduled

These are review subjects, not implementation commitments. Each should produce a
bounded decision or roadmap proposal before code starts.

### Hostile-input and resource limits

Audit every untrusted boundary: EPUB/KF8 entry count, expanded bytes and compression
ratio; MOBI decompression ceilings; pathological chapter/passage counts; malformed
covers and shared images; backup path traversal, duplicate entries, oversized JSON and
unknown sections; pack archives; temporary-file cleanup; and disk-full behavior during
import, restore and pre-generation. Existing XXE hardening is a baseline, not the whole
resource-exhaustion contract.

Partially closed 2026-09-10 (decisions #146): container/entry/per-entry/cumulative-expanded
ceilings on the EPUB/KF8 path plus OOM containment at the per-file parse boundary. Still
open: proactive MOBI `HuffCdic`/`PalmDoc` expansion ceilings, backup-archive limits, pack
archive limits and disk-full behavior.

### Release readiness

Distribution decision made (decisions #126, 2026-09-05): **GitHub Releases signed APK,
manual local signing** — CI stays a gate (tag assemble only). The local signing pipeline
ships: release keystore outside the repo, gitignored `keystore.properties`, unminified
`release` buildType, `tools/release.sh` (build + apksigner verify + draft/publish
release), `NOTICE.md` attribution.

**v0.1.1 is not published.** The artifact is now prepared at HEAD (decisions #146): the
signed release build is **arm64-v8a only** (the espeak-ng phonemizer is an arm64 native
library, so the other ABIs' libs were ~114 MB of dead weight and would have installed a
non-functional app) — 165.2 MB → 50.8 MB payload, the notes carry the SHA-256 and the
signing-certificate fingerprint, and the import path now has resource ceilings + OOM
containment (a zip bomb fails one file instead of killing the process).

Remaining before publishing, in order:

1. **Device smoke on the signed 0.1.1 APK** — the previously verified signed build
   predates 21 commits; no device was attached during the prep session, so this is owed.
2. **Publish:** `tools/release.sh --upload --publish --notes docs/release-notes-0.1.1.md`
   (the script defaults to **draft** — `--publish` is required). Publishing creates tag
   `v0.1.1`, which fires the CI `assemble-on-tag` gate.

The next release after that increments `versionCode` (2 → 3, v0.1.2).

Deferred until a store listing is actually wanted: AAB + Play Data Safety, store privacy
policy, listing/screenshots, supported-devices declaration. Native crash symbols and
shrink rules are moot while unminified; a shrink pass (R8 rules + device regression) is
the gate for enabling minify. Backup versioning: `versionCode` increments per release;
the backup codec carries its own version, so restore compatibility stays codec-scoped.

### Android lifecycle and interruption matrix

Exercise wired/Bluetooth disconnect and reconnect, calls/assistant/navigation focus,
permanent vs. transient loss, lock screen, process recreation/low-memory kill, reboot
during scheduled work, notification restoration, Android Auto and rapid commands from
multiple surfaces. Fold failures into A2/A5 acceptance rather than creating parallel
player state machinery.

### Targeted follow-up reviews

- OCR replacement technology for the known legacy-tessdata accuracy ceiling.
- Library metadata: series, author normalization, duplicate editions and sorting.
- A privacy-preserving local diagnostic export containing versions, pack/storage state
  and typed failures, never book text.
- Battery/storage policy for overnight pre-generation defaults, charging constraints
  and cache-budget consequences.

## Outstanding verification and tooling debt

- Android Auto controls: tracked as an **Open** product bug in
  [open-bugs.md](open-bugs.md) — that list is authoritative, so this roadmap keeps no
  separate row for it.
- Continue physical-device acceptance on the S22 and HiBreak for behavior or performance
  claims affecting playback.

The A1/A2/A4/A5–A7/A6/F2 device-evidence rows and the ktlint gate are all closed
(decisions #105, 2026-08-31/09-01); B4/C2 device checks are complete (decisions
#98, #105).
