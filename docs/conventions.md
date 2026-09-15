# Conventions

## Tech stack

- **Language:** Kotlin (JVM). No Java in new code.
- **Build:** Gradle Kotlin DSL. Version catalogs (`libs.versions.toml`).
- **Kotlin:** K2 compiler, `-Xjsr305=strict`, coroutines + `kotlin-flow`.
- **JVM target:** 17+. **minSdk 26** (needed for ONNX Runtime Mobile; document why).
- **Architecture:** MVVM + Repository, unidirectional-ish data flow.
  - UI observes `StateFlow`; user intents go one way in.
  - Business logic in `ViewModel`/`StateHolder`; keep it testable and UI-agnostic.
- **Concurrency:** Coroutines on `Dispatchers.IO` for file/model I/O,
  `Main` for UI. Cancellations matter (long TTS queues, file scans) — propagate them.
- **DI:** **Hilt** (decided 2026-08-24). Constructor injection everywhere; no
  hand-rolled container.
- **Persistence:** Room for the library index, reading progress, settings, and cached
  parsed content pointers. Never re-parse a book on every launch.
- **UI:** Jetpack Compose + Material 3. Keep composition functions pure; hoist state.
- **Audio:** Foreground playback service. Use a dedicated audio playback path with
  `AudioManager`/`AudioAttributes` (PLAYBACK_MEDIA), ducking/interruption handling,
  and MediaSession so lock-screen/Bluetooth controls work.
- **Naming:** camelCase code, snake_case resources/DB columns, PascalCase types,
  UPPER_SNAKE for constants. Resource IDs and package segments lowercase.

## Do and don't

**Do**
- Design against the abstractions in [modules.md](modules.md) (`EBook`, `TTSEngine`,
  `LibraryStore`), not concrete formats/models. New formats become another implementor,
  not new call sites.
- Keep the model download and file I/O off the main thread and cancellable.
- Persist reading progress and per-book settings; restoring where the user was is core.
- Respect Android storage/permissions norms; use SAF (`ACTION_OPEN_DOCUMENT`) for
  opening user files, scoped storage, and a foreground service notification for playback.
- When the spec is unclear, choose the conservative, reversible option and say why.

**Don't**
- Don't add network/cloud/account features without an explicit reason in
  [hard-facts.md](hard-facts.md).
- Don't automate or hide DRM removal. Don't ship keys, key-derivation, or DRM tools.
- Don't scrape the Kindle app on-device for reading progress — use the official export
  API or a manual resume point instead.
- Don't bundle TTS model weights or language/voice packs in the APK; download them once
  (explicit, resumable), cache, allow re-download.
- Don't ship a bare `android.TextToSpeech` system-TTS wrapper as the engine — the point
  is the open-weight on-device model. System TTS is acceptable only as a documented
  degraded fallback behind the same interface.
- Don't introduce a second convention (folder, pattern, DI style) when one already
  exists elsewhere. Match the nearest existing pattern.
- Don't leave dead parsers, dead engines, or half-migrated callers. Clean cutover.
- Don't write a regex that reaches the device with an UNBOUNDED lookbehind: text rules
  (e.g. `PronunciationNormalizer`) run under Android's ICU engine, which rejects
  `(?<!x+)`, while the host JVM accepts it — so it passes every local test and throws
  `PatternSyntaxException` at class load on the phone. Keep lookbehinds bounded and let
  the suite assert it (the G1 rules expose `patternSources` for exactly this).
- Don't trust a difference as evidence: an assertion that "the output changed" can pass
  on synthesis nondeterminism (fp32 ORT renders are not bit-reproducible). Assert the
  deterministic thing — the text that reaches the engine, or the phonemes — and, for a
  device harness, that the build under test actually carries the change (instrumented
  tests load production classes from the APP apk, so reinstalling only the test apk
  silently exercises the previous build).

## Definition of done for a change

- Behavior covered by a test that fails without the change.
- No silent fallbacks that mask failure (missing language, corrupt file → clear state).
- No new blocking permissions or dependencies without noting the tradeoff.
- `./gradlew ktlintCheck` and the module unit suites green. `ktlintCheck` runs the
  pinned ktlint CLI (1.7.2) over every module's sources with **no baseline** — any
  violation fails; `./gradlew ktlintFormat` is the formatter twin (2026-09-15
  cleanup). Pure-JVM core modules run `test`, Android modules run
  `testDebugUnitTest`. `./gradlew checkFeatureBoundaries` fails on any
  `feature-* → feature-*` edge. CI runs all three (`.github/workflows/ci.yml`).
- The relevant doc(s) updated if a convention or fact changed.
