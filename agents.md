# agents.md — ayvu

Context for AI coding agents working in this repo. Read this first, then the docs it
points to. When a decision in a doc conflicts with the existing code, resolve it by
reading the code and updating the doc (not by contradicting it silently).

## What this is

A **greenfield Android app** that reads the user's book library aloud using **on-device
text-to-speech** from an **open-weight model**: **Kokoro-82M is the v1 primary engine and
Piper (`piper-v1`) is a second primary** — both `EngineTier.PRIMARY`, registered with
pinned packs and selectable for playback (`core-tts/.../DefaultEngines.kt`).
Fun-CosyVoice3-0.5B is metadata-only and gated behind the fallback tier (CPU RTF 14.7–17.5
on the S22 Ultra, decisions #21); there is no auto-fallback — the playback seam chooses an
engine explicitly. Everything runs offline. No cloud, no account, no telemetry.

Three capabilities, one pipeline:

1. **Content** — load a book (Kindle-sourced) and produce a flat, ordered sequence of
   text passages with navigation structure (chapters/spine). Importing a book also adds
   it to the text index so the share feature can match against it.
2. **Speech** — convert passages to audio with a local model and play it back with
   transport controls, bookmarking, and progress persistence.
3. **Share-and-identify** — when the user shares text or a screenshot (e.g., from the
   Kindle app), identify which book + chapter/passage it came from (against books in the
   local library) so playback can resume there.

## Docs

- [docs/hard-facts.md](docs/hard-facts.md) — domain constraints that are not options
  (Kindle/ebook content, DRM, sync/progress, TTS engines, offline-first).
- [docs/architecture.md](docs/architecture.md) — module dependency graph, capability
  data flows, cross-module contracts.
- [docs/conventions.md](docs/conventions.md) — tech stack, code conventions, do's and
  don'ts, definition of done.
- [docs/modules.md](docs/modules.md) — module layout: built modules live, rest planned (split only when a cycle forces it).
- [docs/landscape.md](docs/landscape.md) — adjacent projects (sherpa-onnx engine,
  candela app): what they validate, the license boundary, and the T2 engine direction
  (decisions #24–#27).
- [docs/build.md](docs/build.md) — how to build, run, and test.
- [docs/features/](docs/features/) — feature plans (share-and-identify; player/TTS/settings pending).
- [docs/brand.md](docs/brand.md) — brand identity: name (Ayvu), tagline, icon timeline, poem plan.
- [docs/decisions.md](docs/decisions.md) — decision log (why things are the way they are).
- [docs/roadmap.md](docs/roadmap.md) — forward sequencing, queue order, and the
  authoritative release state (§Release readiness).
- [docs/open-bugs.md](docs/open-bugs.md) — the authoritative open-defect list.
- [docs/reviews/](docs/reviews/) — dated review passes (architecture, peer cross-checks).
- [docs/features/share-and-identify.md](docs/features/share-and-identify.md) — the
  share-and-identify (resume-from-share) feature plan and status.
