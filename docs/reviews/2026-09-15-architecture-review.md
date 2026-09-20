# Architecture review — 2026-09-15

Companion to [architecture.md](../architecture.md) (intended shape),
[modules.md](../modules.md) (module list) and [conventions.md](../conventions.md)
(patterns). This is a point-in-time audit of the repository as it stood at
`HEAD 13062b9`, plus the follow-up work it queued. It is **not** a spec: where it
contradicts the code, the code wins, and the doc gets fixed.

Line numbers are as of the reviewed tree. The cleanup passes that landed the same
day moved some of them (noted inline where it matters).

## Method and limits

Reviewed by static reading plus targeted measurement (module/file counts, test
counts, build-graph edges, doc inspection) across 18 Gradle modules, 477 tracked
files, ~44.4k Kotlin LOC in product modules and ~10.6k in `spike-tts`.

**What this review does NOT establish.** No profiling, no device run, no
dependency-CVE scan, and no security review (see Appendix A — that absence is the
single largest gap). Sampling rather than exhaustive reading of every file, so
"no violation found" means "none in what was read". It also does not evaluate
whether the *product* is right — only whether it is built coherently.

## Verdict

**Not a rewrite.** The design is better than the code volume, and much better than
the verification harness that guarded it. The layering is real (acyclic graph,
dependencies pointing at `core-model`, one domain model with zero duplicates), the
engine seam is textbook, and the test suite is unusually honest (no mocking
framework anywhere). The debt is concentrated and fixable: one god class, three
abstractions that were too thin to be used (since fixed), a build/verification
harness that enforced less than it claimed, and doc drift.

## What is genuinely strong (do not "improve" these)

- **Acyclic, single-direction build graph.** 60 project edges, no cycles, no
  `core-* → feature-*`, no `feature-* → feature-*`; `core-model` is a sink.
- **One canonical domain model.** Zero genuine duplicate domain types; every
  near-duplicate is a boundary DTO with an explicit mapper.
- **Test realism.** 0 `mockk`/Mockito usages in the tree; ~30 hand-rolled fakes and
  two production in-memory doubles (`InMemoryLibraryStore`, `InMemoryPlayerStore`)
  serve as test seams. The largest suites assert observable state, store rows and
  produced audio, not mock echoes.
- **The engine seam.** `TTSEngine` + `TranslatingEngine` as a Kotlin delegate with a
  documented degrade-to-original contract is the best code in the repository.
- **The player's persistence discipline.** `PlayerStateMachine` is the only
  `PlayerStore` writer; every commit goes through one transactional path
  (row + ring push together).

## 1. Layers — honest, broken at one boundary

| Claim | Reality |
|---|---|
| `core-*` has no `android.*` imports | Holds except 3 statements in 2 files: `CorruptDatabaseGuard.kt` (`Context`, `Log` — DB quarantine via `context.filesDir`) and `core-ui`'s `PlayerCard.kt` + `Motion.kt` (`BitmapFactory`, `Settings` — inside the sanctioned Compose module, but not Compose). |
| `core-*` is pure JVM | False at the build level for 3 of 11: `core-persistence` and `core-ui` (sanctioned adapters) and `core-llm` — an `android.library` with a vendored llama.cpp CMake build (arm64-only) that is in neither exception list. |
| Dependencies documented | 38 of 60 real edges are undocumented, and one documented edge is drawn backwards (`architecture.md` §2 says `core-persistence ← core-player`; the build has `core-persistence → core-player`). |
| `core-ui` depends on `core-player` only | False: it also depends on `core-tts` (`core-ui/build.gradle.kts:49`). |

Drift inside layers: `app` (the composition root) implements real behavior
(`tts/system/SystemTtsEngine`, `tts/audition/VoiceAuditionCoordinator`) while the
engine *selection* policy lives in `feature-player`; `core-player` hosts
non-player concerns (`EspeakStager`, `VoicePackDownloader`, `FormatBytes`).
Package hygiene is thin: 9 of 11 core modules are a single flat package (25 files
in `core-persistence`), and `core-translate` lives inside `core-tts`'s namespace
(`…tts.translate`).

## 2. SOLID

- **SRP — the failure.** `PlaybackService.kt` was 2,093 lines with ~49 mutable
  fields and 13 distinct responsibilities (command dispatch + A5 generations, play
  loop, prefill/fill, sleep tick, publication/`stateCopy`, MediaSession + focus +
  noisy, notifications + cover art, buffer planning, CR-2 final write, listening
  capture, measurement probes, companion helpers). Core-side: `OpfBookReader.kt`
  (452) and `BackupCodec.kt` (452); `PlayerStateMachine.kt` (391) the near-miss.
- **OCP — good, one wart.** Engines plug in behind `TTSEngine`; but
  `EngineSelector.engine()` is a string `when`, so a new engine edits the selector.
- **LSP — no violations found.**
- **ISP/DIP — three piercings, all fixed in pass 2 (see §6).**

## 3. Tests — real where they exist, absent where they don't

755 `@Test` (712 JVM + 43 instrumented); test:main ≈ 0.47 by bytes; **72% of unit
tests in four modules** (`core-ebook`, `core-player`, `core-tts`, `feature-player`).

Four modules have **zero** unit tests: `core-model`, `core-llm`, `feature-ocr`,
`spike-tts`. `LlamaTranslator.userMessage`'s own docstring claims a JVM test that
does not exist. `architecture.md` §1's "Every public behavior has a test" is false
as written — and was never measurable, because no coverage tooling exists anywhere
(no JaCoCo, no Kover).

Flakiness surface: 26 `Thread.sleep` sites across 9 `PlaybackService` test files,
7 real-clock deadline loops, 2 real-thread `CountDownLatch` gates, 11 tests that
silently no-op via `assumeTrue` when host `espeak-ng`/packs are absent, one
CWD-relative fixture read. Five test files each re-declare their own `private fun
await(...)`, so the cure for the sleeps already exists in-tree, unshared. 57
reflection accesses exist because tests must reach service internals and reset the
process-global `PlaybackStateHolder`.

## 4. Build, guards and the lint gate

- **`checkFeatureBoundaries` could never fire** and ran nowhere. It read
  `configuration.dependencyConstraints` (only `constraints {}` entries — none
  declared here) and compared `dep.name` against `project.path` values. Two
  independent dead ends. **Fixed in pass 1.**
- **CI skipped tests that exist**: `:core-translate:test` (16),
  `:core-backup:test` (8), `:core-ui:testDebugUnitTest` (19). **Fixed in pass 1.**
- **No convention plugin.** `compileSdk` re-typed in 10 files, `minSdk` in 9, Java
  17 in 18, JUnit-platform boilerplate in 17; `spike-tts` had already diverged to
  minSdk 27 and `ndkVersion` is pinned in `core-llm` alone. `app/build.gradle.kts`
  opens a second `android {}` block whose second `testLogging` call silently
  overrides the first.
- **Catalog bypasses**: hardcoded `javax.inject` ×2, `tess-two`, two QNN deps;
  three `jna:${libs.versions.jna.get()}@aar` interpolations; `material-icons-core`
  declared as a raw string in 4 modules and via the catalog in 2.
  **Partly discharged (2026-09-20, decisions #186)**: `tess-two` is gone with the
  OCR binding swap — the new artifact is `libs.tesseract4android`. What remains:
  `javax.inject` ×2 (`core-persistence:43`, `core-player:27`), the QNN pair in
  `spike-tts`, the three `jna` interpolations, and the four raw
  `material-icons-core` lines.
- **Lint**: a 2,937-entry baseline suppressed every violation across 207 files
  (all cosmetic). **Deleted in pass 1** — one bulk format over 196 files, 60 hand
  fixes, a root `.editorconfig`, and `ktlintFormat` kept as the formatter twin.

## 5. Docs

`docs/` is 852 KB of markdown and the repo's design surface, so drift there is a
defect. Found: `modules.md` lists `core-ocr` twice and says Room v2 where
`architecture.md` says v3; `architecture.md` §2 omits `core-translate`/`core-llm`
and combines three modules into one pseudo-edge; `README.md` claims 255 tests (755),
"decisions #1–#139" (162), calls the live `core-translate` "deferred", and — the
only doc that does — presents the app as **shipped** ("Ayvu ships as a signed APK on
this repository's Releases page", lines 51/64) although no `v0.1.1` tag exists and
both `roadmap.md` §Release readiness and the decision ledger say "release-ready, not
published"; `agents.md` still names CosyVoice3 the primary engine.
`decisions.md` (412 KB, 162 entries) is an append-only log with unmarked reversals
(#6 vs #21, #10 vs #27, #22 vs the KSP reality) — fine as an archive, but it means
the current state needs its own layer (this file, the roadmap, `modules.md`).

## 6. Cleanup passes

The audit queued six workstreams plus a pre-release gate; **the living plan, status and
sequencing are [roadmap.md §Source cleanup](../roadmap.md#source-cleanup-in-flight)** — this
file does not duplicate them, so there is one authority per fact. What follows is the
part that is *this review's* conclusion rather than the plan's content:

- Passes 1 (verification harness), 2 (contract boundaries) and 3 (playback-edge
  concurrency) landed the same day as the audit — decisions #163. Pass 3 was the direct
  answer to the highest-severity finding in §2: both machine writes that escaped the
  single-writer discipline now run on the player thread (the bookmark write inside
  `commandLock` too), the CR-2 final write targets the session's own machine, its
  teardown clear is generation-guarded, and the sleep-timer policy moved into the
  machine. Each behaviour is mutation-verified.
- **Pass 4 must follow the roadmap's D5 (engine choice) and G1 (player rule set, gated on
  G0), not precede them** — both land inside `PlaybackService`, and refactoring first
  means refactoring twice. Its mechanical half (three dedupes) is independent of them.
- Pass 5 split in two because half of it is blocked on a decision (coverage tooling vs the
  test-runtime ceiling) and half is not.
- Pass 7 exists because the app is **not published** (no `v0.1.1` tag; ledger and roadmap
  agree): security, licence and cross-app-id migration obligations attach at
  distribution, so they are a release gate — cheap now, expensive after publishing.
- The audit's non-cleanup findings (thermal/battery-aware synthesis, resource envelopes,
  accessibility + UI language, the device-verification oracle) are folded into the
  roadmap's unqueued-candidates paragraph, not into these passes.

## Appendix A — open questions this review did not settle

Ranked by what the answer would change. Each names what would settle it.

1. **Threat model for untrusted bytes** (largest gap — the review contains no
   security analysis). The app consumes files it did not create: EPUB/MOBI, shared
   screenshots, a user-picked restore zip, downloaded packs. Settle with: a security
   pass over the five entry points — zip/decompression bombs (EPUB, backup archive),
   path traversal on restore (`BookFileStore` sidecar paths), whether the pack
   registry's SHA-256 pin is enforced at install or advisory, JNI model-file paths,
   and NOTICE/licence completeness. Consequences are gated on distribution, which
   has not happened yet.
2. **Resource envelopes.** No measurement of `TextIndex` RSS vs library size, the
   launch-time rebuild cost, the PCM cache cap vs free space, or storage growth
   policy for activity rows/covers. Settle with: RSS + rebuild timing on a real
   library, and a stated cap.
3. **Thermal/battery-aware synthesis.** `roadmap.md` §Peer-app cross-check already
   records it: nothing reacts to thermal status, battery-saver or charge state; the
   measured follow-up rule (default 6 threads, demote to 4 when hot/charging) is
   unimplemented while the pregen queue runs flat out to budget.
4. **The verification oracle for device-only behavior.** AudioTrack/MediaCodec/
   MediaSession/WorkManager real behavior is verified by manual `am instrument` runs
   on the owner's devices, and the app ships no telemetry by design. Settle with: an
   explicit decision — accept manual device gates, or invest in ABIs/a device farm.
5. **Room migration policy** (now answered in code, worth stating in docs):
   forward-only, explicit `ALTER`/`CREATE` migrations, no destructive fallback
   anywhere in the tree, byte-level corruption quarantined rather than deleted,
   migrations covered by tests. Consequence: any future schema bump must ship a
   migration + test; the pass-2 contract changes needed none (no schema change).
6. **Accessibility and UI language.** The reader is gesture-driven (tap zones,
   double-tap, long-press) with no stated TalkBack path; the UI is English-only
   while content serves nine languages. Never examined.
7. **Dead-code inventory.** No pass was made for unused symbols (`EngineTier.FALLBACK`
   and the metadata-only CosyVoice3 entry; `KokoroTimings`/`AudioTrim`; several
   one-caller helpers in `core-player`).
8. **Invariant → test traceability.** CR-1…CR-9 are prose across `agents.md`,
   `architecture.md` and code comments; there is no map saying which test fails when
   one breaks. Same disease as the guard fixed in pass 1.
9. **Test-runtime ceiling.** The Android lane rebuilds a Docker image and the
   toolchain from scratch per push; pass 1 added only free work. Kover (slows every
   Android test) and `spike-tts` (own APK, QNN deps) need an explicit budget.
10. **Module granularity.** 18 modules for a solo app; whether `core-backup`,
    `core-locate`, `core-ocr` earn a module rather than a package.

## Appendix B — re-running the evidence

```bash
# JVM suite (no Android SDK)
./gradlew :core-model:test :core-ebook:test :core-locate:test :core-tts:test \
          :core-translate:test :core-player:test :core-ocr:test :core-backup:test

# Architecture guard: fails on any feature-* → feature-* edge (0.6 s)
./gradlew checkFeatureBoundaries

# Lint gate: no baseline — any violation fails
./gradlew ktlintCheck ;  ./gradlew ktlintFormat   # format is the fix path

# Android unit suites (Robolectric). NOTE: the test JVM's tmpdir defaults to /tmp;
# on this workstation /tmp is a 16 GB tmpfs that fills up, and every Room/Robolectric
# test then fails with "Unable to load Robolectric native runtime library" — a
# pre-existing environmental failure, verified identical on a clean HEAD worktree.
# Free /tmp, or point the test JVM elsewhere:
./gradlew --init-script <(echo 'allprojects { tasks.withType(Test) { jvmArgs "-Djava.io.tmpdir=/some/roomy/dir" } }') \
          :core-persistence:testDebugUnitTest :feature-player:testDebugUnitTest
```

Instrumented suites stay manual (device staging: [build.md](../build.md)).
