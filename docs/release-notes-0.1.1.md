# Ayvu v0.1.1 — release notes

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device. No account, no telemetry, no cloud.

## What's in this release

- **Import & library** — EPUB, AZW3/KF8, MOBI/AZW, TXT and Markdown; single-file and
  whole-folder import; "Open with Ayvu" and book-file shares; one import overlay with
  progress, stage and per-file failure isolation; library search over title/author.
- **Reading** — paginated chapter text with a chapter selector, book-wide passage
  indicator, and an immersive full-screen mode (title overlay + minimal player, follows
  the active sentence, middle double-tap toggles the chrome, Play starts at the top of
  the visible page, a manual page turn stops playback). Long-press a paragraph for
  **Play from here** / **Copy text**; chapter and bookmark jumps land without auto-play.
- **Listening** — Kokoro-82M narration with sentence-grain read-along highlighting;
  sleep timer (incl. end of chapter); bookmarks; undo-skip; playback volume; a
  configurable synthesis thread count so the phone stays responsive while generating;
  emit-early streaming that starts audio before a passage finishes.
- **Offline pre-generation** — render a book (or an arbitrary listening-time budget
  anchored at your reading position) to the on-device audio cache, with slice-relative
  progress, a Stop control on the library row, a generated/not-generated coverage bar
  and a generation notification; per-book audio usage with one-tap delete in Settings.
- **Voices & settings** — guided first run (privacy → packs → voice → import); one shared
  voice sheet (collapsible language sections, display names, upstream data grades,
  favourites, one-tap preview); settings grouped into Speech, Reading & sharing,
  Storage & data, Appearance, and Backup & restore.
- **Read in another language** — the optional LFM2.5-1.2B translate model renders a book
  read-aloud in a target language independently of the displayed text, and can project
  the translation into the reader (interleaved or translated-only) behind a per-book
  toggle.
- **Data safety** — Backup & restore export/import (optionally including the book
  files), with content-hash book ids so a restored library reattaches to its progress.
  Restore validates the archive (entry names, entry count and expanded-size ceilings) and
  fails cleanly on a malformed or hostile file instead of copying anything outside its
  own storage.

## Install

- **Requires a 64-bit ARM device (arm64-v8a) running Android 8.0+ (API 26).** The build
  is arm64-only because the espeak-ng phonemizer it ships is an arm64 native library;
  32-bit and x86 devices are not supported and the APK will not install there.
- Unminified signed release build, **≈52 MB** (ONNX Runtime and JNA are inside).
- Install the APK from this release (allow "install unknown apps" for your browser or
  file manager). The signing key is stable across releases, so later versions install
  straight over this one — no uninstall, and your library, progress, bookmarks and
  settings are kept.
- There is no in-app update check: watch this repository's Releases page.

### Verify the download

The APK attached to this release:

```
sha256  9320273cc68fd177dea17d25a845811f9e51eae2d6fa14273c4dc6c98fa984ce
```

Check it with `sha256sum app-release.apk` (compare the value above — `tools/release.sh`
prints the digest of the exact artifact it uploads, so re-read it there if this tree
changes before the release is cut), and confirm the signer is this project's release
certificate:

```
Signer #1 certificate DN: CN=Ayvu, O=moronigranja, C=BR
Signer #1 certificate SHA-256 digest: a5057984c0c285898a619b76c397f72030df124e55db88616de5e1a241902ae5
```

(`apksigner verify --print-certs app-release.apk` prints the same fingerprint; `keytool
-printcert -jarfile app-release.apk` works without the Android SDK.)

### First run

The app downloads its packs on first run — explicit, resumable and SHA-256-verified —
from four pinned sources, none of which is bundled (decisions #7):

| Pack | Size | Source |
|---|---|---|
| Kokoro-82M model `kokoro-v1.0.onnx` | 325 MB | `thewh1teagle/kokoro-onnx` release `model-files-v1.1` |
| Kokoro v1.0 voices (54) `voices-v1.0.bin` | 28 MB | same release |
| espeak-ng 1.52.0 phonemizer bundle | 9.9 MB | this project's `espeak-ng-1.52.0` release |
| LFM2.5-1.2B translate model (optional, read-in-language) | 730 MB | this project's `translate-lfm12b-v1` release |
| OCR language packs (per language, optional) | 13–22 MB each | `tesseract-ocr/tessdata` (tag `3.04.00`) |

After the TTS packs land, everything works offline — no network is used again unless you
add OCR languages or the translation model.

## Upgrading from a pre-release build

- Builds under the old id `com.moronigranja.localttsreader` are a **different app** from
  this one (`io.github.moronigranja.ayvu`): install fresh. Export a backup from the old
  build first and restore it here to carry the library, progress and bookmarks over.
- Installing over a **DEBUG** build of the same id requires an uninstall first — the
  debug key differs from the release key by design.

## Known limitations in this build

- **OCR accuracy** is capped by the bundled engine: tess-two 9.1.0 is pre-LSTM, so the
  pinned packs are legacy `3.04.00` tessdata (eng, spa, fra, deu, por, ita). A newer
  binding is required before LSTM models can be used.
- **Android Auto** media controls are wired through `MediaSession` but have not been
  verified on Auto hardware.
- **Playback runs at 1.0×.** The speed selector was removed in this cycle; the speed
  model is retained for a planned revisit.
- **Formats**: DRM-free files only; `.kfx` is detected and rejected. Share-and-identify
  recognizes only books already in your library, assumes contiguous plain text (non-space
  scripts are not supported by the matcher yet), and very short snippets are rejected by
  the confidence threshold.
- **Distribution**: unminified APK published as a GitHub Release (no Play listing, no
  AAB, no auto-update).
- **Translation model licence**: the optional read-in-language model (LFM2.5-1.2B) is
  distributed under the **LFM Open License v1.0**, not Ayvu's GPL-3.0 — that licence's
  terms (including its revenue threshold for commercial use) apply to the model itself.
  Its licence text ships in the model's release archive.

## Licenses

Ayvu is GPL-3.0; the corresponding source for this build is this repository at the
`v0.1.1` tag — <https://github.com/moronigranja/ayvu>. The full licence and the
third-party notices ship **inside the app** (About → Licences) and in the repository
([`LICENSE`](../LICENSE), [`NOTICE.md`](../NOTICE.md)). They cover the bundled runtimes
(ONNX Runtime, JNA, llama.cpp, tess-two, AndroidX/Kotlin), the KindleUnpack-derived
MOBI/KF8 parser, and every downloaded pack with its upstream host and licence.
