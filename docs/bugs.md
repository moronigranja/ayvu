# Bug log

Bugs found during device passes, especially the **weak-device pass** (a
second, lower-spec Android device the S22 Ultra is compared against). Log,
don't fix: the weak-device pass is a *measurement* pass — performance and
resource findings are recorded here for a later tuning slice, not patched
mid-pass. Functional bugs found anywhere get recorded here too and fixed in
their own slice.

## How to log

Each entry: date · device · app build/commit · what happened (repro steps) ·
observed vs expected · severity (blocker/major/minor/cosmetic) · whether it
is a **perf** finding (not to fix now) or a functional bug.

## Entries

### 2026-08-27 — Weak-device pass #1 · Bigme HiBreak (Android 14, MT6765 8×A53, 3.9 GB, e-ink) · build efa4081
Pass: cold start → play → card → ±30s → reader, Wind and Truth (Kokoro synth). No crashes, no ANRs; UI behaved. All findings **perf** — logged, not fixed.

- **[perf] Play-to-first-audio 25 s** — tap Play on the library row → first `loop: playing` logged ~25 s later (RTF collapses vs ~4–8 s on the S22 for the same first-listen synthesis path). The "Generating…" spinner holds the whole time (correct feedback; the wait is the problem).
  **B6 re-measure (2026-08-29, build af431c4+):** Kokoro CPU RTF is **2.84–3.12** (D2 benchmark, 3 stable runs) — far better than the folklore 8–17, but still > 1, so live synthesis cannot sustain playback: every uncached passage stalls the loop behind its synthesis (tiny front-matter passages cycle ~6 s each; a longer passage holds the spinner for `audio × RTF`). The disk tier works (`loop: source=disk` observed) and the per-passage queue serves short passages instantly (`source=pregen`) — pre-generation is what makes the B6 usable. Precise first-audio timestamp lost to the device's hwcomposer log spam wrapping the buffer in seconds; mechanism and magnitude documented.
- **[perf] ±30s cross-boundary seek ~58 s (B6 re-measure 2026-08-29: 107.0 s; S22: 79.6 s — both worse)** — `+30s` (or −30s) onto a passage in neither the RAM queue nor the disk tier synthesizes ~50 s+ on this SoC. Same layering as the S22 (5–25 s there); adds urgency to the roadmap "instant ±30s seek horizon" slice (time-bounded look-ahead + survive-seek ensure).
  **Mechanism pinned (2026-08-29, build af431c4+):** the fill's cushion NEVER builds on EITHER device — every passage logs `buffer: waiting for 45.0 s ahead` → `ahead=0.0s after 60007-60041ms` (the full budget expires producing nothing) → `loop: source=synthesized`, repeatedly. Seek decomposition is identical in shape on both devices: ~1 s command + **60.0 s contended ensure (0 yield)** + synthesis (S22: ~19 s at RTF 0.69 → 79.6 s total; B6: ~46 s at RTF 2.9 → 107.0 s total). The 60 s ensure block is the device-independent dominant cost — suspected fill/loop engine contention (QW4's `startFill` vs the loop's on-demand ensure sharing the singleton engine). Per-passage synthesis is healthy on both (RTF matches the D2 benchmark).
  **Fix verified on device (2026-08-29, build 7d27226):** after Playa's fix (fill restarted on every loop-restart command, decisions #78 addendum), the B6 shows `loop: source=buffer` on first play (was `synthesized`), the fill builds real cushion in the same budget (`ahead=5.79s after 60023ms` — was `ahead=0.0s`), and a +30s seek lands `source=pregen` for the target followed by consecutive `pregen` hits (was `synthesized` every passage after a full 60 s dead-owner wait). The seek's one `synthesized` is the cold target — the wasted 60 s owner-less ensure is gone.
  **Fix implemented, device acceptance pending (2026-09-13, decisions #91/#155):** the
  remaining cold-target sync synthesis is covered by the D1 horizon (45 s → 30 s
  audio at the playhead, one constant for the queue bound / buffer wait / notification
  denominator) plus survive-seek (in-flight ensure survives, dead fill restarted by
  the loop-restart command). Host-tested (`PlaybackServiceSeekHorizonTest`: a seek
  inside the horizon resolves with zero target synthesis; dead-fill seek restarts and
  plays within one budget); re-measure both devices with `D1SeekHorizonBenchmarkTest`.

- **[perf] App memory 834 MB PSS / 919 MB RSS** — `dumpsys meminfo` TOTAL during a play session on a 3.9 GB device (~26% of usable RAM). ONNX Runtime sessions + audio pipeline; candidates: session reuse, buffer pooling, or the 0.5 / accel path — to tune in the weak-device slice, not here.
- **[perf] UI frame-skip jank** — 14,978 `Choreographer: Skipped` events accumulated during the pass (e-ink panel + slow SoC; not app-crash). No interaction froze (card buttons responded), but scrolling/animations (card expand) will be rough on this class of device.
- **[observation, not a bug] Disk tier works** — 859 KB offline used from the first-listen persist path (fewer passages persisted this session, correct).
- **[observation, not a bug] Data migrated** — the same library/book appeared on the HiBreak (user restored/migrated); app data behaved identically to the S22 pass.

### 2026-08-27 — S22 pass (reference for the weak-device comparison)
Build 0bf2b2f/efa4081: play-to-first-audio ~4–8 s; ±30s cross-boundary 5–25 s; no crashes; card + menu + reader verified (decisions #55/#56).

### 2026-08-31 — Z Fold pass · Samsung Galaxy Z Fold (SM-F971B, Android 17/SDK 37, SM8850 Snapdragon 8 Elite) · build 3263e23

- **[blocker, functional] Play crashes with SIGILL / ILL_ILLOPC in libonnxruntime.so** — first play tap on any book crashes the process. Crash: `Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC)` in tid `DefaultDispatch`; stack `Java_ai_onnxruntime_OrtSession_run` ← `OrtKokoroSession.infer` ← `KokoroEngine.synthesizeBlocking` ← `KokoroEngine$synthesize$2.invokeSuspend`. Fault addr is the same libonnxruntime offset every time (8 identical crashes in the buffer across 08-30/08-31). Root cause: SM8850 Oryon advertises **SME but not SME2** (`/proc/cpuinfo` has `sme, smei8i32, smef16f32, smeb16f32, smef32f32` — no `sme2`); onnxruntime 1.23.2's KleidiAI gating checks `HasArm_SME()` instead of `HasArm_SME2()` (upstream microsoft/onnxruntime#26377, fix PR #27403), so SME2-only KleidiAI kernels are enabled on an SME1-only core and the first dispatched instruction traps. Upstream fixed in 1.24.4 (confirmed by the reporter in microsoft/onnxruntime#27884); Maven Central publishes no 1.24.4, so the first fixed published release is **1.25.0** (1.29.0 also fixed). Version bump deferred to the perf-spike session that owns the ORT pin; logged, not fixed here.
- **[RESOLVED same day — ORT pin bumped 1.23.2 → 1.29.0 (decisions #100,
  commit bc7e7ea)]** Verification on this device with the 1.29.0 app build:
  the full playback instrumented set (PlaybackE2e, VoiceSelectionE2e,
  PlayPositionE2e, PtVoiceE2e) passed **0 failures** — play → synthesize →
  AudioTrack through the real engine, the exact stack that trapped; the
  spike `ConvInteger` probe also confirms 1.29.0 behavior on-device. No
  SIGILL since the bump (the 8 buffered crashes are all pre-bump). Android
  version corrected: SDK 37 = Android 17 (getprop `ro.build.version.release`
  = 17; Android 16 is SDK 36 = the S22).