# Changelog

Every Ayvu release, newest first. This is the long form: the GitHub release body for each
version is the short version of the same section (highlights plus a link back here), and
`docs/release-notes-<version>.md` keeps the release body as it shipped.

Versions are `versionName` from `app/build.gradle.kts`; `versionCode` increments by one per
release. The signing key is stable across every release, so a newer APK installs straight
over the previous one with the library, progress, bookmarks and settings kept.

---

## 0.2.1 — 2026-10-13

**Published 2026-10-13** — <https://github.com/moronigranja/ayvu/releases/tag/v0.2.1>.
Shipped `Ayvu-0.2.1.apk`, 52,312,680 B, sha256
`fd1adc8df9fbd8d2b38241e8f54cc1ed1604a0fb4c30b72305714bac18d7e2cb`, signer `a5057984…`
(CN=Ayvu — the same certificate as every release, so it updates in place). Decisions #199
(the reader reopen/immersive/preview reports), #200 (immersive by default, single-tap
chrome, word/phrase lookup), #201 (the display-cutout padding) and #202 (this release).

Four 2026-10-11 owner reports against the 0.2.0 reader and voice picker, then a batch of
reader changes, then the cutout fix.

### Reopening, foreground recovery, the immersive toggle, the voice preview (#199)

Three of the four reports shared one root cause in the reader's effect chain.

- **A listen session no longer freezes the reading place.** The reading-place write armed
  the presented-passage assertion at every fresh presentation and only disarmed it on a
  manual turn, so `book.reading.<id>` stayed at the open-time page for a whole playback
  session — and the open prefers that row over the audio resume row. It now tests "the
  asserted passage is on this page" by line-span overlap (a page break can land
  mid-paragraph) and drops the assertion once it lands, and gained a `totalLines` key so
  it re-evaluates after pagination settles.
- **The player mirrors the listened position** into the same row (`publish()`, non-IDLE,
  deduped to a passage change), so listening with the reader off-screen or a process
  death still reopens where listening stopped.
- **A dropped open recovers on the foreground return.** `LifecycleEventEffect(ON_START)`
  re-dispatches `open(bookId)` when the holder does not carry the book (the first
  `ON_START` is skipped), fixing the reader stuck on *Opening book…* after screen off/on
  when the service-side publish dropped and the bounded retry had given up.
- **The immersive toggle keeps the page.** The same un-disarmed assertion re-fired the
  snap on the toggle's reflow; the write-effect disarm fixes it. A ±1 passage shift
  remains the fixed-line-grid semantics of #166.
- **The voice preview plays the book's voice.** `VoiceAudition.preview(voice, bookId)`
  resolves under the book's engine, so a Piper global engine with a per-book Kokoro voice
  no longer previews the Piper default; the reader passes its book, Settings/Setup keep
  the global default.

### Immersive by default, single-tap chrome, word/phrase lookup (#200)

- **The chrome shows on entry, then hides.** `immersive` stays `false` on entry; a
  `LaunchedEffect(Unit)` shows the chrome for `CHROME_INTRO_MS = 3 s`, then hides it —
  unless the user already toggled or turned a page. `introDone`/`chromeTouched` are
  `rememberSaveable`, so a rotation neither re-runs the intro nor lets the timer fight a
  user action; `setImmersive` keeps the `barsSettled` top-cut hold.
- **One middle tap toggles; a page turn hides.** The middle-zone double tap is gone; a
  single middle tap toggles the chrome, and every page turn (swipe, side-zone tap,
  chapter jump) dismisses it.
- **Word/phrase lookup.** Long-press selects the word under the finger, drag extends it,
  and an anchored card offers **Translate** (into the book's display language; the
  selection must lie wholly inside one Original block — a range spanning the
  original/translation separator, or in a translation/placeholder block, gets a
  Copy-only card) and **Copy** (always). Offered only when a display language is set and
  the layout is interleaved; the selection is the app's own (`TextRange` + drag handles),
  because Compose's `SelectionContainer`/`Selection` are internal in the pinned
  foundation 1.11.4. `TranslationService.translateSnippet` decodes a fresh,
  never-persisted snippet for it.

### The display cutout no longer overlaps the page (#201)

- **The camera hole clears the text.** On Android 15+ the app is edge-to-edge
  (`targetSdk = 36`) with the window forced to `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`; in
  immersive mode the system-bar insets are zero and nothing padded for `displayCutout`,
  so the floating title overlay and the body text ran into the camera punch-hole/notch.
  The reader's content root now applies `Modifier.displayCutoutPadding()` gated on
  `showOverlays`, moving the title overlay, the body and the bottom player overlay clear
  in one edit; regular mode is untouched (the status bar contains the cutout there). A
  no-op without a cutout and on Android ≤ 14, where the app is not edge-to-edge.

---

## 0.2.0 — 2026-09-28

A per-book reading place restored on open — the page last seen, not the chapter's first —
with an open that says so when it fails instead of hanging; a per-book voice *and* engine
override; resume after a pause rewinding by how long you were paused; and two-decimal
long-operation progress. `versionCode` 5 → 6.
Release body: [`docs/release-notes-0.2.0.md`](docs/release-notes-0.2.0.md) · decisions
#196–#198.

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
