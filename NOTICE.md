# Third-party notices

Ayvu — Copyright © 2026 moronigranja — is licensed under the
GNU General Public License v3.0 (see `LICENSE`).

This distribution and its source bundle or invoke the following third-party
components (revisited at each release — decisions #126):

## Bundled in the APK

- **ONNX Runtime** (Android AAR) — MIT License —
  <https://github.com/microsoft/onnxruntime>
- **JNA** (Java Native Access) — Apache-2.0 OR LGPL-2.1-or-later —
  <https://github.com/java-native-access/jna>
- **llama.cpp / ggml** (native libs bundled via `core-llm`, vendored at commit
  `b75ecd1971bf2d3f29d5d334520868a01942cbc6` by `tools/fetch-llama-cpp.sh`) —
  MIT License — <https://github.com/ggml-org/llama.cpp>
- **Tesseract4Android 4.9.0** (Android AAR with Tesseract 5.5.1 + Leptonica
  natives, via `feature-ocr`, from JitPack) — Apache-2.0 —
  <https://github.com/adaptech-cz/tesseract4android> (the maintained successor
  to tess-two, Apache-2.0, <https://github.com/rmtheis/tess-two>)
- **Jetpack Compose / Material 3 / Room / Hilt / WorkManager,
  kotlinx-coroutines, Kotlin stdlib** — Apache-2.0 (per the AndroidX and
  Kotlin project licenses)

## Derived source

- **KindleUnpack** — GPL-3.0 — <https://github.com/kevinhendricks/KindleUnpack>.
  The MOBI/KF8 path in `core-ebook` is ported from KindleUnpack: the
  PalmDOC/HUFF-CDIC decompressor (`HuffCdic.kt`, from `mobi_uncompress.py`), the
  NCX/TAGX index reader (`MobiNcx.kt`, from `MobiIndex`/`mobi_ncx`), and the
  trailing-data trim in `MobiParser.kt` follow its semantics. The ports are
  rewritten in Kotlin against Ayvu's own parser contracts (no KindleUnpack code
  is copied verbatim); those files carry the provenance and GPL-3.0 notice in
  their headers.

## Downloaded at runtime (packs; never embedded in the APK)

Nothing here is bundled — no model data ships in the APK (decision #7). The app
downloads these packs at first run, into its internal storage, from **four
pinned sources**; two are served from this project's own releases, two from
third-party repositories:

- **Kokoro-82M TTS** model `kokoro-v1.0.onnx` (325,505,369 B) and voices
  `voices-v1.0.bin` (28,214,398 B) — Apache-2.0 (upstream model by hexgrad) —
  served from the `thewh1teagle/kokoro-onnx` release `model-files-v1.1`:
  <https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1>
  (upstream project: <https://github.com/hexgrad/kokoro>)
- **espeak-ng** phonemizer bundle (`libespeak-ng.so` + `espeak-ng-data`, 1.52.0,
  10,144,828 B) — GPL-3.0-or-later — served from this project's
  `espeak-ng-1.52.0` release:
  <https://github.com/moronigranja/ayvu/releases/tag/espeak-ng-1.52.0>
  (upstream project: <https://github.com/espeak-ng/espeak-ng>). Corresponding
  source: the upstream repository at tag `1.52.0`, cross-compiled by
  `tools/build-espeak-android.sh`; the bundled archive carries the licence text
  and a `SOURCE-OFFER.txt` pointing at them.
- **LFM2.5-1.2B-Instruct** translate model `LFM2.5-1.2B-Instruct-Q4_K_M.gguf`
  (730,895,168 B) — **LFM Open License v1.0** (from LiquidAI) — served from this
  project's `translate-lfm12b-v1` release:
  <https://github.com/moronigranja/ayvu/releases/tag/translate-lfm12b-v1>
  (upstream project: <https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF>).
  The licence text is included in that release archive; its terms (including the
  revenue threshold for commercial use) apply to the model, not to Ayvu's own
  GPL-3.0 code.
- **LFM2.5-2.6B-Base** translate model `LFM2.5-2.6B-Base.Q4_K_M.gguf`
  (1,674,454,080 B, SHA-256 `bff5a730…`) — **LFM Open License v1.0** (from
  LiquidAI) — served from this project's `translate-lfm26b-base-v1` release:
  <https://github.com/moronigranja/ayvu/releases/tag/translate-lfm26b-base-v1>
  (upstream project: <https://huggingface.co/LiquidAI/LFM2.5-2.6B-Base>; the
  Q4_K_M quant is by mradermacher). The licence text is included in that release
  archive; its terms (including the revenue threshold for commercial use) apply
  to the model, not to Ayvu's own GPL-3.0 code.
- **Piper** VITS voices (one model + config per voice, 22050 Hz) — from the
  MIT-licensed `rhasspy/piper-voices` repository @ `1162a917`; per-voice dataset
  terms are stated on each upstream voice card: `en_US-lessac-medium` (Blizzard
  2013 Lessac dataset), `de_DE-thorsten-high` (Thorsten dataset),
  `es_ES-davefx-medium` and `pt_BR-faber-medium` (CC0),
  `it_IT-serena-medium` (CC-BY-4.0, attribution: Serena dataset):
  <https://huggingface.co/rhasspy/piper-voices>
- **Tesseract OCR** data (tessdata, one file per OCR language) — Apache-2.0 — served
  from the upstream `tesseract-ocr/tessdata_fast` repository (tag `4.1.0`, LSTM
  models for the Tesseract 5 engine):
  <https://github.com/tesseract-ocr/tessdata_fast>

Every pack is SHA-256-verified against a value pinned in the source before it is used.
Because some of the hosts are third-party repositories, a deleted or re-tagged
upstream release would break first-run downloads — the pinned hashes turn that into a
typed download failure, never a silent bad install.

Full license texts accompany their upstream distributions. Ayvu itself is
GPL-3.0: `LICENSE` and this file ship inside the app (About → Licences) as well
as in the repository, and the corresponding source for a released build is the
same repository at that release's tag.
