# Roadmap

Forward sequencing for Ayvu. Shipped work is reference-only below; the open
items are the active queue. Implementation history belongs in
[decisions.md](decisions.md); candidate features remain in [ideas.md](ideas.md)
until they are promoted here.

## Current state

v0.1.1 is release-ready but **not published**. The signed-APK pipeline, the on-device
sanity pass on the signed build and `docs/release-notes-0.1.1.md` are all done
(decisions #126, #128), and the release gate (pass 7 — untrusted-input hardening,
licence/NOTICE completeness) closed 2026-09-17 (decisions #174); what remains is the
device smoke on the freshly built signed APK and then publishing the GitHub release — no
`v0.1.1` tag exists on the remote (see "Release readiness" below). The v1 capability
spine is complete and device-verified: import → index → local TTS playback with
read-along → share-and-resume, plus settings, OCR, offline pre-generation, storage
controls, backup & restore, and the app-wide player card. The current module and test
snapshot lives in the [README](../README.md#status).

Queue order (dependency-first): the **owner's G0 listening pass** → ~~D1~~ **done,
device-verified** (2026-09-13, #155) → **D7** cross-app performance spike
(measurement-only, decisions #148) → ~~D4~~ **adoption landed** (PiperEngine,
#154/#155/#159 — K2 unblocked, es/it/pt-BR pinned) → ~~K5~~ **per-book voice landed**
(#156) and ~~Phase H~~ **stats landed** (#157) → ~~D5~~ **CLOSED — the voice-clone
engine class is deferred for the app** (owner decision, decisions #173; all three
candidates fail the run axis on the S22, and no clone engine ships). G1's rule
set is bounded by G0; H is independent, so its position
is preference rather than dependency. This order, the release-state correction and the
D6 closure are recorded in decisions #145. Open defects and their acceptance criteria are
authoritative in [open-bugs.md](open-bugs.md).

Unqueued candidates surfaced by the 2026-09-15 architecture audit — each needs its own
scoping before it enters the order ([review Appendix A](reviews/2026-09-15-architecture-review.md)):
**thermal/battery-aware synthesis** (the peer cross-check below already records the gap
and the measured follow-up rule: nothing reacts to thermal status, battery-saver or charge
state, and the pregen queue runs flat out to budget), **resource envelopes** (index RSS vs
library size, launch rebuild cost, PCM cache cap vs free space — the repo's own rule says
performance work starts with a device measurement gate), **accessibility + UI language**
(the reader is gesture-only with no stated TalkBack path; the UI is English while the
content serves nine languages), and the **device-verification oracle** (instrumented
suites are manual and the app ships no telemetry — accept that, or invest in ABIs/a device
farm). These are product and measurement items, not source cleanup.

**The `PregenKey.engine` dimension is inert (audit + device-confirmed, 2026-09-15).** No
product site populates it — `PregenQueue`, `OfflinePregen`, `PregenSpaceEstimator` and the
edge's `livePregenKey` all take `DEFAULT_ENGINE` — so every entry on disk is
`kokoro/<voice>/…` whatever engine produced it (observed on the S22:
`files/pregen/t4-e2e-book/kokoro/en_US-lessac-medium/...`, a Piper voice under the kokoro
slug — see decisions #163 pass 4). Decision #77's cross-engine-collision protection is
therefore nominal, and `PcmPassageCache`'s legacy branch is taken by every entry. Fixing it
needs a decision, not a patch: which slug vocabulary (the setting is `kokoro-82m` /
`piper-v1`, the key's default is `kokoro`), and acceptance that existing Piper entries
become unreachable cache (re-synthesized, then reaped).

**Shipped (2026-09-16): read-in-language display** (decisions #166; the slice below is the
historical scope — deviations from it are in the design doc's Status). Full plan:
[features/read-in-language-display.md](features/read-in-language-display.md). The goal
widened from "separate translation from TTS" once the owner settled the product shape: the
translation becomes **visible** in the reader (interleaved single column, plus a
translated-only option, export planned for later), and the **spoken language is independent
of the displayed one**. Six ordered steps, 0–5; steps 0 and 1 are prerequisites, not
optional:

- **0 — `TranslationTarget`** (as previously scoped): `(translateLang, translator)` is
  threaded as two loose nullable Strings through `PregenPlanner` / `PregenQueue` /
  `OfflinePregen` / `PregenSpaceEstimator` / `PlaybackService.livePregenKey` /
  `PregenWorker`, always set together (`translateLang?.let { PregenKey.LFM_TRANSLATOR }` at
  every site — three production sites). Unifying them into one value type makes the slice's
  extra dimension (translator *version*) one edit instead of six, and deletes the
  impossible "lang null, translator set" state the key's `toString` defends against.
- **1 — pass 4's publication/text extraction, re-scoped.** Extract the reader-text
  collaborator from `PlaybackService.stateCopy` — the single producer of every text field
  the reader sees — behind a **ranged display-block seam**, so the slice edits a
  collaborator instead of the 2,100-line service. The gate the roadmap held it behind is
  discharged (#164 cleared D5 + G1).
- **2 — `TranslationService`.** Translation leaves `TTSEngine.synthesize` and becomes a
  service consumed by *both* the audio path (`TranslatingEngine`, behaviour unchanged) and
  the display path: keyed cache + in-flight dedupe, playback > pregen > display priority,
  never on the `AyvuPlayer` thread. **The "do not pre-build the translate dispatcher" note
  above is superseded** — a visible translation *requires* it, because translation must be
  callable with no synthesis at all. Today there is no text-level memo, so re-translation
  is avoided only incidentally when that passage's audio is already cached.
- **3 — translated-text store.** Room v4 (forward-only additive, migration + test), keyed
  `(bookId, chapter, passage, lang, translatorVersion)`. Eviction and the backup section are
  explicit decisions, not inherited from the PCM cache — regenerating text costs an LLM run,
  not a synthesis.
- **4 — publish blocks + split the settings key.** `book.translate.<bookId>` stays the
  speech target; a new per-book display key needs **no Room migration** (`SettingsStore` is
  typed accessors over a key/value table). A display-only change must not rebuild the player
  through `changeVoice` — it is a text re-projection, not a session rebuild.
- **5 — reader.** Three modes over one block list; differentiation by **colour + paragraph
  indent**, never font size (it breaks the line-pitch grid the pagination samples).

**Corrected premise.** An earlier draft of this section said the text cache must reuse the
audio cache's identity — "one identity, two payloads" — or text and audio hits would
disagree. With independent audio and display it must not: `livePregenKey` deliberately
omits `x<lang>` whenever the *original* is what gets synthesized, while the text key always
carries it. They are two identities with overlapping dimensions, and the text key must not
inherit `livePregenKey`.

**Invariant.** `PlaybackUiState.chapterPassages` documents it (decisions #165): index ==
original passage index, original-language text, and a displayed translation is a **separate
projection**. Interleaving that list would silently re-point every bookmark, resume row and
share match in the book.

**Pre-existing defect in scope:** `RoomLibraryStore.delete` drops progress/bookmarks/
history/activity/passages/book but **not** the `book.*` settings rows, though
`SettingsStore` and decisions #156 claim it does — so per-book voice/translate settings
already orphan on delete, and the new display key would inherit the leak. — **Fixed with
the slice** (the delete now drops `translations` + the voice/translate/display settings
rows; regression-tested).

**Slice shipped 2026-09-16 (decisions #166).** Shipped deviations from the scope above:
differentiation is colour-only (the planned paragraph indent crashed Compose's paragraph
span expansion on page slices), Pending renders live animated dots, reflows re-anchor on
the page's first passage, no automatic text eviction, and the `TranslationService`
interface lives in `core-player` (not `core-translate` — no new module edge).

## Planning rules

- Correctness work precedes features that build on the affected contract.
- Room is durable truth; indexes and audio caches are derived, recoverable state.
- Performance work starts with a measurement gate on physical devices; no delegate,
  quantization, or engine path ships because it is plausible on paper.
- A roadmap item is complete only after its observable acceptance scenario is run.
- No date or total-duration forecast is maintained while the stabilization scope is
  still changing.

## Source cleanup (in flight)

A repo-wide architecture audit (2026-09-15) queued a source-cleanup pass that runs
beside the feature queue and changes no product behavior except where noted
([findings and open questions](reviews/2026-09-15-architecture-review.md)).
Decisions #163 logs what landed; this table is the sequencing.

| Pass | Scope | State |
|---|---|---|
| 1 — verification harness | `checkFeatureBoundaries` fixed (it read `dependencyConstraints` and could NEVER fire) + wired into CI; `:core-translate`/`:core-backup`/`:core-ui` unit tests added to the CI lanes; the 2,937-entry ktlint baseline deleted (one bulk format over 196 files, 60 hand fixes, a root `.editorconfig`, and `ktlintFormat` kept as the formatter twin) | **done** (#163) |
| 2 — contract boundaries | `LibraryStore.cachedBooks()` on the contract (kills five concrete `RoomLibraryStore` injections); `ActivityStore` port for both halves of Phase H (service write side, library read side); `PlayerState.canUndo` owned by the state machine (edge mirror deleted, regression-tested) | **done** (#163) |
| 3 — playback edge concurrency | **landed**: `addBookmarkAtPlayhead` serialized on the player thread inside `commandLock` (offset read at dispatch; still not a command, so the next command cannot cancel it) — it had no test at all before; the CR-2 `finalStopJob` stays uncancellable but serialized on the player thread, targets the session's own machine, and its teardown clear is generation-guarded (a superseded STOP no longer blanks a loading session); the sleep-timer cycle moved into `PlayerStateMachine` with its 30-minute constant. All four behaviours mutation-verified: reverting them fails exactly the four new tests | **done** (#163) |
| 4 — `PlaybackService` decomposition | **mechanical half landed** (#163): one `bindBook` for the four machine-rebuild sites, one spine walk (`BookLayout.next` — the pre-arm's private copy deleted), one cache key (`livePregenKey`, pinned to the canonical `PregenPlanner.key`; the pre-arm used to drop the read-in-language dimensions and peek the original's audio). The remaining collaborator extraction (MediaSession / notification / audio-focus / coverage / probes) is still **gated on D5 + G1** — both land inside the same file — but the translate/TTS slice below argues for pulling the publication/text part forward | **open** (gated) |
| 5a — verification floor | **Split by the owner (2026-09-15): the half that needs no build churn is DONE; the coverage ratchet is deferred behind pass 6.** DONE — (i) **`architecture.md §6` is now the contract→enforcement MAP**: 7 contracts, each naming the test that enforces it, with the gaps stated instead of assumed (contract 1 is review-only, contract 4 is half-enforced — the 'same file parses to identical passages' half has NO test, contract 5 is enforced by construction rather than by a test); the ~60-line dated chronicle the section carried was deleted because it duplicated decisions #71-#79 (architecture.md 212 → 153 lines). (ii) **`InMemoryLibraryStoreTest`** — core-model's reference store had no test at all while every store-agnostic consumer is tested against it; five behaviours pinned (import order ≠ insertion order, replace-on-same-id, `contains` as the durable gate, delete + unknown-id no-op, `cachedBooks` spine-order flattening); no build change needed. (iii) The audit's `core-llm` item was already done (`LlamaTranslatorPromptTest` exists) and its `feature-ocr` item is **mis-scoped**: feature-ocr is a 75-LOC adapter with no failure typing of its own, core-ocr's seam exposes no failure type, and the typed-failure surface that DOES exist (the import pipeline's) is already covered by `IntakeRoutingTest`/`ImportLimitsTest`/`FolderScanPolicyTest`. (iv) **Unused-symbol inventory**: 18 candidates over 18 modules, with evidence per row and no unused files, vestigial parameters or dead test seams — see [the inventory](reviews/2026-09-15-unused-symbols-inventory.md). Twelve are one-line visibility/removal changes; SIX are deliberate keeps (test/device-pinned seams, and the sample-rate duplication decisions #77 records); FOUR are owner calls, of which one is a product question: the per-book voice override is READ in production and settable only from tests. Nothing is deleted without the owner's word. DEFERRED — coverage tooling + per-module floors: no Kover/JaCoCo is configured anywhere today, and per-module configuration is 18 build-file edits unless pass 6 lands first; its value is regression-ratcheting, not coverage. | **cheap half done; coverage deferred behind pass 6** |
| 5b — doc reconciliation | **done (2026-09-15).** Re-deriving every claim against the CODE (not against the audit row) found that passes 1-4 had already fixed most of the list: README's release claim is honest ("release-ready but not published"), `modules.md` is "as of #163" with Room v3 + both migrations, `conventions.md` documents the real `ktlintCheck` (1.7.2, no baseline), `architecture.md` already declares its graph "representative, not exhaustive" and names each module's `build.gradle.kts` + `checkFeatureBoundaries` as authoritative, and `agents.md`'s engine primacy is CORRECT (code: Kokoro PRIMARY, Piper PRIMARY, CosyVoice3 FALLBACK-gated). What the pass actually changed: README's test counts 785/743/42 → **816/773/43**, re-derived with `grep -rn '@Test\b'` (the word boundary is why `@TestInstance` is not counted; no commented-out annotations exist); and the approved disk hygiene, with ONE plan correction — the corpus moved to `core-tts/src/test/resources/` instead of `src/main/resources/`, because a main-source resource **ships inside the app** and this is dev-only evidence (`G0CorpusGen` resolves its output from whichever source root it found, so the task works from either CWD). | **done** |
| 6 — build convention | `build-logic` convention plugins: `compileSdk` re-typed ×10, `minSdk` ×9, Java 17 ×18, JUnit boilerplate ×17, and `spike-tts` has already diverged (minSdk 27) | **open** (only if the churn justifies it) |
| 8 — product-name alignment | Owner question 2026-09-15 ("should I rename the classes and repo to match the new product name?"). **Identity layer:** repo rename → `ayvu`, plus the FOUR repo URLs pinned in code in the same commit — `KokoroPacks`' espeak-ng bundle, `TranslatePacks.BASE`, and `AboutSection`'s source + NOTICE links — because those are SHA-pinned DOWNLOADS and a rename redirect must not become the failure mode; the `localtts-android` Docker image; README/docs references. **Code layer:** package `io.github.moronigranja.ayvu` → `io.github.moronigranja.ayvu` (one name everywhere — it is already the applicationId) across **396 files / 1,857 occurrences**, `AyvuApp` → `AyvuApp`, the manifest `android:name`, the app `namespace`. **One trap is silent:** `core-llm`'s three hand-written JNI symbols (`Java_io_github_moronigranja_ayvu_llm_LlamaTranslator_*`) — a stale symbol keeps the build GREEN and fails at runtime with `UnsatisfiedLinkError`, so the device `LfmTranslateE2eTest` is the acceptance gate. Two usual traps are absent today (verified): no exported Room schema JSONs and no R8 keep rules. **Deliberately NOT renamed:** the Room DB file `local-tts-reader.db` (on-device data, invisible to users, a file migration for zero gain), the upstream pack ids, the old applicationId + its migration, and the host pack cache `~/.cache/local-tts-reader/packs` (renaming re-downloads hundreds of MB). | **done** (#169) — package/namespace/class + repo identity landed; deliberately kept: the DB file, the host pack cache, the old applicationId and the upstream pack ids. |
| 7 — pre-release gate | belongs to the publish decision, not to cleanup: a **security pass over the five untrusted-input entry points** (zip/decompression bombs in EPUB and the backup archive, path traversal on restore, whether the pack registry's SHA-256 pin is enforced at install or advisory, JNI model-file paths); **licence/NOTICE completeness once bytes are distributed** (GPL-3 source offer, KindleUnpack-derived parser attribution, the CC-BY-NC voice gate); and **exercising the old-id → `io.github.moronigranja.ayvu` migration for real**. None of it is triggered until distribution — which is why it is cheap now and expensive after | **code + licence half discharged (2026-09-17, decisions #174).** The pack SHA-256 pin was already a hard install gate (not advisory) and JNI model paths are fixed internal paths. Fixed: restore zip-slip + archive ceilings + OOM + atomic sidecars; MOBI PalmDOC/HUFF-CDIC expansion ceilings and complete per-file OOM containment; `NOTICE.md` completed (KindleUnpack, llama.cpp, tess-two, the LFM pack) with in-code GPL provenance headers; `LICENSE` + `NOTICE.md` now ship **inside the APK** and render at About → Licences; the published espeak-ng archive carries its licence + source offer (re-pinned). No CC-BY-NC voice ships (the Korean piper voice stays unpinned). Cross-id restore verified: the backup codec carries no app id and reattaches by content-hash book id. **Remaining is mechanical** — the device smoke and the publish (which creates the `v0.1.1` tag) |

Two audit findings are deliberately NOT queued: `spike-tts` stays (load-bearing —
the ledger cites its measurements throughout — and it ships in nothing), and the
gitignored `docs/prints/` + `m/` scratch data on disk stays out of the repo's
concerns. Owner-approved disk hygiene LANDED in pass 5b: the ~29 GB of NMT corpora and staging now live at `../_ayvu-scratch/local-tts-reader/` (one rename away on the same filesystem) behind symlinks, so the documented paths and the tools' defaults keep working while the working copy holds 8 KB of `m/`.

## Shipped — reference only

The v1 spine:

| Legacy IDs | Delivered capability | Evidence |
|---|---|---|
| F1–F2 | Android/Hilt foundation and canonical domain model | README module inventory; decisions #1–#13 |
| C1–C7 | EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown import; segmentation; SAF library flow | decisions #10–#13, #29, #50, #170 |
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
| Piper en_US-lessac-medium | 0.57 | KEEP — adopted (#154/#159); passage-level read-along only (#30b) |
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
the fp32 Kokoro baseline belongs to whichever engine D5 adopts, and is recorded there —
**discharged with D5's closure (decisions #173); it re-opens only with a re-opened D5 or
the D7 gate-amendment decision.**

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
| SMaLL-100 int8 | RETIRED (#162) — was the translate-then-read engine (one 915 MB pack, 8.9–9.9 ms/token, chr-F 51.9–63.2); deleted with the LFM2.5 swap, no fallback |
| OPUS-MT per-pair | measured record; specialist alternative (tc-big int8 speed-disqualified; fp32 quality fallback) |
| LFM2.5-1.2B-Instruct | **SHIPPED as the read-in-language translator (#162)** — llama.cpp-android on S22: 22.3 tok/s, chrF 67.37, 730 MB, co-residency passed; LiteRT-LM deadlocks on the S22 (all 3 profiles), host-measured 6.4-6.5× slower — exception closed |
| LFM2.5-350M | REJECTED — chrF 59.11 below the retired incumbent (62.77); drops content, code-switches to English; there is no low-memory translator fallback (#162) |
| Gemma-4-E2B QAT / LFM2.5-2.6B-Base | measured ceiling, not selected — higher chrF (68.8/68.3) at 2.4-2.3× memory and slower on-device |

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
`PiperEngine` is registered as `piper-v1` (PRIMARY) with five pinned voices
(`en_US-lessac-medium`, `de_DE-thorsten-high`, and the #159 es/it/pt-BR pins)
downloading through the existing registry flow.
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
Remaining for this slice family: es/it/pt-BR pinned (decisions #159 —
`es_ES-davefx-medium` CC0, `it_IT-serena-medium` CC-BY-4.0, `pt_BR-faber-medium`
CC0, same revision); ko is deliberately unpinned (`ko_KR-kss-medium` is
CC-BY-NC-SA, awaiting an owner licensing call). The
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
([g0-findings.md](g0-findings.md)). **Listening pass COMPLETE (2026-09-15)**: the
Roman-language classification is closed — 14 confirmed classes before the 2026-09-15
continuation, 6 more from it (below), the wholesale verdicts (url-code, page-furniture —
pagination only, long-paragraph) and a recorded closure of the residual rows with no new
classes. ja/cmn/hi remain a recorded non-native-ear limitation. **G0's third acceptance
line is met: G1's built-in rule set is fully bounded by the typed findings.**

The confirmed classes, in the order the pass recorded them: `abbrev-not-expanded` (abbreviations
truncated or spelled letter-by-letter instead of the full word, cross-language, the
class that bounds G1's built-in set) plus its paired `abbrev-period-pause`,
`date-slash-read-aloud` (slash-dates spoken as numbers with the slash verbalized),
`measurement-unit-not-expanded` (unit symbols spelled letter-by-letter, en/it/pt
gaps; `km/h` also verbalizes the slash), `dialogue-quote-attribution-pause` (no
pause between a closing quote and its narration attribution),
`decade-trailing-s-read-literally` (decades read with "hundred" + dangling s),
`roman-numeral-read-with-label` (espeak-en/fr prepend a literal "roman"/"romain"
label), `footnote-reference-marker-read-aloud` (superscript footnote markers read
as numbers; the `filter_page_numbers`-family drop), and
`question-boundary-pause-short` (insufficient pause after `?` before the next
clause, sharing the boundary-pause family with the dialogue class),
`negative-sign-english-injection` (es/fr read the minus as an English "minus",
pt drops it), `currency-amount-misread` (English currency name before the
number + decimal read as "point N N" instead of "and <cents> cents" — owner
settled the "and" form), `decimal-comma-pause` (the decimal comma is
treated as a clause comma, inserting a pause inside numbers like "dois virgula
[gap] cinco"; the spoken separator word itself was accepted), and
`heading-title-colon-pause` (pause at the heading colon/em-dash too short before
the title; boundary-pause family). From the 2026-09-15 continuation of the pass:
`hyphen-read-as-dash` (a numeric range/score hyphen is spoken as "dash" — `2-1` →
"two dash one", `lines 8-19` → "eight dash nineteen"; expected "to"),
`decimal-period-pause` (a decimal point spoken as a clause break — `0.5%` → "zero
[break] five per cent", `99.5` → "ninety-nine [break] five" — while `3.14159` and
`12.50` in the same corpus say "point" correctly, so the fix is the separator's
punctuation role, not the word), and `roman-numeral-regnal-not-ordinal` (regnal
numerals read ordinally — "Isabel segunda", not "Isabel dois"; **boundary settled
2026-09-15: ordinals from I to X, cardinals from XI onward, identical for pt and
es**; it ordinalizes throughout per its own convention and needs no rule) — plus a
**scope extension** to the
existing `measurement-unit-not-expanded`: espeak-es/fr also spell single-letter
metric symbols and imperial abbreviations as letters (`8 m` → "ocho eme"), so the
G1 dictionary needs all six Latin-script languages, not en/it/pt — and the pt-br
currency rule (`R$` expands to "real dólar", owner: "deveria ser 'reais' somente",
100% of `R$` uses; needs number agreement, so it is value-dependent and pt-only, since
es/fr/it already say the currency after the amount).

Verified clean
the same day (page-furniture's verdict covers pagination and boundaries only — its
numeric ranges carry the new `hyphen-read-as-dash` class): page-furniture,
long-paragraph, url-code (owner-accepted as-is), the decimal comma (owner withdrew
it — the Spanish "coma" convention stands), and `&&` (accepted as-is). All
owner-calls resolved: url-code, `&&` accepted as-is; `e.g.`/`i.e.` expand to
"for example"/"that is" (folded into `abbrev-not-expanded`). ja/cmn/hi
are recorded as a non-native-ear limitation.

**Gates:** G1's built-in rule set (bounded by the typed findings) and the
pt-BR blind read (decides whether the *translated* render is acceptable in the
read-in-language slice — written for SMaLL-100, which #162 deleted for
LFM2.5-1.2B on llama.cpp, so the read now targets
the shipped translator). D5's engine choice was the third gate —
**discharged by owner decision (decisions #173): D5 closed, the clone class
deferred, no engine A/B needed.** The next action was human, not code: the owner's
listening pass over the Roman-language classes; with the class deferred, the
listening material (G1 batches 1–7) is already ear-passed.

#### G1 — TTS pronunciation replacements — BUILT-IN SET COMPLETE (bounded by G0; candidates unshipped)

Add a deterministic, testable normalization/replacement stage before phonemization for
names, honorifics, abbreviations, pauses and intentionally skipped page furniture. The
reported `Ms.` → "M S" defect was the first regression case, already shipped
(`PronunciationNormalizer` + `NormalizingPhonemizer`, `Ms.` → `Miz`, 2026-08-29). The
remaining built-in correction set is bounded by G0's typed findings.

**Landed and EAR-VERIFIED on the S22 (2026-09-15):** `abbrev-not-expanded` +
`abbrev-period-pause` (ordered per-language abbreviations/honorifics, consuming the
trailing period, with the article-driven feminine forms and the `M.`/`D.` name guards),
`measurement-unit-not-expanded` + the separator family (`decimal-period-pause`,
`decimal-comma-pause`, the thousands separator), the footnote markers, the minus sign,
the hyphen range, `decade-trailing-s-read-literally`, `roman-numeral-read-with-label`
(with the pt/es regnal ordinals to X), `currency-amount-misread` (en) and
`pt-br-currency-real-read-with-dollar` — owner-passed in five batches, each rendered as
before/after WAV pairs through `G1ListeningHarnessTest`.

**Batch 5 — dates — EAR-VERIFIED on the S22 (2026-09-15).** The pass flipped one
case: the "fraction guard" control proved to be a defect of its own, so fractions
became a class (owner: "3/4 cups of sugar … should be three fourths of a cup of
sugar. Same for 1/2 being half"). `date-slash-read-aloud` is rewritten per locale:
the corpus's own pairs settle the day/month order (every row reads `3/4/2024` as
"4 March 2024", i.e. month-first) and a figure that cannot be a month settles the
rest by arithmetic (`25/12` → 25 December). The spoken shape is per locale (en-us
"March 4th, 2024", en-gb "4 March 2024", es/pt "D de <month> de Y", fr/it "D
<month> Y"). The day/month pair requires a date preposition (`el 25/12`) because a
bare `3/4` is a fraction far more often than a date; a four-digit year needs no
context. **Region-scoped rules** exist for exactly this case (the English date
shape is the only rule that differs by region, so `regionRules` applies before the
base list rather than shipping two English lists).

**Batch 6 — fractions — EAR-VERIFIED on the S22 (2026-09-15, "batch 6 is
fine").** `fraction-read-as-slash` (owner-reported, see
[g0-findings.md](g0-findings.md)): a proper fraction becomes words, the following
partitive is kept as-is ("3/4 of a cup" never doubles it), a following measure noun
is absorbed and singularized for English ("3/4 cups" → "three fourths of a cup",
"1/2 cup" → "half a cup"), and the Romance languages use their invariant halves
("la mitad de taza" rather than a gender-dependent "media taza"). Ratios and scores
(`2/1`, `5/2`) are never expanded.

**Batch 7 — the clause-boundary pause family — EAR-VERIFIED on the S22
(2026-09-15, "ok, not perfect but passable"), with the reservation recorded rather
than smoothed over:** the guillemet dialogue pairs did not gain measurable pause
time on the device (the pause there comes from Kokoro's duration prediction over
espeak's phoneme markers, not from espeak's own synthesis), while the headings,
the question beat and the English dialogue did. **Recorded lever, not
implemented:** the em-dash transfers on the device where the period does not (the
question pairs are the proof: +600 ms en, +145 ms es), so a future pass can swap
the guillemet dialogue rule's period for an em-dash — one line per rule, and one
more ear round. It is deliberately NOT done now: it would invalidate this ear
verification without the owner asking for it. The mechanism is the owner's call: *insert a
punctuation mark*, never reach into the audio path. The mark was then chosen by
measurement on the shipped engine (espeak-ng 1.52.0, en-us: clause comma 269 ms,
semicolon 349, colon 359, period 429), so `dialogue-quote-attribution-pause` and
`heading-title-colon-pause` become **periods** and `question-boundary-pause-short`
takes an **em-dash** (409 ms; a "?" alone already measures 299, so the missing beat
was never the question mark). All three are punctuation-shape rules in `common`,
and every transformation leaves the words untouched.

The mark alone was not sufficient and the first render proved it: a period placed
OUTSIDE a closing quote (`«Cuidado». dijo él.`) leaves the pause SHORTER than the
comma it replaced (110 ms against 165), because espeak breaks on `." he said`
(434 ms en / 409 ms es·fr·it·pt) and not on `". he said`. Every dialogue rule
therefore puts the period INSIDE the closing quote, and the French form keeps its
space there (`« Attention. »`). This is the class's real content: the pause depends
on the punctuation's *position* relative to the quote, not just on its kind.

Verification is three-layered: per-rule pure tests with boundary negatives, a
**real-espeak oracle** (the corpus rows must render exactly as their expected spoken form
— espeak-ng 1.52.0 ships on the host; the date rows assert the pipeline renders the
corpus's own spelled-out half byte-for-byte), and the **device listening harness**
(`G1ListeningHarnessTest`), which renders each case twice — raw espeak vs the production
pipeline — for the owner's ear.

**Five constraints this work established (all bit us):**

- **Rules run under Android's ICU regex engine, not the host's.** ICU rejects a
  lookbehind of unbounded length, so `(?<!\p{Lu}\p{Ll}+\s)` compiled and passed on the
  host and threw `PatternSyntaxException` at class load on the device. Keep lookbehinds
  bounded; `PronunciationNormalizer.patternSources` exists so the suite asserts it.
- **`(?i)` is not the same flag on both engines.** Java folds ASCII only; ICU folds
  Unicode. A case-insensitive pattern containing an accented word (`capítulo`, `até`,
  `dès`) therefore MATCHED on the device and did not on the host, where the test suite
  lives — the divergence is invisible until a device run. Accented letters in a
  case-insensitive pattern are written as explicit classes (`cap[\u00ed\u00cd]tulo`).
- **The device harness needs BOTH APKs installed** — instrumented tests load production
  classes from the target app APK, so reinstalling only the androidTest APK silently
  renders the OLD behaviour. The harness now fails fast on a rules-present guard, and its
  A/B assertion is on phonemes (deterministic) rather than PCM (fp32 ORT is not
  bit-reproducible, which is how a stale run once passed).
- **Rules interfere with each other, so a rule's guard must be the unit's own shape.**
  The `st` (stone) measurement rule bit the ordinal suffix of "21st" and said
  "twenty-one stone"; it was invisible until the date rule started producing ordinals.
  Both the unit and the ordinal are written after a figure, so the discriminating
  property is the space (`21st` attached = ordinal, `8 st` spaced = stone) — now
  `unitSpaced`, pinned by a test. Assume the next batch finds another cross-rule pair.
- **Rule tables are built eagerly, so declaration order is a behaviour.** `byLanguage`
  constructs its rules at object initialization, which means any table a rule reads must
  be declared ABOVE it: `headingWords` moved below the list and the pattern silently
  became `…null…` (no match, no error, no crash). Rules read tables; tables precede.

**Status: every class G0 typed is landed and EAR-VERIFIED — batches 1-7, the
third acceptance line of G0 satisfied.** `g0-findings.md` carries the per-class
record (rule, scope, expectation, the owner's verdict, and the measured evidence).
This also discharges the gate G1 held over D5 and over the remaining
`PlaybackService` collaborator extraction (MediaSession / notification /
audio-focus / coverage / probes), which is gated on D5 + G1 — both now free.

**Still unshipped:** the *candidate* pause rules the peer probe proposed
(parenthetical clauses, dash pauses, dropping standalone page-number lines). None
was confirmed as a G0 class, so none ships without the owner's ear first — the
same disposition `lowercase-roman-garbled` got (owner-accepted as-is, no rule).
A future pass may also lift the em-dash lever recorded under batch 7.

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

### Sequenced after G0 — D5 high-end engine choice — **CLOSED**

**Owner decision (2026-09-16, decisions #173): D5 is closed and the whole
voice-clone engine class is deferred for the app.** Every candidate fails the
run axis on the S22 as measured: Pocket TTS RTF 1.5–1.8 / PSS 0.86–0.92 GB
(best of the three, still ~2× Kokoro fp32), Chatterbox q4 RTF ~16.7 with a
~600-frame context wall (process killed under swap pressure), CosyVoice3
12.5–31.1 RTF at 3.22 GB VmHWM (#93) with its duplicated-honorific quality
flag. None clears the runtime+memory bar for on-device cloned-voice pregen;
Kokoro + Piper remain the shipped voices. The G0 blind read and the ORT-int4
reference leg discharge with the closure (an int4-vs-fp32 Kokoro reference
would belong to a re-opened D5 or the D7-gated gate amendment). The
measurements and the §3.6 blind-set tooling below stand as the reference
record for any future revisit; nothing in the class ships.

#### D5 — High-end cloning: Chatterbox vs CosyVoice3 vs Pocket TTS (CLOSED — reference)

- Candidates: **CosyVoice3** (incumbent — 9 langs incl. es/it, zero-shot + cross-lingual
  cloning, pinned pack, measured 3.22 GB VmHWM on the S22) vs **Chatterbox Multilingual
  ONNX** (MIT, 23 langs incl. es/it/pt/de/ko, zero-shot cloning, 0.5B AR Llama backbone)
  vs **Pocket TTS** (Kyutai; MIT code, CC-BY-4.0 weights, **100M params / ~176 MB**,
  en/de/fr/it/pt/es, zero-shot cloning, native streaming — added 2026-09-11, decisions
  #149. It is the only candidate that could clone *inside* a phone's live budget instead
  of pregen-only: ~20× smaller than the other two, and already shipped on Android by
  NekoSpeak as five ORT sessions).
  **Chatterbox measured on the S22 (2026-09-16, decisions #172): DROP-level.** RTF ~16.7
  at 2 threads (q4 kernels never reach efficient ARM paths), PSS 1.69 GB, and the process
  is killed past ~600 context frames — utterance-scale only, dead for book pregen on this
  device class; long-form renders are host-only. Pocket TTS stands as the D5 pregen
  candidate; CosyVoice3 renders remain pending.
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
  better Pocket config on this device (~5% faster, 15% less memory). **S22 leg measured
  (2026-09-16, decisions #171; corrected same day — the first leg's renders were
  degenerate, the BOS conditioning embedding was dropped until the owner's ear
  flagged them): RTF 1.53–1.81 @ 2/4 threads (2.06–2.57 @ 6 — oversubscribed),
  PSS 0.86–0.92 GB, cold open ~0.6 s — the live-cloning answer is NO on both
  devices and Pocket TTS is a pregen candidate at RTF ~2× the fp32 Kokoro
  baseline on the S22; its native output is quiet (-30 dBFS, gain is an
  integration item). **CLOSED with the class (decisions #173); the Fold leg and
  the G0 blind read were discharged by the owner's decision to defer clone
  engines — no render A/B or second-device run was needed for a class that
  does not ship.**

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
| Translate-then-read (`core-translate`) | **LANDED 2026-09-14, decisions #160; ENGINE SWAPPED 2026-09-15, decisions #162** — implemented end-to-end and DEVICE-VERIFIED on the S22 under SMaLL-100 (pt-BR playback under the auto-picked voice at 121–1352 ms per passage, `x<lang>` cache separation incl. the Off toggle, offline pregen under translation, idle-close logcat-verified; three defects found and fixed: firstVoiceFor case mismatch, target-voice pack-readiness gate, render-truth cache keys). The translator is now **LFM2.5-1.2B-Instruct on llama.cpp** (`:core-llm`, #162): chrF 67.37 vs 62.77, ~730 MB pack, `pregen` keys carry a `t<translator>` segment, SMaLL-100 deleted with no fallback. **DEVICE-VERIFIED 2026-09-15, instrumentation + UI pass** (`LfmTranslateE2eTest`, `LfmTranslatedPlaybackE2eTest`, then the S22's own screens): sha-verified download through the Read-in dialog, Speech subscreen showing "LFM2.5-1.2B translate model — ready · installed", pt-BR playback on a real book at 2.2–4.1 s/passage under concurrent Kokoro synthesis, renders cached under `xpt-BR/tlfm12b/` (pre-#162 small-100 audio correctly not a hit), retired small-100 artifacts reclaimed, zero lmkd kills. Remaining: a full whole-book pre-gen run under the new translator (~2 h on Jumper) and the HiBreak co-residency check (not attached). |
| High-end cloned-voice pre-generation (engine chosen by D5) | **DEFERRED with the class (decisions #173).** D5 is closed and no clone engine ships for the app — measured on the S22: Pocket RTF 1.5–1.8 / PSS 0.86–0.92 GB (best of the three), Chatterbox ~16.7 + a ~600-frame context wall, CosyVoice3 12.5–31.1 @ 3.22 GB VmHWM with a quality flag (#93). The row re-opens only with a candidate that clears the runtime+memory bar. |
| Kindle official export/API sync | External API/export contract and account UX; manual share/resume already covers the core use case. |
| Word-level highlighting | Requires a stable word/phoneme timing contract beyond current sentence anchors. |
| Auto language detection and voice routing | Needs per-language voice mappings, mixed-language policy and pack-availability UX. The manual single-book case is covered earlier by Phase K item 5 (per-book voice, decisions #144). |
| App UI localization (every language the TTS engines serve) | Scope, not a dependency: the UI is English-only today (one 3-line `strings.xml`; Compose strings are hardcoded inline), so this needs a full string-extraction pass to `res/values/strings.xml` before any translation. Target locales = the union of engine languages — en, de, es, fr, it, pt, ja, zh, hi (Kokoro ∪ Piper; ko pending its CC-BY-NC licensing call, decisions #159). Two decisions before code: (1) does the UI locale follow the system locale, the narration language, or a separate app-language setting; (2) who maintains the translations — offline-first with no hosted service, so reviewed catalogs committed per locale, not machine-translated at runtime. |
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
ceilings on the EPUB/KF8 path plus OOM containment at the per-file parse boundary.

Further closed 2026-09-17 (decisions #174): the MOBI `HuffCdic`/`PalmDoc` expansion
ceilings (per-record + book-wide, plus the CDIC phrase-table bound) and complete
per-file OOM containment on the import pipeline; the backup archive's own limits
(archive/entry/entry-count/cumulative/manifest ceilings, duplicate-entry rejection,
typed OOM) and restore path traversal; sidecar writes are now temp-file + rename, so
a failed restore leaves no partial file.

Still open: pack-archive limits (the downloaded zips extract through the staged
`canonicalPath` zip-slip guard but have no expanded-size ceiling of their own) and
disk-full behaviour during pre-generation.

### Release readiness

Distribution decision made (decisions #126, 2026-09-05): **GitHub Releases signed APK,
manual local signing** — CI stays a gate (tag assemble only). The local signing pipeline
ships: release keystore outside the repo, gitignored `keystore.properties`, unminified
`release` buildType, `tools/release.sh` (build + apksigner verify + draft/publish
release), `NOTICE.md` attribution.

**v0.1.1 is not published.** The artifact is prepared at HEAD: the signed release build is
**arm64-v8a only** (the espeak-ng phonemizer is an arm64 native library, so the other
ABIs' libs were ~114 MB of dead weight and would have installed a non-functional app) —
165.2 MB → ≈52 MB payload, the notes carry the SHA-256 and the signing-certificate
fingerprint, the import path has resource ceilings + OOM containment, and (decisions #174,
2026-09-17) the release gate closed: restore hardening, MOBI decompression ceilings,
`NOTICE.md` completed and `LICENSE` + `NOTICE.md` shipped inside the APK, the espeak-ng
archive carrying its own licence + source offer.

Remaining before publishing, in order:

1. **Device smoke on the signed 0.1.1 APK** — owed: no device was attached during the
   release-gate pass. Re-build at HEAD first (`tools/release.sh`), then install and
   exercise the reader/player on the S22.
2. **Refresh the notes' SHA-256** from the `tools/release.sh` output for the exact
   artifact being uploaded (the value in `docs/release-notes-0.1.1.md` is from the
   previous build), then
3. **Publish:** `tools/release.sh --upload --publish --notes docs/release-notes-0.1.1.md`
   (the script defaults to **draft** — `--publish` is required). Publishing creates tag
   `v0.1.1`, which fires the CI `assemble-on-tag` gate and satisfies the GPL source offer
   the notes make.

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
- **`SettingsViewModelTest` is flaky** (observed 2026-09-17 during the 0.1.1 device
  pass): `setTtsThreads is observed by the state immediately()` failed once with
  `kotlinx.coroutines.CompletionHandlerException` in a cancellation handler and passed on
  re-run of the same class. Same disease as the review's flakiness surface (real
  coroutines/clock in a host test); needs a deterministic seam, not a longer wait.

The A1/A2/A4/A5–A7/A6/F2 device-evidence rows and the ktlint gate are all closed
(decisions #105, 2026-08-31/09-01); B4/C2 device checks are complete (decisions
#98, #105).
