# Open bugs & known limitations

Register of reported-but-unfixed bugs and known limitations, as of 2026-08-27.
Each entry names where it was reported and its current status; entries are
removed (or marked fixed) when a fix lands. This is a tracking doc, not a work
plan — slicing happens through decisions.md.

## Product bugs

| Bug | Symptoms / impact | Status | Reported in |
|---|---|---|---|
| ~~**`assemble-on-tag` CI gate could never fire**~~ | The `assemble-on-tag` job is conditioned on `startsWith(github.ref, 'refs/tags/')`, but the workflow's `on.push` listed only `branches: [main]` and no `tags:` — a tag push never started the workflow at all, so the gate the release docs relied on ("publishing creates tag `v0.1.1`, which fires the CI `assemble-on-tag` gate") was unreachable. Impact: the release pipeline shipped with a verification step that did not exist. | Fixed — 2026-09-17 while publishing 0.1.1: `on.push.tags: ["v*"]` added (pack tags stay out — they ship no app artifact); the gate was then actually exercised by a dispatch on the `v0.1.1` tag. | release publish 2026-09-17; decisions #126/#128 claim vs `.github/workflows/ci.yml` |
| ~~**Backup-complete dialog shows two "OK" buttons**~~ | A successful export ended in a "Backup complete" dialog with **two identical OK buttons** side by side. `BackupSection` passed `confirmLabel = "OK"` *and* `dismissLabel = "OK"` (both wired to `consumeResult`) because `ConfirmDialog` always rendered a dismiss action — a single-action acknowledgement could not be expressed by the shared component. Cosmetic/confusing; both buttons did the same thing. | Fixed — 2026-09-17: `ConfirmDialog.dismissLabel` is now `String?` (null renders no dismiss button) and the backup result passes `null`. Device-verified on the signed 0.1.1 build (one OK). | owner report during the 0.1.1 device smoke, 2026-09-17; decisions #174 |
| ~~**LSTM traineddata unusable: OCR capped at legacy accuracy**~~ | `tessdata_fast 4.0.0` LSTM models fail `init` on tess-two 9.1.0 (native build is pre-LSTM), so the pinned packs were the legacy `3.04.00` artifacts and OCR accuracy sat below what current Tesseract LSTM models give. | **Fixed 2026-09-20 (decisions #186); arm64 device gate closed 2026-09-21 (decisions #190)**: binding moved to Tesseract4Android 4.9.0 (Tesseract 5.5.1, LSTM-only) and packs re-pinned to `tessdata_fast` 4.1.0 (real sizes/SHAs measured on the host; eng 4.1 MB vs 21.9 MB). Staged data is versioned by tier+release so no legacy model can be read; the retired generation (staged dir + pack cache) is reclaimed on the next staging run. Verified on the x86_64 emulator 2026-09-20: the settings download SHA-verified and staged the pack (marker `verified:7d4322bd…`), the retired generation was reclaimed, `OcrSmokeInstrumentedTest` + `SharePipelineInstrumentedTest` pass, and logcat shows `tesseract 5.5.1 engineMode=1` (LSTM-only). **Arm64 gate run 2026-09-21 on the S22 (SM-S908U1)**: the `0.1.3` debug build (versionCode 4) installed over the debug build, the pinned eng artifact staged at `files/tesseract/fast-4.1.0/tessdata/` (device sha256 `7d4322bd…`), `OcrSmokeInstrumentedTest` (1) and `SharePipelineInstrumentedTest` (2) green in their own invocations; logcat shows `lib/arm64/libleptonica.so` + `lib/arm64/libtesseract.so` loading, `Initialized Tesseract API with language=eng`, and `AyvuOcr: tesseract 5.5.1 engineMode=1 … under …/files/tesseract/fast-4.1.0` (`docs/prints/match-sweep/ocr-arm64-gate.log`). NOT re-run on arm64 because it is architecture-independent (its emulator pass stands): the settings download's SHA-verify/staging-marker path and the retired-generation reclaim — pure `File` logic pinned by `TessDataStagerTest`. | #186; supersedes #36/#37; arm64 gate #190 |
| **Opus audio tier impossible via MediaCodec on the S22** | MediaCodec opus DECODER is broken at the native level (every stream/decoder errors; async path SIGSEGVs inside the codec memcpy); the ENCODER emits non-conformant payloads (83-byte csd-0; #50 falsified the "maybe savable" read — reference libopus refuses the stream). An Opus cache would require bundled libopus for encode AND decode. | Open — decided "not a dependency"; PCM cache (≈170 MB/h) stays | decisions #46, #50 |
| ~~**ktlintCheck green-goal unmet**~~ | `./gradlew ktlintCheck` is part of the definition of done but no lint plugin is configured — the check doesn't exist yet. | Fixed — 2026-08-29: baseline-gated `ktlintCheck` task (ktlint 1.7.2, committed `.ktlint-baseline.xml` of 3,643 pre-existing violations; fails only on new ones; CI `jvm-tests` lane runs it) | `conventions.md` (definition of done) |
| ~~**Library search not built**~~ | ~~`feature-library` has no search UI; the index/matcher core (core-locate) is live and rebuilt at launch, the surface is not.~~ | Fixed — 2026-08-29, F2 (decisions #90): local title/author filter over Room rows with `EmptyState` no-result state; host-tested, runtime device verification tracked in the roadmap debt register | `modules.md` (feature-library row), decisions #90 |
| ~~**CosyVoice3 bundle URLs not pinned in the repo**~~ | The #49 on-device run staged models ad-hoc (HF snapshot `jiangzhuo9357/cosyvoice3-0.5b-onnx` + locally derived prompt wavs); the repo carries no URL/hash for the bundle, so the fallback tier isn't reproducible from the repo. | Fixed — 2026-08-29: `docs/cosyvoice3-pack.md` pins revision `d1b9350` + a 26-file manifest (real sha256) + ffmpeg prompt derivations + staging commands | decisions #49 |
| **Android Auto media controls unverified on device** | T4 acceptance lists "Auto verify" (MediaSession-based controls on Android Auto); no device-pass evidence is recorded yet. | Open — verification pending | `roadmap.md` T4 row, ideas.md |
| ~~**No progress feedback during book import**~~ | ~~A SAF import batch shows no in-flight progress or cancellation — the "Added N · Unchanged M" summary appears only after completion, so large/multi-file imports look hung.~~ | Fixed — F1, 2026-08-27 (decisions #64) | user report 2026-08-27; `roadmap.md` F1 |
| ~~**"Ms." read as "M S" (spelled out)**~~ | TTS narrates the honorific as two letters instead of "miz". The segmentation layer is abbreviation-safe ("Dr."/"e.g.") for sentence boundaries, but phonemization still spells the title — a G2P/normalization gap on the synthesis path, not segmentation. Impact: titles/honorifics misread in narration. | Fixed — 2026-08-29: `PronunciationNormalizer` + `NormalizingPhonemizer` rewrite the spoken form only (`Ms.` → `Miz`, host-verified `mɪz`); first roadmap G1 rule, unit-tested | user report; ideas.md "TTS pronunciation replacements" (Librera) |
| ~~**CR-1: whole-book manual pre-generation is a false-success no-op**~~ | ~~Choosing “Whole book” opens the engine, skips every book before `OfflinePregen.run`, and reports successful work without adding audio. Finite 30 min–3 h budgets take a different path.~~ | Fixed — A1, 2026-08-27 (decisions #60; record below) | code review 2026-08-27; decisions #42/#50 |
| ~~**CR-2: STOP and service teardown lose the live intra-passage playhead**~~ | ~~STOP releases `PassageOutput` before sampling it, so persistence rewinds to the offset at which the current buffer started rather than the actual playhead.~~ | Fixed — A2, 2026-08-27 (decisions #61; record below) | code review 2026-08-27; T4 progress contract |
| ~~**CR-3: Room library state and `TextIndex` can diverge**~~ | ~~Index mutation happens before Room import/delete commits and startup rebuild is unsynchronized. Persistence failure or a cold-start race can leave a visible book unsearchable, or an indexed book absent from the library.~~ | Fixed — A3, 2026-08-27 (decisions #65; record below) | code review 2026-08-27; P1/P2 import⇒index contract |
| ~~**CR-4: PCM cache LRU state is lost across process restart**~~ | ~~Existing disk entries are absent from the in-memory eviction map. Near the 4 GiB cap, every newly synthesized passage can evict itself while old entries remain, effectively freezing cache replacement.~~ | Fixed — A4, 2026-08-27 (decisions #63; record below) | code review 2026-08-27; decisions #35/#42/#44 |
| ~~**CR-5: asynchronous book commands can overwrite newer player state**~~ | ~~`ACTION_OPEN`/`ACTION_PLAY` load books in untracked coroutines. `stopEverything()` cannot cancel them; an older load may publish after a newer command or remove its foreground notification.~~ | Fixed — A5, 2026-08-27 (decisions #62; record below) | code review 2026-08-27; T4 single-writer contract |
| ~~**CR-6: feature-module dependency rules are no longer enforced**~~ | ~~Feature modules depend directly on other feature modules and concrete Android/persistence implementations, while `feature-library` owns application-wide DI. `app` is not the effective composition root described by the architecture.~~ | Fixed — A6, 2026-08-27 (decisions #66; record below) | code review 2026-08-27; `architecture.md` §2 |
| ~~**CR-8: read-along anchors + live playhead never published (`PlaybackUiState.segments`/`offsetSeconds` dead)**~~ | `3e01cd3` (decisions #51 gap pass) collaterally dropped `segments = segments,` and `offsetSeconds = liveOffsetSeconds(),` from `PlaybackService.publish()` while rewriting the reader footer. `activeSentenceIndex`/`offsetSeconds`/`segments` stayed in the state contract but no path ever set them — the read-along highlight and passage progress bar are unobservable. Surfaced by the E2E regression: `PlaybackE2eTest` "read-along anchors surfaced somewhere" failed every run. | Fixed — on-device E2E run 2026-08-27 (both publishes restored + rerun-safe E2E; record below) | device E2E 2026-08-27; decisions #31/#34 |
| ~~**CR-9: chapter text never published (`PlaybackUiState.chapterPassages` dead — reader body blank, touch nav dead)**~~ | The `26a3272` CR-8 fix collaterally dropped `chapterPassages = position?.let { p -> book?.chapters?.firstOrNull { it.index == p.chapterIndex }?.passages?.map { it.text } } ?: emptyList()` from `PlaybackService.publish()`'s `it.copy(...)` block while inserting the two restored publishes (same class of `copy-move` regression that hit `segments`/`offsetSeconds` in `3e01cd3`). `chapterPassages` stayed in the `PlaybackUiState` contract and `PaginatedChapter` reads it, but no path wrote it — the reader body went blank on every book, side-tap page turns and the empty-state guard stopped rendering. | Fixed — on-device repro 2026-08-27 (chapterPassages restore; record below) | device repro 2026-08-27; decisions #51 follow-up |
| ~~**CR-8/CR-9 family: chapter titles never published (`PlaybackUiState.chapters` dead — chapter selector/title dead)**~~ | The CR-9 fix `3bc2057` replaced `chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()` in `PlaybackService.publish()`'s `it.copy(...)` with the `chapterPassages` block — same copy-move class of regression as CR-8/CR-9. `chapters` stayed in the contract but no path wrote it; the reader's chapter selector (`enabled = state.bookId != null && state.chapters.isNotEmpty()`), "Ch X/Y" label, chapter menu and top-bar chapter title were dead. | Fixed — PR-0, 2026-08-28 (decisions #72; guard test prevents recurrence; record below) | code trace 2026-08-28; `docs/generate-play-lean-up.md` QW1 |
| ~~**Reader bottom line cropped under the shared player card**~~ | On the reader, the last text line is clipped at the boundary with the bottom transport card — the visible slice ends mid-line ("…He") with no way to scroll the remainder above the card. Evidence: `docs/prints/reader-bottom-crop.png`, `reader-bottom-crop-2.png`. | Fixed — B3, 2026-08-29: `lineHeightPx` is ceiled (was truncated, over-counting one line) and the chapter body is measured with the active-sentence bold span so pagination matches the rendered text; the last line now fits fully above the card. Device visual re-verification done in B4 at 1.0×/1.3×/2.0× font scale, S22 (decisions #87) | user report + device run 2026-08-27 |
| ~~**Reader bottom crop residual (item 2)**~~ | The #29 fix still cropped the last line at some OEM font scales: the footer reserve measured the PROXY "Page 999 of 999" and floored the line count over the measured footer, so a real footer wider/taller than the proxy or a paint-scale drift re-opened the crop. | Fixed — item 2, 2026-09-04: the reserve proxy is the WIDEST renderable footer and the reserve is conservative — the measured footer height plus one line of slack (`bottomReservePx = indicatorReservedPx + lineHeightPx`, one line lost on the last page beats a clipped line) with a `TextPaginationTest` boundary regression locking the floor. device-verified in the S22 session 2026-09-05 at 1.0×/2.0× (last line fully visible, clear gap above the indicator, no overlap with the player card) | open-bugs #29 residual; plan-reader-immersive-setup item 2 |

| ~~**Immersive top-cut under the floating title overlay**~~ | In full-screen mode the top of the text was cut “sometimes” — on a NEW CHAPTER the large chapter title's glyph tops were hidden under the translucent book-title overlay band (the plan's “overlay enters no reservedPx” left page content starting at the body top). | Fixed — 2026-09-05 (decisions #125): immersive now reserves the measured band height in pagination AND offsets the body content down by it (tap math updated); transition frames also hold chrome through the bar fade (5c0a7cb). Verified on S22: chapter-start + mid-chapter pages, first body row ≥ y200, heading fully rendered at 1.0×/2.0× | device report 2026-09-05; decisions #125 |
| ~~**PlaybackE2eTest/PregenE2eTest device failures (pre-existing fixture geometry)**~~ | ~~`PlaybackE2eTest.playsThroughTheBookAndCompletes` times out: the 90 s test window is consumed by the 60 s `PLAY_BUFFER_TIMEOUT_MS` wait on a 2-passage book that can never reach `PREFILL_LOOKAHEAD_SECONDS = 45` ahead. `PregenE2eTest.workerPregensTheBookThenPlaybackCompletesOverTheCache` can flake: the worker reads the shared Room DB via the app singleton and can see the fixture book missing (WAL/instance race), settling SUCCESS with an empty cache.~~ Not regressions of the immersive plan; surfaced during its device pass. | Fixed — 2026-09-10 (decisions #141, commit c9c43fb): (1) PlaybackE2E window 90 s → 180 s (the cold open's unreachable 45 s target burns the full 60 s buffer wait once; 90 s sat on the edge). (2) PregenE2E writes its fixture through the app's Hilt singleton `RoomLibraryStore` — the exact instance the worker reads — removing the second-Room-instance race by construction (a stale-success `doWork()` path was found on the way: a run for requested-but-absent books settled SUCCESS; now fails with a typed error, CR-1 class, host-regression-tested). PregenE2E teardown also removes the fixture row from the live library. **Device-verified same day**: both tests green on the S22 (SM-S908U1, Android 16) via `am instrument` after staging the pinned packs — 185 s total, confirming the 90 s window was genuinely too tight. | device report 2026-09-08; this session 2026-09-10 |
| ~~**Chapter menu doubles the ordinal ("1. 1. Millie…")**~~ | `ReaderScreen` chapter dropdown prefixes `${index + 1}.` to chapter titles that already carry their own ordinal (Impulse navPoint labels "1. Millie: The Underlying Problem"), rendering "1. 1. Millie: …". Evidence: `docs/prints/reader-chapter-menu-doubled-ordinal.png`. | Fixed — B3, 2026-08-29: `chapterMenuLabel` (core-player) renders numbered titles as-is; unlabeled titles gain the dense prefix. Unit-tested | code trace + device run 2026-08-27 |
| ~~**Room DB absent for hours after reinstall, then an empty file appeared; library rendered books from non-Room state**~~ | The 08-29 observation was a path-scoping illusion: Room's `getDatabasePath()` resolves to `<data-root>/databases/`, never `files/databases/` — the `files/`-scoped `find` could not see the live DB. The library always rendered from Room. The "deletion" was our own instrumentation: 8/10 E2E teardowns called `context.deleteDatabase` on the production DB of the same package (#42 class); the 68 B skeleton is the still-running singleton re-creating the file post-delete. Samsung install-time handling excluded: `adb install -r` left DB+WAL SHA-256 byte-identical (S22, 2026-09-01). Fixed — decisions #107: the six remaining `deleteDatabase` teardowns removed (1df3a57) | decisions #88 trace + A8 device session 2026-09-01 |

| ~~**Offline-audio usage row is stale on return to a live Settings screen**~~ | The "Offline audio" per-book rows read `PregenStorage.usageByBook()` once in `SettingsViewModel.init` (and after each delete), so an in-place return to a still-live Settings instance shows the payload as it was when the screen was first created — a book pre-generated in between renders as an empty tier. Surfaced by the Phase K item-3 device pass: the row only refreshed after the settings screen recreated its ViewModel. | Fixed — 2026-09-10, release 0.1.1 prep (commit 7c505ec): the screen re-reads the disk tier on every `ON_RESUME` (`SettingsScreen`'s `LifecycleEventEffect` → `SettingsViewModel.refreshOfflineUsage()`), idempotent, no polling; `SettingsOfflineUsageTest` pins the publish (a tier change behind a live instance is reflected after the refresh). Route note: fixed during the 0.1.1 release pass, before the Phase K item-6 sweep — docs (roadmap status, decisions #156) closed the loop on 2026-09-13 | decisions #142 (device note), #144 |

| ~~**Reader shows the empty-state placeholder after screen off/on (intermittent)**~~ | After turning the display off and back on, the reader sometimes shows "Tap play to start listening from this book." — the empty branch `chapterPassages.isEmpty() && phase == PlayerPhase.IDLE` (ReaderScreen) — instead of the book text; the reading place is gone. Root cause: the reader's `LaunchedEffect(bookId, startAt)` only re-opened when `bookId` changed or `startAt != null`, so a service-state reset or process death underneath (`PlaybackStateHolder.reset()` / machine teardown — e.g. LMK while the screen is off) left the reader on the empty branch with no recovery. | Fixed — 2026-09-08 (decisions #130): re-open now keys on `(bookId, state.bookId)` and restores from the persisted resume row whenever the service state diverges; `startAt` share-target play split into a once-only effect. Host-verified: core-persistence + feature-settings + feature-player suites green; installed on the Z Fold 6. | user report 2026-09-07; decisions #130 |
| **Reader passage indicator floats and shows the played passage, not the visible page** | The bottom indicator was the page `Column`'s last child in normal flow, so it rode the content instead of the viewport bottom — pagination already reserved its band (`bottomReservePx`) but nothing pinned it. It also formatted `bookPassageIndex`, the *playback* position; a manual page turn stops playback, so advancing pages left the label on the last played passage while the page moved. | Fixed — 2026-09-15: `Spacer(weight 1f)` before the footer pins it to the reserved band, and the label is the page's first passage, book-wide (`chapterPrefix(state) + firstPassageOnPage`; no new published state — `stateCopy` builds `bookPassageIndex` as chapter prefix + in-chapter index). The immersive overlay's copy of the indicator uses the same source. **Device-verified (S22)**: across four `Jumper` page turns the label advanced `112 → 116 → 119 → 123` of 4060 at a constant y≈1270 with the card top at ≈1295 (pinned), and it read the visible page's passage (`50%`) while playback sat at `75%` — the played-passage behaviour it replaced. | user report 2026-09-15; decisions #165 |
| **Reader shows the empty-state placeholder while a book is still opening** | `chapterPassages.isEmpty() && phase == IDLE` renders "Tap play to start listening from this book." during every open — cold start and drift recovery — because the holder carries the previous book (or its process default, `bookId = null`) until the open command's single `publish()`. `phase` cannot signal this: it stays `IDLE` through every open path (`LOADING` is play-path-only), so no existing state could key a loading message. | Fixed — 2026-09-15: a `LoadingState("Opening book…")` branch keyed on `state.bookId != bookId` (the reader knows which book it asked for) sits above the placeholder, which now means what it says — a book genuinely open with no text. **Device-verified (S22)**: the state renders centred with "Opening book…" during the open transition (caught in three frames of a screen recording) | user report 2026-09-15; decisions #165 |
| **A bookmark jump loses the in-passage offset** | `Bookmark.offsetSeconds` is persisted and asserted, but a jump calls `openPosition(bookId, chapterIndex, passageIndex)` — the offset is carried at neither layer (`ReaderViewModel.openPosition`, `PlaybackService.openPosition`) — so pressing play after a bookmark jump restarts the passage instead of resuming the bookmarked spot. | Open — recorded, not fixed: offset fidelity, distinct from the language/display invariant the owner asked about; the owner's call | code trace 2026-09-15; decisions #165 |

> Closed 2026-09-01: classified and fixed in decisions #107 (A8) — the
> mechanism was E2E teardown `deleteDatabase` calls on the live production
> DB, not Samsung install-time handling; Phase C gate satisfied.

| ~~**A translation still renders after the display language is turned off**~~ | Owner report on the 0.1.2 device build (2026-09-17): *"sometimes I have the translation set to off, but I see an english translation of english text."* A display-priority decode captures its target when it STARTS, and clearing the book's display language starts no new prefetch — so nothing superseded the in-flight one (`TranslationServiceImpl.prefetch` supersedes on a NEW target only). Its `TranslationReady` then landed in the reader's display map, whose merge accepted ANY landed text: the projected chapter carried a translation block while display was OFF, and the entry survived until the next re-seed (a chapter or target change), so the whole chapter read as a rewrite of the original. The speech path was not the source — `EngineSelector.translateTarget` resolves live and yields null with display Off, so no audio translation ran. | Fixed — 2026-09-17: the reader's `ready` merge (the display map's only text writer) now requires the book's CURRENT display target to be non-null AND to name the landed decode's own target (`ReaderViewModel`). Regression test `feature-player/.../ui/ReaderDisplayTargetOffTest` drives the exact window through the fake service's `emit`: it reproduced the bug before the fix (`[Original, Translation, Original]` with display Off) and now asserts both directions — a stale decode never renders or enters the map, and the in-force language still lands. | owner report on the 0.1.2 device build, 2026-09-17 |

| ~~**Pressing play while the reader shows the loading/empty state starts playback at the beginning**~~ | Owner report on the 0.1.2 device build (2026-09-17): pressing play while the empty state or the loading message is showing started playback at the book's start. Both states are entered exactly when `chapterPassages.isEmpty()` (ReaderScreen.kt:352-354), and the reader's play control dispatched `ACTION_RESUME` there — a ROW-DEPENDENT command that resolves its own start position service-side (`machine.resume()` → the persisted `ProgressDao` row) **racing the in-flight `ACTION_OPEN`**, with a silent `firstPosition()` fallback to chapter 0 / passage 0 whenever no valid row exists at that instant (never played, a row invalidated by a re-parse, or a transient store read swallowed by `storeOp`). The open's own resolution (`openPosition()` → the same row) was superseded and never reached the play. | Fixed — 2026-09-17: the reader's play control is now `ReaderViewModel.playFromView` (the docked card's `resume()` override routes there, so the reader can no longer dispatch a raw `ACTION_RESUME`): with the chapter loaded it plays from the VIEW's position as before; while loading it REMEMBERS the press and completes it against the position the open PRESENTS (no second row read, no race). Regression `ReaderPlayWhileLoadingTest` pins both arms — nothing dispatched while loading, `ACTION_PLAY_POSITION` at the presented (2,5) after a deferred press, and the view position (3,4) when the chapter is already loaded. | owner report on the 0.1.2 device build, 2026-09-17 |

| **Screen-off playback occasionally stops and buffers (Fold)** | Owner report on the 0.1.2 device build (2026-09-17): "when listening to the audio with the screen off, it seems to occasionally stop and buffer (on the fold)". Code review found NO screen/UI dependency in the fill (service scope; no `ON_STOP`/`ON_PAUSE`/`isScreenOn` hooks anywhere in production code) and the engine arbitration is correct (`PlaybackActive.engineInUse` makes WorkManager pregen yield per book/passage). The stall is a THROUGHPUT deficit — `docs/kokoro-on-device-perf.md` measured **unplugged + display-off RTF 1.32-1.79** vs 0.52-0.60 screen-on, and ruled out Doze whitelisting and thermal (§3.3/§4.1) — against a 30 s look-ahead cushion sized for RTF < 1. What made it stick is the wake-lock gap: the only CPU-awake holder is the audio HAL's lock, which exists only while a track is PLAYING, and a dry buffer is exactly when nothing plays — so an unplugged, screen-off device can suspend mid-stall, where the 50 ms cushion poll, the fill's 200 ms top-up and the 1 s ticker all stop firing (thread-park timers, no alarms) until an external wake. | **Partly fixed — 2026-09-17**: the player holds a `PARTIAL_WAKE_LOCK` for the whole dry-buffer window (`CpuAwakeLock` around `PlaybackService.bufferForPlayback` — the cushion wait AND the synchronous synthesis; `WAKE_LOCK` declared in `feature-player`'s manifest; `CpuAwakeLockTest` pins acquire/release/idempotence). That removes the suspend failure mode, so a stall now self-recovers instead of sleeping. **Still open**: the deficit itself — unplugged + display-off synthesis above real time, which no wake lock raises (decisions #180 names the reviewed-and-left `realtimeCapable` skip). Measuring it needs a device: per-boundary logcat (`loop: source=`, `buffer: waiting`), the fill's screen-off vs screen-on RTF, and `dumpsys power` during a stall. | owner report on the 0.1.2 device build, 2026-09-17; `docs/kokoro-on-device-perf.md` §3.3/§3.8/§4.1 |

| ~~**Translate: a line break inside a passage silently truncates the translation**~~ | **Measured on the host Q4_0 copy, NOT the shipped pack — corrected 2026-09-20.** The 2026-09-18 finding (the model ends its turn at a newline: 3 of 6 single paragraphs truncated with a `\n` after the first sentence, and every multi-paragraph block) came from the host's `LFM2.5-1.2B-Instruct-Q4_0.gguf`, the quant caveat `docs/prints/book-samples/samples.md` carried at the time. Re-measured against the SHIPPED `LFM2.5-1.2B-Instruct-Q4_K_M.gguf` on the same host, same production prompt shape, same endpoint (`docs/prints/book-samples/break_truncation_probe.py`): **0 of 6 hard-wrapped paragraphs differ** — the shape a .txt/Markdown book actually produces (`TextParser` joins wrapped lines with `\n`, one passage per blank-line block), ratios 0.96-1.04 against the whitespace-collapsed control. The mechanism is real but **not reachable in the app**: only a multi-paragraph prompt ends at a paragraph boundary (1,244 vs 1,496 chars on a 3-paragraph block, `finish=stop` vs `length`), and blank lines already split passages at the parser. The prompt builder now normalizes whitespace anyway (decisions #185), so the class cannot come back through a future caller. | Fixed — 2026-09-20: corrected as a mis-recorded defect (host quant, not the shipped pack); the normalization landed as hardening with a JVM-pinned prompt contract (`LlamaTranslatorPromptTest`) | prose-sample pass 2026-09-18; re-measurement 2026-09-20; decisions #181/#185; `docs/prints/book-samples/break_truncation_probe.py` |

## Serious code-review findings — detailed repair records (2026-08-27)

These findings were discovered by tracing production call paths and their existing
tests. They are not roadmap feature requests and are not duplicates of the known
limitations above. “Major” means the current implementation can silently violate a
shipped product contract, lose user-visible state, or make a completed operation lie
about its result. File/symbol references are preferred over line numbers so the record
survives nearby edits.

### CR-1 — ~~Whole-book manual pre-generation is a false-success no-op~~ (FIXED — A1, 2026-08-27)

**Severity/status:** Major; open; deterministic from the current control flow.

**Shipped contract:** The pre-generation picker offers 30 min, 1 h, 2 h, 3 h, and
“Whole book.” A whole-book manual run is unbounded by time and should stop only after
the book is cached, the disk tier saturates, or WorkManager cancels it (decisions
#42/#50; `PregenWorker.MANUAL_BUDGET`).

**Affected path:**

1. `LibraryScreen.PregenBudgetDialog` maps “Whole book” to `onPick(null)`.
2. `LibraryViewModel.pregenerate(bookId, null)` forwards the null budget.
3. `PregenManager.pregenerate` omits `KEY_BUDGET_TIME_MS` when the budget is null.
4. `PregenWorker.doWork` maps the absent input to `MANUAL_BUDGET`.
5. `MANUAL_BUDGET = PregenBudget()` correctly represents an unbounded run with
   `maxTimeMs == null`.
6. The worker then evaluates:

   ```kotlin
   val remaining = budget.maxTimeMs
       ?.minus(elapsed)
       ?.takeIf { it > 0 }
       ?: break
   ```

   Null means “unbounded” in `PregenBudget`, but this expression treats it as
   “deadline exhausted” and exits before constructing/running `OfflinePregen`.
7. `doWork` reaches `Result.success()` after the loop. WorkManager and the library UI
   therefore see a successful settled job; `refreshOffline()` still reports no new
   bytes.

**User impact:** The most prominent/highest-cost pre-generation choice silently does
nothing. The engine may still be opened before the loop, so the user can pay model-load
latency and memory cost before receiving a false success. Finite budget buttons are not
affected because they create a non-null `maxTimeMs`.

**Broader false-success mapping:** Even after the null-budget branch is fixed, the
worker currently ignores the `PregenProgress` returned by `OfflinePregen.run` and
unconditionally returns `Result.success()`. The runner can return early because the
engine produced `SynthesisOutcome.Unavailable` or hit the consecutive-failure cap, as
well as for legitimate completion/budget/saturation/yield reasons. Its return type
carries counts but not the terminal reason, so the Android adapter cannot currently
distinguish “completed/safely bounded” from “engine failed and generation stopped.” A
repair should close both false-success paths instead of fixing only the null Elvis.

**Why existing verification missed it:**

- `OfflinePregenTest` exercises the pure runner, where null budgets correctly mean
  unbounded. The defect is in the Android worker adapter before the runner starts.
- `PregenE2eTest.workerPregensTheBookThenPlaybackCompletesOverTheCache` supplies manual
  mode with no `KEY_BUDGET_TIME_MS`, exactly the broken case, but this is a physical-
  device instrumented test and is not executed by `testDebugUnitTest`/normal host CI.
  Compiling the test does not exercise the worker.

**Required repair properties:**

- Preserve the semantic distinction between an absent deadline and an expired finite
  deadline. A safe shape is: calculate nullable `remainingMs`; break only when it is
  non-null and `<= 0`; pass null through to `OfflinePregen` for an unbounded run.
- Do not replace null with a very large synthetic duration; overflow and clock math
  should remain unnecessary.
- Do not report `Result.success()` merely because the outer loop never started. A
  successful whole-book run must have reached one of the documented terminal
  conditions (complete or cache saturated); cancellation remains cancellation.
- Keep the 3 h overnight budget and finite manual budgets unchanged.
- Give `OfflinePregen.run` an explicit terminal reason/result (completed, budget
  exhausted, cache saturated, yielded to playback, unavailable, failure cap) or an
  equivalent unambiguous contract. Map failure terminals to WorkManager failure/retry
  deliberately; do not collapse all partial runs into success. Preserve progress counts
  for UI diagnostics.

**Regression coverage/acceptance:**

- Worker-level test: manual input without `KEY_BUDGET_TIME_MS` invokes synthesis and
  writes at least the first uncached passage.
- Worker-level test: a finite expired budget performs no synthesis; an unbounded budget
  is not classified as expired.
- Existing device `PregenE2eTest` must run and reach 100% with its current no-budget
  input.
- UI acceptance: choose “Whole book” on a book with zero cached bytes; WorkManager
  progress advances and per-book usage becomes non-zero before settlement.
- Failure-result test: repeated synthesis failures or `Unavailable` must not settle as
  an indistinguishable successful job; the WorkManager output should expose a useful
  error for the row/UI.

### CR-2 — ~~STOP and service teardown lose the live intra-passage playhead~~ (FIXED — A2, 2026-08-27)

**Severity/status:** Major; open; deterministic from `PassageOutput` semantics.

**Shipped contract:** Reading/listening progress is single-source and persists the live
book-time playhead. Pause, stop, focus loss, service destruction, and relaunch must not
move the user backwards within a passage (roadmap T4; `PlayerStateMachine.stop`).

**Affected path:**

- `PlaybackService.liveOffsetSeconds()` calculates
  `baselineOffset + output.positionSamples / sampleRate`.
- `AudioTrackPassageOutput.stop()` releases the track; afterward
  `positionSamples == 0` by contract.
- `PlaybackService.stopPlayer()` currently calls `stopEverything()` first. That method
  calls `output.stop()`. Only afterward does the launched coroutine call
  `machine.stop(liveOffsetSeconds())`.
- `PlaybackService.onDestroy()` repeats the same order inside `runBlocking`:
  `stopEverything()` then `machine.stop(liveOffsetSeconds())`.

The final write therefore uses `baselineOffset`, the offset from which the current PCM
slice started, not the physical playhead at STOP. A passage started at 0 rewinds to its
beginning. A passage resumed at 30 s and stopped at 42 s rewinds to approximately 30 s.

There is an additional lifecycle race: `stopPlayer()` launches an asynchronous store
write and immediately calls `stopSelf()`. `onDestroy()` can run before that coroutine,
perform its own baseline write, and then cancel the service scope. There should be one
authoritative final write, not two competing teardown paths.

**Abrupt process-death gap:** `onDestroy()` is not guaranteed when Android kills the
process. The 1 s `ticker()` publishes a live UI offset but does not call
`PlayerStateMachine.notePlaybackOffset`; the core method's contract says the edge will
call it “throttled during playback,” but the service currently calls it only around
specific commands. Between passage transitions, Room can therefore still contain the
buffer's starting offset. Abrupt process death loses the same current-passage progress
even after the STOP ordering is repaired. This belongs in the same issue because both
failures come from lacking one explicit live-playhead checkpoint policy.

**Reachability/user impact:**

- MediaSession exposes `ACTION_STOP`; Bluetooth/Auto/media controls can invoke it.
- The notification includes an explicit Stop action.
- Android/OEM destruction of the foreground service reaches `onDestroy()`.
- Progress loss is bounded by the current passage/slice, but passages are intentionally
  paragraph-sized and can be long; repeated stop/resume cycles repeatedly replay text.

**Why existing verification missed it:**

- `PlayerStateMachineTest.stop at the playhead writes before going idle` passes an
  already-captured `offsetSeconds = 2.0` directly. It proves the core machine honors a
  supplied playhead; it does not test the service capturing that playhead.
- Playback E2E covers natural completion, not STOP mid-buffer or service teardown.

**Required repair properties:**

- Capture `val finalOffset = liveOffsetSeconds()` before any call that pauses, flushes,
  releases, or replaces `PassageOutput`.
- Feed that captured value to exactly one final `machine.stop(finalOffset)` operation.
- Make service shutdown wait for the final transactional store write without allowing
  `onDestroy()` to overwrite it with a stale offset. Avoid indefinite main-thread
  blocking; a bounded structured teardown job is preferable to duplicated writes.
- Add a throttled persistence checkpoint while `PlayerPhase == PLAYING` so abrupt
  process death loses at most the documented checkpoint interval. Do not write every UI
  tick by accident; choose and test an explicit interval, and force a final checkpoint
  on every graceful phase exit.
- Preserve book-time semantics at all speeds; do not multiply/divide the captured
  offset by speed because `positionSamples` is already book-time.

**Regression coverage/acceptance:**

- Service-edge test with a fake `PassageOutput`: baseline 10 s + 5 s played, STOP must
  persist 15 s even though fake `stop()` resets its head to zero.
- Repeat for `onDestroy()` without an explicit STOP.
- Abrupt-death test: play beyond one checkpoint without reaching the passage end, kill
  the app process without a graceful service callback, relaunch, and verify progress is
  no older than one checkpoint interval.
- Device test: stop midway through a long passage, kill/reopen the app, and verify the
  resumed sentence/playhead is within the existing timing tolerance rather than at the
  passage/slice start.
- Verify pause, focus loss, noisy-route pause, speed change, and natural completion
  retain their current behavior.

### CR-3 — ~~Room library state and `TextIndex` can diverge~~ (FIXED — A3, 2026-08-27)

**Severity/status:** Major; open; architectural correctness issue with multiple concrete
failure interleavings.

**Shipped contract:** Room is durable library truth; `TextIndex` is a derived in-memory
search accelerator. Every durably imported book must be indexed, every deleted book
must be absent, failed imports must not mutate the index, and launch-time rebuild must
be safe when racing an import (architecture §3/§4/§6; decisions #13/#22).

**Current ownership split:**

- `BookImporter.import` uses `TextIndex.contains(bookId)` as its duplicate check.
- After parse/segmentation, `BookImporter.import` executes `index.add(segmented)` and
  returns `ImportOutcome.Added`.
- Only later, in `LibraryViewModel.buildSummary`, does
  `repository.add(outcome.entry)` commit the book and cached passages to Room.
- Startup independently runs
  `indexRebuilder.rebuild(libraryStore.cachedBooks())` on `appScope`.
- Removal executes `index.remove(bookId)` and deletes offline audio before
  `repository.delete(bookId)` commits the durable deletion.

**Failure A — persistence failure poisons retry:**

1. Parse succeeds and `BookImporter` adds ID X to `TextIndex`.
2. Room `repository.add(X)` throws (disk full, SQLite failure, cancellation, or process
   interruption).
3. The ViewModel coroutine exits without producing a typed failed outcome; UI can
   remain on its last importing state. The index still contains X, but the library does
   not.
4. Retry hashes the same file, sees `index.contains(X)`, returns `Unchanged`, and never
   retries Room persistence.

The user cannot durably add that file again in the same process without an index purge.

**Failure B — cold-start rebuild purges a successful concurrent import:**

1. `LocalTtsReaderApp` obtains cached Room snapshot S before new book X is persisted.
2. The import adds X to the index and persists it to Room.
3. `IndexRebuilder.rebuild(S)` computes `cachedIds` from stale S and removes every
   indexed ID absent from S, including X.
4. Room/library UI contains X, but share-and-identify cannot match it until a later
   process rebuild or explicit re-index.

All individual `TextIndex` methods are synchronized, but synchronization of individual
map operations does not make this multi-component sequence atomic.

**Failure C — deletion failure creates the inverse mismatch:**

1. `LibraryViewModel.removeBook` removes X from `TextIndex` and may delete regenerated
   audio.
2. `repository.delete(X)` fails.
3. Room and the library UI still contain X, but matching is disabled and expensive
   derived audio may already be gone.

**Why existing verification missed it:**

- `BookImporterTest` validates parse failures before `index.add`; it has no persistence
  dependency and cannot model a Room failure after indexing.
- `LibraryViewModelTest` uses an always-successful `InMemoryLibraryStore`.
- `IndexRebuilderTest` tests sequential snapshots (populate, replace, clear), not a
  barrier-controlled stale snapshot racing an import.
- The “concurrent import safe” claim is documented but not represented by a concurrent
  contract test.

**Required repair properties:**

- Treat Room as the duplicate/idempotency source of truth. `TextIndex` membership must
  not decide whether durable persistence work is necessary.
- Establish one import orchestration boundary with this order: parse/segment without
  externally visible mutation; commit book+passages transactionally; then update the
  derived index. If post-commit index update is interrupted, a rebuild must recover it.
- Serialize rebuild reconciliation with import/delete index publication, or use a
  generation/snapshot protocol that prevents a stale rebuild from purging IDs committed
  after its snapshot.
- Delete durable state first, then remove derived index/cache state, or provide a
  compensating rebuild when durable deletion fails.
- Convert storage failures into typed import/remove UI results; do not leave the
  ViewModel in `Importing` or count an uncommitted entry as `added`.
- Preserve content-hash identity and the requirement that share queries never parse the
  source file.

**Regression coverage/acceptance:**

- Failing-store test: first add throws after parsing; retry with the same bytes must
  persist and index exactly one book.
- Barrier-controlled race test: rebuild captures S, import X commits, rebuild completes;
  final Room IDs and index IDs must be equal and include X.
- Delete-failure test: failed durable deletion must not leave a surviving Room book
  missing from the index.
- Property/invariant assertion after every tested import/delete/rebuild interleaving:
  `index.bookIds() == persistedBookIds` once operations settle.
- Share acceptance: immediately after a cold-start import, a snippet from the new book
  resolves without relaunch.

### CR-4 — ~~PCM cache LRU state is lost across process restart~~ (FIXED — A4, 2026-08-27)

**Severity/status:** Major; open; deterministic once a reopened cache approaches its
4 GiB cap.

**Shipped contract:** `PcmPassageCache` is a process-independent disk tier with an LRU
byte cap. Old regenerable audio should be evicted so newly requested audio can enter;
relaunch must not disable replacement (decisions #35/#42/#44).

**Current implementation:**

- Disk usage is discovered by walking `root`.
- Eviction candidates exist only in the in-memory access-ordered `recency` map.
- `recency` starts empty for every `PcmPassageCache` instance and is populated only by
  `put` or successful `get` during that process.
- Construction does not scan existing PCM paths into `recency`, and `contains` does not
  refresh recency.
- `evictLocked` stops when `recency.keys.firstOrNull()` returns null, even if actual disk
  usage still exceeds `maxBytes`.

**Failure sequence:**

1. Process A fills the disk tier near the 4 GiB cap and exits.
2. Process B constructs a new cache over the same root; old files count toward
   `totalBytesLocked()`, but none are eviction candidates.
3. Playback synthesizes and `put`s new key N, temporarily exceeding the cap.
4. N is the only key in `recency`, so eviction deletes N and its metadata.
5. Old files remain. Repeated new passages repeat the same self-eviction. If old files
   are already over cap, eviction deletes N, finds no next candidate, and exits still
   over cap.

The cache can therefore become effectively frozen across relaunch: old audio remains,
new listening repeatedly pays synthesis cost, and “normal use fills the cache” is no
longer true. Manual per-book deletion is the only reliable escape.

**Related integrity gap:** `contains` checks only that the PCM file exists. An orphan or
invalid `.meta` sidecar makes `get` fail but still tells `OfflinePregen` to skip the
passage. Reopen/bootstrap is the natural place to remove invalid/tmp pairs rather than
preserve permanent false hits.

**Why existing verification missed it:** Both LRU tests create one cache instance, put
all entries, and evict without reopening. No test constructs a second
`PcmPassageCache` over a populated root.

**Required repair properties:**

- Bootstrap every valid on-disk entry into an eviction structure before enforcing the
  cap, or persist the eviction index/access sequence alongside the cache.
- Since filesystem timestamps were deliberately rejected as reliable LRU truth, decide
  explicitly between:
  - persisted logical access order (true cross-process LRU), or
  - deterministic approximate startup order that still guarantees the hard byte cap.
- The hard invariant is more important than perfect recency: after a successful put,
  `totalBytes() <= maxBytes` unless one individual entry itself exceeds the cap, which
  must have an explicit policy.
- Startup/bootstrap should delete stale `.tmp`, PCM-without-valid-meta, and
  meta-without-PCM artifacts, or at least exclude them from `contains` and recency.
- Keep all public operations thread-safe and preserve atomic reader visibility.

**Regression coverage/acceptance:**

- Reopen test: instance A writes two entries, instance B opens the same root and writes
  a third over a two-entry cap; an old entry, not the new one, is evicted.
- Pre-populated-over-cap test: opening/first mutation converges below the cap.
- Invalid-pair test: PCM without valid metadata is not considered cached and can be
  regenerated.
- Device acceptance: fill a small test cap, force-stop/relaunch, play an uncached
  passage, and confirm it remains on disk while an older entry is reclaimed.

### CR-5 — ~~Asynchronous book commands can overwrite newer player state~~ (FIXED — A5, 2026-08-27)

**Severity/status:** Major; open; race in the player control plane.

**Shipped contract:** `PlaybackService` is the single writer for active book/player
state. A newer user command must supersede an older one; stale loops or loads must never
advance/publish after navigation moved elsewhere (T4 and decisions #55).

**Current implementation:**

- `openBook`, `openChapter`, and `startPlayback` call `scope.launch` for settings/Room
  work and later assign shared `book`, `machine`, `queue`, bookmarks, foreground state,
  and `PlaybackStateHolder`.
- The service declares `playerJob` and `stopEverything()` cancels it, but no production
  path assigns any launched load job to `playerJob`.
- `commandLock` serializes seek/skip/speed/undo mutations only. It does not serialize
  book open/play loads.
- Cancellation of `loopJob` fixes stale audio loops after transport changes but does not
  cancel pending control-plane loads.

**Representative race:**

1. `ACTION_OPEN(A)` stops current work and launches load job A.
2. Before A finishes `settings.reload()`/`cachedBooks()`, `ACTION_PLAY(B)` calls
   `stopEverything()` and launches load job B. Job A is not tracked, so it survives.
3. B finishes first, assigns machine/book B, enters foreground, and starts its loop.
4. A finishes later, assigns machine/book A and publishes it. Its open path can also
   call `stopForeground(...REMOVE)`, removing B’s required playback notification.

Possible outcomes include the wrong book appearing/playing, an audio loop operating on
shared state replaced by another job, or foreground-service state no longer matching
active playback. Similar races exist between repeated card taps, reader open plus a
quick reader play (play-from-view), share-target playback, and chapter opens.

The race is not limited to selecting books. `pausePlayer()` stops output and launches
`active.pause(live)` asynchronously without the command lock. A Resume intent arriving
before that coroutine updates the phase can still observe `PLAYING`/`LOADING` and return
as a no-op; the delayed pause then lands, leaving playback paused after the user asked
to resume. Navigation coroutines also capture `active` before acquiring `commandLock`,
so a concurrent book switch can let a queued command mutate an obsolete machine and
then call `startLoop()` against whichever global machine won the race.

**Why existing verification missed it:**

- Existing player tests target the pure state machine and sequential service scenarios.
- Rapid-tap verification in decision #55 covers transport commands after a machine is
  loaded; it does not delay/reorder two book-loading commands.
- No test asserts that `playerJob` owns the launched loading coroutine or injects a
  delayed library load to force out-of-order completion.

**Required repair properties:**

- Restore a single-writer command model for all control-plane transitions, not only
  transport. Preferred boring design: one service command actor/queue processes intents
  in order and owns `book`, `machine`, `queue`, focus, loop, and foreground state.
- A smaller repair may track a dedicated load job and cancel/join it before starting a
  superseding load, but cancellation alone needs a monotonically increasing generation
  check before every publish/foreground side effect in case the underlying operation
  reaches a non-cancellable section.
- Keep the long-running synthesis/play loop outside the command lock/actor critical
  section; commands must remain responsive enough to cancel it.
- Separate load/control job ownership from `loopJob`, `tickerJob`, `pregenJob`, and disk
  persist jobs. An unused `playerJob` field is not protection.
- A stale command must never call `publish`, `startForeground`, or `stopForeground`.

**Regression coverage/acceptance:**

- Deterministic service test with a fake/delayed library store: hold load A, complete
  load B, then release A; final state and active loop must remain B.
- Repeat for OPEN(A)→PLAY(B), PLAY(A)→PLAY(B), and OPEN(A)→STOP.
- Assert only the winning generation can update `PlaybackStateHolder` and foreground
  ownership.
- Device stress: rapidly alternate two library-row Play actions and open the reader;
  title, audio, notification, persisted progress, and controls must all refer to the
  final selected book.

### CR-6 — ~~Feature-module dependency rules are no longer enforced~~ (FIXED — A6, 2026-08-27)

**Severity/status:** Architectural; open; already increasing correctness and test risk.

**Documented invariant:** `architecture.md` §2 says feature modules never depend on one
another and `app` wires them. `app` is described as the Hilt composition root.

**Actual Gradle edges:**

- `feature-library -> feature-player`
- `feature-settings -> feature-player`
- `feature-settings -> feature-ocr`
- `feature-share -> feature-ocr`
- `feature-share -> feature-library`

These are direct `implementation(project(":feature-*"))` dependencies, not test-only
edges. `feature-share` depends on `feature-library` primarily to discover
`ImportModule`/`PersistenceModule` singletons. `feature-library.PersistenceModule`
constructs the application-wide Room database and binds library/player/settings stores.

**Concrete leakage caused by the graph:**

- `LibraryViewModel` directly imports `PlaybackService`, `PlaybackStateHolder`,
  `PregenManager`, `PregenStorage`, Room DAOs/entities, `TextIndex`, WorkManager types,
  filesystem cover storage, and Android `Context`.
- `SettingsViewModel` imports player-owned pre-generation storage and espeak staging.
- `PlaybackService`, `PregenWorker`, and `PregenStorage` depend on concrete
  `RoomLibraryStore` rather than the `LibraryStore` contract because the existing
  contract does not expose the cached-book query they need.
- Unit-test constructors use nullable production dependencies with defaults to avoid
  assembling the real feature graph. This creates silent no-op branches that exist only
  because boundaries are not injectable at the correct level.

**Why this is serious rather than cosmetic modularity:**

- A share receiver transitively pulls in unrelated library/player/WorkManager wiring to
  obtain singleton identity. The feature cannot be composed or tested independently.
- Application-scoped ownership is hidden in `feature-library`; replacing the library
  UI can accidentally remove database/player/settings bindings.
- Post-v1 backup/stats work both cross persistence, library, settings, and player. With
  the current graph, each addition is likely to create more cross-feature edges or a
  real cycle.
- Contract tests cannot substitute focused fakes cleanly, contributing directly to
  CR-3 and CR-5 coverage gaps.
- The code and architecture document disagree, so future work cannot reliably infer
  which dependency rule is authoritative.

**Required repair properties:**

- Move application-wide Hilt bindings (Room database, shared stores, index/rebuilder,
  Android adapters) to `app` or an explicitly named infrastructure/composition module.
  Do not keep them in an arbitrary UI feature.
- Put narrow interfaces at the lowest stable owner:
  - playback commands/state source in `core-player` or a small player API surface;
  - cached-library reads in `core-model`/persistence contract rather than concrete
    `RoomLibraryStore`;
  - offline-audio management behind a core-player contract;
  - OCR implementation binding in the composition root, while share depends only on
    `OcrEngine`.
- Feature modules should depend on core contracts and Android UI libraries, never on
  another feature’s implementation.
- Replace nullable “Hilt supplies it, tests omit it” dependencies with required
  contracts and explicit fakes. Optional behavior should be represented by an explicit
  optional contract, not constructor defaults that silently disable production paths.
- Preserve one singleton `TextIndex`, database, and runtime through app-level bindings;
  removing feature edges must not create duplicate instances.

**Suggested migration order:**

1. Move existing Hilt provider modules to the composition root without changing
   behavior.
2. Extract the minimum contracts needed by library/settings/share; implement adapters in
   player/OCR/persistence modules.
3. Rewire each feature to contracts and remove direct feature project dependencies.
4. Move cross-feature navigation/orchestration (`Intent` commands, open-target handling)
   to app-owned adapters.
5. Add a build check that rejects `feature-* -> feature-*` project edges, making the
   documented rule executable.

**Regression coverage/acceptance:**

- Gradle dependency graph contains no direct feature-to-feature edges.
- Each feature unit-test target can compile against core contracts/fakes without pulling
  another feature implementation.
- Hilt app build proves exactly one database, `TextIndex`, `IndexRebuilder`, player
  runtime, and settings mirror.
- Existing import, playback, settings, OCR/share, and pre-generation device scenarios
  remain behaviorally unchanged after the cutover.

### CR-7 — ~~Pause and navigation during first-audio generation do not settle the session~~ (FIXED — A7, 2026-08-27)

**Severity/status:** Major; open; device-observed state disagreement in the player
control plane.

**Shipped contract:** UI, `MediaSession`, and the notification must report one player
state. A pause command during generation (`LOADING`) must cancel the in-flight
synthesis and publish `PAUSED`; a seek command while paused repositions without
resuming audio (T4; decisions #53/#55).

**Device observation (S22, debug build, 2026-08-27):**

1. Play pressed from reader P1 → mini-card shows "Generating…".
2. +30s pressed → still "Generating…"; `dumpsys media_session` reports
   `state=PLAYING(3)`.
3. Center transport pressed (pause path: `PlayerCard` → `commands.pause()` →
   `ACTION_PAUSE`), then `cmd media_session dispatch pause` → subsequent
   `dumpsys media_session` still `PLAYING(3)`; the notification is still built with
   the Pause action.
4. −30s from that state re-synthesized ("Generating…", "10:41:20 left" unchanged)
   instead of repositioning a stopped playhead.
5. Only process teardown (force-stop) ended the session.

**Current implementation (relevant facts):**

- `PlayerCard` renders a spinner while `loading`, but the center box stays clickable
  (`if (playing) commands.pause() else commands.resume()`) — no visual confirmation
  the command landed.
- `pausePlayer(reason)` runs `stopEverything()` then
  `scope.launch { active.pause(live); publish() }` on `Dispatchers.Default`
  (`scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`), outside
  `commandLock`.
- `startLoop()` synthesizes synchronously in its loop (the cold/jumped-passage
  fallback `runtime.engine()?.synthesize(...)`) and calls `publish()` after each
  iteration; `publish()` writes MediaSession state from `machine.state.phase` and
  posts the notification.
- `seekBy`/`navigate` also run outside the pause path: they `stopEverything()` and
  launch a `commandLock`-serialized mutation, then `startLoop()` — a seek pressed
  while generation is in flight replaces the loop with a new synchronous synthesis
  from the new position.

**Open trace:** whether the dropped pause is (a) a superseded `publish()` from the
finishing loop overwriting the paused state (the CR-5 unpublish class), or (b) the
pause landing while the loop's synchronous synthesize occupied the Default dispatcher
and a queued seek re-entered `LOADING` — plus the missing serialization between
`pausePlayer` and the loop epoch (`commandLock` covers seek/skip/speed/undo, not
pause).

**Required repair properties:**

- Pause during `LOADING` cancels the in-flight synthesis and any queued loop publish;
  UI, MediaSession, and notification all report `PAUSED`.
- One command actor/generation for every transport transition (pause, resume, seek,
  navigate, undo, speed, sleep); a superseded command must never `publish` or change
  foreground state after a newer state.
- Loading feedback distinguishes "pause requested" from "generating" — the spinner
  stays interactive and cancels on tap.
- Seek while genuinely paused repositions silently (current `seekTo` semantics) and
  never starts audio.

**Regression coverage/acceptance:**

- Service test: `ACTION_PLAY` with a delayed fake synthesis; while `LOADING`, issue
  `ACTION_PAUSE`; assert MediaSession `STATE_PAUSED`, notification action "Play", no
  further synthesis call, `PlaybackStateHolder.phase == PAUSED`.
- Same for `PAUSE`→`SEEK` during generation: paused, moved position, no synthesis.
- Device: press play on a cold engine, press pause during "Generating…"; within 2 s
  the notification shows "Play", `dumpsys media_session` reports `PAUSED`, and no
  further synthesis work is observable.

### CR-8 — ~~Read-along anchors + live playhead never published~~ (FIXED — device E2E run, 2026-08-27)

**Severity/status:** Major; regression from `3e01cd3` (decisions #51, library gap
pass); caught by the outstanding device E2E batch on the S22.

**Shipped contract:** `PlaybackStateHolder`/`PlaybackUiState` is the single
observable player surface (T4-2, decisions #34). `segments` (sentence spans of
the current passage's audio, #31) and `offsetSeconds` (live playhead, book-time
seconds within the passage) are published on every `publish()`; the reader's
read-along highlight (`activeSentenceIndex`) and the passage progress bar
(`offsetSeconds / passageDurationSeconds`) consume them.

**Regression:** `PlaybackService.publish()` dropped both lines while rewriting
the reader footer (same edit `speed = state.speed` lost, later restored
separately). The fields stayed in `PlaybackUiState` but no code ever wrote
them — `state.segments`/`offsetSeconds` pinned at defaults, highlight dead.

**Why existing verification missed it:** no host test reads the published
`PlaybackUiState.segments`; the only consumer is `PlaybackE2eTest`
("read-along anchors surfaced somewhere"), which needs a device + staged
packs + a real run — and additionally was not rerun-safe: a previous run's
resume row parked the playhead at the book's ending, so later ACTION_PLAY
slices ~0 frames, completes instantly, and the 250 ms poll misses the
anchor-bearing PLAYING window.

**Device evidence (S22, 2026-08-27):**
```
PlaybackService: loop: playing 1/0 88832 frames … await returned finished=true (pos=1/1)
```
— started at the ending, slice=1 frame; the assertion "read-along anchors
surfaced somewhere" failed at `PlaybackE2eTest.kt:114` in both batch runs.
Cleared progress → a real two-passage run (0/0 444880 frames → 1/0 88832
frames, sources disk → pregen), COMPLETED, "OK (1 test)".

**Repair:**
- `PlaybackService.publish()`: restore `segments = segments,` and
  `offsetSeconds = liveOffsetSeconds(),` in `PlaybackStateHolder.update`
  (matching `PlaybackUiState` field order, decisions #31/#34).
- `PlaybackE2eTest.playsThroughTheBookAndCompletes()`: delete the book's
  `progress` row before `ACTION_PLAY` so every run starts at 0/0 with real
  audio (replaces the doomed "resume at ending" path).

**Regression coverage/acceptance:**
- Device: `PlaybackE2eTest` passes with a real two-passage AudioTrack run
  (full-frame drains, anchors published); `PlayPositionE2eTest`,
  `PregenE2eTest`, `VoiceSelectionE2eTest`, `PtVoiceE2eTest` re-run green.

**Regression coverage/acceptance:**
- Device: `PlaybackE2eTest` passes with a real two-passage AudioTrack run
- (full-frame drains, anchors published); `PlayPositionE2eTest`,
- `PregenE2eTest`, `VoiceSelectionE2eTest`, `PtVoiceE2eTest` re-run green.
§
**Regression coverage/acceptance:**
- Device: `PlaybackE2eTest` passes with a real two-passage AudioTrack run
- (full-frame drains, anchors published); `PlayPositionE2eTest`,
- `PregenE2eTest`, `VoiceSelectionE2eTest`, `PtVoiceE2eTest` re-run green.
### CR-9 — ~~Chapter text never published (reader blank, touch nav dead)~~ (FIXED — device repro, 2026-08-27)

**Severity/status:** Major; regression from `26a3272` (CR-8 commit); caught by a
device repro on the S22 within hours of the fix landing.

**Shipped contract:** `PlaybackStateHolder`/`PlaybackUiState` carries the
current chapter's passage texts in `chapterPassages: List<String>` (decisions
#51 follow-up, "stitch the chapter into one continuous reader surface").
`PaginatedChapter` joins the list with `\n\n` and paginates the joined
chapter as one continuous page; `ReaderScreen`'s side-tap and swipe page-turn
gestures and the empty-state guard depend on it.

**Regression:** the CR-8 commit (segments + offsetSeconds restoration)
replaced the existing `chapterPassages = position?.let { p -> book?.chapters
?.firstOrNull { it.index == p.chapterIndex }?.passages?.map { it.text } }
?: emptyList()` block in `publish()`'s `it.copy(...)` with the two new
lines (same `copy-move` class of regression that originally hit
`segments`/`offsetSeconds` in `3e01cd3`). `chapterPassages` remained in the
state contract and the reader rendered nothing, so the body was a void,
side-tap page-turning had no text to scroll through, and the empty-state
string only showed while `phase == IDLE`.

**Why existing verification missed it:** no host test reads
`PlaybackStateHolder.state.chapterPassages`; the only consumers are the
Compose reader and the player card's chapter-passages hint, and the
instrumented E2E classes don't drive the reader surface.

**Device evidence (S22, 2026-08-27):**

```
publish diag:  book!=null=true bookChapters=3 bookCh0Passages=908 bookChAtPosPassages=908 phase=IDLE
publish post:  stateChapterPassages=0 statePassageText=483 stateBookTitle=No More Mr Nice Guy
```

— the service has 908 passages for chapter 0, but the published state carried
zero. `uiautomator dump` on the reader body showed only the empty-state
sentence; after restoring the line the same dump shows the chapter's first
page rendering and side-tap/swipe page turns work.

**Repair:**

- `PlaybackService.publish()`: restore `chapterPassages = position?.let { p ->
  book?.chapters?.firstOrNull { it.index == p.chapterIndex }?.passages
  ?.map { it.text } } ?: emptyList()` in `PlaybackStateHolder.update` (between
  `chapters` and `segments`, matching `PlaybackUiState` field order, decisions
  #51 follow-up).

**Regression coverage/acceptance:**

- Device: every reader open renders the chapter body (not the empty-state
  sentence) and side-tap/swipe page turns land on subsequent pages; existing
  E2E classes (`PlaybackE2eTest` etc.) re-run green.

### CR-8/CR-9 family — ~~Chapter titles never published (chapter selector/title dead)~~ (FIXED — PR-0, 2026-08-28)

**Severity/status:** Major; regression from `3bc2057` (the CR-9 fix commit), the
third instance of the `publish()` copy-move class; no separate CR number — filed
with the CR-8/CR-9 family (code trace 2026-08-28, `docs/generate-play-lean-up.md`
QW1).

**Shipped contract:** `PlaybackUiState.chapters: List<String>` carries the book's
chapter titles in spine order. `ReaderScreen`'s chapter selector gates on it
(`enabled = state.bookId != null && state.chapters.isNotEmpty()`), the header
label reads "Ch X/Y" from `state.chapters.size`, the chapter dropdown iterates
`state.chapters`, and the top-bar chapter title is
`state.chapters.getOrNull(state.chapterIndex)`.

**Regression:** the CR-9 fix commit `3bc2057` replaced the line
`chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()` with the
restored `chapterPassages` block inside `publish()`'s `it.copy(...)` — the same
`copy-move` class that originally hit `segments`/`offsetSeconds` in `3e01cd3`
(CR-8) and `chapterPassages` in `26a3272` (CR-9). `chapters` stayed in the
`PlaybackUiState` contract but no production path wrote it: the chapter selector
was permanently disabled, the "Ch X/Y" label read "Ch 1/0", the dropdown was
empty and the top-bar chapter title was blank.

**Why existing verification missed it:** no host test read
`PlaybackStateHolder.state.chapters`; the only consumers are the Compose reader
surfaces, and the instrumented E2E classes don't drive the reader UI. The drop
landed in the CR-9 fix commit itself (`3bc2057`), so it postdates the CR-8/CR-9
repair records and predates this trace.

**Repair (PR-0, decisions #72):**

- `PlaybackService.publish()`: restore `chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()` in `PlaybackStateHolder.update` (between `passageDurationSeconds` and `chapterPassages`, matching `PlaybackUiState` field order).
- New `PlaybackServicePublishGuardTest` (feature-player): runs a real publish against a positioned machine + book and asserts the full historically collateral-dropped field set — `chapters`, `chapterPassages`, `segments`, `offsetSeconds` — so any future copy-block edit that drops a field fails the suite (recurrence guard covering the whole CR-8/CR-9 family).

**Regression coverage/acceptance:**

- `PlaybackServicePublishGuardTest` — `publish populates the full historically-dropped field set` fails against the pre-PR-0 publish (`chapters` empty) and passes now; companion tests pin the QW2 action surface (`notification action intents carry the book id`, `media session play rebuilds the machine from the holder book id after death`).
- `:core-player:test :feature-player:testDebugUnitTest` green via `./tools/docker-build.sh` (feature-player 20 incl. the 3 new guard tests).
- Device visual acceptance (chapter selector enabled, "Ch X/Y" label, top-bar chapter title) not run this round — no device; host tests only.

| Limitation | Workaround | Reported in |
|---|---|---|
| Instrumented test classes sharing one process trip Room-reopen races | Run each test CLASS in its own `am instrument` invocation | `build.md` (instrumented set), decisions #36/#45 |
| Hilt instrumented-test application override does not take effect in this AGP 9 project | Share-pipeline test builds the real components manually | decisions #37/#38 |

## Fixed items removed from this list

For the record, items reported in docs that have since been fixed (kept out of
the open list): sub-1% progress truncation to "0%" (fixed in #51/#52 — both
surfaces format `%.1f%%` below 1%), remove-book-from-library (#51), the #50
Dagger File-collision + FGS-timeout device findings, `INTERNET` permission +
typed download failure (#47), the three S-debug regressions (#39), and the stale-resume crash (2026-08-27 `fix(player)` commit), scene-break marker passages (2026-08-27 — `BookSegmentation.segment()` now drops letter-free passages, so `* * *` spacers are never read aloud), and chapter-boundary page turns (2026-08-27 — ReaderScreen side-taps/swipes now advance to the next chapter past the last         page and back to the previous chapter before the first, repositioning silently via a new `ACTION_OPEN_CHAPTER` — since refined the same day: a backward turn now lands on the previous chapter's LAST passage (its ending), not its first), and CR-1 (2026-08-27, A1 — whole-book manual pre-generation now runs: an absent budget is unbounded, never an expired deadline, and `Unavailable`/failure-cap terminals fail the job with a typed error and progress counts instead of a false success), and CR-2 (2026-08-27, A2 — STOP and teardown capture the live playhead before the passage output is released and write exactly once via a joined final stop; playback checkpoints the resume row every 5 s so abrupt process death loses at most one interval), CR-5 and CR-7 (2026-08-27, A5+A7 — every control-plane command runs as a tracked, generation-guarded single writer: a newer command's `stopEverything` cancels and supersedes in-flight loads/loops, so a stale command can never publish, startForeground, or stopForeground; pause during "Generating…" now settles UI/MediaSession/notification to PAUSED; seek/skip/undo while paused reposition without resuming audio), and CR-4 (2026-08-27, A4 — `PcmPassageCache` bootstraps every valid on-disk entry into the eviction order by pcm mtime at open and converges an over-cap cache at construction; stale `.tmp`, meta-less PCM, pcm-less meta and unparseable paths are removed on reopen, so `contains` can never report a permanent false hit and replacement works after any restart), and the no-import-progress report (2026-08-27, F1 — the batch now publishes `Importing(0/total …)` the moment it starts (pre-parse event per file), shows a Cancel control that stops the batch at the next file boundary without publishing a partial `Done`, and keeps per-file failure isolation typed), and CR-3 (2026-08-27, A3 — the import pipeline is one `ImportCoordinator` boundary: parse without publication → durable Room commit → index publish under the shared `IndexLock`; the durable store (`LibraryStore.contains`) is the re-import duplicate gate, so a failed commit can never poison the retry; deletes run durable-first with the index removal only after a successful Room delete; the launch rebuild reads its Room snapshot inside the lock, so no stale snapshot can purge a concurrently committed book; storage failures are typed `ImportFailureReason.Storage`), and CR-6 (2026-08-27, A6 — the composition root is `app`: `PersistenceModule`/the import-core providers/`OcrModule` moved to `app.di`, player state+commands+pregen/storage contracts moved to core-player, `TessDataStager`/`EspeakStager` to core-ocr/core-player, the shared `PlayerCard` to the new `core-ui` module; feature-library/settings/share lost every feature-to-feature edge and a `checkFeatureBoundaries` Gradle task now rejects new ones).