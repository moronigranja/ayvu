# Architecture

Companion to [modules.md](modules.md) (module list) and [conventions.md](conventions.md)
(patterns). This is the load-bearing structure: dependency graph, data flows, and the
contracts every change must respect. If a change bends a contract, update this doc with
the change.

## 1. Guiding shape

- **Pure core, thin edges.** All business logic lives in `core-*` modules, unit-testable
  without a device. Most are pure JVM (no Android dependency at all: `core-model`,
  `core-ebook`, `core-locate`, `core-tts`, `core-translate`, `core-player`, `core-ocr`,
  `core-backup`); three are `android.library` because their responsibility *is* the
  platform edge — `core-persistence` (Room), `core-ui` (Compose), and `core-llm` (a
  vendored llama.cpp native build). Android otherwise appears only as thin adapters:
  activities/receivers (feature-*), SAF plumbing, Compose UI, Hilt wiring (app).
- **One canonical domain model.** `core-model` (Book, Chapter, TextPassage,
  LibraryEntry) is the single vocabulary; no module defines its own duplicate types.
- **Behaviour coverage.** Host-testable behaviour is covered by a test that fails
  without the change (conventions.md §Definition of done). Neither half is absolute:
  `core-model`'s store contract and `core-llm`'s prompt shape got their first tests in
  the 2026-09-15 cleanup (decisions #163), while `feature-ocr` (Tesseract native init) and
  `spike-tts` (device benchmarks) have no host-testable surface, and there is no coverage
  *metric* yet — the cleanup's verification-floor pass adds per-module floors at today's
  numbers rather than a target percentage.

## 2. Modules & dependency graph

```
core-model      canonical domain: Book(id, title, authors, chapters), Chapter, TextPassage, LibraryEntry
core-ebook      EBookParser + EBookFormats + EpubParser/MobiParser → Book;
                BookSegmentation (grain, front/back matter); BookImporter (parse→segment→index)
core-locate     TextIndex, TextMatcher, TextNormalizer, MatchResult; IndexRebuilder (launch-time sync)
core-ocr        (live) Tesseract behind OcrEngine/TesseractOcrEngine + stager; six pinned tessdata_fast 4.1.0 LSTM packs (#186)
core-tts        (live) TTSEngine interface + pack registry; model/language-pack download, verify + caching
core-player     (live) v1 player state machine: transport, transactional writes, ring, sleep timer, bookmarks; PlayerStore contract; A5 single-writer command model (generations); PregenQueue + PregenPlanner + PregenKey, PcmPassageCache (A4 LRU), PregenStorage façade; read-in display projection (ChapterDisplay) + TranslationService/TranslationTarget (#166)
core-persistence (live) Room v4: books, cached passages, progress (offset+speed), settings, bookmarks, position_history, activity_seconds (#157), translations (#166); LibraryStore + PlayerStore impls; ImportCoordinator/IndexLock boundary (A3); BackupStore snapshot/merge + BookFileStore sidecars (E1, #111)
core-ui         (live) AyvuTheme design tokens (B1, #68): brand light/dark color roles, typography, shapes, spacing, motion, elevation; shared components (PlayerCard, BookCover, PillButton, ConfirmDialog, EmptyState, LoadingState, SectionHeader, LabeledProgress, CoverageProgress, formatPercent); no business logic/ViewModels; depends on core-player + core-tts
core-backup     (live) versioned v1 backup archive codec + DTOs — BackupSnapshot/BackupCodec (E1 phase 1, #89); consumed by core-persistence (BackupStore) + feature-settings (SAF edge) — E1 complete (#111)
core-translate  (live) read-in-language seam (#114/#160/#162/#182): TranslatingEngine TTSEngine decorator (degrades to the original audio on any failure), language surface, translate ENGINE registry — the shipped LFM2.5-1.2B and the selectable LFM2.5-2.6B-Base, each with its own pack descriptor + staged bundle root — plus TranslatePackStager; model-agnostic by construction (a suspend translate lambda, with the selected engine named on every target); consumed by feature-player/feature-library/feature-settings/app
core-llm        (live, android.library + vendored native) the translate runtime's native leg (#162): llama.cpp pinned by tools/fetch-llama-cpp.sh into the gitignored build/llama.cpp-src, AGP externalNativeBuild (arm64-v8a only), LlamaTranslator (JNI session, single-flight, greedy) — opens the SELECTED engine's staged GGUF; consumed by feature-player
feature-library (live) SAF import + library list UI (Compose, Hilt) — C5/C6, F2 search (#90), F3 folder import via SAF tree (root + one level, 200-file cap, #108); row pre-gen action + usage/estimate/delete
feature-player (live, T4-2) PlaybackService (MediaSession, focus, foreground) + docked read-along ReaderScreen; PregenWorker/PregenManager single-mode manual pre-gen
feature-ocr     (live) TesseractOcrEngine (Tesseract4Android 4.9.0) + TessDataStager + Hilt; LSTM packs (#186)
feature-settings (live) settings screen, packs download UI, voice picker + favorites, offline-audio section, "Backup & restore" SAF export/import (E1, #111)
feature-share   (live) ACTION_SEND gateway (text+image), typed resolver, found/not-found UX, OpenTarget + listen-from-here
app             (live) Hilt composition root (app.di owns shared infrastructure, A6): PersistenceModule, import-core providers, OcrModule, BackupModule (BookFileStore + BackupStore, E1); first-run SetupScreen (C1, voice-step dropdown #112; engine-aware required packs + engine radio #159); MainActivity → LibraryScreen; checkFeatureBoundaries rejects feature-* → feature-* edges
```

Current dependency edges (representative, not exhaustive — `X ← Y` means Y depends on X.
The authoritative graph is the `project(":…")` entries in each module's
`build.gradle.kts`; the feature-boundary rule is enforced by
`./gradlew checkFeatureBoundaries`):

```
core-model  ←  core-ebook  (parsers return Book)
core-model  ←  core-locate (TextIndex consumes Book)
core-model  ←  core-persistence  (persists LibraryEntry; LibraryStore contract)
core-locate ←  core-ebook  (BookImporter indexes into TextIndex — the import contract)
core-persistence ←  feature-library  (Hilt provides the Room-backed LibraryStore)
core-player  ←  core-persistence  (RoomPlayerStore implements PlayerStore)
core-player  ←  feature-library  (Hilt provides the PlayerStore binding)
core-player  ←  core-ui     (tokens/components render player state; no business logic)
core-player/tts/persistence ← feature-player (PlaybackService + ReaderScreen drive the machine+engine)
core-ocr     ←  feature-ocr
core-ops     ←  core-tts  (PackInstaller reports through OperationReporter/OperationSpec)
core-ops     ←  feature-library / feature-settings / feature-player / app
                (operation entry points; the Android OperationRunner impl lives in app)
core-tts     ←  feature-settings (pack download UI drives the TtsPack flow)
core-locate  ←  feature-share (resolver queries TextIndex)
core-ebook  ←  feature-library  (SAF sources → BookImporter)
feature-library/settings/share/ocr ← app  (app wires the composition root)
core-backup  ←  core-persistence  (BackupStore snapshot/merge, E1 #111)
core-backup  ←  feature-settings  (BackupViewModel codec edge, E1 #111)
core-locate  ←  feature-settings  (post-restore index resync, E1 #111)
```

Rules:
- `core-*` modules contain no `android.*` imports, no framework — stdlib/JDK only.
  Three exceptions, each because its responsibility is the platform edge itself:
  `core-ui` (the shared Compose surface — tokens + stateless components, still no
  business logic, stores or ViewModels), `core-persistence` (Room), and `core-llm`
  (a vendored llama.cpp native build).
- Dependencies point toward `core-model`; nothing depends on `app`/`feature-*`.
- `feature-*` never depend on each other; `app` wires them.
- A component lives in the module of its primary responsibility. Orchestration that
  spans modules (the import pipeline) lives in the module of its domain (core-ebook)
  rather than a new module, until a circular dependency forces a split.
- Add modules only when a circular dependency or real build isolation forces it
  (modules.md).

## 3. Content capability data flow

```
file (SAF) ─EBookSource─▶ EBookFormats.parserFor(fileName) ─▶ Parser.parse ─▶ Book (raw)
      ─▶ BookSegmentation.segment ─▶ TextIndex.add(book)  ─▶ LibraryEntry → Room
            (passages cached in the same transaction — the launch-time rebuild source)
```

- **Identity**: `Book.id` = SHA-256 of the container bytes. Content-addressed: no
  cloud, deterministic across machines, idempotent re-imports.
- **Passage grain**: the passage (paragraph; long passages split at sentence
  boundaries) is the unit of matching **and** of resume. Stable across re-parses.
  Front/back-matter chapters are stripped by segmentation (position-guarded).
- **Index contract**: import MUST run `BookSegmentation.segment` before
  `TextIndex.add` (docs/features/share-and-identify.md).
- **Failure contract**: bad input yields typed failures
  (`ImportOutcome.Failed` + `ImportFailureReason`), never throws, never mutates the
  index.

## 4. Identification capability data flow

```
shared snippet → normalize → word n-grams → recall vs every indexed passage
  → MatchResult(bookId, bookTitle, chapterIndex, chapterTitle, passageIndex, confidence) or null below threshold (0.6, configurable)
```

- `TextIndex`: in-memory, synchronized writes, snapshot reads (queries never block
  import); per-passage gram sets precomputed at add time; linear scan until the
  inverted-index follow-up. Populated on import and **rebuilt at launch** from Room's
  cached parses by `IndexRebuilder` — never re-parses a source file; mirror-set
  semantics (ids absent from the cache are purged), idempotent under concurrent
  imports (P2).
- OCR (live, core-ocr): Tesseract behind `OcrEngine` (`TesseractOcrEngine`), languages downloadable
  (`eng+spa+fra+deu+por+ita` start), screenshot downscale; feeds the same snippet path
  (S1 shipped, #36).

## 5. Concurrency model

- Coroutines: `Dispatchers.IO` for file/parse/OCR/engine work, `Main` for UI;
  cancellation propagates (long TTS queues, scans).
- Cross-thread state lives in the synchronized surfaces of `TextIndex`;
  query uses snapshots so concurrent import never blocks readers.
- Everything else passes immutable value types (Book, LibraryEntry, MatchResult).

## 6. Contracts that must not silently break

Every contract names what enforces it TODAY. "Review-only" is stated rather than implied:
a contract with no mechanical guard is one refactor away from breaking silently, and the
value of this table is that the gap is visible instead of assumed. When a change bends a
contract, update this table and the doc it points at, in the same change.

| # | Contract | Enforced by |
|---|---|---|
| 1 | **One domain model** — `core-model` types everywhere; no parallel Book/Passage. | **Review-only.** `checkFeatureBoundaries` guards feature→feature edges, not model duplication. The mechanical evidence is that exactly one `Book`/`Chapter`/`TextPassage` exists across every `src/main` (verified 2026-09-15); the other `Book*`/`Chapter*` types are persistence entities and view types. |
| 2 | **Content-hash identity** — the id comes from bytes, not metadata or file name. | `EpubParserTest` ("book id is a stable content hash"), `MobiParserTest` ("stable content hash across parses"). |
| 3 | **Import ⇒ index** — parsing without segment+index silently kills share-and-identify. | `ImportCoordinatorTest` plus the index assertions in the `core-locate` / `core-persistence` suites. |
| 4 | **Passages stable & bounded** — segmentation output must not drift between re-parses of the same file. | **Half-enforced.** The bounded half is pinned by `BookSegmentationTest` (front/back-matter windows, the whole-book guard, contiguous renumbering); the "the same file parses to identical passages" half has NO test — recorded here as a gap rather than hidden. |
| 5 | **TTS assets never bundled** — models/language packs are runtime downloads, explicit and resumable. | **By construction:** there is no `app/src/main/assets`, and every pack descriptor is a remote URL (hard-facts.md). A unit test cannot inspect the APK. |
| 6 | **DRM never in-app** — encrypted files are rejected up front; deDRM stays out-of-app. | `IntakeRoutingTest`, `EBookFormatsTest`, `FolderScanPolicyTest`, and the device-side `ExternalIntakeInstrumentedTest`. |
| 7 | **Share result = location** — a match carries (bookId, chapter, passage) and the player resumes there. | `IndexRebuilderTest` (bookId / chapterIndex / passageIndex), `ShareSnippetResolverTest`. |

The dated narrative this section used to carry (2026-08-26 → 08-28, plus the provenance
paragraph) was the decision ledger's material duplicated — every entry cited its own
decisions #N — so it lives in exactly one place, docs/decisions.md, which is the record of
what changed and when. This section holds only what must not break.
