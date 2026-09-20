# Ayvu v0.1.3 — release notes

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device. No account, no telemetry, no cloud.

## What's new

- **A second, better-reading engine for read-in-another-language.** Settings → Speech →
  Translation now offers two: the shipped **LFM2.5-1.2B** (default, ~730 MB) and — new —
  **LFM2.5-2.6B-Base** (~1.7 GB), which reads clearly better (chrF 70.44 vs 67.37 on the
  40-sentence gate) for about 1.5× the translation time. Each engine downloads, installs
  and can be removed on its own, and switching engines re-translates rather than reusing
  the other engine's audio. An untouched install behaves exactly as before.
- **Playback no longer sleeps through its own recovery.** Screen off, a dry audio buffer
  used to leave nothing holding the CPU awake, so a stall could turn into a long
  stop-and-buffer; the player now holds a partial wake lock for exactly that window. The
  underlying deficit (unplugged, screen-off synthesis is slower than real time) is
  unchanged, and the look-ahead cushion is sized against it.
- **Two fixes.** Pressing play while the reader was still opening the book no longer
  starts at the beginning, and a translation that finished after you turned the display
  language off can no longer appear on the page.

## Install

- **64-bit ARM (arm64-v8a), Android 8.0+ (API 26)** — arm64-only because the espeak-ng
  phonemizer ships as an arm64 native library.
- Unminified signed release build, **≈52 MB**; the signing key is stable, so it installs
  straight over 0.1.1/0.1.2 with your library, progress, bookmarks and settings kept.
- No in-app update check: watch this repository's Releases page.
- Shipped `Ayvu-0.1.3.apk`, <size> B, `sha256 <digest>`; signer `a5057984…` (CN=Ayvu).

## First run

Packs download on first run — explicit, resumable, SHA-256-verified, none bundled:

| Pack | Size | Source |
|---|---|---|
| Kokoro-82M model + v1.0 voices (54) | 325 MB + 28 MB | `thewh1teagle/kokoro-onnx` release `model-files-v1.1` |
| espeak-ng 1.52.0 phonemizer bundle | 9.9 MB | this project's `espeak-ng-1.52.0` release |
| LFM2.5-1.2B translate model (optional) | ~730 MB | this project's `translate-lfm12b-v1` release |
| LFM2.5-2.6B-Base translate model (optional, the new option) | ~1.7 GB | this project's `translate-lfm26b-base-v1` release |
| OCR language packs (per language, optional) | 13–22 MB each | `tesseract-ocr/tessdata` (tag `3.04.00`) |

After the TTS packs land everything works offline; the network is used again only to add
OCR languages or a translation model.

## Known limitations in this build

- **Read-in-language on plain-text/Markdown books**: a hard-wrapped line break can end a
  passage's translation early. EPUB and MOBI imports are unaffected.
- **Screen-off playback throughput** is unchanged (see above).
- **OCR accuracy** is capped by the bundled pre-LSTM tess-two 9.1.0, so the pinned packs
  are legacy `3.04.00` tessdata.
- **Android Auto** media controls are wired through `MediaSession` but unverified on Auto
  hardware; playback runs at 1.0×; DRM-free files only (`.kfx` is rejected).
- **Model licences**: both read-in-language models are **LFM Open License v1.0**, not
  Ayvu's GPL-3.0 — that licence's terms (including its revenue threshold for commercial
  use) apply to the model. Each licence text ships inside its release archive.

## Licence

Ayvu is GPL-3.0; the source for this build is this repository at the `v0.1.3` tag —
<https://github.com/moronigranja/ayvu>. The full licence and the third-party notices ship
inside the app (About → Licences) and in the repository
([`LICENSE`](../LICENSE), [`NOTICE.md`](../NOTICE.md)).
