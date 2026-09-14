# Third-party notices

Ayvu (local-tts-reader) — Copyright © 2026 moronigranja — is licensed under the
GNU General Public License v3.0 (see `LICENSE`).

This distribution and its source bundle or invoke the following third-party
components (revisited at each release — decisions #126):

## Bundled in the APK

- **ONNX Runtime** (Android AAR) — MIT License —
  <https://github.com/microsoft/onnxruntime>
- **JNA** (Java Native Access) — Apache-2.0 OR LGPL-2.1-or-later —
  <https://github.com/java-native-access/jna>
- **Jetpack Compose / Material 3 / Room / Hilt / WorkManager,
  kotlinx-coroutines, Kotlin stdlib** — Apache-2.0 (per the AndroidX and
  Kotlin project licenses)

## Downloaded at runtime (packs; never embedded in the APK)

Nothing here is bundled — no model data ships in the APK (decision #7). The app
downloads these packs at first run, into its internal storage, from **three different
hosts**; only the espeak-ng bundle is served from this project's own releases:

- **Kokoro-82M TTS** model `kokoro-v1.0.onnx` (325,505,369 B) and voices
  `voices-v1.0.bin` (28,214,398 B) — Apache-2.0 (upstream model by hexgrad) —
  served from the `thewh1teagle/kokoro-onnx` release `model-files-v1.1`:
  <https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1>
  (upstream project: <https://github.com/hexgrad/kokoro>)
- **espeak-ng** phonemizer bundle (`libespeak-ng.so` + `espeak-ng-data`, 1.52.0,
  9,857,162 B) — GPL-3.0-or-later with the espeak-ng toolchain exception —
  served from this project's `espeak-ng-1.52.0` release:
  <https://github.com/espeak-ng/espeak-ng>
- **Piper** VITS voices (one model + config per voice, 22050 Hz) — from the
  MIT-licensed `rhasspy/piper-voices` repo @ `1162a917`; per-voice dataset
  licenses: en_US-lessac-medium + de_DE-thorsten-high (repo MIT),
  es_ES-davefx-medium (CC0), it_IT-serena-medium (CC-BY-4.0),
  pt_BR-faber-medium (CC0):
  <https://huggingface.co/rhasspy/piper-voices>
- **Tesseract OCR** data (tessdata, one file per OCR language) — Apache-2.0 — served
  from the upstream `tesseract-ocr/tessdata` repository (tag `3.04.00`, legacy
  non-LSTM models; see the README's OCR limitation):
  <https://github.com/tesseract-ocr/tessdata>

Every pack is SHA-256-verified against a value pinned in the source before it is used.
Because two of the three hosts are third-party repositories, a deleted or re-tagged
upstream release would break first-run downloads — the pinned hashes turn that into a
typed download failure, never a silent bad install.

Full license texts accompany their upstream distributions.