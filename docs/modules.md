# Module layout — LIVE modules as of #118; add modules only when a cycle or build isolation forces it

```
core-model/       Book, Chapter/Section, TextPassage, LibraryEntry (no Android deps)
core-ebook/       EBookParser interface + format parsers (epub, azw3/kf8, mobi/azw: PalmDOC LZ77 + HUFF/CDIC), passage segmentation + BookImporter (parse→segment→index); defensive
core-locate/      TextIndex + matcher (n-gram recall over indexed books); no Android deps
core-ocr/         on-device OCR (tess-two) behind OCRService interface; eng+spa+fra+deu+por+ita
core-tts/         LIVE — TTSEngine + Kokoro-82M impl (T2, decisions #25/#28): espeak-ng phonemization (JNA), ORT session behind a compileOnly seam, pinned fp32 packs (kokoro-model + kokoro-voices @ model-files-v1.1). Piper impl (D4, #154): one-voice-per-instance VITS engines + pinned rhasspy/piper-voices packs (model + voice-config per voice), stock export = passage-level read-along (#30b). CosyVoice3 stays gated metadata-only
core-persistence/ LIVE — Room schema v2 (books, cached passages, progress + offset/speed, settings, bookmarks, position_history), DAOs, LibraryStore + PlayerStore impls, launch-time index rebuild (P1/P2, T4-1 #33), BackupStore snapshot/merge + BookFileStore sidecars (E1 #111)
core-player/      LIVE — v1 player state machine (T4-1, #33): transport, transactional writes, undo ring, sleep timer, speed, bookmarks; PlayerStore contract; T5 pre-generation (PregenQueue + PcmPassageCache + OfflinePregen planner, #35/#42); PregenSpaceEstimator + per-book usage (#44)
core-ui/          LIVE — AyvuTheme design tokens (B1, #68): brand light/dark color roles, typography, shapes, spacing, motion, elevation + the shared components (SectionHeader, PillButton, ConfirmDialog, EmptyState, LoadingState, PlayerCard, BookCover, LabeledProgress, CoverageProgress, VoiceSelector — the ONE C2 voice-picking surface, #102/#105/#143, collapsible by language with display names + upstream grades, formatPercent); no business logic/ViewModels; depends on core-player only; consumed by feature-library/feature-settings/feature-share/feature-player and app
core-backup/      LIVE — versioned v1 backup archive: codec + DTOs, export/import of library data (decisions #89/#111); consumed by core-persistence (BackupStore) + feature-settings (SAF edge); no Android deps
core-ocr/         LIVE — S1: OcrEngine seam, OcrImage/OcrResult, screenshot downscaler, six pinned legacy-traineddata packs (#36)
feature-ocr/     LIVE — S1: TessTwoOcrEngine (tess-two 9.1.0) + TessDataStager + Hilt; LSTM models unusable on this binding (see #36)
feature-settings/ LIVE — V1: settings screen (root + Speech subscreen — pane pattern shared with the OCR-languages picker, #156/#48), packs download UI (engine-agnostic, descriptor-derived — K2 #156), voice picker + favorites, threshold, OCR langs, theme; AndroidHttpTransport; Offline-audio section (per-book usage + delete, #44); "Backup & restore" SAF export/import (E1, #111); About group (version via the AppInfo seam, GPL-3.0 source link, NOTICE link, on-device privacy line — release 0.1.1)
feature-share/    LIVE — S2/S3: ACTION_SEND gateway (text+image), typed resolver, found/not-found UX, OpenTarget contract + Listen-here entry (#37/#38)
feature-library/  SAF + external-file import (F4: ACTION_VIEW / shared book files → MainActivity singleTop, one in-library IntakeOverlay with progress + stage, #117/#118) + library list UI (Compose, Hilt) — C5/C6; F2 title/author search + empty state (#90); F3 folder import via SAF tree grant (root + one level, 200-file cap, #108); library-row pre-gen action + usage/estimate/delete over PregenStorage (#42/#44)
feature-player/   LIVE — playback service, MediaSession, pregen wiring (queue + disk tier, #35/#42); reader surface + S3 gestures (#38); PregenWorker/PregenManager single-mode manual offline pre-gen — overnight arm removed (S1b, #76; QW5d startup cancel kept); PregenStorage storage-transparency façade (#44)
app/              Hilt composition root: Library/Settings/Reader routes, S3 open-target intent handling, AppShareModule (Listen-here handler) — F1/C5/C6/#36/#38
```

Prefer one cohesive module per responsibility over feature sprawl. Add modules only when
a circular dependency or real build isolation forces it.
