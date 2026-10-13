# Ayvu v0.2.1

Ayvu is a fully offline text-to-speech reader for Android: import your DRM-free ebooks
and listen to them narrated on-device. No account, no telemetry, no cloud.

## Highlights

- **The reader is immersive by default.** The chrome shows for three seconds on entry and
  then hides; a single middle tap toggles it, and every page turn dismisses it.
- **Select a word or phrase to translate or copy it.** Long-press picks the word under your
  finger, drag extends it, and a card offers Translate (into the book's display language)
  or Copy.
- **Reopening a book lands where you were.** The reading place is now written during
  playback too and mirrored by the player, so a listen session or a process death no
  longer freezes it at the open-time page; a dropped open recovers when the app returns to
  the foreground, toggling immersive no longer moves the page, and a per-book voice
  previews under that book's engine.
- **The camera cutout no longer overlaps the page.** On Android 15+ devices with a
  punch-hole or notch, the immersive reader now keeps the title band and the body text
  clear of the display cutout.

Full itemized changelog, with the reasoning and the device evidence:
**[CHANGELOG.md → 0.2.1](https://github.com/moronigranja/ayvu/blob/v0.2.1/CHANGELOG.md#021--2026-10-13)**.
Install and update steps, requirements and current limitations live in the
[README](https://github.com/moronigranja/ayvu#install).

## Artifact

`Ayvu-0.2.1.apk` — arm64-v8a, Android 8.0+ (API 26), GPL-3.0. It is signed with the same
certificate as every release since 0.1.1 (`a5057984…`, CN=Ayvu), so it installs straight
over them. The digest is pinned at publish — release bytes are not reproducible across
builds, so the value is the artifact uploaded by `tools/release.sh --upload`. The source
for this build is this repository at the `v0.2.1` tag — <https://github.com/moronigranja/ayvu>
(the full licence and third-party notices ship inside the app under About → Licences).
