# Ayvu v0.2.0

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free ebooks
and listen to them narrated on-device. No account, no telemetry, no cloud.

## Highlights

- **Reopening a book lands where you were reading.** A book's reading place is now stored
  per book and restored on open — the page you last saw, not the chapter's first page —
  and it survives a cold start. A book that cannot be opened says so instead of hanging on
  *Opening book…*, and a stuck open retries itself a couple of times.
- **Per-book voice *and* engine.** The reader's voice sheet now saves the voice to that
  book (with **Use book default** to go back), and a new per-book engine override joins it
  — a German Piper voice on one book no longer re-voices the whole library.
- **Resuming after a pause rewinds by how long you were paused** — a short pause ≈ 3 s, an
  overnight one ≈ 30 s — never past the start of the current chapter.
- **Progress shows two decimals.** Pre-generation (in-app row and notification) and export
  notifications now read `42.37%` instead of `42%`.

Full itemized changelog, with the reasoning and the device evidence:
**[CHANGELOG.md → 0.2.0](https://github.com/moronigranja/ayvu/blob/v0.2.0/CHANGELOG.md#020--2026-09-28)**.
Install and update steps, requirements and current limitations live in the
[README](https://github.com/moronigranja/ayvu#install).

## Artifact

`Ayvu-0.2.0.apk` — arm64-v8a, Android 8.0+ (API 26), GPL-3.0. **52,294,976 B**,
`sha256 e17b83fdd587dd7b9d5e059c4ef85b69d035fe4cc4db4a4ae1ae8f28cf296df7` (the stored
asset, re-downloaded and hashed). It is signed with the same certificate as every release
since 0.1.1 (`a5057984…`, CN=Ayvu), so it installs straight over them. The source for this
build is this repository at the `v0.2.0` tag — <https://github.com/moronigranja/ayvu> (the
full licence and third-party notices ship inside the app under About → Licences).
