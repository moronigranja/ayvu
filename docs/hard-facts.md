# Hard domain facts (do not re-litigate, design around these)

These are constraints, not options. Do not "just add an Amazon API layer" or assume
Kokoro ships inside the app.

## Kindle / ebook content

- **Amazon has no public personal-library API.** Do not build against one. The
  "Manage Your Contents and Documents" export endpoint exists but is undocumented,
  rate-limited, and only DRM-free lending-eligible titles — treat it as a possible
  future bridge, not a foundation.
- **Realistic on-device content paths, in order of priority:**
  1. **User-provided local files** — the user opens/sends `.azw3` / `.kf8` / `.mobi`
     (`.azw`), or `.epub`. Parse directly. This is the v1 scope. No DRM involved.
  2. **Kindle-for-PC/Mac local library** — a SQLite DB (`content store` /
     `metadata.db`) points at encrypted book files. Reading this on the **Android
     device is not feasible** (that DB lives on a PC/Mac). Support this via a
     companion export step or by the user transferring files, not by shelling out to
     the desktop app.
  3. **Amazon-DRM path (DRM-encrypted .azw3/.kf8/.kfx)** — unlocking these requires
     DRM removal, which **is DRM circumvention** (legally fraught; see stance below).
     - **Out-of-app only.** Any DRM removal runs on the user's own machine, using
       tools the user chooses and runs themselves. The app NEVER logs into Amazon,
       NEVER touches the user's Amazon account or credentials, and NEVER ships keys,
       decryption, or DRM-removal code. Accepting the user's Amazon login in-app turns
       a personal reader into a circumvention-with-capture device — the highest-risk
       path — and is off the table. The app consumes only DRM-free files the user
       supplies.
     - **Legal stance:** the app itself performs no circumvention and facilitates
       none; any DRM removal outside the app is the end user's own act and risk. Flag
       prominently; never default to it.
     - **Kindle Unlimited (KU) books are excluded.** They are borrowed, not owned;
       DRM unlocks the container, not the borrow window, so a KU copy goes dead when
       the loan expires. Only handle books the user owns.
- **File formats to parse:**
  - `.azw3` / `.kf8` are essentially **EPUB3** (a ZIP with `content.opf`, a spine, and
    XHTML/CSS media). Parse the OPF spine for chapter order, extract body text.
  - `.mobi` / older `.azw` are MOBI (PalmDOC/EXTH container semantics).
  - `.epub` is EPUB2/3.
  - `.kfx` is Amazon's newer, closed container — **out of scope for v1**; detect it and
    guide the user to convert/export rather than attempting to parse.
- Treat every file as untrusted and malformed-able. Parse defensively; never crash on
  a bad spine or missing metadata. Prefer a single `EBook` abstraction that adapts a
  format parser to a common domain model.

## Kindle sync / reading progress

- **Reading the Kindle app on-device for progress is not feasible on a normal device.**
  The Kindle app's data lives in its Android sandbox (`/data/data/com.amazon.kindle`),
  is encrypted, and its schema is undocumented. Reading position is also **server-side**
  (Amazon syncs it through the account). Touching it needs root + adb-backup/extract
  — brittle, ToS-violating, and excludes all non-rooted users.
  **Do not build this.**
- **Legitimate sync sources (Amazon returning the user's own data):**
  - **Official export** — "Your Content and Documents" / "Download your data" gives
    per-book reading position, last-read time, highlights, and notes.
  - **Read-only Kindle Highlights/Reports API** (access-token, cursor pagination) —
    the sanctioned programmatic path for highlights/notes/position.
  - Both are **on-demand/scheduled, not real-time**; expose an explicit
    "sync from Kindle" action rather than assuming live updates.
- **Manual resume** is always available: the user sets their location in the reader and
  it is persisted. Because the TTS reader is a separate app, a manual resume point is
  often the cleanest UX and needs no Amazon access.

## Text-to-speech

- **Expressive narration is a requirement, not a nice-to-have** (user review 2026-08).
  Kokoro-82M is natural but flat in prosody; the expressive engine (CosyVoice3) is a
  measured gate away from the primary slot.
- **v1 primary: Kokoro-82M** (`hexgrad/kokoro-82M`) — decisions #21/#25/#28. ~82M
  ONNX (~300 MB fp32, smaller quantized), 8 languages incl. pt-BR, Apache-2.0, fast
  and light but flat. Verified on the S22 (RTF 0.66–0.76, engine open 1.5 s); pinned
  fp32 packs (`model-files-v1.1`), pt-BR voices pf_dora/pm_alex/pm_santa.
- **Gated fallback / pre-gen-only: Fun-CosyVoice3-0.5B** (Apache-2.0,
  `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`, Dec 2025). 0.5B — the lightest model with
  real emotion control: instruct support for emotion, speed, volume and dialect; 9
  languages (zh, en, fr, es, ja, ko, it, ru, de) + 18+ Chinese dialects; zero-shot and
  cross-lingual voice cloning. ONNX (community exports) and GGUF
  (`cstr/cosyvoice3-0.5b-2512-GGUF`) exist. **Gate measured (decisions #21/#49):
  CPU-only RTF ≈13.4 (12.6–14.4) on the S22 — 13–22× over the ~1×-realtime bar — so
  it stays out of live playback; viable only as a pre-generation engine behind the #42
  overnight window (decisions #54), with zero-shot cloning in scope for that slice.**
- **Non-realtime synthesis is acceptable.** This is an audiobook player, not a chatbot:
  the engine only has to stay ahead of playback by pre-generating upcoming passages in
  the background. Even ~0.5–1x realtime is fine if generation keeps up — this
  de-risks a 0.5B engine on phone CPU, and is exactly why CosyVoice3 stays usable
  despite the RTF gate.
- **Engine tiers** (all behind `TTSEngine`; select per measured need):
  | Engine | Size | Expressiveness | Languages | Notes |
  |---|---|---|---|---|
  | **Kokoro-82M** (v1 primary) | 82M ONNX | Natural but flat | 9 groups incl. pt-BR | Apache 2.0. Measured baseline; pinned fp32 packs (#28). |
  | **Fun-CosyVoice3-0.5B** (high-end tier incumbent: cloning, pregen-only) | 0.5B; int8/GGUF ~0.4–0.6 GB | High: emotion/speed/volume instruct, zero-shot voices | 9 + 18 dialects | Apache 2.0. CPU RTF ≈13.4 on S22 — pre-gen only (#21/#54). |
  | **Chatterbox Multilingual ONNX** (high-end challenger — D5, gated on G0) | 0.5B AR Llama; community ONNX export | High: zero-shot cloning, exaggeration control | 23 langs incl. es/it/pt/de/ko | MIT. Only community exports exist (`onnx-community`; `textagent/…` is a mirror, not a pin candidate) — provenance gate + PyTorch parity before measurement; AR KV-cache memory on-device is the open risk. Official Resemble ONNX is Turbo = en-only. |
  | **Pocket TTS** (high-end challenger — D5, gated on G0; added 2026-09-11 #149) | 100M params / ~176 MB total (5 ONNX graphs + int8 variants) | Flow-matching + zero-shot cloning; native streaming (~200 ms first chunk) | en/de/fr/it/pt/es (Kokoro's German gap) | MIT code / **CC-BY-4.0 weights, gated upstream** — use the ungated community ONNX export; ship CC0/CC-BY voices only (**`expresso/`+`ears/` are CC-BY-NC**); a second G2P convention (Misaki) and no phone RTF measured. |
  | **Piper** (small tier — D4, adopted 2026-09-13 #154) | VITS, 14–100 MB per-voice ONNX | Mostly flat | ~20+ langs incl. de/ko (Kokoro's gaps) | MIT. Direct-ORT port (not sherpa) keeps espeak-ng/JNA; per-voice model+config packs via TtsPack (first pins: lessac en-US + thorsten de-DE @ `1162a917`); the stock export exposes no alignments → passage-level read-along only (#30b); quality gate passed (owner listening pass, #99 addendum). |
  | **Supertonic 3** (small-tier candidate — D4) | ~99M, ONNX int8 export | Flow-matching, inline tags | 31 langs | Archived upstream — supply gate: pin HF revision + hashes; vendor RTF ~0.3 on e-reader; duration introspection unverified. |
  | **KittenTTS** | 15–80M ONNX (25–80 MB) | Unproven; tiny | en only (dev preview) | Apache 2.0. Ultra-light watch item. |
  | **MeloTTS** | small | Moderate | 6 | MIT, CPU real-time. Watch item. |
  | **Orpheus-TTS** | 3B only | High (emotion tags) | multilingual research family | Apache 2.0 but desktop-only at 3B — out of phone scope. |
  | CosyVoice-300M | 300M (v1) | Moderate | 5 | Older gen; superseded by 0.5B v3. |
  | Qwen3-TTS | 1.7B | High | ~10 | Heavy; only with strong NPU quant. Long-term option. |
  | Coqui XTTS v2 | large | High | multilingual | Restrictive Coqui license — avoid. |
- **AR codec-token family — watch item (2026-08-25).** Parallel-lane candidate that
  removes the DiT entirely: an autoregressive LLM predicting discrete audio tokens
  (Mini-Omni / Qwen2-Audio class, 0.5–1.5B) + a neural codec (SNAC / Mimi / EnCodec).
  Motivated by the measured T3 result (flow DiT = ~72% of CosyVoice3's on-device RTF —
  see decisions #21). Gate before it can be a primary: measured RTF/TTFA on the S22
  Ultra **and** a narration-quality review (expressiveness for the audiobook use case
  is unproven in that family).
- **Portuguese (pt-BR) is a nice-to-have, not a blocker** (user 2026-08) — and the
  v1 primary covers it directly: the pinned Kokoro pack ships 3 pt-BR voices
  (pf_dora, pm_alex, pm_santa), so pt is first-class at v1 (decisions #29), with
  Piper per-language packs as the fallback tier. CosyVoice3's official 9 languages
  still exclude pt; revisit when a primary release adds it.
- **Language/voice packs are downloadable, never bundled.** All engine assets — model
  weights, per-language assets, voice packs — are runtime downloads: explicit,
  user-consented, resumable, cached after first fetch (consistent with offline-first).
  `core-tts` owns a pack registry (engine → pack → status) plus download/verify/cache
  management; the APK ships no TTS model data. A language that is not downloaded is
  surfaced in settings with a "download" action, never a silent failure. CosyVoice3
  covers its 9 languages in one pack; engines like Piper would add per-language packs.
- **Kokoro on-device performance/fidelity report (2026-09-11, decisions #148/#150)** — full
  numbers and method in `docs/kokoro-on-device-perf.md` (self-contained, written to be
  reusable by other projects investigating the same comparison). Durable results:
  * the 92 MB int8 export peers ship is **not** a lever — ~2× slower than fp32 on the Fold 8
    and 1.44× on the S22 Ultra, 1.6–1.9× the energy per audio-hour, and audibly "frayed" on
    pt-BR (fp32 preferred on both devices) while en-US is near-indistinguishable; it also
    names its waveform output `audio`, which an engine hard-coded to `waveform` rejects;
  * a **small window cap (150) is the weak-device latency setting** — throughput is flat
    across caps while first-audio grows from 8.7 s (150) to 143.6 s (510) on the HiBreak;
  * **keep the whole-passage `MODE_STATIC` output path** — per-window streaming reaches
    first audio after 34–85 s and underruns twice per 64 s of audio;
  * **an ADPF hint session is the only measured speed lever** (+23 % throughput, +12 %
    energy per audio-hour); spinning/priority/thread tweaks did nothing;
  * **duty cycling is a thermal tool, not an energy one** (power 2.5× down, energy per
    audio-hour 12 % up);
  * **ORT ≥ 1.29 has a CPU `MatMulNBits` (int4) kernel**, with packed shapes;
  * **no absolute waveform-noise gate below ~0.03 can be passed by any computation-path
    change on this model** (the vocoder amplifies fp32 kernel-order noise): gate precision
    changes per-layer or by listening, not on max-abs PCM diff;
  * the three measurement **corpora are published** in `docs/corpus/` (2-passage,
    pregen-16, leg-G) with sha256 + measured audio per cap, so legs A–G can be re-run
    rather than only read (decisions #152).
- **Synthesis power and heat, measured (2026-09-11, Fold 8 / SM-F971B, on battery with the
  screen on; decisions #147):** live Kokoro synthesis runs at ~3.0–4.8 W (screen-on idle
  floor 0.66 W), takes the device from thermal status 0 to 3 (SKIN 36 → 45 °C) in ~15 min
  without cooling while it continues, and costs ~5% of the 4800 mAh battery per 17 min —
  ~10% per *listening hour* when audio is synthesized live. Thread count does not rescue
  it: 1 thread is both slower than realtime (RTF 1.212) and the most expensive per audio
  hour (3.59 Wh vs 1.94 Wh at 4 threads). Playing from the pre-generated cache is the only
  lever that idles the SoC during listening.
  **The thread knee is regime-dependent, so it is not a constant**: the same axis re-measured
  plugged/charging (thermal status 3) puts the knee at 4 (0.781) with 6/8 behind it, and
  charging+hot costs 1.36–2.0× at the same T (decisions #151, report §4.9). Never benchmark
  a charging device and read the number as capability.
- **Reference device:** Galaxy S22 Ultra (Snapdragon 8 Gen 1) — the performance gate for
  engine selection. CosyVoice3-0.5B int4 measured there (decisions #49): RTF ≈13.4,
  peak native VmHWM 2.27 GiB / PSS 336 MiB, no thermal trip — fails the live-realtime
  bar, so it is not a default; pre-gen-only (decisions #21/#54).
- **Android path:** ONNX Runtime Mobile. Kokoro has a working Kotlin reference
  (`thewh1teagle/kokoro-onnx`, its `SpeechPipeline`). CosyVoice3 needs a spike to port a
  community ONNX export (e.g. `Lourdle/Fun-CosyVoice3-0.5B-2512_ONNX`); all engines stay
  behind `TTSEngine` so swapping is a config change.

## Offline-first

- No network in the happy path. Any download (model weights, language/voice packs) is a
  single, explicit, user-consented, resumable operation. If you add any socket use,
  justify it in a PR.
## Platform / OS

- **Background neural/TTS access can be taken away by OS policy.** iOS 27 beta
  removed background neural-engine access for third-party apps (landscape,
  2026-08-25). Android analog: OEM background restrictions / Doze can kill
  long-running playback. The player MUST run as a proper foreground service
  (mediaPlayback) and handle being re-killed gracefully — implemented in T4-2
  (decisions #34); re-verify on each new OEM/OS pass.
