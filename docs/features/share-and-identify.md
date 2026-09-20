# Feature plan: share-and-identify (resume-from-share)

**Goal.** The user copies text or screenshots a page in the Kindle app and shares it to
this app. The app identifies which book + chapter/passage the shared text came from
(against books already in the local library) and offers to start listening there.

**Scope of this slice.** Match core + `TextIndex` + share receiver (`text/plain` +
`image/*` OCR). Player-resume wiring is the next slice.

**Constraints (all already in [hard-facts.md](../hard-facts.md) unless restated):**
- Offline, on-device only. No cloud, no account, no telemetry.
- **Matches only books already imported/indexed in the app.** The share feature can only
  recognize books the user has loaded. This is by design (no cloud text DB).
- Reflowable content ⇒ identify **chapter/passage**, not physical pages.
- **OCR is fully open-source: Tesseract 5 (Tesseract4Android, decisions #186). Not Google ML Kit.**
- **Multilingual OCR — languages `eng+spa+fra+deu+por+ita` to start** (English, Spanish,
  French, German, Portuguese, Italian). All roman-alphabet, all supported by Tesseract.
  More languages ⇒ slower OCR + larger app + marginally lower per-language accuracy. No
  auto-detection — run the bundled set. Non-roman scripts (CJK, etc.) need character-level
  matching in the matcher — a matcher follow-up, out of scope for this slice.

**Data flow.**
```
Share (text or image)
  → ShareReceiver (ACTION_SEND) extracts payload
  → image → OCRService (Tesseract) → text;  text/plain → as-is
  → TextIndex.query(snippet, minConfidence) → MatchResult(bookId, bookTitle, chapterIndex, chapterTitle, passageIndex, confidence)?
  → "Found: book · chapter · passage" + "Start listening here"  (player wiring = next slice)
```
Low confidence (below threshold) ⇒ "not found in library — import it first".

**Match core (pure logic, no Android deps — lives in `core-locate`, see
[modules.md](../modules.md)):**
- `TextNormalizer`: NFKD (accents decomposed) → strip combining marks + apostrophes →
  lowercase → keep only `[a-z0-9 ]` → collapse spaces → trim. ("café" → "cafe",
  "It's" → "its" — matches OCR that drops apostrophes; other punctuation → word separator.)
- `TextMatcher`: word n-grams **n=4 with 3-gram fallback credit** for windows containing
  local OCR typos (chosen by measurement: matches stay ≥0.6 under realistic noise while
  cross-book text scores ≤0.05); snippets <4 tokens → unigram recall; best passage wins,
  ties → earliest.
- `TextIndex`: holds `IndexedBook`s (each with ordered `Passage`s), precomputes per-passage
  gram sets, `query(snippet, minConfidence): MatchResult?` (null below threshold).
- **Confidence threshold.** Recall fraction: 1.0 = every word-group in the shared text
  appears in a passage (verbatim/contiguous/truncated). Default threshold **0.6**,
  **configurable in settings**. Below threshold ⇒ "not found". Chosen high enough to avoid
  wrong matches; tunable. A normal shared sentence/paragraph chunk scores well above 0.6.

**Cross-cutting requirement.** Every book the **file-import pipeline parses must also be
added to the `TextIndex`** (capability 1 in [agents.md](../../agents.md)). The share
feature matches against this index; parsing without indexing silently breaks
share-and-identify. The index is populated during import; `TextIndex` itself is an
in-memory layer over the library store, rebuilt at launch.

**Index/segmentation contract (C4).** The import pipeline runs
`BookSegmentation.segment(parsedBook)` **before** `TextIndex.add(...)`: paragraph-grain
passages, front/back-matter chapters stripped (position-guarded), passages over 100
words split at sentence boundaries. The passage is the unit of both matching and
resume, so its grain decides match precision; passages must stay stable across
re-parses. Segmented chapters keep their original spine indexes.

**Status.** `core-locate` implemented and **24 JUnit tests pass** — pure JVM, no Android
SDK needed (`./gradlew :core-locate:test`, or `tools/docker-build.sh :core-locate:test`).
Match core + index are done; the import side they match against is done too: parsers,
segmentation, and `BookImporter` in `core-ebook` (50 tests), plus the SAF import +
library UI in `feature-library` (7 unit tests, Hilt, verified in the Docker toolchain) —
**98 tests total** across the repo. Persistence is live (P1/P2): `LibraryStore`
contract in core-model, Room-backed store in `core-persistence`, and `TextIndex` is
rebuilt at launch from the cached parses — never re-parsed. OCR (Tesseract) and the
`ShareReceiver` are the remaining Android components (roadmap S1/S2) — designed here,
verified on-device later; player-resume wiring is the slice after that.

**Recorded decisions.** Default confidence threshold **0.6, configurable in settings**;
OCR = Tesseract 5, `eng+spa+fra+deu+por+ita`, roman-alphabet curated set; slice = match
core + index + share receiver, text + OCR; import pipeline must also index each book.
