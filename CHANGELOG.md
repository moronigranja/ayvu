# Changelog

Every Ayvu release, newest first. This is the long form: the GitHub release body for each
version is the short version of the same section (highlights plus a link back here), and
`docs/release-notes-<version>.md` keeps the release body as it shipped.

Versions are `versionName` from `app/build.gradle.kts`; `versionCode` increments by one per
release. The signing key is stable across every release, so a newer APK installs straight
over the previous one with the library, progress, bookmarks and settings kept.

---

## 0.2.0 — 2026-09-28

Prepared 2026-09-28. Ships as `Ayvu-0.2.0.apk` on the `v0.2.0` release; the size and
SHA-256 are pinned in that release's notes from the `tools/release.sh --upload` output.
Decisions #196 (the batch) and #197 (this release and the notes shape).

Owner batch reported 2026-09-27: *"when opening the app back, it sometimes hangs on the
loading, then pressing back goes back to the library and opening the book again restarts
the chapter"*, *"engine/voice does not seem to be saving per book"*, the two-decimal
progress request, and the pause-resume rewind request.

### Reading place — reopening a book lands where you were

- **A per-book reading place, separate from the playback position.** The reader records
  the visible page (chapter + chapter-local passage) in `book.reading.<bookId>` on every
  page change — manual turns, follow turns, chapter changes and reflow snaps. It is
  written while merely *reading*, so a book that was never played still reopens where it
  was left; the audio resume row stays what pressing play uses. The key rides the existing
  generic settings table (no migration) and is dropped with the book.
- **An open presents the reading place**, when it is still valid for the current layout,
  and only falls back to the audio row and then the book's start.
- **The reader pages to the presented passage.** The chapter was restored before, but the
  page was not: the follow effects only track the active sentence *during playback*, so a
  reading open always showed the chapter's first page — and that first frame then
  overwrote the stored place. The reader now snaps to the presented passage's page
  (re-applied until pagination settles), disarms the snap on a manual turn, and skips it
  while playing or paused, so a pause still never moves the view.
- **A reopen can no longer clobber another book's place.** For a frame while an open is in
  flight the state holder still carries the previous book; the reader now refuses to
  record a position that belongs to a different book.
- **A failed open says so.** `ACTION_OPEN`/`ACTION_OPEN_CHAPTER` publish a terminal
  *"Could not open this book."* instead of returning silently, so the reader lands on its
  error branch rather than an endless `Opening book…`; the reader also re-dispatches a
  stuck open at most twice, 4 s apart, and cancels that when the open lands or fails.
- **Playing from the reader after a pause resumes** (through `ACTION_RESUME`) instead of
  restarting the visible page, so the rewind below applies to the on-screen play button.

### Per-book voice *and* engine

- **The reader's voice sheet no longer rewrites your global default.** Selecting a voice
  there writes `book.voice.<bookId>` (the per-book override decisions #144 introduced at
  the persistence/resolution layers but no production caller ever wrote — the sheet wrote
  the global). The sheet's voice dropdown gained a **Use book default** entry, which
  clears the override.
- **New per-book engine override**, `book.engine.<bookId>`. Resolution is book-scoped
  end to end: `EngineSelector.engineIdFor / isDegraded / engine / engineFor /
  failureReason / resolveVoice / activeCatalog`, the play and resume gates, the
  pre-generation worker (a per-book gate instead of one pre-loop check) and the offline
  size estimate all follow the book's engine, defaulting to the global. **This supersedes
  decisions #144's "no per-book engine override".** Settings and first-run setup keep
  writing the global engine.

### Pause → resume rewind

- **Resuming after a pause rewinds by how long you were paused** — under a minute ≈ 3 s,
  under an hour ≈ 10 s, under six hours ≈ 20 s, otherwise ≈ 30 s — never before the first
  passage of the current chapter. `PauseRewind` is a pure policy object, so the curve is
  one file.
- The pause instant is persisted per book (`book.pausedAt.<bookId>`); the resume consumes
  it, and every user-directed move (open, navigate, seek, undo, voice/engine change, stop,
  an explicit play target) clears it so a stale rewind can never fire.
- `PlayerStateMachine.notePosition` commits the rewound position without changing the
  phase or touching the undo ring.

### Two-decimal progress (Phase L)

- **Long-operation progress shows two decimals** (`%.2f%%`) through one shared
  `formatProgressPercent`: the library pre-generation row, the pre-generation
  notification, and the export notification text (`k/N passages (42.37%)`).
- The producers carry a 0..1 fraction rather than an integer percent
  (`PregenProgress.fraction`, the `progressFraction` transport key, `PregenJobState`).
  Notification **bars** stay integer — the system draws a bar, not a number.

### Verified

- Host: `core-player`, `core-persistence`, `core-ui`, `feature-player` and
  `feature-library` suites green (new `PauseRewindTest`, `notePosition`, the open-failure
  and pause-rewind service tests, per-book `EngineSelector` cases, the bounded open-retry
  cases, `formatProgressPercent`), plus `ktlintCheck` and `checkFeatureBoundaries`.
- Device (S22, debug build, staged packs): reopen lands on the page last read — including
  after a force-stop — with the stored place preserved; a cross-book visit leaves both
  books' places intact; the reader sheet writes `book.voice.<id>` and `book.engine.<id>`
  while the globals stay untouched, and a second book shows the globals; pause → resume
  moved the committed offset 11.16 s → 8.18 s and cleared the pause marker.
- Instrumented regression on the S22: `ReaderRotationReplayE2eTest`,
  `OpenChapterE2eTest`, `PlayPositionE2eTest` and `PlaybackE2eTest` all pass.

---

## 0.1.4 — 2026-09-23

Translated-book export (Markdown / plain text / EPUB 3 from a library row), the OCR
binding swap to Tesseract 5.5.1 with `tessdata_fast` 4.1.0 LSTM packs (the retired
generation reclaimed on upgrade), the share-match threshold default lowered 0.6 → 0.3,
bookmark jumps keeping their in-passage offset, the skippable first-run import step, three
playback-target fixes (rotation replay, stale long-press mapping, out-of-layout play
target) and cancellation-no-longer-a-failure. `versionCode` 4 → 5.
Release body: [`docs/release-notes-0.1.4.md`](docs/release-notes-0.1.4.md) · decisions
#184–#194.

## 0.1.3 — 2026-09-20

The selectable second read-in-language engine (LFM2.5-2.6B-Base beside the shipped
LFM2.5-1.2B, each with its own pack release), the dry-buffer partial wake lock, the
reader play-while-loading fix and the display-language staleness fix. Notes in the concise
shape decisions #179 fixed.
Release body: [`docs/release-notes-0.1.3.md`](docs/release-notes-0.1.3.md) · decisions
#180–#183.

## 0.1.2 — 2026-09-17

Long operations as foreground work (downloads, pack unpacking and import with progress
notifications and Stop; the pre-generation and generation notifications gained Stop; the
stagers became cooperatively cancellable).
Release body: [`docs/release-notes-0.1.2.md`](docs/release-notes-0.1.2.md) · decisions
#177–#178.

## 0.1.1 — 2026-09-17

First signed release: arm64-v8a only (the espeak-ng phonemizer is an arm64 native
library, and the other ABIs were ~114 MB of dead weight), 165.2 MB → ≈52 MB payload, with
the release gate closed — restore hardening, MOBI decompression ceilings, `NOTICE.md`
completed and `LICENSE` + `NOTICE.md` shipped inside the APK, the espeak-ng archive
carrying its own licence and source offer.
Release body: [`docs/release-notes-0.1.1.md`](docs/release-notes-0.1.1.md) · decisions
#126, #128, #174.
