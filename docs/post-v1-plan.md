# Post-v1 plan: reading stats + app backup/restore

Written 2026-08-27 for sign-off before any code. Two independent post-v1
slices (`TODAY stats dashboard` and `app export/backup + restore`, both from
`ideas.md` #45/#55 and the roadmap post-v1 list), grounded in the shipped
seams. Not started.

## Preconditions — stabilization first

The active stabilization gate is defined in [roadmap.md](roadmap.md#phase-a-stabilize-shipped-contracts).
The relevant dependencies are structural, not a temporary working-tree snapshot:

- Complete CR-3 before backup restore publishes restored books into `TextIndex`; Room
  must be the durable source of truth and rebuild/import/restore publication must share
  one consistency boundary.
- Complete CR-6 before either slice adds more library/settings/player coupling; shared
  stores and Android adapters belong at the composition root behind narrow contracts.
- Complete CR-2 before stats relies on playback transitions; the same authoritative
  playhead/phase-exit path should drive both final progress and listening-time flushes.

Working-tree cleanup instructions were removed because they became stale as soon as the
underlying fixes landed. Consult `git status` and the current roadmap when implementation
actually starts.

## Shared facts this plan builds on

- **Room v3** (v2 + `activity_seconds`, #157) (`LibraryDatabase`, `exportSchema = false`): `books`, `passages`
  (cached parse, FK-cascade), `progress` (resume row, one per book),
  `settings` (generic key-value), `bookmarks`, `position_history` (capped ring).
  Forward-only migrations, no destructive fallback (decisions #22).
- **Content-hash book ids** = SHA-256 of container bytes (decisions #11) →
  idempotent re-import and restore reattachment.
- **`LibraryStore.add(entry)`** = one transaction: replace book + cached
  passages (upsert by id). `delete(bookId)` cascades passages + explicitly
  deletes progress/bookmarks/history. The store is *not* the index owner; the
  importer indexes into `TextIndex` before `add`.
- **`PlayerStore`** (`RoomPlayerStore`) = the single transactional write point:
  `commitProgress(progress, ringPush?)` upserts the resume row and pushes the
  ring in one transaction; `popRing`; bookmarks.
- **`SettingsStore`** = typed accessors over the `settings` table (five keys
  today: match_threshold, voice, favorite_voices, theme_mode, ocr_languages).
- **`BookProgress`** = pure orientation math (fraction / elapsed / remaining /
  `positionAt`) on the chars/15 estimate — informational, speed-independent.
- **`kotlinx-serialization-json` 1.11.0** is already in the catalog and used
  no-codegen in `core-tts` (`JsonElement`/`jsonObject`). Follow that pattern —
  do not add the serialization compiler plugin.
- Home surface = `LibraryScreen` (MainActivity entry); settings =
  `SettingsScreen`. SAF helpers exist (`BookSources`: `toEBookSources`,
  `takeReadPermission`, `displayName`).

---

## Slice A — TODAY stats dashboard — LANDED (2026-09-13, decisions #157)

> **Status:** implemented as designed, with three documented deltas: the reading
> capture is the #109 page-flip-active dwell (not the plan's raw-dwell option 1),
> with a 180 s active-window bound the plan left open; the Room schema bumped to v3
> (v2 → v3 additive, matching #109's whole-seconds storage); and the `TodayCard`
> hides itself until the first activity. Event/session timeline deliberately not
> built. Capture semantics and evidence: decisions #157.

### Goal

Per-day read/listen minutes + a consecutive-days streak, shown as a header card
on the library home (`LibraryScreen`). All on-device, no network. Matches the
Audiobookify home the idea came from (today 29m, week read/listen split,
streak).

### The hard part: capture

There is no consumption logging today — `progress` is a position, the ring is
positions, `BookProgress` is an estimate. This slice adds real measurement for
listening, and requires one explicit capture decision for reading.

- **Listening time** is unambiguous and already close to free: count
  wall-clock time while `PlayerPhase == PLAYING`. A pure `TimeSpanAccumulator`
  (injected clock) starts on the PLAYING transition and flushes on every exit
  (pause / sleep / seek / advance / stop / process kill via `onDestroy`),
  splitting spans across a local-day boundary. Flush writes through an
  `ActivityStore.accumulate(dayKey, bookId, LISTEN, wholeSeconds)`.
  The state machine itself is untouched — the accumulator is driven by phase
  transitions at the `PlaybackService` edge (which already owns
  `clock: () -> Long`).
- **Reading time** is the open decision. No dwell tracking exists. Options:
  1. **Reader dwell** (recommended): count time while the reader is the
     foreground screen *and* the screen is on, flushed on pause/leave, with a
     minimum span floor (≈10 s) to drop accidental opens. Honest but noisy —
     a book left open counts.
  2. **Page-flip estimate**: turns × words-per-page ÷ wpm. Fully offline and
     deterministic but synthetic; reported "read time" stops meaning elapsed time.
  3. **Manual session** ("start reading"): exact but high friction.
  Recommend (1) with the screen-on gate and span floor. Needs user sign-off
  before build; the slice is structured so the capture source is one seam the
  other three phases don't care about.

### Schema (Room v3)

`MIGRATION_2_3`, forward-only, additive:

```kotlin
@Entity(
  tableName = "activity_seconds",
  primaryKeys = ["dayKey", "bookId", "kind"],
)
data class ActivitySecondsEntity(
  val dayKey: String,      // local-date "yyyy-MM-dd" (device tz — "today" is local)
  val bookId: String,
  val kind: String,        // "READ" | "LISTEN"
  val seconds: Long,
)
```

- `ActivitySecondsDao`:
  - `accumulate(dayKey, bookId, kind, seconds)` — `INSERT ... ON CONFLICT DO
    UPDATE SET seconds = seconds + excluded.seconds` (Room `@Upsert` is
    replace, not add; use an `@Query` upsert).
  - `day(dayKey): List<ActivitySecondsEntity>` / `summaries(dayKey)` for the card.
  - `deleteByBook(bookId)` — book removal drops its rows (mirrors the #50
    housekeeping in `RoomLibraryStore.delete`).
- `MIGRATION_2_3` = `CREATE TABLE activity_seconds` + the above index. It must
  not touch v2 tables (pure add).

### Aggregation & streak (pure, sandbox-testable)

In `core-player` (next to `BookProgress`, the existing orientation-math home —
no new module):

- `DailyTotals.summarize(day, seconds rows)` → read/listened seconds, rounded display minutes + total.
- `WeekSummary(rows, todayKey)` → 7-day read/listen series for the mini bar.
- `Streak.count(activeDayKeys, todayKey, clock)` → consecutive days ending
  today-or-yesterday with ≥1 total minute. Pure over a `Set<String>` of active
  days with injected "today", so midnight and gap boundaries are unit-testable.

### UI (library home)

A `TodayCard` header in `feature-library`: today's read/listen/total, a 7-day
mini bar, streak count. `LibraryViewModel` exposes a
`stats: StateFlow<TodayStats>` combined from `ActivitySecondsDao.day` + the
`books` flow; recomputes on resume (listening flushes may land while the library
screen is on the back stack). No home "restructure" beyond a header insertion
above the continue-list.

### Phases

1. **Persistence**: entity + DAO + `MIGRATION_2_3` + `ActivityStore`; a
   Robolectric/Room migration test proving v2 data survives → v3.
2. **Recording**: `TimeSpanAccumulator` (core-player) + `PlaybackService` listen
   flushes; reader dwell capture behind the sealed seam (per the open decision).
3. **Aggregation + UI**: `DailyTotals`/`WeekSummary`/`Streak` + `TodayCard`.

### Acceptance (S22 / host)

- Play ~5 min across 2 books → today's LISTEN ≈ 5 (within the span floor);
  read in the reader ~8 min → today's READ ≈ 8; relaunch → numbers persist.
- Consecutive active days → streak increments; an empty day breaks it; today + 0
  seconds does not count toward today's streak.
- Book removed from library → its rows gone (card totals drop).
- Offline: no network call anywhere in the path.

### Tests

- `MIGRATION_2_3` up from a populated v2 DB (books/passages/progress/bookmarks/
  ring/settings all retained).
- `TimeSpanAccumulator`: start/stop, floor, split-across-midnight, no-double-
  count on rapid pause/resume.
- `Streak`: day-gap, empty-today, single-day, clock boundary.
- `WeekSummary`: 7-day windowing at month/year boundaries.

### Open decision (before phase 2)

Reading-time capture method — recommend reader dwell + screen-on gate + 10 s
floor.

---

## Slice B — App export/backup + restore

> **Complete (2026-09-02, decisions #111):** all three phases shipped and
> device-verified on the S22 — export/restore round-trip, double-restore
> zero-duplicates, book-sidecar round-trip, post-restore index resync
> without relaunch. Merge precedence signed off in #109 (progress local-wins,
> settings restore-wins-with-absent-kept, bookmarks/history natural-key
> idempotent, include-books opt-in off).

### Goal

Export a versioned zip (settings + library metadata + cached parses + progress
+ bookmarks + undo ring [+ optional book files]) via SAF; restore merges it back
idempotently by content-hash. Data portability for the offline/no-account ethos
(`ideas.md` #55; decisions #29).

### Archive format (v1)

A plain zip with JSON section files (kotlinx-serialization, no-codegen, matching
`core-tts`):

```
manifest.json        { version: 1, appVersion, exportedAtEpochMillis }
settings.json        { "<key>": "<value>" }          (raw rows, not typed)
library.json         [ { id, title, authors, importedAtEpochMillis } ]
passages.json        [ { bookId, chapterIndex, chapterTitle, passageIndex, text } ]
progress.json        [ { bookId, chapterIndex, passageIndex, offsetSeconds, speed, updatedAtEpochMillis } ]
bookmarks.json       [ { bookId, chapterIndex, passageIndex, offsetSeconds, label, createdAtEpochMillis } ]
position_history.json[ { bookId, chapterIndex, passageIndex, offsetSeconds, createdAtEpochMillis } ]
books/               optional: <bookId>.<ext>, only when "include books" is chosen
```

`passages.json` is the cached parse riding along — restore reattaches progress
without re-parsing (P2 "never re-parse"). The TTS pack cache re-downloads and
stays out (decisions #29).

### Module layout

- **`core-backup`** (new pure-JVM module, mirrors `core-ebook`): DTOs (as above,
  self-contained, no Android import), `BackupCodec` (`write(inputBytes) →
  zipBytes`, `read(zipBytes) → BackupSnapshot`), and the **merge-policy
  functions** — all sandbox-testable against `java.util.zip`. Depends on
  `kotlinx-serialization-json` only.
- **`core-persistence`**: a `BackupSnapshot` producer/consumer — `suspend fun
  snapshot(db, includeBooks): BackupSnapshot` reading all six tables, and
  `suspend fun merge(db, snapshot, policy)` writing through the existing DAOs.
  Adds DAO helpers where none exist (see merge semantics). No schema change.
- **`feature-settings`**: the SAF edge + a "Backup & restore" section —
  Export (`ActivityResultContracts.CreateDocument("application/zip")`) writes
  `BackupCodec.write` bytes through `ContentResolver.openOutputStream`;
  Restore (`OpenDocument`) reads the zip into `BackupCodec.read`, then
  `merge`. Progress + typed summary mirror `ImportUiState`. After a merge, run
  the same `TextIndex` sync the import path uses so restored books are
  searchable without a relaunch.

### Restore merge semantics (idempotent by hash)

| Table | Merge rule |
|---|---|
| `books` | upsert by id (same hash = same content; replacing is safe and idempotent) |
| `passages` | `upsertAll` (identical rows) — only when the book's cache is absent, to honor never-re-parse |
| `progress` | skip if a local row exists (local wins — don't clobber fresh local progress) |
| `bookmarks` | insert only if no row matches the natural key `(bookId, chapterIndex, passageIndex, offsetSeconds, label, createdAtEpochMillis)` — prevents double-restore duplicates (ids are auto-increment, not portable) |
| `position_history` | append, then `prune(bookId, cap)` to restore the cap/order |
| `settings` | restored keys overwrite local; keys absent in the archive keep local |

The merge policy (progress local-wins, settings restore-wins) is an open
decision to confirm.

### Phases

1. **`core-backup` codec** + DTOs + round-trip tests (pure JVM).
2. **`core-persistence` snapshot/merge** + the missing DAO helpers + tests
   against in-memory Room (no migration).
3. **`feature-settings` SAF edge** + section UI + index resync.

### Acceptance (S22 / host)

- Export on a populated device → zip opens with `manifest.json` + all six
  sections; an empty library exports a valid, restorable no-op.
- Restore onto a fresh install → library, resume points, bookmarks, ring, and
  settings all reappear; a second restore adds **zero** duplicate rows.
- Restore without `books/` → library reattaches from cached parses (never
  re-parse); pack cache re-downloads, stays out.
- DRM-free only (encrypted files were already rejected at import; the archive
  never bypasses that).

### Tests

- `core-backup`: round-trip (write→read→write byte/DTO equality); malformed zip
  → typed failure; version-skip (future/unknown `version` → typed failure, not
  partial merge).
- `core-persistence`: merge idempotency (double-merge → no dup rows), progress
  local-wins, settings merge, prune-after-append ring cap.

### Open decisions

- Merge policy sign-off (progress/bookmarks/settings rules above).
- "Include books" default for v1 (recommend: opt-in checkbox, off by default).

---

## Ordering & risks

- **Stabilization first**: CR-2/CR-3/CR-6 are prerequisites as described above; do
  not deepen the current ownership seams while those cutovers are open.
- **Visual baseline before feature UI**: roadmap Phase B defines the theme and shared
  components that backup and stats screens must reuse. Pure codec, persistence and
  aggregation work may proceed independently while the visual system lands.
- **Backup first among product slices** (recommended), after merge precedence and the
  "include books" default are signed off: its codec is fully sandbox-testable, it is
  additive to persistence, and it removes the data-loss risk that would otherwise
  shadow later schema work.
- **Stats second**: reading capture is its one product decision and Room v3 is the only
  schema change. Store seconds, not rounded minutes, so short valid spans accumulate
  without loss.
- Shared mutation boundary is `core-persistence` (v3 migration for stats,
  snapshot/merge DAO helpers for backup). Serialize that module if the slices are
  developed concurrently; the pure codec and aggregation work can proceed independently.
