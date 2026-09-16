# Feature plan: read-in-language display (translation in the reader)

**Goal.** Show the translation in the reader instead of only *hearing* it. Today the
translation exists solely inside the TTS path: `TranslatingEngine` swaps the utterance and
the reader keeps rendering the original, so the app narrates text the user cannot see.
This slice separates *what is displayed* from *what is spoken*, and makes translated text
a first-class, cached, readable artifact.

**Relationship to what shipped.** Read-in-language itself is live (#114/#160/#162): the
per-book target resolves in `EngineSelector`, `TranslatingEngine` degrades to the original
on any failure, and the audio cache carries `x<lang>` / `t<translator>` identity. This
slice adds the display half and moves translation out of the synthesis path.

## Product decisions (owner, 2026-09-15)

- **Presentation: interleaved single column**, plus a **translated-only** option. Not a
  top/bottom split — that would need a second measurement/slice pipeline and per-pane sync
  that the single column gets for free.
- **Audio is constrained to Off or the display language** (a passage is translated AT MOST
  once): the display translation and the audio translation are the SAME stored artifact,
  so speech and display can never name two different translations of one passage. The
  constraint is one choke point (`EngineSelector.translateTarget`) plus write-time
  normalization, with per-book keys `book.display.<bookId>` (display) and
  `book.translate.<bookId>` (speech, unchanged).
- **Differentiation: colour only** (`onSurfaceVariant`). *Not* font size — see the
  pagination constraint below. The originally-planned paragraph indent was dropped on
  device: Compose expands a `ParagraphStyle` span to its containing paragraph, so a
  clipped paragraph span on a page slice can end beyond the slice text
  (`StringIndexOutOfBounds` in `substringWithoutParagraphStyles`); character styles never
  affect measurement, so the render's wrap stays identical to the measured chapter.
- **Bookmarks must survive any language configuration.** A bookmark taken with one
  language must open with two, and vice versa.
- Future (planned for, not built here): **export translated book**.

## The invariant this slice must not break

`PlaybackUiState.chapterPassages` documents it (decisions #165): the list is
**index == passage index, original-language text**, and every reader path plus every
persisted pointer keys off that positional identity. `Bookmark` and the resume row both
store `(bookId, chapterIndex, passageIndex)` and re-resolve through `BookLayout.isValid`
against a freshly reloaded original `Book` (`PlaybackService.openPosition:481-516`).

**Therefore a displayed translation is a separate projection, never an interleaved
`chapterPassages`.** Interleaving that list would silently re-point every bookmark, resume
row and share match in the book. This is the single most load-bearing rule in the slice.

## Constraints

- The reader's pagination is a **uniform line grid**: one chapter measured once, pitch
  sampled from lines 0→1 (`ReaderScreen.kt:578-583`), pages sliced by line index
  (`TextPagination`). Anything that changes a block's line *pitch* breaks it — hence
  colour/indent, not font size.
- **llama decode is not cancellable mid-decode** — ~2.7 s tail per teardown
  (`llama_translator_jni.cpp:211-233`). Display translation can be *superseded* but not
  *abandoned*; the design must tolerate a stale result arriving late.
- `translate()` is **greedy** (temp 0, no seed knobs, `MAX_TOKENS=400`) — deterministic for
  a given (text, model), which is what lets a text cache be keyed rather than invalidated.
- **Room is durable truth; caches are derived.** Migration policy is forward-only additive
  (`LibraryDatabase`/`Migrations` KDocs, decisions #22); a bump ships a migration + test.
- No cloud, no account, no telemetry. Translation stays on-device.

## Data flow (implemented)

```
reader page (blocks ── ChapterDisplay.project(chapterPassages, translations, mode))
    │   seed:  TranslationService.cached(bookId, ch, p, target)   (Room, no LLM)
    │   fill:  prefetch(bookId, ch, [current+next page], target)  (display priority)
    │   land:  ready ──► TranslationReady ──► translation block
    │   audio: TranslatingEngine ↦ translate(bookId, ch, p, target)  (SAME artifact)
    ▼
text store (Room, `translations` v4, keyed book/chapter/passage/lang/translatorVersion)
    ▼
ChapterDisplay.join/offsets ──► PaginatedChapter renders the three modes
```

The service's shipped surface is `cached / translate / prefetch / ready :
Flow<TranslationReady> / translatePossible`. Display works progressively: `cached` seeds a
chapter, `prefetch` fills the current + next page at display priority (yielding to
`PlaybackActive.engineInUse` within a bounded quiet window — the service prefill holds the
flag for its whole session, so an unbounded retry would starve display forever), `ready`
lands each passage's text, and `translate` is the cache-first audio path with keyed
in-flight dedupe. Decodes run on the service's `Dispatchers.Default` scope — never the
`AyvuPlayer` thread — and are superseded by identity, never cancelled (the native decode
is not interruptible).

Audio path: `TranslatingEngine` is a *consumer* of the same service instead of owning the
LLM call — the display translation and the speech translation are one stored artifact.

## Steps

Ordered by dependency. Step 1 must precede the rest because everything else edits the
collaborator it creates. The steps below are the ORIGINAL scoped plan; the shipped design
differs where the Status / Resolved-decisions sections note it (service interface shape,
module placement, colour-only differentiation, no eviction, prefetch).

### 0 — `TranslationTarget` value type (cheap, do first)

`(translateLang, translator)` travels as **two loose nullable Strings** through
`PregenPlanner`, `PregenQueue`, `OfflinePregen`, `PregenSpaceEstimator`,
`PlaybackService.livePregenKey` and `PregenWorker`, always set together (the translator is
derived by `translateLang?.let { PregenKey.LFM_TRANSLATOR }` — exactly three production
sites). Unify into one value type carried inside `PregenKey`.

Buys: the translator **version** dimension (needed by step 3's cache key) becomes one edit
instead of six, and the impossible "lang null, translator set" state that `PregenKey`
guards against at parse time is deleted.

### 1 — Extract the reader-text collaborator, with a display-block seam

`PlaybackService.stateCopy` (`:1261-1326`) is the **sole** producer of every text field the
reader sees, reading the in-memory `book` synchronously. This is cleanup pass 4's
publication/text extraction, re-scoped by this slice (the roadmap's original wording
assumed an audio-only slice). Gate is discharged: #164 cleared D5 + G1.

The seam emits **ranged display blocks**:

```kotlin
data class DisplayBlock(
    val passageIndex: Int,   // ORIGINAL-keyed: index into chapterPassages
    val kind: Kind,          // Original | Translation
    val charRange: IntRange, // into the joined display text
    val text: String,
)
enum class Kind { Original, Translation }
```

Ranges are not decoration: the reader re-applies spans clipped per page
(`ReaderScreen.kt:746-768`) and derives sentence spans from char offsets, so styling by
kind needs block ranges at render time. The seam must **own** offset computation and
**replace** `computePassageOffsets` (`:1035-1056`), which hardcodes `passage.length + 2`
for the `"\n\n"` join — any richer separator silently corrupts every downstream offset
(the long-press menu, `activeSentenceRange`, play-from-view).

Navigation stays original-keyed: `chapterPassages` is unchanged and `chapterBlocks` is the
display projection. `bookPassageIndex` / `bookPassageCount` / the "Passage X/Y" indicator
stay original-keyed (decisions #165).

### 2 — `TranslationService`

```kotlin
interface TranslationService {
    /** Translated passage text, or null when translation is unavailable. */
    suspend fun translate(bookId: String, chapter: Int, passage: Int, target: TranslationTarget): String?
}
```

It must own four things today's code does not have:

- **Keyed cache lookup**, so a repeat read is free. Today there is **no text-level memo** —
  re-translation is avoided only incidentally, when that passage's *audio* is already
  cached. A reader that re-opens a chapter would otherwise re-run the LLM.
- **Keyed in-flight dedupe** — precedent: `PregenQueue`'s `inFlight` set (`:60-62`). Today
  single-flight is only the session mutex, so two callers for the same passage queue up and
  both decode.
- **Priority: playback > pregen > display.** Precedent: `PlaybackActive.engineInUse`
  (`:15-46`). Without it, reader page turns starve the pregen queue.
- **Thread discipline**: display translation must not run on the `AyvuPlayer` thread — the
  sync cold path already calls `synthesize` there (`:932`, `:1801-1803`), so a translation
  started from the reader would block player commands.

Cancellation semantics are deliberately *not* cancel: the decode cannot be interrupted, so
the service drops stale results by identity + generation instead of trying to stop them.

Availability needs a **display-side sibling** of `TranslateAvailability` (`:29-38`), which
is voice-*and*-translator shaped: showing a translation needs the translator pack only, not
a voice that can speak the target.

### 3 — Translated-text store

New Room table mirroring the bookmarks trio (`BookmarkEntity` / `BookmarkDao` /
`RoomPlayerStore` mapping), keyed
`(bookId, chapterIndex, passageIndex, lang, translatorVersion)`, with `MIGRATION_3_4`
(Room is at v3). Files to touch: `LibraryDatabase` (entity + accessor),
`Migrations` (3→4), the new entity/DAO, a store contract + Room impl, `PersistenceModule`,
and the book-delete housekeeping path.

Two decisions it forces:

- **Eviction.** Unlike the PCM cache (`PcmPassageCache`, 4 GiB, `files/pregen/`, numeric
  `.meta`, no text), a text hit costs 121–1352 ms/passage to regenerate. Losing it is far
  more expensive than losing audio, so the cap and eviction order need stating, not
  inheriting.
- **Backup.** The v1 codec has 7 *required* sections + an optional `books/` dir. Adding a
  required section breaks v1, so translated text rides as an **optional** section — or is
  excluded as derived state. Recommendation: include it (small next to book bytes, and it
  is the most expensive derived state in the app to rebuild); either way the choice is
  explicit.

### 4 — Publish blocks; split the settings key

- `book.translate.<bookId>` **stays the speech target**, unchanged — no migration, no
  behaviour change. A second per-book key (proposed `book.display.<bookId>`) is the display
  target. New settings keys need **no Room migration**: `SettingsStore` is typed accessors
  over the generic key/value table.
- `ReaderViewModel.setTranslateTarget` currently persists *and* rebuilds the book through
  `changeVoice` (`:161-172`), because changing the target changes the audio. A
  **display-only** change must not rebuild the player: it is a text re-projection, not a
  session rebuild. Two setters, two rebuild policies.
- UI: the voice sheet's "Read in" section (`ReadInLanguagePicker`, `ReadInLanguageUiState`)
  becomes display + speech. Needs its own small IA decision — one control plus a toggle, or
  two pickers.

### 5 — Reader rendering

Three modes over the one block list: original-only, interleaved, translated-only — a
projection of `chapterBlocks`, no second pagination pipeline.

Differentiation, ranked by safety against the line grid:

- **Colour** — `MaterialTheme.colorScheme.onSurfaceVariant`, the repo's established
  secondary-text token. Zero metric impact.
- **Paragraph indent** — `addStyle(ParagraphStyle(textIndent = …), blockRange)`. Changes
  width, not pitch. Metric-safe.
- **Not font size** — `lineHeightPx` is sampled from lines 0→1 and used as the grid pitch
  for the whole chapter; a differently-pitched block makes `linesPerPage` wrong for those
  lines. A drawn separator has the same problem: the page renders a char substring through
  one `Text`, so a rule must live *in* the text flow to remain a line in the grid.

The spoken-language independence falls out cleanly: with original audio the anchors come
from the original render, so the highlight is applied to the original pane and the
sentence alignment is *correct* by construction — retiring the #101 highlight drift, which
only exists while the utterance is translated.

## Module placement

No new Gradle module — `modules.md`'s rule is to add one only when a cycle or build
isolation forces it, and this slice forces neither.

- **`core-player`** takes the `TranslationService` **interface** (next to `PregenKey`, the
  pre-generation/cache-identity seat) AND the `ChapterDisplay` projection/`DisplayBlock`
  types (beside `TextPagination`). The interface lives here, not in core-translate:
  core-persistence already depends on core-player, and core-player and core-translate do
  not see each other — core-translate would have forced a new module edge in one direction
  or the other.
- **`feature-player`** holds the implementation that binds the service to the app: the
  `LlamaTranslator` session (reused through `TranslateRuntime` — no second session), the
  Room-backed text store lookup, the keyed single-flight map, the priority gate and
  `TranslatingEngine`'s consumption. This mirrors where `TranslateRuntime` lives now.
- **`core-persistence`** takes the entity/DAO/store for the `translations` table
  (`MIGRATION_3_4`) and the backup optional section; **`core-ui`** takes the picker
  additions only (the differentiation is applied in the reader's own text styling).

## Ancillary concerns (each verified in source)

1. **Non-cancellable decode** (~2.7 s tail) — supersede by identity, never cancel.
2. **No text memo today** — this slice fixes an existing cost bug, not just a new need.
3. **Reader vs pregen race** — unmeasured. Co-residency of llama.cpp and Kokoro passed on
   the S22 (#162), but a display request racing the pregen queue has no measurement and no
   priority rule today.
4. **`AyvuPlayer` thread is off-limits** for display translation.
5. **Book-scoped settings leak on delete (pre-existing defect).**
   `RoomLibraryStore.delete` drops progress/bookmarks/history/activity/passages/book but
   **not** `book.*` settings rows, though `SettingsStore` and decisions #156 claim it does.
   The new display key would inherit the leak — fix it in this slice.
6. **A model bump invalidates the text cache.** #162's byte-identical gate means a
   translator swap must miss old entries; that is what the key's `translatorVersion`
   dimension is for (and why step 0 comes first).
7. **Export must not be foreclosed.** The store is ordered and queryable per book, so a
   future whole-book text batch is an `OfflinePregen`-shaped job — but it needs a named,
   versioned artifact, and a listen-quality translation is not automatically
   export-quality.

## Acceptance

- A book with a display target shows original + translation interleaved, indistinguishable
  in layout from the original-only mode except for the chosen differentiation; the
  translated-only mode shows the translation alone.
- Page turns, the passage indicator, follow, play-from-view and the long-press menu all
  behave identically in every mode and after any target change.
- **A bookmark taken in one mode opens at the same passage in every other mode**, and the
  same holds for the resume row across a process restart.
- Reading a passage a second time — after a process restart — does **not** re-run the LLM
  (observable as no translator session open, or a store hit).
- Display and speech targets are independent: reading the translation while hearing the
  original produces original-language audio and translated text.
- Speech-only translation behaviour is unchanged (the audio path's degrade contract holds).
- Host suites + `ktlintCheck` green; device pass on the S22 for the reader modes.

## Resolved decisions (owner + device pass, 2026-09-16)

| # | Decision | Outcome |
|---|---|---|
| 1 | Do the two targets share one control in the reader, or two? | Two pickers — "Show translation in" (display) and "Read aloud in" (speech), under a `Layout` header for the mode rows; the display picker never rebuilds the player |
| 2 | Display-key name (`book.display.<bookId>` vs reuse `book.translate.`) | New `book.display.<bookId>` key; global `display_mode` for the layout |
| 3 | Does translated text ride the backup archive? | Yes — **optional** `translations.json` section (a v1 archive without it restores cleanly); restored rows overwrite local on the natural key |
| 4 | Text-store cap and eviction order | **No automatic eviction** — text is single-digit MB per language against the 4 GiB PCM cap (a 4,060-passage book ≈ 800 KB); per-book delete is offered; a per-book cap maps onto the keyed store later if usage demands |
| 5 | Does the display path translate ahead of the reader (prefetch the next page), or on demand? | Prefetch — current + next page per page entry, display priority, keyed in-flight dedupe shared with the audio path |
| 6 | Speech constraint under the display | Speech rows are Off + the display language; `EngineSelector.translateTarget` is the single choke point (a stored mismatch degrades to original audio, never a second translation); `setBookDisplay` normalizes the mismatch away on write |

## Status

**Live (2026-09-16, decisions #166).** Steps 0–5 shipped: `TranslationTarget` on
`PregenKey` (disk layout byte-identical), the `translations` v4 table + store + optional
backup section, `TranslationService` + `TranslationServiceImpl`, the reader's
`ChapterDisplay` projection with Pending/Unavailable states, the two-picker sheet, the
speech constraint, and `ReaderTextPublisher` (cleanup pass H). Device pass on the S22
verified: progressive landing, page hold, bookmark/mode invariance, translated-only
highlight suppression, pack-removed notes, and zero re-translation after a process restart.

Device-pass fixes folded into the design: paragraph indent dropped (crash — paragraph
spans end beyond clipped page slices; colour-only differentiation); the page slice is
rebuilt from block chunks spanning through the `"\n\n"` separators (offsets are +2-spaced —
dropping them shifted every later style span past the text end); Pending renders **live
animated dots** (~3/s, same-length substitution so the line grid never moves); reflows
re-anchor on the page's first passage (mode switches and the immersive toggle keep the
reading place); `resolve()` never touches the LLM session without a target in force (the
eager-open contradiction with `TranslateRuntime`'s idle policy is closed).

## Next slice (planned 2026-09-16)

Owner-reviewed follow-up on the shipped feature. The four changes below were discussed and
the fork options chosen; **chosen** marks the owner's call.

### 1. Page break after each translated passage — keep-together (chosen)

A passage's group (its Original block + its translation) must never be split by an automatic
page break, so both are visible together. Owner chose **keep-together, not one-group-per-page**
(groups pack greedily into a page instead of wasting the rest of it).

- Pagination becomes break-aware. New `TextPagination.layout(totalLines, firstPageLines,
  fullPageLines, keepTogether: List<IntRange>): PageLayout` with `pageOf(line)`,
  `startLine(page)`, `pageCount`; `linesPerPage` is unchanged. Greedy pack against the page
  capacity (`firstPageLines` page 0, `fullPageLines` after): a group that does not fit the
  remaining lines starts a new page; a group taller than a full page degrades to a capacity
  split (else the page cannot render). Pure function → `TextPaginationTest`.
- `ReaderScreen` derives the group line spans from `firstBlockOfPassage` + `passageOffsets` +
  which passages carry a translation block, and replaces every `pageOf/pageStartLine/
  totalPages` call (`ReaderScreen.kt:691,713,732,776`, gesture boundary checks) with the
  layout. Keep-together applies only when a translation exists — original-only mode keeps
  today's flow.
- Invariants: the break lands on a `"\n\n"` block boundary, so the line grid and `lineHeightPx`
  are untouched; `chapterPassages` and all navigation stay original-keyed.
- **Risk:** a landing translation grows a group past the page's remaining lines, so the group
  moves to the next page. The keep-place effect still holds the reading place on the page's
  first passage; page content shifts by at most one group.

### 2. Voice menu: radio lists → dropdowns

Shared `LabeledDropdown` (M3 `ExposedDropdownMenuBox`) in core-ui; `ReadInLanguage.kt`'s three
sections become dropdowns (**Show translation in**, **Layout**, **Read aloud in**) and the
sheet no longer auto-closes on a selection (`ReaderScreen.kt:470/487`). `ReadInLanguageUiState`
is unchanged.

### 3. One compact engine+voice picker on every surface + download visibility

- `VoiceSelector`'s ~54-row radio list is replaced by the compact picker on **all** surfaces
  (Setup, Settings, reader) — the #102 one-picking-surface rule (chosen over a reader-only
  variant) — and the Settings `SpeechPane` engine radios fold into it.
- **Engine dropdown** (`kokoro-82m` / `piper-v1` / `system-tts`) + **voice dropdown** (the
  selected engine's catalog, grouped by language). Selecting the engine persists `ttsEngine`
  **and** dispatches the `changeVoice` rebuild.
- **Downloaded state is visible per voice/pack** (a "downloaded / not downloaded, N MiB"
  marker) with the inline Download retained, so the list shows what is already on device.

### 4. Two voice sets over one engine (chosen) + Settings link

- **One engine, two voices** (chosen over two engine+voice pairs — two resident engines would
  double the memory pressure and `EngineSelector` resolves one engine per session). The
  original voice stays `voice` / `book.voice.<bookId>`; the **target-language voice** is a new
  setting (`translate_voice` + per-book `book.translateVoice.<bookId>` override), constrained
  to voices whose language matches the target. `EngineSelector.resolve` uses it and falls back
  to `bestVoiceFor(lang)` when unset (behaviour unchanged for users who never set one).
- **Load-bearing:** `PregenKey` must gain the target-voice dimension. It currently carries the
  ORIGINAL effective voice while the translated voice is implicit/deterministic, so an explicit
  target voice would reuse stale audio; translated entries are keyed by the target voice.
  Text/display is voice-independent, so nothing else moves.
- **Settings link (chosen: leave the reader, open Settings):** `ReaderScreen(onOpenSettings)`;
  `MainActivity` closes the reader and opens Settings (the Speech pane holds the downloads).

### Slices and verification

| Slice | Change | Verification |
|---|---|---|
| A | keep-together pagination | `:core-player:test` (fit / boundary group / oversized group / first-page reserve) + device page turns in both modes |
| B | dropdown pickers | `:core-ui:testDebugUnitTest`, `:feature-player:compileDebugKotlin`, sheet on device |
| C | engine+voice picker, download visibility, Settings link | `:app:assembleDebug`; device: engine switch rebuilds, missing pack → link; `ktlintCheck` |
| D | target voice set | `:core-player:test` (key layout) + device: new target voice → new audio, old cache not reused |

Decisions.md entry lands with the slice.

## Recorded decisions

Presentation = interleaved single column + translated-only option (global `display_mode`);
speech constrained to Off or the display language (at most one translation per passage);
differentiation by colour only (never font size, and never paragraph indent); passage
indices stay original-keyed and a translation is a separate projection; translation moves
out of `TTSEngine.synthesize` into a `TranslationService` consumed by both the audio and
display paths; translated text is durable (Room v4) and rides the backup archive as an
optional section; no automatic eviction; read-along highlight follows the utterance block
(translation block when speech == display, original otherwise, suppressed in translated-only
with original audio) while page-follow anchors on the passage's first block.
