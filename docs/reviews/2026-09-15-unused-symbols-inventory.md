# Unused-symbol inventory — cleanup pass 5a (2026-09-15)

Method: plain-text search over the repo (GitLab-style `grep`, `build/` excluded, no
Gradle/Android tooling run). A hit inside the symbol's own file, or in any test source
set, does not disqualify a row; a hit in another `src/main` file does. Used-by-construction
surfaces (`@Inject`, `@Provides`, `@Composable`, Room members, Hilt bindings, `external`
JNI, interface `override`s) were excluded from candidacy.

**No unused files. No vestigial parameters. No dead test seams.** Every target file has at
least one consumed declaration; the only `@Suppress("unused")` is `EspeakPhonemizer.dataMemory`
(a documented JNA pointer-lifetime hold, not a vestige); no `@VisibleForTesting` exists at
all, and every `internal` seam checked is referenced from a test.

| module | symbol (kind) | zero-reference evidence | confidence | disposition |
|---|---|---|---|---|
| core-ocr | `TessDataStager.unstage` (public fun) | pattern `.unstage(|fun unstage` repo-wide, excludes `build/` — 1 hit, its own definition (TessDataStager.kt:61); no test, no caller | high | **delete** |
| feature-share | `OpenTarget.DEFAULT_CHAPTER` (const) | `DEFAULT_CHAPTER` repo-wide — 1 hit, its own file (OpenTarget.kt:18); MainActivity hardcodes `-1` | high | **delete** |
| core-tts | `KokoroVoiceMetadata.missingFrom` (public fun) | `missingFrom` — own file (KokoroVoiceMetadata.kt:204) + own test; its KDoc says "never required at app runtime" | high | **delete** |
| core-ebook | `OpfBookReader.parseXmlPublic` (public fun in an `internal object`) | `parseXmlPublic` — own file (OpfBookReader.kt:231) + own test; the `public` modifier is redundant | high | **make private** |
| core-tts | `PackRegistry.packsFor` (public fun) | `packsFor` — own file (PackRegistry.kt:54) + own test; production iterates `registry.engines()` | high | **delete** |
| core-tts | `SetupEnginePacks.kokoroIds` (public val) | `kokoroIds` — own file only (used inside `requiredIds`); callers re-hardcode the list | high | **make private** |
| core-ui | `languageLabel` (top-level fun) | `languageLabel` — own file only (consumed in the same file's picker) | high | **make private** |
| core-tts | `KokoroVoiceBank.style(name)` (public fun) | `.style(`/`fun style(` — own file + own test; production uses `styleFor` | high | **delete** (inline in the test) |
| core-player | `PackCache.directory` (public fun) | `cache.directory` — own file (used by `targetFile`) + own test; no external caller | high | **make private** |
| core-player | `EspeakStager.bundleDir()`/`libFile()`/`dataDir()` | external refs exist only for `isStaged`/`stage`; the three path helpers are file-local (runtimes hardcode `files/espeak/...`) | high | **make private** |
| core-ebook | `BookSegmentation.splitLongPassages`/`wordCount` (public funs) | own file (used by `segment`) + own tests only | medium | **narrow to internal** |
| core-tts | `VoiceCatalog.names()` (public fun) | `.names()` — **no matches anywhere**; `VoiceCatalog` is only constructor-injected and `invalidate()`d | high | **owner call** — the "echo the real pack roster" mechanism is superseded by the static voice tables |
| core-player | `ReadingSpanTracker` (public class) | `ReadingSpanTracker` — **zero production references**, only its definition (ActivityCapture.kt:135) + its test + docs | high | **owner call** — a finished, tested feature (decisions #109/#157) with no edge consuming it; the *stats* dwell path is wired through `PlaybackService.activitySink`, this span tracker is not |
| core-persistence | `AppSettings.bookVoice()`/`setBookVoice()` | `bookVoice(`/`setBookVoice` — definitions (AppSettings.kt:82,87) + tests only; production READS `state.value.bookVoices[bookId]` directly (EngineSelector:83) | high | **owner call** — the per-book voice override (decisions #144) is read in production and settable only from tests: nothing in `src/main` calls the setter |
| core-persistence | `SettingsStore.bookVoice()`/`setBookVoice()` | same pattern — definitions (SettingsStore.kt:51,55) + `AppSettings` + tests; `reload()` uses the plural `bookVoices()` | high | **owner call** (same seam as above) |
| core-player | `PcmPassageCache.totalBytes()` | `totalBytes` — own file + unit test + device E2E only; tier gating uses `bytesRemaining()` | high | **keep** — contract seam pinned by unit + device cap invariants |
| core-tts | `KokoroEngine.SAMPLE_RATE` | own file + tests/benchmarks + device E2E; no production cross-file caller (consumers keep a private 24_000) | high | **keep** — the duplication is deliberate and documented (S5, decisions #77) |
| core-player | `PregenSpaceEstimator.sampleRateHz` | own file + own test + device E2E | medium | **keep** — test-pinned per-engine rate map |

## The four owner calls, with the extra check I ran

The setter's call sites were re-checked independently of the scan above:

```
core-persistence/src/main/kotlin/com/moronigranja/localttsreader/persistence/AppSettings.kt:87:        suspend fun setBookVoice(
core-persistence/src/main/kotlin/com/moronigranja/localttsreader/persistence/AppSettings.kt:91:            store.setBookVoice(bookId, voice)
core-persistence/src/main/kotlin/com/moronigranja/localttsreader/persistence/SettingsStore.kt:55:    suspend fun setBookVoice(
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/AppSettingsTest.kt:136:            settings.setBookVoice("b1", "de_DE-thorsten-high")
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/AppSettingsTest.kt:145:            settings.setBookVoice("b1", null)
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/BookVoiceOverrideTest.kt:53:            store.setBookVoice("b1", "de_DE-thorsten-high")
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/BookVoiceOverrideTest.kt:70:            store.setBookVoice("b1", null)
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/BookVoiceOverrideTest.kt:80:            store.setBookVoice("b1", "de_DE-thorsten-high")
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/BookVoiceOverrideTest.kt:81:            store.setBookVoice("b2", "en_US-lessac-medium")
core-persistence/src/test/kotlin/com/moronigranja/localttsreader/persistence/BookVoiceOverrideTest.kt:112:            source.setBookVoice("b1", "de_DE-thorsten-high")
```

- **Per-book voice override (decisions #144)** — production *reads* the override
  (`EngineSelector.effectiveVoice` → `state.value.bookVoices[bookId]`), but **every call site
  of the singular setter lives in test sources**: in `src/main` it is only DECLARED
  (`AppSettings.kt:87`) and delegates down (`SettingsStore.kt:55`). So the override is
  read-only in practice — the honest resolution is either to wire a writer or to drop the
  seam, and that is a product decision, not a cleanup.
- **`ReadingSpanTracker`** — the dwell *statistics* path is live through
  `PlaybackService.activitySink`; this span-level tracker is not consumed by any edge.
- **`VoiceCatalog.names()`** — the pack-roster echo is superseded by the static voice
  tables; only `invalidate()` survives.
- **Six "keep" rows** are deliberate: `PcmPassageCache.totalBytes`, `KokoroEngine.SAMPLE_RATE`,
  `PregenSpaceEstimator.sampleRateHz` are test/device-pinned seams, and the sample-rate
  duplication is recorded in decisions #77.

Deliverable of pass 5a: the LIST. Nothing here is deleted without the owner's word — the
cheap twelve are one-line visibility/removal changes, the four owner calls may imply wiring
a feature rather than removing code.
