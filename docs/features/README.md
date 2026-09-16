# Feature plans

One doc per planned feature, written when the feature is planned in detail (before its
implementation slice). Roadmap (docs/roadmap.md) tracks status; unplanned candidates
from other local-TTS reader apps live in [ideas.md](../ideas.md) (a pool, not
commitments).

| Feature | Doc | Status |
|---|---|---|
| Share-and-identify (resume-from-share) | [share-and-identify.md](share-and-identify.md) | Live: S1 OCR (#36), S2 share receiver + S3 resume wiring (#37/#38); design doc updated |
| Read-in-language display (translation in the reader) | [read-in-language-display.md](read-in-language-display.md) | Live 2026-09-16: display projection, `TranslationService`, `MIGRATION_3_4` store, speech-vs-display constraint (#166); doc reflects the shipped design |
| Content / import (library) | Roadmap C + [architecture.md](../architecture.md) §3; full UX doc pending | Done: parsers, segmentation, importer, SAF import + library UI (C1–C6, `feature-library`/`app`); 98 tests total |
| Persistence (Room library store, index rebuild) | Roadmap P1/P2 | Done: Room schema v2 (progress offset+speed, bookmarks, position_history, #33), LibraryStore + PlayerStore contracts, launch-time index rebuild |

**Convention:** a feature's doc is the source of truth for its decisions and status;
the roadmap stays the index of work items. Update a feature doc when a convention or
constraint for it changes (see [conventions.md](../conventions.md), definition of done).
