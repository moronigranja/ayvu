# Distribution and launch plan

Decision-support document, **not started**. Two independent tracks for getting Ayvu in
front of users beyond the GitHub Releases page (decisions #126 is the shipped
distribution decision; #128 the `io.github.moronigranja.ayvu` identity; #174 the
licence/NOTICE completeness gate the submission track depends on):

- **Track A — community announcement** (one subreddit first: feedback, not reach).
- **Track B — IzzyOnDroid listing** (an F-Droid-compatible repo that redistributes
  the signed APK, and the realistic answer to "why not F-Droid?").

Every external claim below carries the date it was observed. Reddit rules pages are
**not machine-readable from this host** (the `/about/rules` endpoints answer with the
"prove your humanity" wall; the redlib mirrors tried — catsarch, freedit, nadeko,
privacyredirect, artemislena, safereddit — returned 429/404/403/Anubis). Treat every
Reddit row as *needs a manual check in a browser the day you post*.

Owner intent (2026-09-27): save this, pick it up later.

## Track A — one subreddit to start

**Start with r/fossdroid, and only that one.** Rationale: the install path is a signed
`Ayvu-0.1.4.apk`, 52,279,328 B, arm64-v8a only, from GitHub Releases, GPL-3.0, no
account, no telemetry, offline after the one-time pack download — i.e. exactly the
r/fossdroid user (sideloading FOSS Android apps, reads release notes, files bug
reports instead of asking for iOS). One sub also avoids the sitewide "same promo across
communities" pattern that reads as spam.

Rule position, as far as it was verifiable:

| Subreddit | Position | Evidence |
|---|---|---|
| r/androidapps | **Don't.** Explicitly banned: "Any self-promotion, tester requests, new app ideas or app feedback are not allowed in r/androidapps." | rule text quoted by LeadsRover's classifier, snapshot 2026-06-02 |
| r/fossdroid | **First choice.** Rule text not fetched — verify before posting; expect a discovery/`[DEV]` flair or an author-disclosure requirement. | Reddit bot wall blocked every attempt, 2026-09-27 |
| r/opensource, r/degoogle | Fallbacks if r/fossdroid is gated. r/degoogle is the ethos twin (no cloud, no account, no telemetry). | unverified |
| r/LocalLLaMA | Secondary, for the *on-device llama.cpp translation* angle only. Rule 4 "Limit Self-Promotion … the 1/10th rule … no sensationalized titles, no affiliate links". | subreddit rule text via search index + LeadsRover "Restricted" |

Context for why this list is short: of ~11,800 classified subreddits, 68% of those that
state a policy ban promotion outright, and **zero of 232 subreddits above 1M
subscribers explicitly allow it** (LeadsRover classification, 2026-08-09). Subscriber
count is close to an inverse indicator of welcome.

### The post (draft shape)

Lead with the friction, not the feature list:

1. **Disclosure first line** — "I built this, I'm the author."
2. **The install reality, up front** — arm64-v8a only, Android 8.0+, ≈50 MB APK, GitHub
   Releases, *not on F-Droid/IzzyOnDroid yet*.
3. **One concrete demo** — share a quote/screenshot from another reader app → Ayvu finds
   the book in the library and jumps playback to that passage. This is the memorable
   hook; everything else (EPUB2/3, AZW3/KF8, MOBI, TXT/MD import; Kokoro and Piper
   voices; sentence-level read-along; OCR of screenshots) is supporting detail.
4. **Specific asks** — Kokoro/Piper RTF on *their* SoC; voice-quality judgments; which
   DRM-free file failed to import; whether arm64-only blocks anyone; whether the
   read-along highlight tracks well enough.

Answer every comment for the first 48 h. That thread *is* the feedback channel.

### Risks to preempt, not defend later

- **The LFM2.5 translate packs are not OSI-licensed** (Liquid's LFM Open License).
  Framing: translation is one optional feature, its engine is named per target, and the
  app is fully usable without it.
- **The first question will be "why not F-Droid?"** — the honest answer is the pinned,
  auto-downloaded model packs plus the arm64 prebuilt espeak-ng phonemizer. Having a
  Track B listing in flight converts that from a criticism into a compliment.
- **Repetition is the actual ban risk**, not any single post. One sub, one post, then
  participate.

## Track B — IzzyOnDroid listing

**What it is.** A third-party, F-Droid-compatible repo (`apt.izzysoft.de/fdroid`) that
redistributes **the developer's own signed APK** taken from tagged releases — it does
*not* rebuild from source (F-Droid does). Trust at their end is APK scanning + pinned
signing certificate + optional reproducible builds. Updates are picked up from tagged
GitHub releases within ~24 h. Submission = one issue on the ticket tracker, which has
**moved to Codeberg**: <https://codeberg.org/IzzyOnDroid/repodata> (the GitLab tracker
is archived; its README says to request inclusion over there). Metadata (author, URLs,
description, screenshots) is maintained on their side, or synchronized from a
`fastlane/metadata/android/` tree in this repo.

### Fit against their [Inclusion Policy](https://gitlab.com/IzzyOnDroid/repo/-/wikis/Inclusion-Policy)

| Criterion | Ayvu |
|---|---|
| Libre license, OSI/FSF (SPDX) | ✅ GPL-3.0; deps MIT / Apache-2.0 / GPL-3.0 (espeak-ng); tessdata + Kokoro weights Apache-2.0; Piper MIT |
| End-user app; code openly accessible | ✅ GitHub repo with README, NOTICE, decisions |
| Signed release key, no `android:debuggable` / `testOnly` | ✅ same key across 0.1.1–0.1.4; cert fingerprint published in the release notes (decisions #126/#174) |
| APK attached to a tagged GitHub release | ✅ `releases/tag/v0.1.4` |
| Unique packageName / displayName; fork rules | ✅ `io.github.moronigranja.ayvu` (#128) — state explicitly in the issue that the retired `com.moronigranja.localttsreader` is a predecessor of the same app, not a fork |
| No ATS elements at all (privacy-targeted app) | ✅ no telemetry, no analytics, no account; `INTERNET` is the only network permission |
| `usesCleartextTraffic` avoided | ✅ flag not set, so cleartext is off by default (targetSdk ≥ 33); no `networkSecurityConfig` needed |
| Not a game / not a big-AI-platform client | ✅ reader with on-device models |
| **≤ 30 MB per APK** (rule of thumb, exceptions "well reasoned") | ❌ **52,279,328 B** — needs the exception. The usual remediation (per-ABI builds) is already done: arm64-v8a only. Argument: models are *not* bundled (the 50 MB is ONNX Runtime + JNA + espeak-ng) |
| **Runtime download of non-repo binaries needs explicit, informed, opt-in consent** | ⚠️ **the real gate** — see below |

### The consent gate is load-bearing beyond admission

Their README lists delisting triggers, including an app that "started downloading
additional binaries without the explicit and informed consent of the user". Their
definition of consent: opt-in, **not harder to decline than to accept**, and the copy
must clearly say the user is bypassing the checks this repo performs.

Concretely, before filing:

1. The first-run pack step must be **skippable in one tap**, and the skip path must lead
   somewhere usable. It does: the **"Device voice" (system TTS) engine needs no pack at
   all**, so "use the app without downloading anything" is a real configuration — quote
   that in the request.
2. Add one line of consent copy stating the packs come from upstream hosts (Kokoro /
   Piper / llama.cpp / Tesseract pins) and are not verified by the repo.
3. The `PregenKey`/stager plumbing is already explicit, resumable and SHA-256-verified
   (decisions #7, #154, #162) — that is the material for "structured so it clearly
   explains what the user is choosing".

### Pre-submission checklist

- [ ] `fastlane/metadata/android/en-US/` in the repo: `title`, `short_description`,
      `full_description`, `changelog`, `images/phoneScreenshots/`. **No fastlane tree
      exists today** — without it the listing copy is whatever the maintainer types from
      the README.
- [ ] One-tap-declinable first-run pack step + the consent sentence (above).
- [ ] Size justification written into the issue: arm64-only already, no bundled models,
      ≈50 MB is the native runtime.
- [ ] Release discipline: tag + attach the APK to the GitHub release (already the
      practice, decisions #126) — that is what drives their update checker.
- [ ] Reddit post (Track A) held until the Track B issue is filed, so the "why not
      F-Droid?" answer has a link.

### Rejected / not applicable

- **F-Droid proper** — rebuilds from source and signs itself; hostile to runtime-
  downloaded, non-OSI model packs. Not pursued. `[INFERENCE]` from F-Droid's own
  inclusion policy, which the IzzyOnDroid policy explicitly relaxes.
- **Play Store** — not in scope (decisions #126).
