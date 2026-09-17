# Ayvu v0.1.2 — release notes

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free
ebooks and listen to them narrated on-device. No account, no telemetry, no cloud.

## What's new in 0.1.2

Every long-running job is now a **visible, interruptible** one. Downloads, the unpack
step that follows a download, and book import used to run with no notification and no
reliable way to stop them — and they died with the screen that started them.

- **Progress notifications with a Stop action.** Downloading an engine or voice pack
  (including the 730 MB read-in-language model), unpacking the verified pack afterwards,
  and importing books (a picked file, a folder, or "Open with Ayvu") each run as one
  foreground operation: a notification with the file's name, a real percentage and
  **Stop**. The library's import overlay and the setup wizard's import step now show the
  same one import, from wherever it was started.
- **They survive the screen.** Lock the phone or switch apps and the work continues — a
  `dataSync` foreground service holds the process; Android 15+'s six-hour cap ends a run
  and clears its notification instead of leaving a stale one.
- **Stop means stop, and a retry resumes.** A stopped download keeps its partial file
  and the next attempt continues from that byte (device-verified: a translate-pack
  download stopped at 23 MB resumed at 3% and finished). A stopped unpack leaves the
  previous unpacked bundle intact and re-runs by itself the next time you open Settings.
  A stopped import keeps the books it had already committed and clears the overlay.
- **The two existing notifications can be stopped too.** Offline pre-generation carries a
  **Stop** that ends every manual run (already-cached audio stays), and the audio
  generation that follows a seek carries one that ends playback and the generation it was
  waiting on.

Nothing else in the app changes shape: the first-run flow, the reader, listen-along
highlighting, read-in-another-language and Backup & restore are as in 0.1.1.

## Install

- **Requires a 64-bit ARM device (arm64-v8a) running Android 8.0+ (API 26).** The build
  is arm64-only because the espeak-ng phonemizer it ships is an arm64 native library;
  32-bit and x86 devices are not supported and the APK will not install there.
- Unminified signed release build, **≈50 MB** (ONNX Runtime and JNA are inside).
- Install the APK from this release (allow "install unknown apps" for your browser or
  file manager). The signing key is stable across releases, so this installs straight
  over 0.1.1 — no uninstall, and your library, progress, bookmarks and settings are kept.
- There is no in-app update check: watch this repository's Releases page.

### Verify the download

The APK attached to this release:

```
sha256  197f89333b1e8ab73b7fff5b4cde19952f4313d4e0e047df5f81997d55ac8f1b
```

Check it with `sha256sum Ayvu-0.1.2.apk` (compare the value above — `tools/release.sh`
prints the digest of the exact artifact it uploads, so re-read it there if this tree
changes before the release is cut), and confirm the signer is this project's release
certificate:

```
Signer #1 certificate DN: CN=Ayvu, O=moronigranja, C=BR
Signer #1 certificate SHA-256 digest: a5057984c0c285898a619b76c397f72030df124e55db88616de5e1a241902ae5
```

(`apksigner verify --print-certs Ayvu-0.1.2.apk` prints the same fingerprint; `keytool
-printcert -jarfile Ayvu-0.1.2.apk` works without the Android SDK.)

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

Each of those downloads now reports progress and can be stopped (see above). After the
TTS packs land, everything works offline — no network is used again unless you add OCR
languages or the translation model.

## Upgrading from a pre-release build

- Builds under the old id `com.moronigranja.localttsreader` are a **different app** from
  this one (`io.github.moronigranja.ayvu`): install fresh. Export a backup from the old
  build first and restore it here to carry the library, progress and bookmarks over.
- Installing over a **DEBUG** build of the same id requires an uninstall first — the
  debug key differs from the release key by design.

## Known limitations in this build

- **Stop lands at a boundary, not mid-file.** A single book's parse + segmentation is one
  uninterruptible step, so stopping an import takes effect at the next file; one download
  chunk can outlive the Stop by up to the transport's 30-second read timeout (the resume
  then continues from the bytes that did arrive).
- **A process kill loses in-flight work.** If Android reclaims the process or you
  force-stop the app, a download resumes from its partial file on the next attempt and an
  import must be started again — unchanged from 0.1.1, and deliberately not a
  restart-after-death service.
- **OCR accuracy** is capped by the bundled engine: tess-two 9.1.0 is pre-LSTM, so the
  pinned packs are legacy `3.04.00` tessdata (eng, spa, fra, deu, por, ita). A newer
  binding is required before LSTM models can be used.
- **Android Auto** media controls are wired through `MediaSession` but have not been
  verified on Auto hardware.
- **Playback runs at 1.0×.** The speed selector was removed in an earlier cycle; the
  speed model is retained for a planned revisit.
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
`v0.1.2` tag — <https://github.com/moronigranja/ayvu>. The full licence and the
third-party notices ship **inside the app** (About → Licences) and in the repository
([`LICENSE`](../LICENSE), [`NOTICE.md`](../NOTICE.md)). They cover the bundled runtimes
(ONNX Runtime, JNA, llama.cpp, tess-two, AndroidX/Kotlin), the KindleUnpack-derived
MOBI/KF8 parser, and every downloaded pack with its upstream host and licence.
