# Ayvu v0.1.4 — release notes

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device. No account, no telemetry, no cloud.

## What's new

- **Export your translated text.** A library row's new **Export translation…** action writes
  the book — or a single chapter — as **Markdown**, **plain text**, or **EPUB 3**, in a
  language you pick in the dialog (each row shows how many passages are already translated,
  so a partly translated book says what it will finish). Bilingual output keeps the original
  and marks the translation; the translated-only shape omits it.
- **Much better OCR.** Share matching now runs on **Tesseract 5.5.1** (LSTM-only) instead of
  the legacy pre-LSTM build, with the language packs re-pinned to the current
  `tessdata_fast` 4.1.0 models — English is 4.1 MB instead of 21.9 MB and all six languages
  total 13.1 MiB. Upgrading reclaims the retired models' disk space automatically.
- **Share matching is less strict by default** (match threshold 0.6 → 0.3): a short shared
  quote or screenshot is matched to the passage more readily, and the below-threshold
  "closest candidate" hint still covers the misses. An already-changed value is kept.
- **Bookmark jumps keep their place inside the passage.** Jumping to a bookmark used to
  restart that passage from its first sentence; play now resumes at the saved spot.
- **The first-run setup's import step is skippable** and stays skipped — import from the
  library whenever you want.
- **Three playback-target fixes.** Rotating the device no longer restarts playback at the
  share target; **Play from here** after page turns now names the passage you pressed
  (before, it named a passage from the page the paging started on); and a stale
  share/bookmark play target after a book is re-parsed no longer crashes the app — it
  resumes at your stored place instead.
- **A cancelled save is no longer reported as a failure.** The reader could show
  `StandaloneCoroutine was cancelled` instead of the book text after play → stop → reopen;
  that is gone.

## Install

- **64-bit ARM (arm64-v8a), Android 8.0+ (API 26)** — arm64-only because the espeak-ng
  phonemizer ships as an arm64 native library.
- Unminified signed release build, **≈50 MB**; the signing key is stable, so it installs
  straight over 0.1.1–0.1.3 with your library, progress, bookmarks and settings kept.
- No in-app update check: watch this repository's Releases page.
- Shipped `Ayvu-0.1.4.apk`, 52,279,328 B,
  `sha256 038db8ee89708c17e6ed25993dbd18723f9aaa91e9aa350c99f4f4a04a42d242`;
  signer `a5057984…` (CN=Ayvu) — the same certificate as 0.1.1–0.1.3.

## First run

Packs download on first run — explicit, resumable, SHA-256-verified, none bundled:

| Pack | Size | Source |
|---|---|---|
| Kokoro-82M model + v1.0 voices (54) | 325 MB + 28 MB | `thewh1teagle/kokoro-onnx` release `model-files-v1.1` |
| espeak-ng 1.52.0 phonemizer bundle | 9.9 MB | this project's `espeak-ng-1.52.0` release |
| LFM2.5-1.2B translate model (optional) | ~730 MB | this project's `translate-lfm12b-v1` release |
| LFM2.5-2.6B-Base translate model (optional) | ~1.7 GB | this project's `translate-lfm26b-base-v1` release |
| OCR language packs (per language, optional) | 1.1–4.1 MB each (13.1 MiB for all six) | `tesseract-ocr/tessdata_fast`, tag `4.1.0` |

After the TTS packs land everything works offline; the network is used again only to add
OCR languages or a translation model.

## Known limitations in this build

- **Screen-off playback throughput** is unchanged — the player holds a partial wake lock
  only while its buffer is dry, which prevents a stall from becoming a long stop-and-buffer
  but does not make unplugged, screen-off synthesis faster than real time.
- **OCR runs the `fast` accuracy tier** (the distro-standard models, matched to this app's
  size discipline); a switch to the `best` tier is a one-line re-pin if accuracy ever proves
  short.
- **Android Auto** media controls are wired through `MediaSession` but unverified on Auto
  hardware; playback runs at 1.0×; DRM-free files only (`.kfx` is rejected).
- **Model licences**: both read-in-language models are **LFM Open License v1.0**, not
  Ayvu's GPL-3.0 — that licence's terms (including its revenue threshold for commercial
  use) apply to the model. Each licence text ships inside its release archive.

## Licence

Ayvu is GPL-3.0; the source for this build is this repository at the `v0.1.4` tag —
<https://github.com/moronigranja/ayvu>. The full licence and the third-party notices ship
inside the app (About → Licences) and in the repository
([`LICENSE`](../LICENSE), [`NOTICE.md`](../NOTICE.md)).
