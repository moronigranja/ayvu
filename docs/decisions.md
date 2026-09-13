# Decision log

The rationale behind load-bearing decisions. New decisions get an entry here with date,
context, alternatives considered, and consequences. Keep entries short — this is a log,
not a spec (specs live in architecture.md / feature docs).

## 155. D1 — instant ±30-second seek horizon: 30 s audio-time horizon, survive-seek join/restart, no hot-zone persistence (2026-09-13)

Roadmap D1. The survive-seek half landed as #91; the deferred "narrower horizon is a
measured follow-up" re-parameterization lands now, with the join/restart semantics
unit-tested end to end.

- **Horizon 45 s → 30 s, one constant.** `PREFILL_LOOKAHEAD_SECONDS = 30.0` IS the
  horizon everywhere — the queue's time bound, the buffer-before-start wait target,
  and the generation notification's 100% denominator (consistent by construction);
  `PregenQueue`'s default follows (30.0). The 60-passage bulwark is unchanged and now
  only bites for <0.5 s passages — the memory bound is the horizon itself: ~1.4 MB
  kokoro PCM (24 kHz mono 16-bit) at full cushion. PregenWorker/OfflinePregen budgets
  share nothing with it.
- **Coverage margin.** The fill breaks AFTER the crossing passage (stop at
  `ahead >= 30`), so the queued span reaches past the +30 s point for any mid-passage
  playhead (target ≤ span end ⟺ Q + remaining ≥ 30, true for remaining ≥ 0): +30 s
  resolves from the cushion, −30 s from the disk tier (every synthesized passage is
  write-through persisted — queue `onSynthesized` plus the loop's first-listen
  persists). Only an exact `Q == 30.000` ∧ boundary-aligned playhead could fall to the
  next passage; not observed with real text.
- **Join/restart (completes #78/#91).** Seeks keep `stopEverything(stopFill = false)`;
  the surviving fill re-arms from the live playhead between passages (`ensure(from,
  rearm)`) and on its 200 ms tick; a seek onto a dead fill takes the guarded restart
  (`pregenJob == null → startPrefill`) — the fill owner exists on every loop-restart
  command. No dead-owner wait: a concurrent/joining ensure returns immediately (the
  contiguous-prefix plan stops at in-flight work, never waiting on it).
- **Hot-zone persistence: not added** (the optional half). Reading of bugs.md ±30s:
  the misses were the 60 s dead-owner ensure (#78 addendum) and the cold target's sync
  synthesis — never RAM churn; the look-ahead is already persisted on synthesis and
  the entry's mechanism note shows the disk tier serving (`source=disk` observed).
- **B6 headroom trade accepted** (reverses the #91-era deferral comment): the cold
  target's pre-start wait now buys 30 s of cushion instead of 45 s; the cold path is
  not an SLO and each horizon second is a seek second the acceptance measures.
- **D4 coordination footnote:** `piper-v1` added to `PregenSpaceEstimator.SAMPLE_
  RATE_HZ = 22_050` (#154) per the map's new-engine convention (the 24 kHz fallback
  over-estimated piper space ~9%).

Evidence: `PregenQueueTest` +2 (horizon accounting — 30 × 1 s passages stop the fill
at exactly 30.0 s ahead, nothing extra; concurrent ensure from a seeked playhead
returns without waiting on the parked stale owner, stale result pruned, refill from
the new playhead), `PlaybackServiceSeekHorizonTest` +2 (a seek inside the horizon
plays with zero target synthesis after the seek lands; a seek onto a dead fill
restarts it and playback proceeds within one budget), `PlaybackServiceFillRestartTest`
updated to the 30 s horizon (≥3 × 10 s fills the cushion);
`:core-player:test :feature-player:test` green (138 + 57). Device acceptance scaffold:
`D1SeekHorizonBenchmarkTest` (androidTest, spike-tts pattern — `AyvuD1` rows +
`d1_seek_results.json`: ten ±30 s seeks classified `buffer|pregen|disk`, strict
zero-sync-synthesis assert, cold-first-play + Choreographer-skip count, per-row
queue size/ahead/PSS). **Device-verified 2026-09-13 (both devices, strict mode):**
ten ±30 s seeks each — B6 29–61 ms per seek (forward `pregen`, backward `disk`),
S22 19–31 ms, **0 synchronous-synthesis seeks and 0 Choreographer skips on either**;
cold first play 236 s (B6, Kokoro RTF 2.9 filling the horizon once) / 44.8 s (S22).
Full rows: `docs/prints/d4/d1-seek-{hibreak,s22}.json`; roadmap D1 and the bugs.md
±30 s row are closed as FIXED, device-verified.

## 154. D4 PiperEngine adopted — `piper-v1` registered with two pinned voices, one-voice-per-instance, no word-timestamp read-along (2026-09-13)

The D4 quality gate passed (owner listening pass, #99 addendum 2026-09-13), so the small
tier lands as a third `TTSEngine` implementor beside Kokoro: `PiperEngine` +
`OrtPiperSession` + `PiperVoiceConfig` + `PiperPacks` in `core-tts/.../tts/piper/`,
registered in `DefaultEngines` as **`piper-v1`** (PRIMARY — a genuine quality-gated
narration engine class, not a fallback tier; the playback seam never auto-selects it).
The runtime session mirrors the proven D4 spike invocation exactly: `input` int64
`[1,L]`, `input_lengths` int64 `[1]`, `scales` float32 `[3]` = (noise_scale,
length_scale, noise_w) from the voice json, output read via `floatBuffer` — never
`getValue()` on the rank-4 tensor (the #99 correction lesson) — with 6 intra-op
threads and memory-patterns/CPU-arena off (the #93 weak-device lesson).

- **One voice per instance.** A Piper voice IS a model (no speaker mixing): an
  instance is opened for one of `PiperVoices` (`en_US-lessac-medium`,
  `de_DE-thorsten-high`) and a request for any other voice fails typed ("unknown
  voice") instead of silently switching models. The instance's `packs` are that
  voice's model + config; the registry descriptor carries all four packs.
- **Packs pinned @ rhasspy/piper-voices `1162a9173d0ce503555aed757976b7a9912eae4c`:**
  lessac model 63,201,294 B `5efe09e6…af019f` (the HF LFS oid, cross-checked
  byte-for-byte against the staged spike copy), thorsten model 113,895,201 B
  `9df1c43c…`, plus each voice's `.onnx.json` as a VOICE-kind pack (the
  phoneme_id_map + scales + espeak voice the engine needs besides the weights;
  4,885/4,875 B, hashed from the downloaded artifacts). German is Piper-only at v1 —
  Kokoro v1.0 ships no German voices.
- **Phoneme ids verified against official piper.** piper-tts (piper1-gpl)
  `PiperVoice` on the lessac artifact produced exactly `PiperVoiceConfig.phonemeIds`'s
  sequence for the sample sentence: BOS=1 + PAD=0 open, (id, 0) per NFD-decomposed
  phoneme codepoint, EOS=2; unmapped codepoints skip like official piper, and a
  framing-only sequence fails as "nothing to synthesize" instead of rendering
  silence. The recorded official ids are pinned in a JVM test.
- **Read-along degradation (#30b) recorded:** the stock export exposes a single
  audio output — no alignments, no word timestamps — so `SynthesisOutcome.Audio`
  ships `segments = null` (the same shape as the system voice); a custom re-export
  could surface VITS alignments later.
- **Speed flows through the VITS length scale** (durations ÷ speed, clamped to the
  0.5–2.0 contract bounds); otherwise the scales are the voice json's measured
  values (lessac 0.667/1.0/0.8).
- **Registration + selection shape:** `PackModule`'s registry picks the engine up
  through `DefaultEngines.descriptors` — no extra DI binding. Selection is wired
  end-to-end (same-day follow-up): `EngineSelector` routes `ttsEngine == piper-v1`
  through a `PiperRuntime` (the `KokoroRuntime` pattern — one-voice-per-instance over
  `PackCache` targets, the shared espeak bundle, user intra-op threads, #93 session
  options); `PlaybackService.activeVoice()` resolves through the selector (the #144
  availability shape: a stored Kokoro voice falls back to the engine's default); the
  Settings engine row, engine-aware pack-row filter and audition phrases are in; the
  K2 descriptor refactor stays a separate slice. `PregenSpaceEstimator` maps
  `piper-v1` to 22_050 exactly.
- `pcm16` moved out of `KokoroEngine`'s companion into a shared internal helper
  (`tts/Pcm16.kt`): one implementation of the `SynthesisOutcome.Audio` encoding for
  both engines.

Host smoke (`:core-tts:piperSmoke`): both packs download + verify through the real
registry flow; 18.37 s of finite mono PCM @ 22050 Hz, RTF 0.031, segments=null; WAV +
log at `docs/prints/d4/piper-engine-smoke.{wav,txt}`. The B6 re-measure stays
available through the spike harness (device offline this session; the spike's B6
numbers on ORT-android 1.29 stand: RTF 0.566–0.575, #99 correction).

Evidence: `:core-tts:test` 136/136 green (PiperVoiceConfigTest incl. the
verified-vs-official-piper id case, PiperEngineTest contract + degradation shape,
DefaultEnginesTest pins); `:app:compileDebugKotlin` green; `:core-tts:piperSmoke`
run logged to `docs/prints/d4/`.

**Addendum (2026-09-13, selection wiring — the follow-up slice #154 named):**
`piper-v1` is selectable end-to-end through the existing engine switch, with no
auto-switching and no D1 semantic change (SeekHorizon's 30 s horizon, decisions #155,
re-read and preserved).

- **`PiperRuntime` (feature-player)** — the [KokoroRuntime] pattern: lazy open over
  the downloaded packs ([PiperPacks.forVoice] → `PackCache` targets), the shared
  espeak-ng bundle (`files/espeak/`, decision #32), the user's intra-op thread setting
  (#137), the #93 session options, and the QW3 retry cap. One voice per instance: the
  runtime resolves the served voice and re-points to a fresh instance on a voice
  change; superseded instances are NOT closed (process-scoped like Kokoro's single
  session — closing under a live caller races in-flight synthesis), so at most one
  session per ever-used voice exists.
- **`EngineSelector`** gains the explicit `piper-v1` branch (`SettingsStore.PIPER_
  ENGINE`, the DefaultEngines id): `engine()` routes to [PiperRuntime], the typed
  prerequisite reason surfaces through `failureReason` like Kokoro's, and Piper is
  never `degraded` (PRIMARY engine class). New `resolveVoice(stored)`: the #144
  availability shape — engine-exposed ids pass through, anything else falls back to
  the engine's default voice (`PiperEngine.DEFAULT_VOICE`); Kokoro/system stay
  byte-for-byte passthrough. `PlaybackService.activeVoice()` routes through it (the
  #144 choke point), so synthesis requests, queue keys and the pre-arm name a voice
  the instance actually serves.
- **Voice sheet/catalog** engine-aware (`SettingsViewModel` + `ReaderViewModel` via
  the ONE shared builder): `PiperVoiceMetadata` rows (names = the served
  [PiperVoices] ids) when piper-v1 is selected, readiness = the resolved voice's
  model + config + espeak; a saved voice the engine does not expose degrades to the
  builder's unavailable row (#144). `SettingsScreen` gains the Piper radio, engine-
  aware pack rows (visiblePackIds: piper models/configs + espeak) and `downloadVoicePacks`.
  **K2 verification recorded:** the settings engine rows are NOT descriptor-driven —
  `KOKORO_PACK_IDS` is a hardcoded plan card and the pack-row filter was hardcoded to
  the Kokoro ids; the pack-card hardcode stays untouched (K2 owns the refactor), the
  row filter is now engine-aware.
- **Audition**: `PiperVoicePreview` supplies the per-voice phrases; the coordinator
  falls back to it after Kokoro's `VoicePreview` (unknown names still fail typed).
  Setup flow untouched (kokoro download + system-tts opt-in only).
- **#30b verified structurally**: Piper outcomes ship `segments = null` — the service
  maps them through the SAME `?: emptyList()` as system-tts, so the read-along surface
  stays empty (active sentence 0, `passageDurationSeconds` 0.0) instead of fabricated
  anchors; follow-active-sentence cannot break. Pinned by `PlaybackUiStateTest`
  (segment-less vs anchored shapes) and the service-level piper test.
- **Cache keys**: piper audio rides the default-engine `PregenKey` keyspace,
  distinguished by its voice ids — a collision with a Kokoro voice name is impossible
  (`en_US-lessac-medium`-shaped ids vs `af_heart`-shaped). Engine-correct keying plus
  coverage/estimator engine threading stays with K5 (per-book overrides + the
  PregenStorage surfaces, decisions #144). The generation-threads row now shows for
  Piper too (the runtime consumes the same setting).

Evidence: `:core-tts:test` 138 (PiperVoiceMetadataTest roster/phrase pins),
`:core-player:test` 139 (read-along degradation shape), `:feature-player:test` 60
(`EngineSelectorPiperTest` — routing/voice-resolution/failure-surface + never-touches-
Kokoro; `PlaybackServicePiperSelectTest` — piper-v1 end-to-end through the service
seam with the resolved voice and no fabricated read-along), `:feature-settings:test` 5,
`:app:testDebugUnitTest` 24, `:app:compileDebugKotlin` green. **Device smoke
verified on both devices** (`PiperDeviceSmokeTest`, 2026-09-13): the REAL
`PiperEngine` opened over staged packs synthesizes the P&P opening sentence —
B6 (HiBreak) RTF **0.579**, 6.16 s audio, ~219 MB PSS / 374 MB VmHWM, open 9.2 s;
S22 RTF **0.094**, 5.98 s audio, ~400 MB PSS, open 1.5 s — matching the #99 spike
numbers; WAVs at `docs/prints/d4/d4-piper-engine-smoke-{hibreak,s22}.wav`
(segments=null confirmed on device; listen for quality, not just finiteness).

## 153. D5 Pocket TTS spike — first ARM datapoint: RTF ~5.5–6.4 on the HiBreak, pregen-only, faithful-but-ISA-noisy (2026-09-11)

Owner asked for the Pocket TTS spike with the B6 connected. The `KevinAHM/pocket-tts-onnx`
export @ `58a6d00c` (english_2026-04, sha256-pinned per file from the HF LFS blobs) ran
end-to-end on the HiBreak through ORT-android 1.29, mirroring the `PocketTTS.cpp` loop
exactly (voice+text conditioning through the text slot, KV snapshot/restore, NaN-first
`curr`, EOS −4.0 + 3 extra frames, per-frame decode). Text arrived pre-tokenized (host
SentencePiece) — the on-device tokenizer/G2P is a D5 integration gap, not a spike leg.

**Measured (int8 main/flow/decoder, fp32 encoder+conditioner, HiBreak):**

- **RTF 5.54–6.87** (best 5.54 at threads=4; 2/4/6 threads are within ±10% — the int8
  graphs barely parallelize). Kokoro fp32 baseline on the same device: RTF 2.84–3.12 —
  **Pocket is ~2× slower despite 20× smaller weights.** Per-frame cost at threads=4:
  main ~236 ms, decoder ~147 ms, flow ~6 ms — ~390 ms against an 80 ms realtime budget,
  so the live-cloning question is answered NO on this device; it is a pregen candidate.
- **PSS ~1.40 GB, VmHWM ~1.56–1.60 GB** (the 1000-frame KV tensors + ORT arena; the
  device survived, but this is 4× the CosyVoice3 kill threshold class on paper). Cold
  open ~6 s total (flow_lm_main_int8 alone 3.9–4.2 s); voice encoding 5.6–11.2 s per
  voice (fp32 encoder over 6.3 s of audio).
- **Parity vs the host reference (temp-0, deterministic):** EOS frame 171 and 174 frames
  match the host run exactly at threads=4; latents max-abs-diff 0.081 and audio 0.41 —
  frame 0 already differs (0.052), so the divergence is **int8-kernel ISA variation
  (x86 vs ARM), not accumulated AR drift and not the #86 stub failure mode**. Threads=2/6
  drift ±1 EOS frame (logit −3.93 sits right on the −4.0 threshold). Waveform compare is
  the wrong gate for an AR pipeline; EOS/frame match + rms profile are the substantive
  checks, both pass. Audio is unlistened — WAVs staged for the owner.
- Host reference: PocketTTS.cpp CLI RTFx 3.50 (int8, incl. voice conditioning), python
  ORT driver RTF 0.232–0.243.

**Recorded findings for future legs:** decoder chunking is not transparent — 15-frame
chunks vs per-frame differ (host max-abs-diff 0.13, chunk-size-dependent), so the
canonical streaming unit is 1 frame and any adoption must pin the chunking convention;
the decoder `first` bool fill is irrelevant per-frame (ones vs zeros: 0.0 diff);
`bos_before_voice.npy` ([1,1,1024], concatenated before the voice prompt by upstream
python) is skipped by the C++ reference and by this port — a fidelity risk to re-check
if EOS ever misbehaves; energy is unmeasured (device on USB; the PowerProbe validity
rule reads that as "energy not measured") and ThermalManager is unavailable on this
Android build.

**Consequences:** D5's engine choice now has an ARM datapoint for Pocket TTS: on the
HiBreak it is a pregen-only candidate, slower than Kokoro per audio-second, at 4× the
pack size (148 MB int8 vs 92–325 MB Kokoro) but the only zero-shot cloning candidate
inside a phone RAM budget. The S22/Fold leg (Kokoro 0.66–0.76 baseline) is the next
measurement if the owner wants the flagship picture; the G0 blind read still gates
adoption. Harness: `PocketProbeRunner` + `PocketProbeBenchmarkTest` (staging recipe in
build.md "D5 Pocket TTS staging").

**fp32 control (owner's precision question, 2026-09-12):** the same export with fp32
heavy graphs ran RTF **5.81–7.44** (threads 2/4), PSS ~1.64 GB, main open 5.0–5.1 s —
so on this device **int8 stands as the better Pocket config** (≈5% faster, 15% less
memory, same no-thread-scaling shape), consistent with the one Kokoro HiBreak
counterexample (2.62 int8 vs 2.89 fp32, #148) and against the flagship int8-loses
pattern (leg G). The pregen-only verdict is precision-robust. Quality caveat for the
listening pass: the fp32 path diverges qualitatively (ref speaks 27.8 s vs int8's
13.9 s at rms 0.0011; p2's second sentence never EOSes at temp 0.7) — the runner
now takes `precision` from `d5_inputs.json` so both graphs stay runnable.

 ## 152. Spike corpora published — the measurement is re-runnable, not just readable (2026-09-11)

**Question.** `docs/kokoro-on-device-perf.md` (#148/#150) inlines every number, but shipped
no passage text: the 2-passage corpus existed only as a generated host file
(`~/.cache/local-tts-reader/packs/kokoro-device-corpus.tsv`), the 16-passage pregen corpus
and the leg-G 1-row corpus only under the gitignored `docs/prints/`, and `corpus_g.tsv` only
on the Fold. A peer comparing the same artifacts cannot verify the input text or the audio
timing — the doc was readable, not reproducible.

**Answer: commit the three corpora as `docs/corpus/{corpus,corpus_pregen,corpus_g}.tsv`**,
pulled from the Fold's `files/` (byte-identical to what every leg measured) and pinned by
sha256 + per-cap measured audio in the report's new §2.1. Public-domain text (Austen,
Machado de Assis) — no licensing question. `corpus_g.tsv` turns out to be exactly line 1 of
`corpus.tsv` (verified by comparing the pulled files): leg G's 1-row corpus is derivable
rather than mysterious.

Consequences: `docs/build.md` stages the tracked files (and now stages `corpus_g.tsv`, which
leg G previously needed by hand); the report's intro/§3.1/§5/§7 no longer say the corpus is
unavailable; `hard-facts.md` carries the pointer. Raw JSON/WAV evidence stays gitignored
(§7) — the numbers remain inline.

## 151. Peer claims re-checked at source + our own harness confounds (owner question: "are we missing a variable?") (2026-09-11)

**Question.** Several projects appear to publish conditions we cannot reproduce — about
quantization, about speed. Is a variable fixed in *our* harness?

**Answer: yes — six of them, all in our own measurement code, and two peer claims were
misreadings rather than unreproducible results.** No decision from #150 changes; every
absolute RTF in it now carries an explicit condition.

*Peer claims, verified at source* (full detail: `docs/kokoro-on-device-perf.md` Appendix A):
only **VoiceShelf** publishes a phone number — "about 2.8× faster than real-time"
(RTF ≈ 0.36), Z Fold 7 / SD 8 Elite, fp32, with **100 s buffers and low/high watermarks so
the phone idles between them**. 0.36 is *inside our own spread* for the same fp32 config on
the same SoC class (0.36 rested, 0.49–0.60 warm, 1.49–1.79 display-asleep): their buffer
design is the duty-cycling our continuous legs never do. **Lectern publishes no quantitative
claim at all**; **NekoSpeak publishes no Kokoro RTF** and ships int8 as a **size** choice
(≈115 MB on-demand vs 349 MB; its ADR records int8 Kokoro as 74 % NNAPI-incompatible), so
"peers ship int8, therefore int8 is faster" was our inference, not their claim; **sherpa-onnx**
(runtime behind Lectern/HayaiTTS) defaults to `provider="cpu"`, `num_threads=1`, has no
XNNPACK flag on master, and publishes RTF only for a Raspberry Pi 4 — its defaults are
*weaker* than our baseline.

*The int8 artifact is the variable.* Our own two int8 exports of the same model disagree by
direction: the 114 MB QDQ export measured **faster** than fp32 on the Fold class (0.36/0.50
vs 0.52), the 92 MB Neko-lineage export in #150 measured **~2× slower**. They are different
graphs, not different scales: 148 `MatMulInteger` + 139 `DynamicQuantizeLinear` + **333
`Cast`** and only 26 `MatMul`, against fp32's 102 `MatMul` + 73 `Gemm` + 90 `Conv`.

*Harness confounds found in our code* (magnitudes in the report §3.8):
1. `KokoroBenchmarkRunner.measure` times the **first** inference of a fresh session — lazy
   graph init and, for quantized graphs, weight prepacking land inside the number (2-passage
   corpus ⇒ half the sample).
2. `OrtProvider.CPU.options = {}` — **no `setIntraOpNumThreads`**, i.e. ORT's default (all
   physical cores) while the result file records `"threads": 6`. Legs A and B/D/E therefore
   ran different thread counts, and neither is the measured knee (4).
3. Screen state was **asserted, not observed**: the legacy harness hard-codes
   `"screen": "off/locked (instrumented)"` and the leg-level value is read once at leg start.
   Consequence corrected here: the Fold's leg A was published as "screen on" while its own
   record reads `interactive=false` (`docs/kokoro-on-device-perf.md` §4.1, `README.md`
   Conditions, `build.md`).
4. Oracle-gated legs keep the reference session **resident and inferring between** the timed
   candidate windows — two ORT pools in one process (ORT documents the identical hazard for
   XNNPACK: `xnnpack_execution_provider.cc:166`).
5. Every leg runs **back-to-back**; sustained load walks clocks down (same config inside one
   session: 0.678 / 0.813 / 0.832; cold-vs-warm 0.36 vs 0.60).
6. The window cap was fixed at the model maximum (510) while peers batch at ~150 (leg B: 0.524
   vs 0.604 RTF, 960 ms vs 17.4 s TTFA on the Fold).

*New measurement, XNNPACK (device-independent: graph + ORT-build property).* With
`onnxruntime-android` 1.29, XNNPACK added and `session.disable_cpu_ep_fallback=1`, session
creation over the unmodified dynamic-width fp32 graph **fails** ("session contains graph nodes
that are assigned to the default CPU EP, but fallback … explicitly disabled") ⇒ XNNPACK takes
only part of Kokoro and the rest falls back; ORT also warns that the EP's second pthread pool
contends with ORT's when spinning is on and threads > 1. Any future XNNPACK work must state
those two settings or it measures contention.

*Consequence.* Leg `g` (harness-sensitivity audit: fp32@4 vs ORT-default threads vs
XNNPACK ± spinning vs int8 alone vs int8-with-oracle, round-robin rounds) is implemented in
`PerfSpikeRunner`/`PerfSpikeBenchmarkTest` and **has now run on the Fold 8** (plugged,
display off, 1 passage/2 windows, best-of-2 rounds; full table in the report §4.8):

| config | best RTF | vs fp32@4 |
|---|---|---|
| fp32, explicit 4 threads | 0.591 | — |
| fp32, ORT default threads | **0.504** | 0.85× (15 % faster) |
| fp32 + XNNPACK, 4 threads | 0.786 | 1.33× slower |
| fp32 + XNNPACK, `allow_spinning=0` | 0.692 | 1.17× slower |
| int8, 4 threads | 1.145 | **1.94× slower** |
| int8 + resident fp32 oracle | 2.156 | 1.88× slower than int8 alone |

So: the int8 rejection is confirmed with every confound removed (**not** a harness artifact);
XNNPACK is measured a loss (+33 %, +17 % with ORT's recommended no-spin) on a partial
partition — the 1-D→2-D rewrite line is closed, do not spend on it; the thread axis is a real
~15–20 % lever and our recorded `threads` field was a bug (default threads beat our explicit
4, #147's knee was 6); a resident oracle nearly doubles the candidate's measured time; and
intra-leg thermal drift was +35 % (battery 33.6 → 38.5 °C), which is why round-robin rounds
are mandatory. `PerfSpikeRunner.flush` also now mirrors every JSON into the app's internal
`files/` so results survive a sleeping device (`/sdcard` is not readable via
`adb shell run-as`).

**Leg H (thread sweep, plugged) — the knee moves with the thermal regime.** The owner asked
for #147's T axis re-run on the Fold 8 with the device plugged in; the run sat in a throttled
charging regime the whole time (AC, ~0.47 A charging current, battery 37.9 → 40.1 °C,
**thermal status 3/severe**, AP 51 °C). Best RTF per T, best of 3 round-robin rounds:
t1 1.758, t2 1.147, t3 0.962, **t4 0.781 (knee)**, t6 0.810, t8 0.832, **t_default 0.781**
(the unset setting identifies itself with the knee, i.e. ORT's default is all-cores, not 4).
Against #147's unplugged+cool sweep on the same device and corpus, charging+hot costs
**1.36–2.0× at the same T**, and the knee *moves*: 6 threads wins cool, 4 wins throttled
(more workers = more heat = lower DVFS cap). Two product consequences: (1) a single fixed
`tts_threads` default cannot be optimal — **default 6, demote to 4 when thermal status ≥ 2 or
while charging** (the adaptive pattern candela and Lectern ship); (2) never benchmark a
charging Samsung device and read the number as capability — this run measured the same
configs 1.36–2.0× slower than #147's unplugged pass. Round-to-round drift on a charging
device was large (t1 2.43 → 1.76 → 2.19), so best-of-rounds with the battery temperature
published is the only honest summary. Tooling note: `adb tcpip 5555` run mid-leg kills the
`am instrument` shell (the test survives and keeps flushing; the OK/FAIL report is lost) —
enable TCP mode *before* starting a device run.

---

## 150. Cross-app performance spike (decisions #148): int8 tier rejected, window cap is a latency lever, per-window output feeding fails, ADPF is the one real speed lever, int4 CPU kernel exists (2026-09-11)

Phase D spike legs A–F, measurement only — no pack, engine or setting changed. Raw data
per device in `docs/prints/perfspike/{fold,s22,hibreak}/`, summary in its `README.md`,
host gates at that level.

**A — int8 tier: rejected on this artifact.** The int8 pack peers actually ship (NekoSpeak's
mirror of the thewh1teagle lineage, 92,361,271 B, `6e742170…`) is *slower* than our fp32 in
the same session — Fold 0.77–1.13 vs 0.49–0.56 RTF (≈2×), S22 1.02 vs 0.70 (1.4×) — and
costs 1.6–1.9× the energy per hour of audio on the Fold (3.06–3.51 vs 1.75–2.00 Wh). Its
waveform is far from fp32 (oracle max diff 0.78–0.87; the 0.001 gate rejects) and all six
S22 blind pairs are flagged by the instrumental bands (MCD 1.22–1.31 dB, segment SNR −3.3…
−3.6 dB), which is what the owner heard: on the level-matched blind sets (Fold + S22, slots
shuffled per pair) **pt-br is audibly damaged** — "a little frayed" on both devices, fp32
preferred — while **en-us is near-indistinguishable with a slight preference for the int8
render** ("very little diff"). Two side findings: the
release names its output `audio` and `OrtKokoroSession` needs `waveform` (measured through a
host-verified renamed copy; shipping would need an engine alias), and
`ORACLE_REJECT_THRESHOLD = 0.001` sits *below* this model's own kernel-order noise floor
(the unmodified graph's BASIC-vs-ALL fusion delta is 0.012–0.030), so it rejects any
computation-path change by construction and cannot discriminate an int8 defect.

**B — window length: a latency lever, not a throughput one.** Throughput is flat across
caps 150/300/510 (Fold 1.91/1.86/1.66 audio-s/s; S22 0.84/0.84/0.77; HiBreak
0.18/0.17/0.19) while time-to-first-audio grows with the cap: Fold 0.96/1.62/17.4 s, S22
4.0/4.9/19.5 s, HiBreak 8.7/21.8/**143.6** s. #148's hypothesis (longer window trades
latency for throughput) is falsified on the latency side — a small cap (150) is the
weak-device setting, and large caps buy nothing.

**C — per-window output feeding: keep the whole-passage `MODE_STATIC` path.** On all three
devices the stream seam reaches first audio after 34/35/85 s (Fold/S22/HiBreak) against
17–56 ms for the shipped static path, and underruns twice per 64 s of audio: a window takes
11–14 s (S22) to 55–65 s (HiBreak) to synthesize while the stream buffer holds one second.
The plan's rule (`stream underruns == 0 && first audio ≤ 0.7× static`) fails on both counts.

**D — ADPF is the one real speed lever.** Hinting three ORT worker threads (discovered by
`comm` signature) takes the Fold's RTF 0.596 → 0.459 (**23 % faster**) and first audio
15.4 → 10.7 s, at 1.85 → 2.09 Wh per audio-hour — speed, not savings.
`session.intra_op.allow_spinning=0` is free (0.593) and slightly cheaper (1.67 Wh),
`THREAD_PRIORITY_URGENT_AUDIO` is neutral (0.593) with a worse tail (p95 58 s). On the S22 Ultra
`createHintSession` returns *null* rather than throwing — the ADPF service is absent on that
ROM, so its ADPF configs carry no numbers but do record `adpf_unavailable` (and the thread
discovery falls through to the CPU-placement heuristic there, since ORT's threads are
unnamed).

**E — duty cycling is thermal, not energy.** The Fold's mean power falls 6.66 → 3.80 →
2.68 W at 100/50/33 % duty (measured ratios 1.000/0.551/0.367), but energy per hour of
audio *rises* 2.42 → 2.71 → 2.72 Wh: wall time grows faster than power falls, because the
rest phases do not return the device to its 742 mW idle floor. Use duty cycling to stay
cool, not to save charge.

**F1 — the int4 conflict resolves for the kernel.** A single-node `com.microsoft::MatMulNBits`
graph opens and produces finite, non-degenerate output on host ORT 1.29.0's CPU EP and on
ORT-android 1.29 (all three devices, `perfspike_f1.json`). #148's source reading that no
CPU-EP kernel exists is wrong for 1.29; note ORT 1.29 demands packed shapes
(`B [N, k_blocks, block_size·bits/8]`, `zero_points [N, blob_size]`), not the unpacked ones.

**F2 — XNNPACK half stopped, and why.** The 1-D→2-D conv rewrite (97 convs, 194 reshapes)
is arithmetically exact (per-conv onset test: none; ≤1.6e-06 over clean convs; synthetic
float64 check passes) but fails the plan's waveform parity gate (5.7e-02…8.4e-02 vs 1e-4)
because the model's harmonic generator amplifies fp32 kernel-order noise — the *unmodified*
graph's BASIC-vs-ALL fusion delta is 1.2e-02–3.0e-02. No pack staged, leg F2 not run;
reopening condition (per-conv onset gate instead of the absolute waveform tolerance) in
`docs/prints/perfspike/xnnpack-reshape.md`.

**Conditions that shaped the run** (docs/build.md §"Cross-app performance spike"):
screen-off + unplugged throttles the Fold ~2.5× (`deviceidle whitelist` does not change it,
it is not thermal), so its energy legs ran screen-on and record that; unplugged devices drop
wireless adb mid-leg, which stranded several legs until physical wake-ups; the HiBreak
cannot run leg A at all (int8 + fp32 oracle pair → swap pressure and an lmkd kill loop);
the S22 Ultra's battery gauge reads ~0.3 mA while the phone draws ~4 W, so every S22 energy
field is null with `energy_invalid_reason="gauge_below_floor"`.

Consequences: do not ship an int8 pack from this lineage (revisit only with the 114 MB
export or a re-export that keeps `waveform`); cap the window at 150 for weak devices; leave
the output path as MODE_STATIC; ADPF hint sessions are the only measured speed lever and
cost energy; duty cycling is a thermal tool; and UI-facing "quality tier" copy cannot lean
on the 0.001 oracle gate — a listening pass is the gate.

## 149. Pocket TTS enters D5 as a third candidate; license audit clean with two catches (2026-09-11)

#92's rejection of Pocket TTS ("no Android runtime path") is falsified — NekoSpeak v1.4.2
ships Kyutai Pocket TTS as its *recommended* engine, as five ORT sessions
(`mimi_encoder`, `text_conditioner`, `flow_lm_main`, `flow_lm_flow`, `mimi_decoder`), int8
on the heavy graphs, behind a device-speed-adaptive streaming buffer; the community ONNX
export those graphs match (`KevinAHM/pocket-tts-onnx`) and a C++ ORT runtime
(`VolgaGerm/PocketTTS.cpp`) are two further paths. Correction recorded inline in #92; full
row in landscape.md §"D3 comparison sweep".

**Decision (owner, 2026-09-11): Pocket TTS joins D5** beside CosyVoice3 (incumbent) and
Chatterbox Multilingual. Rationale: it is the only candidate that could clone *inside* a
phone's live budget rather than pregen-only — 100M params / ~176 MB against 0.5B-class
incumbents at 3.2–3.5 GiB (~20×), native streaming (~200 ms first chunk), six languages
including the German gap Kokoro v1.0 leaves to Piper. D5's gate is unchanged (G0's typed
findings + blind read); this moves the candidate list, not the sequencing.

**License audit — two catches, neither fatal:**

- **Code MIT, weights CC-BY-4.0** (Kyutai; the export mirrors inherit CC-BY-4.0) — one-way
  compatible into GPL-3.0 (#27), the same class as the accepted CC-BY-4.0 NMT packs
  (#101/#114). Attribution lands in `NOTICE.md` **when adopted**, not now.
- **The upstream weights are gated** (`kyutai/pocket-tts` @ `492522650173a0…`,
  `gated: auto`, prohibited-use terms incl. voice cloning without consent). A token-less,
  account-free app cannot fetch them and Ayvu takes no accounts, so the pack descriptor
  must point at the ungated CC-BY-4.0 export (`KevinAHM/pocket-tts-onnx` @ `58a6d00c…`,
  `lookbe/…` mirror) — which makes the #23 provenance rule plus a parity check
  load-bearing rather than routine (#97's lesson). Surface the prohibited-use terms in the
  pack row / privacy copy when the pack ships.
- **Voices are per-directory licensed** in `kyutai/tts-voices`: CC0 (`voice-donations/`,
  `voice-zero/`), CC-BY-4.0 (`vctk/`, `alba-mackenna/`, `cml-tts/fr/`), and
  **CC-BY-NC-4.0 (`expresso/`, `ears/`) — excluded**, like the NLLB-600M NC gate (#101).
  The model repo's *built-in* embeddings include NC-derived voices (`cosette` = Expresso,
  `jean` = EARS), so a permissive-only catalog is a filtered set, not the upstream default.
  Celebrity/personality clones (NekoSpeak ships them) are out on policy grounds, not
  license ones.

**Consequences / gates before it is a measured leg:** no ARM/phone RTF exists anywhere
(vendor: 6.33× realtime on an M4, 2 cores) and the cost shape is an AR flow-LM loop +
per-frame flow steps + a separate Mimi decode, so it enters as a **pregen** candidate on
the S22/HiBreak and only becomes a live one if it clears realtime; a second G2P convention
(Misaki/sentencepiece beside espeak-ng/JNA) needs #97 justification; read-along timing is
unverified (Mimi frames are 12.5 Hz); the export mirrors are low-traffic (0 downloads), so
supply-lifecycle risk sits with us (the Supertonic lesson — pin, hash, parity).

Alternatives: leave the #92 veto standing (rejected — factually wrong, and it would keep a
20×-smaller cloning tier invisible); add it as a D3 leg (rejected — D3 is Kokoro-class
small-tier and closed).

## 148. Cross-app Kokoro performance survey — peers ship tiering, energy policy and int8, not faster synthesis; one Phase D spike designed (2026-09-11)

Owner asked whether the Android apps that read books with Kokoro are faster (three named:
Lectern, VoiceShelf, NekoSpeak), to find a lever for ≥realtime on weak devices and lower
battery on flagships. Eight read-only probes covered the named apps plus candela (already
tracked), HayaiTTS and the sherpa-onnx engine APKs. Full reports:
`docs/landscape.md` §"Cross-app on-device Kokoro survey"; raw probes at
`agent://LecternProbe`, `agent://VoiceShelfProbe`, `agent://NekoSpeakProbe`,
`agent://CandelaProbe`, `agent://AndroidKokoroAppsSurvey`, `agent://SherpaKokoroProbe`,
`agent://KokoroModelVariants`, `agent://OrtPerfLevers`.

**No peer synthesizes faster, and none publishes a phone RTF.** Every app runs the same
Kokoro export family on the CPU EP, single-session; NekoSpeak's session config is ours
(6 threads, ALL_OPT). VoiceShelf's only number — 2.8× realtime, RTF ≈0.36 on SD 8 Elite —
is the same ballpark as our SM8850 fp32 measurement (0.43–0.66). Their advantage is
pipeline and policy, i.e.:

1. **A Kokoro quality/tier switch driven by a measured signal.** Lectern ships a 132 MB
   "Light" and a 349 MB "High" pack, warns before the first high-quality play, and
   auto-falls back; it also measures whether a voice keeps up and *suggests a lighter
   one*, and guards on thermal status. Our `realtimeCapable=false` only widens the
   playback cushion — it neither suggests nor switches.
2. **int8 Kokoro that ships.** Three independent projects ship one: NekoSpeak's default
   92,361,271 B dynamic-QUInt8 model (the thewh1teagle lineage, now 401-gated upstream —
   the mirror re-supplies it), sherpa's `kokoro-int8-multi-lang-v1_1` engine APK, and
   Lectern's Light tier. Our own #86/#99 numbers already bound the win: HiBreak 2.621 vs
   2.89 fp32, S22 0.58–0.61, SM8850-class 0.36/0.50 vs 0.52 (~30%); the only rejection is
   our 0.001 waveform gate, and the owner's blind A/B heard no damage. 92 MB also cuts
   cold open and PSS on weak-RAM devices.
3. **Energy policy keyed to power and thermal state.** candela caps synthesis
   concurrency from `THERMAL_STATUS_MODERATE` via `PowerManager.addThermalStatusListener`,
   pauses pre-render in battery-saver, and demotes only the producer thread
   (URGENT_AUDIO→BACKGROUND; the AudioTrack consumer keeps URGENT_AUDIO); VoiceShelf
   buffer duty-cycles explicitly to let the phone rest; Lectern stops synthesis on pause.
   Nothing here reacts to thermal status, battery-saver or charge state.
4. **Core placement beyond a thread count.** Lectern replaced fixed-4 threads with "fast
   cores"; candela auto-sizes from core count. Unused here: ORT thread-pool spinning
   (`session.intra_op.allow_spinning`, `spin_duration_us`, ORT PR #28096) and Android ADPF
   `PerformanceHintManager` (`createHintSession` + `reportActualWorkDuration`).

**Weak-device realtime stays an engine tier** (D4): Piper 0.50 on the HiBreak where Kokoro
is 2.84–3.12 (#99), 0.357 vs 3.19 on RPi4 (sherpa). int8 buys ~10–14% on A53 — not enough.

**Two recorded verdicts need measurement before they close.** (a) XNNPACK partitions
**2D** Conv/ConvTranspose/Gemm only; Kokoro's are Conv1d, so our "XNNPACK slower" tested a
graph the EP could not claim. (b) Weight-only int4 (`MatMulNBits`) is claimed to have no
CPU-EP kernel while our own HiBreak closer-look ran a MatMulNBits graph to finite output
on ORT-android 1.23.2. Also recorded: QNN HTP needs static shapes plus quantized (u8/u16)
graphs and supports `ai.onnx:Slice` only (no `StridedSlice` — the 3110 failure, matching
#115); no peer has Kokoro on an NPU; no distilled/slim Kokoro exists.

**Decision: one `spike-tts` measurement session, six legs, S22 + HiBreak** (roadmap D7):
A int8-vs-fp32 tier with a level-matched blind listening set and a perceptual metric (the
evidence to amend the 0.001 gate); B window-length sweep 150/300/510 (throughput vs
first-audio — #139 varied workers×threads, never window length); C per-window AudioTrack
feed re-probe (#83's inert MODE_STREAM predates #138's emit-early seam); D scheduling
(ADPF hint session, `allow_spinning=0`, fast-core placement); E duty-cycle energy
(continuous vs on/off at equal coverage, Wh per audio hour); F int4 re-probe and XNNPACK
2D-reshape partition check. Every leg carries its own fp32 control and the session repeats
the baseline last (#139 logged ~13% thermal drift); energy legs sample on battery only.
Not spike legs: D4 Piper adoption and the power/thermal policy itself — those are product
code whose *parameters* the spike measures.

**#147 is reserved** for the in-flight single-session thread sweep (`ThreadSweepRunner` +
`PowerProbe`, uncommitted in the worktree at this entry's date), so this entry takes the
next free number.

## 147. Single-session thread sweep on the Fold 8 — 1 thread is slower than realtime, 4 is the knee, 8 oversubscribes (2026-09-11)

#137 shipped the `tts_threads` slider (1-8, default 4) on the S22's reasoning and left the
on-device acceptance unrun; #139 swept workers and threads *together*, so its 4-thread
points (2×2 = 0.945, 4×1 = 1.207) fold session-splitting cost into the thread axis. This
leg holds W=1 and sweeps T alone. New harness in `spike-tts`: `ThreadSweepRunner` +
`ThreadSweepBenchmarkTest` (args `-e threads 1,6`, `-e runs`, `-e corpus`, `-e
wait_unplugged true`) and `PowerProbe` (battery current/voltage/temperature + the calling
thread's CPUs); every leg flushes JSON, so an OOM or an unplug keeps the earlier legs.

**Setup.** SM-F971B (Fold 8, Snapdragon 8 Elite Gen 5, 8 cores, Android 17), the same
2-passage `corpus.tsv` whose 6-thread CPU baseline #115 recorded at RTF 0.43-0.66,
best-of-3 with an untimed warm-up, **on battery with the screen on** (wireless adb),
100% unplugged samples in every leg. A 60 s idle-baseline leg runs first, because a
screen-on leg carries a display/system constant.

| T | RTF | wall | power | energy | batt | (reverse-order re-run) |
|---|---|---|---|---|---|---|
| 1 | **1.212** | 77.4 s | 2961 mW | 3.59 Wh/audio-h | 32.4 °C | — |
| 2 | 0.668 | 42.7 s | 3679 mW | 2.46 | 33.0 °C | — |
| 3 | 0.671 | 42.8 s | 3026 mW | 2.03 | 34.0 °C | — |
| 4 | 0.575 | 36.7 s | 3368 mW | **1.94** | 34.0 °C | 0.545 / 3241 mW / 1.77 |
| 6 | **0.480** | 30.6 s | 4820 mW | 2.31 | 35.5 °C | 0.524 / 3577 mW / 1.87 |
| 8 | 0.611 | 39.1 s | 3942 mW | 2.41 | 36.4 °C | 0.633 / 3461 mW / 2.19 |
| idle | — | — | 662 mW | — | 32.4 °C | 671 mW |

**1 thread is slower than realtime, and reproducibly so.** RTF 1.216 / 1.213 / 1.212
across the three runs — the 1-thread wall is stable to 0.2 s. The host-measured scaling
shape transfers almost exactly: the host's 1→6-thread ratio was 2.79×, the device's
1.212/0.480 = 2.53× (2.87× against the cold plugged t6 run, 0.422). A 45 s fill ahead of
a 1.212 RTF playhead drains in ~3.5 min and the cold path (60 s cap) takes over from
there; pre-generation at 1 thread takes 1.21× the listening time, so it never catches up.

**The knee is 4 threads, and the shipped default is validated.** 4 threads give 2.1× the
1-thread throughput at the lowest energy per hour of audio measured (1.94 Wh, 1.77 in the
reverse run); 6 gives ~20% more throughput for ~1.4 W more. RTF 0.575 leaves 1.7× realtime
headroom, which is what #137's default was chosen to protect.

**8 threads is a loss on an 8-core device.** Both sweep orders agree: 0.611 vs 0.480
(forward, cold→hot) and 0.633 vs 0.524 (reverse, hot) — 8 is 21-27% *slower* than 6, and
its runs are unusually stable (0.633/0.633/0.638), i.e. a real effect rather than noise.
The host sweep saw the same shape (8 ≈ 6, 16 threads 38% worse): ORT's pool has no room
once the OS and the (screen-on) system share the cores. **Open follow-up, not changed
here:** `MAX_TTS_THREADS` is 8; the slider's top end is measurably counter-productive on
this SoC, so either cap it at 6 or explain the ceiling in the settings copy.

**Thermal and battery, measured.** Continuous synthesis takes the device from thermal
status 0 (SKIN 36 °C) to status 3 (SKIN 45 °C) in ~15 min and it does not cool while the
run continues; battery 40% → 35% in 17 min. The sweep's power-weighted average (3.30 W
including the idle baseline) agrees with the battery's own charge counter, which dropped
327.6 mAh over the two sweeps (1,701,000 → 1,373,400 µAh in 1598 s ≈ 2.9 W over the sweep
windows), to within ~10% — an independent check on `PowerProbe`. For the shipping question
that means **synthesize-while-listening costs ~10% of this battery per listening hour**
(1.9 Wh at 4 threads) versus ~2% playing from the pre-generated cache; 1 thread is the
worst of both worlds at 3.59 Wh per audio-hour, and `ThermalProbe`'s thermal-status axis is
dead on Android 17 (`android.os.ThermalManager` no longer resolves — status -1, headroom
0), so the leg's thermal signal is battery temperature plus the shell-side `dumpsys
thermalservice` sampler.

**Screen-state caveat.** The screen stayed awake for the t1-t4 legs and the whole reverse
run, but Samsung restored `screen_off_timeout` from the forced max back to 600000 (10 min)
mid-session, so the forward sweep's t6/t8 legs may have run with the display off. That does
not move the verdict: t1 (screen on, three runs stable to ±0.004), the t8-vs-t6 ordering
(confirmed in the reverse run, which ran entirely screen-on and hot) and the reverse t4
point are all unaffected, no leg shows the ~5× cpuset stall below, and the power numbers
match the coulomb account. Only t2 ≈ t3 (0.668 vs 0.671) is within noise rather than
resolved, which #147 does not need to settle.

**Harness rule discovered (not a product finding).** Unplugged + screen-off put the
instrumentation process in the restricted background cpuset: one t1 run went from RTF
1.214 to **6.575** mid-leg after the cable was pulled, with AP at 35-38 °C — a policy
restriction, not thermal throttling. The spike runs no foreground service; production
pregen and playback do, so energy legs must run with the screen on (and with adb over
TCP, so pulling the cable does not drop the run).

Evidence: `docs/prints/thread-sweep/` (forward + reverse JSON, per-run logcat, thermal
log), `ThreadSweepRunner` / `ThreadSweepBenchmarkTest` / `PowerProbe`, build.md
"Single-session thread sweep".

## 146. First-release prep: import ceilings + OOM containment, Settings About group, accurate pack hosts, arm64-only APK (2026-09-10)

Owner asked what to complete before publishing the first release (decisions #145 recorded
that 0.1.1 is release-ready but unpublished). Four blockers were named and closed.

**1. Import had no resource ceilings and OOM killed the process.** `EpubParser`,
`MobiParser`, `TextParser` and both `BookImporter` reads pulled the whole container into
the heap (`readBytes()`), and `ZipEntries` inflated every entry with no cap; per-file
isolation caught `Exception` only, so a zip bomb's `OutOfMemoryError` escaped and took the
app down. Now:

- `ImportLimits` (new, `internal`) is the single home for the numbers — container ≤ 256 MB,
  entries ≤ 4096, per entry ≤ 64 MB, cumulative expanded ≤ 512 MB — sized at ~2.5× the
  fattest real ebook (repo fixtures top out at ~3 KB) so no legitimate book is refused, and
  injectable so tests exercise the ceilings without a quarter-gigabyte fixture.
- `EBookSource.readCapped(limits)` is the ONE capped source read; all five former
  `readBytes()` sites go through it. Breaching the container ceiling raises
  `EBookLimitExceededException`, a subtype of the (now `open`) `EBookParseException`;
  `ImportCoordinator` reports it as `ParseError` with the ceiling's own message
  ("file is too large …") rather than the generic unreadable reason.
- `ZipEntries` enforces entry count, per-entry bytes and cumulative expanded bytes
  **during** inflation, before writing — never after a `readBytes()` — so the bomb is
  never materialised. The extension is deliberately not an `IOException`, so
  `readUntilBroken`'s lenient path (which tolerates trailing/broken junk in real KF8
  files) does not swallow a ceiling breach: a bomb is not trailing junk.
- `BookImporter.import()`'s parse block adds a narrow `catch (e: OutOfMemoryError)` after
  the typed-parse branch: one file fails with `ParseError("not enough memory to read this
  book")`, the process survives. `StackOverflowError` and other `Error`s still propagate
  (pinned by a test).

Evidence: `ImportLimitsTest` (7: container ceiling with exact-boundary case, entry count,
per-entry, cumulative, trailing junk still parses, lenient path keeps parsed sections,
lenient path still refuses a bomb), `BookImporterTest` +3 (4097-entry archive fails its
own file end-to-end and the next file still imports; OOM → typed failure; non-OOM `Error`
escapes), `ImportCoordinatorTest` +1 (read-stage ceiling → `ParseError`). Guard-removal
check (agent-run, sources restored byte-identical): disabling the four cap guards fails 6
tests; widening the lenient catch to `Exception` fails 4; removing the OOM clause kills
the Gradle test worker — the exact process death the containment prevents.

**Recorded residuals (not blockers):** MOBI `HuffCdic`/`PalmDoc` expansion is still not
proactively capped (bounded input, unbounded inflate) — the OOM clause is the containment;
the public `parse(bytes)` overloads bypass `readCapped` (app path always uses
`EBookSource`); `BookSegmentation.segment`/`coverOf` sit outside the OOM-guarded block.

**2. Settings had no About surface.** Added an `About` group last in the settings list:
`Ayvu <version>` (rendered unconditionally), `Source code (GPL-3.0)` and `Third-party
notices` link rows (48 dp targets, TalkBack click label), and the on-device privacy
statement reused from the setup card. Two seams declared in `feature-settings` and bound
by the composition root (the `ShareOpenHandler` pattern): `AppInfo { versionName }` from
`BuildConfig.VERSION_NAME` (the app module now sets `buildConfig = true` — it was off)
and `LinkOpener`, whose `ACTION_VIEW` adapter is a silent no-op when no browser
resolves the intent, so the version footer still renders on a bare device.

**3. A tracked defect closed:** the open-bugs "Offline-audio usage row is stale on return
to a live Settings screen" row (decisions #144) — `SettingsViewModel` read
`PregenStorage.usageByBook()` only in `init` and after a delete. The screen now refreshes
on `Lifecycle.Event.ON_RESUME` (`LifecycleEventEffect`, already on the classpath via
compose-ui's api dependency — no new dependency, no polling). Regression:
`SettingsOfflineUsageTest` — a live ViewModel publishes the tier as it is *now* after the
tier changes behind it.

**4. The release APK shipped four ABIs of which three cannot work.** Measured on the
built artifact: 165.2 MB compressed payload of which `lib/` was 151.7 MB across
arm64-v8a/armeabi-v7a/x86/x86_64 (± mips stubs), while the phonemizer pack that the app
must stage is a single `libespeak-ng.so` — downloaded from this project's own release and
verified `ELF 64-bit LSB shared object, ARM aarch64` (decisions #32). So ~114 MB of the
download was dead weight, and those ABIs would have installed an app that cannot synthesize
speech. The release buildType now sets `ndk { abiFilters += "arm64-v8a" }` (debug stays
unfiltered for x86_64 emulators): **165.2 MB → 50.8 MB payload, 53,389,398 B on disk**, a
single `lib/arm64-v8a/`. The notes and README now state the arm64 requirement and the real
size.

**Also in this batch:** `NOTICE.md` no longer claims all packs come from this project's
releases — it names the three hosts (kokoro-onnx release `model-files-v1.1` for the model
and voices, `tesseract-ocr/tessdata` tag `3.04.00` for OCR data, this project's
`espeak-ng-1.52.0` release for the bundle), their pinned sizes and hashes, and the
third-party-availability consequence; `docs/release-notes-0.1.1.md` is finalized (no
longer a draft) against HEAD with install/verify (SHA-256 + signing-certificate
fingerprint), upgrade notes and known limitations; `README.md` gains an Install section
plus the arm64 limitation; `docs/modules.md`/README module rows mention the About group.

Verification: pure-JVM suites green (620 test cases across the modules' XML reports, 0
failures/0 errors; `:core-ebook:test` re-run, 141 tests) and `ktlintCheck` green; signed
release build via `tools/release.sh` with `apksigner verify` → DN `CN=Ayvu,
O=moronigranja, C=BR`, cert SHA-256 `a5057984…02ae5`, APK SHA-256 `47ee826a…4311`. **No
device was attached (`adb devices` empty)**, so the signed-build device smoke and the
publish step remain owner actions (roadmap Release readiness).

## 145. Roadmap reconciliation — release state corrected, active work put in dependency order, D6 closed (2026-09-10)

Owner asked whether the roadmap's order still made sense. The tiering was sound and the
"Later" table already respected its dependencies (habit-driven pre-gen after Phase H,
auto-routing after the manual per-book voice case); the active queue did not. Five
corrections, all in `docs/roadmap.md`:

- **Release state was overstated.** Current state claimed "v0.1.1 has shipped — GitHub
  Releases signed unminified APK" while the same file's Release-readiness section still
  said "press publish", and decisions #128 said "nothing released". Ground truth checked:
  `git ls-remote --tags origin` carries only `espeak-ng-1.52.0`, the GitHub releases API
  returns only `espeak-ng-1.52.0` (2026-08-27), `docs/release-notes-0.1.1.md` is headed
  "draft", and `versionCode = 2`/`versionName = "0.1.1"` is built and device-verified but
  untagged. Recorded as **release-ready, not published**, with the publish step named as
  the one owner action that makes the framing true (nothing in the queue depends on it).
- **Phase G was structurally inside Phase D.** G0/G1/G3 were `####` subsections under
  `### Phase D`, contradicting the intro that pairs narration quality (G) with measured
  performance (D). A `### Phase G — narration quality` heading now carries them.
- **D5 preceded its own gate.** D5's heading says "gated on G0" and sat 45 lines above G0.
  It now lives in a `### Sequenced after G0 — D5 high-end engine choice` section below
  Phase G, and G0's block states what it gates (G1's rule set, D5's engine choice, the
  pt-BR blind read for the translation slice).
- **D6 closed rather than left half-open.** Its own evidence said the llama.cpp question
  was answered (#97's one-convention rule is now evidence-backed, decisions #140) and the
  remaining "ORT legs comparison" was never a cross-runtime question — it measured
  Kokoro fp32 against CosyVoice3 int4, an engine that D5 may replace. D6 moved to the
  measured-verdicts section as closed, and the int4 reference became an acceptance bullet
  of D5.
- **The real critical path is human.** G0's status is a corpus built and synthesized with
  the Roman-language classes awaiting the owner's listening pass — that pass gates G1 and
  D5, so the queue order is now stated explicitly (owner listening pass → D1 → D4, which
  unblocks K2 → K5 → H → D5), instead of reading as if code were the blocker.

Also folded in: Phase K gains item 6 (the settings-surface refresh defect filed in
open-bugs.md, decisions #144) and item 2's "second engine" gate now names D4 as the
near-term engine; Android Auto verification is tracked only in the authoritative
open-bugs.md row (it was duplicated across the shipped table, ideas.md and the roadmap
debt list); the "Later" row for cloned-voice pre-generation is retitled to distinguish
*shipping* that slice from D5 *selecting* its engine.

## 144. Phase K item 4 — per-book overrides: speed deferred to the #71 revisit, voice kept as a per-book override (2026-09-10)

Item 4 asked for an explicit keep/defer per override instead of a silent global-only
assumption. The two are decided separately because their preconditions differ.

**Per-book speed — DEFER (gate: the speed revisit, decisions #71).** There is no global
speed control to override: the selector and its command chain are removed, `resume()`
pins 1.0, and only the model was retained. A per-book value with no global base has
nothing to be an override *of*, so it waits for the feature.

Retention is already sufficient for that revisit: the `progress.speed` column and its
math, the `progress.json` backup field, the `PregenKey` speed dimension with the
`<bookId>/<engine>/<voice>/<speed>/` layout, `SynthesisRequest.speed`,
`PassageOutput.play(…, speed)`/`setPlaybackRate`, and
`PlayerStateMachine.setSpeed`/`MIN_SPEED`/`MAX_SPEED` all still exist under test — so no
migration, no cache invalidation and no engine-contract change is owed later.

What the revisit must not assume: no pre-#71 user choice survives. Every progress write
commits `speed = 1.0` (decisions #71), so stored rows are normalized, not preserved —
"restore the speed the user dialed in" is unrecoverable and the revisit defines a fresh
contract. That contract is a global default plus a per-book override, with "no override"
distinguishable from "override = 1.0".

**Per-book voice — KEEP, promoted (roadmap Phase K item 5).** Today the reader's voice
sheet writes `AppSettings.voice`, the global default, so choosing a voice to read one
foreign-language book silently re-voices every other book (`ReaderScreen` voice sheet →
`ReaderViewModel.selectVoice` → `settings.setVoice` + `changeVoice`). That is the silent
global-only assumption this item forbids, and it is live, not hypothetical. Decisions
#59 scoped "one global voice; per-book voice is not implied" as initial scope, not as a
permanent claim; this entry lifts it.

The kept contract, so it need not be re-litigated:

- **Storage.** One nullable per-book key in the existing generic `settings` table
  (`book.voice.<bookId>`). No Room migration; `BackupStore.snapshot` already dumps
  `settingsDao().all()` raw into `settings.json` and the merge's "restored keys overwrite
  local" precedence applies unchanged, so the override round-trips with no codec change.
  Absent key = no override. Content-hash book ids mean a restored override reattaches to
  a re-imported book. `RoomLibraryStore.delete` must drop the key alongside progress,
  bookmarks, history and the cached parse.
- **Resolution.** `effectiveVoice(bookId) = override(bookId) ?: settings.voice`, applied
  wherever the active book's voice is read: `PlaybackService.activeVoice()` (the choke
  point behind synthesis, coverage keys and engine selection), `PregenStorage`'s
  per-book usage/coverage read, and the `PregenWorker` `KEY_VOICE` fallback — so
  pre-generated audio and its coverage bar are computed for the voice the book actually
  plays.
- **Availability.** An override applies only when the active engine exposes that voice
  id; otherwise the global default plays and the selector marks the row unavailable
  (per-language Piper packs, decisions #99; engines with no voice list, decisions #102).
- **Precedence.** An explicit per-book override beats the global default; changing the
  global default neither clears nor rewrites overrides. The reader sheet gains a per-book
  scope (set/clear for this book, explicit "use default") while Setup and Settings stay
  global, so `SetupGate`'s `voiceSelected` derivation is unchanged.
- **No per-book engine override.** The engine is a device-capability decision
  (`realtimeCapable` tri-state, system-TTS degradation), not a book preference.

Caches need no change: `PcmPassageCache`/`PregenKey` are already engine+voice+speed
keyed, so a per-book voice cannot collide with global-voice audio on disk.

Evidence: `ReaderScreen` (voice sheet → `viewModel.selectVoice`), `ReaderViewModel`
(`selectVoice` writes `settings.setVoice`), `PlaybackService.activeVoice()` +
`PregenStorage` + `PregenWorker.KEY_VOICE`, `SettingEntity`/`SettingsStore` (generic
key/value with default-fallback accessors), `BackupStore.snapshot`/`merge`,
`RoomLibraryStore.delete`, `ProgressEntity.speed` and the `BackupCodec` progress fields,
`PlayerStateMachine.resume` (pins 1.0), decisions #59/#71/#99/#102.

**Carried note disposed.** #142's device pass left one observation "for the item-4
review": the settings screen's Offline-audio row keeps a stale empty state when the
screen's ViewModel is not recreated. It is a settings-surface refresh defect, not an
override decision — `SettingsViewModel` reads `PregenStorage.usageByBook()` only in
`init` and after a delete — so this entry routes it to `docs/open-bugs.md` as an open
product bug with the fix direction (re-read on section visibility / `ON_RESUME`), rather
than pretending item 4 settled it.

## 143. Voice selector: collapsible language sections + display names and upstream grades (2026-09-10)

Owner request ("shorten the length of the kokoro voices… collapsible sections by
language… name/language/country in a more visually nice way… extra data about the
voices"). The C2 single-surface contract holds — one [VoiceSelector] serves Setup,
Settings and the reader voice sheet, so all three surfaces get the redesign:

- **Sections collapse by language.** Headers carry the language flag + voice count +
  chevron; collapsed by default EXCEPT the selected voice's language and the
  languages of favorites (a collapsed section must never hide the selection or a
  favorite from the first frame). Expansion state is per-composition
  (rememberSaveable); 54 rows went from ~9 screens to ~2.
- **Rows.** Primary label is the voice's display name with the upstream personal
  trait emoji ("Heart ❤️", "Bella 🔥", "Nicole 🎧"; others just "Alloy"-style);
  secondary line carries the id, gender and the upstream data-grade. The language
  moved to the section header (it was redundant per row); radio/star/preview
  mechanics are byte-identical to the C2 contract.
- **Extra data** (the "studio-like" fields): hexgrad/Kokoro-82M's VOICES.md assigns
  each voice an overall training-data grade (A…F+ — af_heart A, af_bella A-,
  am_adam F+; the es/pt families ship none and render without one). Pinned into
  `KokoroVoiceMetadata.EXTRAS` as presentation only — the pack roster remains the
  contract, cross-checked by `KokoroVoiceMetadataTest` (54 names, every name has
  extras, only es/pt ungraded) and `BuildVoiceSelectorStateTest` (display name +
  grade flow through the one builder).

Verification: full host suite green; ktlint gate green (KokoroVoiceMetadata came
out clean and left the baseline). Device (S22): collapsed/expanded states, section
toggle, emoji rendering and per-row grades all visually confirmed.

## 142. Phase K items 1+3: settings information-architecture regroup + arbitrary pregen budget (2026-09-10)

**Item 1 — grouping.** The settings surface grew by accretion since B3 with
incoherent sections (packs split from the engine that owns them, playback volume
under "Share & reading", storage topics unmarked). Regrouped into four concern
sections (`SettingsScreen`): **Speech** (engine radios, the three kokoro packs +
espeak status, generation threads, the degraded-engine install plan card, voice
selector, playback volume), **Reading & sharing** (match threshold, OCR languages),
**Storage & data** (offline audio + Backup & restore), **Appearance** (theme). No
knob moved to a different ViewModel call — pure view restructure; B4's a11y bar
(radio rows, 48 dp targets) untouched. Restart-knob copy review: `ttsThreads`
already states "Applies after restart" and is kept; `realtimeCapable` resolved as
NOT a user knob — it is the D2 RTF lazy-measurement tri-state (decisions #137's
item 8), measured from real synthesis, surfaced nowhere (and nowhere it should be).

**Item 3 — arbitrary pre-generation budget.** The backend has accepted any
listening-minute budget since A1 (`PregenBudget.maxSeconds`, `KEY_BUDGET_MINUTES` =
Long); the dialog shipped only the five presets. Added a free-form entry to
`PregenBudgetDialog`: `parseListeningMinutes` (pure, host-tested in
`ListeningTimeTest`) accepts plain integer minutes ("90"), unit forms ("90m",
"1h", "1h30", "1h30m", "1.5h", "1 hour 30 minutes", comma decimals), and rejects
ambiguities (empty, garbled, unitless fractions, zero/negative) — a typo must
never start a silent whole-book run. Live byte estimate under the field
(2.88 MB/min model); "Generate" enables only on a parse. UI-only: no
`PregenBudget`/worker change.

Verification: ktlint gate green (baseline re-keyed for line shifts only); full host
suite green. Device (S22, fresh-install APK): settings sections visually confirmed
in order Speech → Reading & sharing → Storage & data → Appearance → Backup &
restore; dialog confirmed end-to-end — "45x" rejected with helper text and a
disabled Generate, "1h30" showed "≈247.2 MB of audio" (90 min at the 2.88 MB/min
model) and enabled Generate; tapping it enqueued the worker, which anchored at the
book's 100% playhead, synthesized the single remaining passage ahead
(c1p0.pcm, 174 KB), and settled SUCCESS in ~5 s. Offline-audio row appeared under
Storage & data and was deleted through it (tier empty again). Device note: the
offline-usage row only refreshes when the settings screen (re)creates its
ViewModel — an in-place return to a live instance can show a stale empty state;
pre-existing behavior, not a Phase K regression; tracked for the item-4 review (disposition in decisions #144).

## 141. E2E fixture repair: PlaybackE2e window, PregenE2e singleton-store fixture, worker false-success terminal (2026-09-10)

Device pass 2026-09-08 surfaced two pre-existing instrumented-test defects (open-bugs
row "PlaybackE2eTest/PregenE2eTest device failures"):

1. **PlaybackE2eTest.playsThroughTheBookAndCompletes timeout.** The 2-passage fixture
   (~18 s of audio) can never reach `PREFILL_LOOKAHEAD_SECONDS = 45`, so the cold open
   burns the full `PLAY_BUFFER_TIMEOUT_MS` (60 s) before playback; 60 s burn + engine
   open + ~18 s playback sat right on the 90 s test window. Fix: window 90 s → 180 s
   (`PlaybackE2eTest`). The production constants are untouched — the wait is correct
   for real books; the fixture is just small.
2. **PregenE2eTest WAL/instance race.** The test wrote its fixture through a SECOND
   Room instance on the production DB file while `PregenWorker` reads the app's Hilt
   singleton `RoomLibraryStore`; the worker could see no book and settle SUCCESS with
   an empty cache. Fix: the fixture is written through the app singleton store itself
   (`(applicationContext as LocalTtsReaderApp).libraryStore`) — same-instance
   write/read removes the race by construction. Teardown now also deletes the fixture
   row so the device library stays clean.

Found while tracing (2): `PregenWorker.doWork()` settled a run for
requested-but-absent books as SUCCESS ("offline audio ready", zero work) — the CR-1
false-success class. A manual run only sends library IDs, so an empty filtered set
means the book was deleted while queued; the worker now fails with a typed
KEY_ERROR. A whole-library run over an empty library still succeeds (no work by
definition). Host regression test:
`PregenWorkerTest.requested books absent from the library fail instead of a false
success`.

Also fixed en route: `VoiceAuditionCoordinatorTest.FakeRuntime` still built
`KokoroRuntime(context)` after a3a41ad added the `settings` parameter — the app host
unit suite did not compile on main.

Verification: `./gradlew ktlintCheck` green (baseline re-keyed for the touched
files — line-number shifts, no new rule classes; G0 corpus files from fe97818
formatted to the gate); full host `./gradlew test` green.

Device verification (same day, S22 SM-S908U1, Android 16): both tests green via
`am instrument` — `OK (2 tests)`, 185 s total — after staging the pinned packs into
the app files dir (kokoro-model/kokoro-voices byte-exact vs the host cache,
espeak-ng zip re-downloaded from the release URL, sha256 `6b2edca…` matching the
pin, extracted bundle + `.ready` markers under `files/packs/kokoro-82m/`). The 185 s
wall confirms the 90 s window was genuinely too tight: the 60 s buffer burn is real
device behavior, not test-host slack.

## 140. D6 cross-runtime spike: llama.cpp cannot run CosyVoice3-class flow TTS — #97 one-runtime rule evidence-backed (2026-09-09)

Host-only feasibility probe (no Android): inspected `cstr/cosyvoice3-0.5b-2512-GGUF`
(HF revision `aa0ea5eb7279bcf54668bcbaa7348019de7d7178`, sha256 per file in
`docs/prints/d6/feasibility.json`) with the `gguf` python package — 1510 tensors dumped.

The repo is not one GGUF but six per-component files, each its own
`general.architecture`: `cosyvoice3-llm` (Qwen2-0.5B frontend + speech-token head,
q4_k), `cosyvoice3-flow` (22-block DiT flow-matching, q8_0), `cosyvoice3-hift`
(HiFT vocoder with NSF sine source, f16), `cosyvoice3-voices` (8 baked voices),
plus cloning-only campplus/s3tok. Together the four synthesis files (745 MB) cover
the full text → speech-token → flow-matching DiT → HiFT pipeline.

Verdict `llama_op_support: none`: no cosyvoice3 arch in llama.cpp, no
flow-matching/DiT-CFM iterative ODE sampling, no vocoder op set, no multi-GGUF
companion loading. The GGUFs target **CrispASR** (github.com/CrispStrobe/CrispASR) —
an unaudited *whisper.cpp* fork carrying its own ggml `cosyvoice3-tts` backend
(model card: ASR-roundtrip WER 0%, byte-exact s3tok vs ONNX); it also ships GGUF
backends for Kokoro and 60+ TTS engines.

Keep/drop: **llama.cpp mainline — drop** (would need CFM sampling, a DiT family,
a vocoder, and multi-file orchestration upstream — a runtime project, not a spike).
**ggml forks (CrispASR) — drop** (technically capable but unaudited; adopting it
swaps one audited runtime for a fork plus a second ecosystem). Precise #97
statement: NOT "no runtime can run this class" — the weights ARE runnable on
ggml-family code — but "no audited on-device runtime other than ORT runs this
class." D6 Android leg (plan Step 3) not built; the Step-2 fallback applies. ORT
legs remain Kokoro fp32 (#67/#86) and CosyVoice3 int4 (#49). Evidence:
`docs/prints/d6/feasibility.json`, conclusion: `docs/prints/d6/d6-conclusion.md`.

## 139. Chunk-parallel window synthesis measured — serial single-session wins (2026-09-09)

Closes the #138 spike. Question: does splitting one passage's inference windows
across W low-thread ORT sessions (candela's "1–8 engine instances, each its own
thread pool") beat our serial single-session engine? **No — serial wins on
throughput AND memory at every config.**

Measured on the S22 (SM-S908U1, SDK 36, `ChunkParallelRunner`, 16-passage
corpus = 374.3 s audio, 3 runs, best wall per leg):

| Config (W×T) | total threads | wall (best) | RTF | audio-s/s | VmHWM | PSS |
|---|---|---|---|---|---|---|
| 1×6 | 6 | 245.9 s | **0.657** | **1.52** | 1.57 GB | 1.46 GB |
| 2×2 | 4 | 353.6 s | 0.945 | 1.06 | 2.72 GB | 2.69 GB |
| 4×1 | 4 | 451.7 s | 1.207 | 0.83 | 3.32 GB | 3.33 GB |
| 2×4 | 8 | 267.9 s | 0.716 | 1.40 | 3.32 GB | 2.69 GB |
| 4×2 | 8 | 372.7 s | 0.996 | 1.00 | 3.37 GB | 3.31 GB |

Speedup vs 1×6: 2×2 **0.70×**, 4×1 **0.54×**, 2×4 **0.92×**, 4×2 **0.66×**.

**Why it loses.** Kokoro-82M is not window-parallelism bound: mlas intra-op
multi-threading *within* one session scales better than splitting windows
across sessions. Even 8 total threads (2×4) is 8% slower than 6 while doubling
the session count, and every 4-session config is ~35–46% slower at ~2.2× the
RAM. Confirms #116 at the window granularity.

**Thermal caveat (honest):** legs ran coldest→hottest (1×6 first, 4×2 last); the
1×6 leg itself climbed 0.657→0.696→0.742 over its own ~13 min, so later legs
are slightly penalized. The 2×2-vs-1×6 gap (0.945 vs 0.657, run second) far
exceeds the observed ~13% thermal drift, and the memory cost is
thermal-independent — the verdict stands.

**Answer to the candela question:** candela is not synthesizing faster. Same
model (hexgrad-82M fp32), same ORT backend, and their parallel-engine
architecture is *slower* on our hardware. Their perceived speed is
pre-generation + fat-buffer playback pipelining (audio already rendered before
you feel the wait), not raw throughput. The levers that actually matter for us
are the ones already shipped: emit-early per-window streaming (#138) for
first-audio latency, and the thread-count setting (#137) for responsiveness.

## 138. Emit-early synthesis — per-window streaming seam (2026-09-09)

Follow-up to the candela/sherpa speed review. The "slower than candela" gap is
pipeline latency (whole-passage synthesis before playback), not the model:
sherpa's Kokoro is the same hexgrad-82M fp32 export family (taylorchu v0.2.0;
ours thewh1teagle v1.1 with the `duration` output) and our port already measures
RTF 0.66–0.77 on the S22 (decisions #25, #93). sherpa has no streaming TTS —
candela's speed is window-level parallel synthesis + eager pregen (its README).

- **`TTSEngine.synthesizeStreaming(request, onWindow)`** — a defaulted interface
  method that buffers then emits one chunk, so the system engine and every test
  fake compile unchanged. `KokoroEngine` overrides it.
- **`KokoroEngine` single core** (`synthesizeCore`): `synthesize` is the
  collector (sink = null); `synthesizeStreaming` hands each window's final
  PCM16 to the sink the moment inference lands — first audio is available long
  before the passage finishes. Both paths return the identical whole-passage
  outcome, so a streaming consumer cannot diverge from the buffered contract.
- **Pause top-up runs per window** (`KokoroTimings.insertPauses` over the
  window's own loudness floor) instead of once over the merged passage. A
  boundary mark is already padded by `PhonemeChunker.pauseAfter` + the model's
  natural gap; an intra-window mark's quiet run is self-contained. The change
  is pause-length-cosmetic (bounded by the 0.15 s reach); on-device output is
  run-to-run nondeterministic at the sample level regardless (decisions #116).

**No playback consumer yet — deliberate.** The output edge is MODE_STATIC
whole-passage (decisions #84); incremental feed was the MODE_STREAM FIFO,
measured inert on the S22 (decisions #83). Emit-early to the ears needs an
incremental-output decision the spike below informs — the seam is the
foundation, not a stub.

**Spike task written (not run):** `ChunkParallelRunner` +
`ChunkParallelBenchmarkTest` (spike-tts) measure candela's actual lever — one
passage's inference windows across W low-thread ORT sessions (1×6 baseline,
2×2, 4×1, 2×4, 4×2) for pregen throughput + resident memory. No oracle gate:
differing intra-op thread counts change mlas reduction order (decisions #116
nondeterminism), so cross-config PCM drift is expected, not machinery.

Evidence: `:core-tts:test` (2 new streaming tests: per-window reassembly ==
buffered bytes + equal segments; no-timings per-window chunks + null segments),
`:feature-player:compileDebugKotlin :app:compileDebugKotlin` green,
`:spike-tts:compileDebugKotlin :spike-tts:compileDebugAndroidTestKotlin` green.

## 137. Configurable synthesis thread count — the phone stops freezing during generation (2026-09-08)

User ask: "Can we limit the audio generating to only some cores? Or somehow
make it not slow down the phone so much?"

Root cause found in the code: `OrtKokoroSession.open` hardcodes an ORT
`intra_op_num_threads` pool of **6** threads. ONNX Runtime's intra-op pool
spins at full priority on every logical core it owns, and playoffs are
second-class: `PregenWorker` runs as a foreground dataSync coroutine while
each passage's inference saturates 6 of the S22's 8 cores (decisions #116)
for minutes-to-hours per run. The UI thread, storage writes and the
`AyvuPlayer` boundary thread all queue behind it.

- **The knob lives in Settings, not code:** a new `tts_threads` setting
  (`SettingsStore`, `AppSettings.Snapshot`, Clamped 1–8, default **4**) drives
  the ORT intra-op pool size. 4 leaves at least half of an 8-core flagship
  free while still generating well past realtime (the S22's RTF is 1.16–1.20
  at 6 threads — Kokoro is throughput-luxurious, trading speed for
  responsiveness is honest). Supported bounds match the measured devices:
  4-core phones (HiBreak MT6765, 8×A53) down to 1; today's 8-core flagships
  up to 8.
- **Applied at open, not live:** the session's pool is fixed when the ORT
  session is created (once per process — `KokoroRuntime` caches the open
  engine). `KokoroRuntime.openEngine` passes the configured count through the
  existing `sessionFactory` seam, which overrides the core-tts default of 6;
  core-tts's default is untouched so harness benchmarks and tests keep the
  device-baseline setting. Settings copy explains "Applies after restart"
  (the engine opens again after a restart; the PLAN is deliberately not
  over-engineered with session rebuilds).
- **UI:** a "Generation threads" slider in the Settings Speech engine
  section, rendered only while the downloaded Kokoro engine is selected (a
  degraded system-TTS session never touches ORT, so the row hides with it).
  Writes flow through `AppSettings.setTtsThreads` → store → hot-path mirror;
  out-of-range values clamp; corrupt persisted values read as the default
  (the V1 bad-value rule).
- feature-player gained `compileOnly(libs.onnxruntime.jvm)` — the first
  `OrtSession.SessionOptions` reference outside core-tts; the runtime AAR
  still ships app-side only.

Evidence: `AppSettingsTest` (`tts threads write through clamped and invalid
values fall back` plus default/reload assertions), `SettingsViewModelTest`
(`setTtsThreads is observed by the state immediately` — mirrors the existing
theme-radio regression pair);
`./tools/docker-build.sh :core-persistence:test :feature-settings:testDebugUnitTest
:feature-player:testDebugUnitTest :app:compileDebugKotlin` → BUILD SUCCESSFUL.
On-device impact re-measurement (S22, threads 4 vs 6, UI-latency + generation
throughput) is the acceptance, pending the next device session.

## 136. Pre-generation progress is slice-relative; library Stop control (2026-09-08)

User report: the pre-generating notification looks stuck at a small percent for
long stretches — "I think the 3% is actually the playback position. If I ask for
30 min, it should go 0 to 100% while the 30 min is generated. Also a stop
generating button would be useful."

Root cause: `PregenProgress.percent` was `processed / totalPassages` — a
WHOLE-BOOK denominator. A 30-min bounded run is a small fraction of a long
book (~3% for an hour of audio in a 30+ h book), so the bar crawled through
the entire run no matter what slice was asked for, and cached-passage skips
moved it without any visible synthesis.

- **`PregenProgress` gains `synthesizedSeconds`** (listening seconds of NEW
  audio this run; cache hits skip) **and `budgetSeconds`** (the run's
  `PregenBudget.maxSeconds`). **`percent` is slice-relative for bounded
  runs**: `synthesizedSeconds / budgetSeconds` → the 30-min ask fills 0→100%
  as those 30 min generate. Unbounded (whole-book) runs keep the
  processed/totalPassages walk; the `totalPassages == 0` edge is unchanged.
- **`OfflinePregen.run`** records the seconds on the progress struct (the
  local `synthesizedSeconds` var is gone). The worker's notification and the
  library row consume `progress.percent` unchanged — both now show the slice
  fill, and the notification's `percent <= 0` indeterminate bar still covers
  the first passage's synthesis.
- **Stop generating** — `LibraryViewModel.cancelPregen(bookId)` →
  `PregenScheduler.cancel` (unique-work cancel; already-cached audio stays).
  Surfaced on the library row (inline Stop button beside the running bar; the
  row menu's "Pre-generate" item swaps to "Stop generating" while a run is
  live — the plain tap was a KEEP no-op anyway) and on the active player
  card's overflow menu, which must carry the stop itself: the card replaces
  the book's row (decisions #55/#56), so the row's Stop is not visible there.
  The budget dialog's "cancel anytime" promise (decisions #132) is now true.

Evidence: `OfflinePregenTest` (`bounded run percent tracks the requested
slice not the whole book` — first passage ≈ 69% of a 30 ms budget, 100% at
exhaustion, monotonic; `bounded run percent ignores cached skips`),
`PregenWorkerTest` (`bounded run notification percent is slice-relative` —
15/30 min reads "(50%)" on a 5,000-passage book);
`./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest
:feature-library:testDebugUnitTest` → BUILD SUCCESSFUL.

## 135. Audio focus is session-scoped — in-place commands must not drop it (2026-09-08)

Device-reported: "playback doesn't pause for other audio (Teams call, WhatsApp
messages)". Root cause found in the code: `PlaybackService.stopEverything` abandoned
audio focus on EVERY command, and the in-place seek/navigate/undo tails restart the
play loop WITHOUT re-requesting — so after the first ±30 s seek or chapter skip the
app played with NO audio-focus request. The system then never delivered the
LOSS/LOSS_TRANSIENT callback to pause (or the CAN_DUCK callback to duck) the book;
it streamed at full volume over the call.

- **`stopEverything` no longer abandons audio focus.** The request is
  session-scoped: created at play/resume, held across every command (the loop
  re-requests on each play/resume anyway, so a focus owner that took the grant
  — a real call — is re-requested against when the user resumes).
- **True-stop paths release it** — `captureAndStop` (the STOP command) and
  `onDestroy` call a new `releaseAudioFocus()`; nothing else does.
- **`changeVoice`'s resumed arm re-requests focus** (its rebuild also went
  through `stopEverything`; a prior full LOSS must be re-asked-against).
- **Focus loss only pauses while actually listening** — new `pauseForFocus`
  guards on PLAYING/LOADING. With the request held while the reader is open
  but idle/paused (open-book mode), the old unguarded `pausePlayer` would have
  spurious-published PAUSED from an IDLE machine (the machine's `pause` sets
  PAUSED unconditionally once a position exists).
- Behavior: calls (Teams/WhatsApp calls+voice notes = full/transient loss) now
  pause the book; message-chime notifications (MAY_DUCK) duck it to 20%. The
  transient-loss auto-resume (`resumeOnGain`) also works now — before, the
  focus-loss pause abandoned the request, so the GAIN callback (and the
  resume) never came back.

Tests: `PlaybackServiceFocusTest` — an in-place seek while playing keeps the
focus request registered (fails pre-fix: the shadow AudioManager records the
abandon), then a simulated LOSS_TRANSIENT pauses the machine; focus loss while
the machine is idle is a no-op. All host suites green.

Same device pass found a second pregen-notification defect: `PregenWorker`'s
terminal notification ("Ayvu — offline audio ready" / "pre-generation stopped")
was posted synchronously from `doWork`, and WorkManager's `SystemFgDispatcher`
removes the FGS notification (same id 43) immediately after the worker returns —
the removal cancelled the terminal in place (observed in `dumpsys notification`
+ logcat). The terminal post is now deferred 500 ms past the teardown
(`TERMINAL_POST_DELAY_MS`).

Device-validated: the terminal now appears ("Ayvu — offline audio ready —
25 passages cached (…)"), the generation notification (44) coexists with the
manual run (43), and 44 cancels the moment audio starts. Same pass + UX ask:
the generation notification's shade text is now `"<book> — <percent>%"` and it
shows a live elapsed timer (`setUsesChronometer(true)`) — "how long it's
taking" — alongside the determinate 45 s-target bar. Dismissibility by design:
44 has no `NO_CLEAR` (swipe-dismissible, re-posted while the wait runs); the
ongoing 43 is a foreground notification (not dismissible, progress stays on
the shade); the terminal auto-cancels.

## 134. Player card coverage bar redo: torrent-style generated/not bar + generation notification (2026-09-08)

User ask: the card's progress bar should look like a torrent download manager — every
span of the book colored by whether its audio is generated, chapter boundary ticks, a
static playhead marker — and playback-time generation feedback should leave the card
(the amber 120 s cushion segment, decisions #94/#98) for a determinate system
notification. Root-cause hunt for the never-seen pregen notification found the
missing half: the manifest never declared `POST_NOTIFICATIONS`, so on API 33+ every
`notify()` was silently dropped (the request could never be granted).

- **Coverage source of truth is the disk tier** — new `PcmPassageCache.generatedKeys`
  (bookId, default engine, voice, speed) reads the in-process `recency` map — the
  exact valid-entry set (CR-4 bootstrap), no disk walk; legacy v1 paths parse as
  the default engine and are included naturally.
- **`CoverageEncoder`** (core-player) walks the spine once, weighting by passage
  text length (the chars/15 model shared with `BookProgress` — NOT equal widths),
  and emits run-length `CoverageSpan`s; `chapterMarks` lists each non-empty
  chapter's start fraction, first mark and duplicates omitted.
- **`PlaybackUiState`** gains `coverageSpans`, `chapterMarks`, `playheadFraction`
  (smooth char-weighted book-time playhead — moves continuously inside a passage,
  unlike passage-granular `readFraction`). `generatedAheadSeconds`/`Fraction` stay
  (the notification progress feeds on them; `PlaybackUiStateTest` pins the contract).
- **Service publication** — `stateCopy` computes the snapshot once per copy,
  cached until dirty. Dirty sources: queue rebuild (`buildQueue`, covers
  voice/speed/book — the coverage key changes with it), every persist
  (`onSynthesized` hook + first-listen disk write), and manual pregen via a new
  `PregenManager.workInfoFlow` observer (`getWorkInfosForUniqueWorkFlow`) that
  re-publishes the bar on every WorkInfo emission — the bar fills while paused,
  where no ticker runs.
- **`CoverageProgress`** (core-ui, replaces `SegmentedProgress`, deleted): 6.dp
  single `Canvas` — `surfaceVariant` track = not generated, `primary` teal spans =
  generated, `outlineVariant` 1.5.dp ticks = chapter starts, `onSurface` 2.dp
  marker = playhead. Static by construction (reduced-motion safe, decisions #98
  pattern). The listened position is the marker only — binary generated/not, per
  the user's torrent analogy.
- **Generation notification** — new `generation` channel (IMPORTANCE_LOW) + id
  44 (**not 43, which `PregenWorker` owns** — the plan draft collided: 43 would
  have overwritten the manual run's in-place; two notifications must coexist).
  Throttled ≥1 s in-place refresh: indeterminate while the buffer holds 0 s
  (engine loading), then determinate against the 45 s fill target. Shown in the
  cold-seek `bufferForPlayback` wait and the post-STOP cushion fill (gated by
  `postStopFill`); cancelled at `output.play` (audio start — the plan draft's
  publish-site-only cancel would have lingered a whole passage: `onAudioStarted`
  does not publish), in `publish`'s backstop (`phase != LOADING && !postStopFill`),
  in `stopEverything` and `onDestroy`.
- **Notification permission (root cause)** — `POST_NOTIFICATIONS` declared in the
  manifest (it was not — see above; without the declaration a request can never be
  granted) and requested once per process at `MainActivity.onCreate` on API 33+,
  no nag on deny. The single gate every notification surface shares (media 42,
  pregen 43, generation 44).
- **Dead code** — `PregenWorker.doWork`'s local `suspend fun notify` (never
  called; `runBooks`'s parameter shadows it) deleted.

Tests: `CoverageEncoderTest` (char-weighted spans, cross-book-key isolation, empty
book, deduplicated ticks, zero-length passages), `PcmPassageCacheTest` +
`generatedKeys` (voice/speed/book filtering, legacy v1 inclusion, eviction
exclusion). Host suites green (`:core-player:test :core-ui:test
:feature-player:test`), `:app:compileDebugKotlin` green, baseline-gated
`ktlintCheck` green (baseline regenerated for PlayerCard/ReaderScreen line shifts).

## 133. Manual pre-generation budget is listening time, not wall-clock (2026-09-08)

Follow-up to #132 (same review): the picker's "30 min / 1 h / …" was computed as a
wall-clock synthesis cap (`PregenBudget.maxTimeMs`), while the dialog labels it in
listening minutes and bytes (`BYTES_PER_MINUTE`, 24 kHz 16-bit mono). At Kokoro
RTF ≈ 0.7 a "30 min" run produced ~42 min of audio — the label and the cap
disagreed. The user also asked whether already-generated spans are skipped.

- **Budget is now `PregenBudget.maxSeconds`** — listening seconds of NEW audio.
  `OfflinePregen.run` measures each synthesized passage's duration from its PCM
  (`pcm.size / (sampleRateHz × 2.0)`, 16-bit mono) and stops when the total
  reaches `maxSeconds`. A passage already on disk is skipped by `shouldVisit` and
  adds nothing to the accumulator — so a run crossing an already-cached stretch
  keeps generating the same total *new* listening time past it.
- **Hole semantics:** reading position 0:00 with 0:10–0:20 already cached and a
  30-min ask synthesizes 0:00–0:10, skips 0:10–0:20 (not counted), then
  0:20–0:40 — exactly 30 minutes of new audio, covering book-time 0:00→0:40.
- **Wall-clock machinery removed:** `maxTimeMs`, `remainingTimeMs`, the injected
  `clock`, and the worker's `startedAt`/`elapsed`/`remaining` checks are gone —
  the overnight arm that needed a wall-clock cap was already removed (S1b), so
  wall-clock had no remaining producer. `PregenBudgetTest` (which tested
  `remainingTimeMs`) is deleted.
- **Input re-encoded:** `PregenManager` now writes `KEY_BUDGET_MINUTES` (a whole
  minute count) and the worker maps it to `maxSeconds = minutes * 60.0`. The
  dialog needs no copy change — its labels were already listening-time.

Tests: `OfflinePregenTest` gains `maxSeconds budget stops mid-book` and
`maxSeconds skips cached passages without counting them` (the hole). 
`PregenWorkerTest` replaces the expired-deadline cases with `a finite listening
budget bounds how much is synthesized` and `an unbounded budget runs the whole
book`. core-player and feature-player unit suites green; app compiles.

## 132. Manual pre-generation time budgets anchor at the reading position (2026-09-08)

User-reported: “generate 30 min” was expected to be the next 30 min from the
current reading/playing location (or the first not-yet-generated point), but the
bounded manual run always walked the book from chapter 0 and synthesized the
opening audio instead. Decision #131's pre-gen note documented that behavior;
this reverses it for the bounded arms only.

- **`OfflinePregen.run` gains `startAt: Pair<Int, Int>?`** — an inclusive spine
  position. The planner's `walk`/`plan` `from` is strictly-after by contract
  (PregenPlannerTest pins it), so a private `inclusiveFrom` maps `startAt` to
  the spine predecessor before the walk; `startAt = null` walks the spine start
  unchanged. Because `shouldVisit` still skips cached passages, an inclusive
  start that is already on disk advances naturally to the first real gap.
- **`PregenWorker` resolves the anchor** — it now injects `ProgressDao` and, for
  a bounded run (`budget.maxSeconds != null`), reads the book's `progress` row and
  rewires its `chapterIndex` (the player's `chapter.index` domain) into the
  planner's dense list position via `chapters.indexOfFirst { it.index == … }`.
  Unbounded (“Whole book”, null budget) still covers the spine from the start.
- **Inclusive, not exclusive:** the resume passage can be uncached after an
  open-book/bookmark jump that presents without auto-play (decisions #131). An
  exclusive start (strictly after the resume point) would strand one passage at
  the exact anchor; inclusive start + cache skip cannot.

Tests: `OfflinePregenTest` gained three cases — `startAt` walks inclusively, the
first-passage start equals the whole-book walk, and a cached start/cushion is
skipped. `PregenWorkerTest` gained `a bounded budget anchors the run at the
persisted reading position` (resume at 0/1 → synthesizes p1..p4, never p0).
Host suites: core-player and feature-player unit tests all green.

## 131. Chapter selector and bookmark jumps present without auto-play (2026-09-08)

User call during the "every play trigger" review (decisions #129/#130): jumping
to a chapter or a bookmark should NOT start audio — it should only move the
reader to that position, leaving playback to an explicit play action (the same
open ≠ auto-play contract as decisions #52, already held by `openBook` and
`openChapter`).

- **New command `ACTION_OPEN_POSITION` → `openPosition(bookId, chapter,
  passage)`** in `PlaybackService`, mirroring `openChapter` exactly: stop
  current audio, rebuild the machine, `present()` the target (phase stays
  `IDLE`, nothing committed), prefill, publish text, drop the foreground.
- **Callers rewired** (`ReaderScreen`): the chapter dropdown and the bookmark
  jump now call `openPosition` instead of `playPosition`. Everything else that
  starts audio is unchanged — long-press "Play from here", play-from-view, and
  share "Listen here" still use `ACTION_PLAY_POSITION`; the docked card's play
  button still uses `resume`/`playFromView`.
- **Empty-spine safety:** the target is resolved before `present`. A valid
  `(chapter, passage)` presents as-is; a stale passage index clamps to that
  chapter's first passage; an empty chapter falls to the nearest playable
  chapter (forward, then backward), and a book with no playable chapter is a
  no-op. This also removes a latent `require(isValid)` crash the old
  `playPosition` path could hit by selecting an empty spine slot.
- **Pre-gen note (same review):** manual "Pre-generate" always starts at
  chapter 0 and walks the whole book in spine order (skipping cached
  passages), independent of the playhead; the automatic playback prefill
  (`PregenQueue.ensure`) starts from the current playhead forward.

## 130. Reader empty-state drift recovery: re-open when the service state diverges (2026-09-08)

The open-bugs row "Reader shows the empty-state placeholder after screen off/on"
is confirmed and fixed: the empty branch `chapterPassages.isEmpty() && phase ==
PlayerPhase.IDLE` (ReaderScreen) was the symptom, not the root cause. The
reader's `LaunchedEffect(bookId, startAt)` only re-opened when `bookId` changed
or `startAt != null` — a service-state reset or process death underneath
(`PlaybackStateHolder.reset()` / machine teardown, e.g. LMK while the screen
was off) set `state.bookId` to null while the composable's `bookId` was
unchanged, stranding the reader on the empty branch with no recovery.

- **Split the effect.** Re-open (`open(bookId)`) now keys on `(bookId,
  state.bookId)` and fires whenever the service's state no longer reflects the
  reader's book — restoring the reading place from the persisted resume row
  without starting audio (open ≠ auto-play, decisions #52). The share-target
  `startAt` play (S3 "listen here") is a separate once-only
  `LaunchedEffect(startAt)`, so it never replays on drift recovery.
- **Drift sources.** Confirmed: media-session STOP (`PlaybackStateHolder.reset()`
  in `stopPlayer`), process death (LMK), and the machine-less `publish()` path.
  The compose state survives (rememberSaveable) but the service state
  underneath resets; the re-open self-heals.
- **Existing test coverage.** `PlaybackServiceRevivalTest` (machine-less resume
  rebuild), `PlaybackServicePublishGuardTest` (holder field-set after
  publish), and `PlaybackServiceCr2Test` (STOP capture) cover the service
  side; the compose-side recovery is a one-line key change.

## 129. Playback volume gain setting (2026-09-08)

The generated voice played at unity (1.0×) on top of the device media volume
with no in-app gain control. `AudioTrackPassageOutput.setVolume` hard-clamped
to `[0, 1]`, so the only gain path was the ducking transition (1.0 ↔ 0.2).
Owner: "it seems a little low."

- **"Playback volume" setting** (0.5×–2.0×, default 1.0×) in Settings → Share &
  reading, persisted as `playback_gain` in `SettingsStore`/`AppSettings` (the
  existing key-value settings table), with a slider in `SettingsScreen` (same
  pattern as the match threshold). The mirror is push-based: `AppSettings`
  updates its `Snapshot` on every write, consumers read `state.value.playbackGain`.
- **Applied as a linear gain** through the existing `PassageOutput.setVolume`
  seam. `AudioTrack.setVolume(gain)` is a linear gain clamped by the platform
  to `getMaxVolume()` (documented: "the word 'volume' is historical; this is
  actually a linear gain"), so values > 1.0 amplify signal without touching
  PCM — no per-frame copy/alloc on the hot synthesis path.
- **Gain survives track rebuilds.** `AudioTrackPassageOutput` now stores the
  multiplier in a private field and re-applies it in `play()` after every
  track build/swap, so passage boundaries that rebuild the static track
  (rate/capacity change, prearm swap) no longer reset to unity. This also
  fixes the latent ducking-loss bug: ducking was applied to the current track
  but silently lost on the next passage's rebuilt track.
- **Ducking scales.** `applyPlaybackVolume()` in `PlaybackService` computes
  `settings.playbackGain × (ducking ? DUCK_VOLUME : 1)` and is called at
  every play-start path (`startPlayback`/`resumePlayer`/`changeVoice`) and
  on audio-focus gain/duck transitions. The clamped `[0, 1]` guard is
  replaced with `coerceAtLeast(0f)` (negative would throw).

## 128. applicationId `io.github.moronigranja.ayvu` (2026-09-07)

Store/device identity renamed to the app name (`Ayvu`, decisions #43) under the
namespace the owner actually controls — `io.github.moronigranja` (the GitHub
account owning the repo) — instead of the `com.moronigranja.*` reverse-DNS of a
domain he does not own (`moronigranja.com`). Play does not verify domain
ownership, but the owned-identity convention is safer: an unowned ID only has
to collide once with another publisher to be permanently blocked storewide.

- **AGP split:** `applicationId` and `namespace` are decoupled. `namespace`
  stays `com.moronigranja.localttsreader` (decisions #1) — all Kotlin packages,
  imports, R-class paths, and the ktlint baseline are untouched, so the change
  is one line, not a 300-file refactor. Custom action `ACTION_IMPORT_BOOK`
  (`IntakeRouting.kt`, manifest) follows the new identity — package-qualified
  and internal, matched on both sides.
- **Runtime follow:** data dir, `SharedPreferences`, Room file, and the
  `${applicationId}.androidx-startup` authority are all keyed by applicationId
  and move with it; uninstall/reinstall only, no migration needed at v0.1.0
  (nothing released).
- **Alternatives:** keep `com.moronigranja.localttsreader` (fine, but not the
  app's name and still an unowned domain); `com.moronigranja.ayvu` (unowned
  domain). Chosen: `io.github.moronigranja.ayvu`.
- Timing: pre-release (versionCode 1, unsigned CI builds), so the rename is
  free of user-visible cost. A change after first release would orphan existing
  installs/data — that is the reason to decide it now.
- **Follow-up (same day, release sanity pass):** the relative manifest
  component names (`.MainActivity`, `.LocalTtsReaderApp`) resolve against
  `applicationId`, so the renamed build failed to launch (Error type 3 —
  `io.github.moronigranja.ayvu.MainActivity` does not exist). Both are now
  fully-qualified (`com.moronigranja.localttsreader.MainActivity` /
  `LocalTtsReaderApp`), decoupling manifest components from the app id for
  good. `ExternalIntakeInstrumentedTest` composes
  `ComponentName(targetContext.packageName, <full class>)` and needed no
  change. On-device sanity pass of the signed 0.1.1 build (v0.1.1/code 2):
  install, launch, pack download + checksum verify (3/3 Ready), VIEW-intent
  import, library + reader render, playback through Kokoro (the engine
  saturates the CPU — UI input lags during synthesis bursts and recovers
  between passages).

## 127. G2 paragraph context menu: long-press Play from here / Copy text (2026-09-06)

Play-at-passage returns as G2's long-press menu (roadmap G2, decisions #122
superseded the middle double tap). Long-press any rendered paragraph on the
reader page opens a `DropdownMenu` anchored at the press point with **Play from
here** (the same `ACTION_PLAY_POSITION` command the bookmark jumps and
play-from-view use) and **Copy text** (the passage text, via the modern suspend
`Clipboard.setClipEntry`/`ClipEntry` surface — BOM 2026.06.01, `ClipEntry`
wraps a native `ClipData` on Android).

- **Three-way discrimination** (decisions #96, against B3, not beside it):
  the single `awaitEachGesture` loop races `awaitPointerEvent()` against a
  per-down long-press deadline (`LocalViewConfiguration.longPressTimeoutMillis`,
  captured once per gesture — the `detectTapGestures` deadline model, so
  in-slop jitter cannot extend it). Deadline wins → menu; quick up → tap
  (side zones turn the page, middle feeds the double-tap window unchanged);
  drag past `SWIPE_PAGE_THRESHOLD` before up/deadline → page turn. A complete
  swipe is never pre-empted by the deadline.
- **Menu-open page is inert**: the `pointerInput` block is keyed on the menu
  state and early-returns while it is open, so an outside tap-away dismisses
  the menu instead of turning the page (the classic DropdownMenu-inside-a-tap-
  surface trap). The B3 middle-zone pressed-passage highlight is untouched;
  the menu's own feedback replaces it on long-press.
- **No `state.positioned` guard on the menu's passage mapping** — a freshly
  opened, never-played book can still "Play from here"; the B3 highlight keeps
  its guard.
- **Watch item carried forward**: the immersive `passageAt` `topInset`
  (decisions #125) was pixel-verified for rendering, not tap-mapping; the
  menu shares the mapping, so boundary-adjacent long-press in immersive is
  B3's own gauge and gets checked on the S22 pass.

Host verification: Docker `testDebugUnitTest ktlintCheck assembleDebug` green
(no behavior test — the slice is Compose gesture code with no host-testable
surface; device pass per roadmap G2). Feature plan:
`docs/features/paragraph-context-menu.md`.

**Device-verified (S22, 2026-09-07):** long-press opens the menu anchored at
the press point (the pass caught and fixed the anchor: the modifier
`Modifier.offset` rendered the popup at the origin — the `offset = DpOffset`
param is the correct anchor); Play from here starts at the pressed passage
(top/bottom of a multi-passage page → 212 vs ~219, not the narrated passage);
Copy text puts the exact passage on the clipboard (pasted on-device, matches
DB passage byte-for-byte); tap-away dismisses without turning; tap / double-tap
/ swipe discrimination intact; immersive + rotation safe. TalkBack pass
deferred (B4).

## 126. Release distribution: GitHub Releases APK, manual local signing (2026-09-05)

First public distribution decision. The app publishes as a signed APK on
GitHub Releases; there is no Play Store / F-Droid listing yet.

- **Why GitHub Releases**: the repo and its pack distribution already live
  there (Kokoro/espeak-ng/tessdata packs ship from the same project's
  releases); no store account/listing/Data-Safety paperwork gates a first
  release. A Play listing (AAB) can follow later without rework — the
  versioning/signing below carries over.
- **Signing stays manual and local** (the existing "release signing stays out
  of the repo" stance): the release keystore lives at
  `~/.android/ayvu-release.jks` (PKCS12, alias `ayvu`, 4096-bit RSA) and is
  wired through `keystore.properties` (gitignored, chmod 600).
  `app/build.gradle.kts` only adds the `release` signingConfig when that file
  exists, so CI's `assemble-on-tag` gate still builds the release variant
  (unsigned) in the toolchain image. `tools/release.sh` builds the signed
  APK, verifies the signature with apksigner, and creates a DRAFT GitHub
  release (`--publish` makes it live).
- **Unminified for 0.1.0**: `isMinifyEnabled = false`. R8 would need shrink
  rules + a device pass for the reflection-heavy stack (Hilt, JNA, ONNX
  Runtime); the first release trades APK size for a crash-proof runtime.
  Revisit with a shrink-rule + regression pass.
- **Universal APK, no ABI splits**: one APK ships all ABIs
  (arm64-v8a/armeabi-v7a/x86_64); ABI-split APKs are a later optimization if
  size becomes a store constraint.
- **Versioning**: release tag == `versionName`; `versionCode` increments per
  release. The first release keeps `0.1.0`/`1` (no earlier public release to
  bump over); installing over an existing DEBUG build requires uninstall
  anyway (different signing key) — versionCode is irrelevant to that.
- **Attribution**: `NOTICE.md` lists the bundled runtimes (ONNX Runtime MIT;
  JNA Apache-2.0 OR LGPL-2.1-or-later; AndroidX/Kotlin Apache-2.0) and the
  runtime-downloaded packs (Kokoro Apache-2.0, espeak-ng GPL-3.0-or-later
  with toolchain exception, OCR tessdata Apache-2.0) — revisited each
  release.
- **Still open** (roadmap "Release readiness"): a full Play-listing package
  (Data Safety, store privacy, screenshots/listing, AAB) is deferred until a
  store listing is actually wanted; roadmap A8 (FS-side Room data deletion)
  remains a device-investigation item — the quarantine+rebuild mitigation is
  the ship-with behavior.

## 125. Immersive top-cut: the floating title overlay clipped tall first lines — reserve + offset fix (2026-09-05)

Device finding (S22, immersive): the top of the text was cut "sometimes" —
most visibly on a NEW CHAPTER page, where the LARGE chapter title's glyph
tops were dimmed/hidden under the translucent book-title overlay band. Body
text (smaller font) only grazes the band's edge and read fine, which is why
it looked intermittent.

Two distinct causes, both fixed:

1. **Standing layout** — the plan's "the title overlay enters no
   `reservedPx`" decision meant page content started at the body top, under
   the floating band (band ≈ measured `labelLarge` title + 2 × SM padding,
   ~105 px). Any first line whose glyphs rose into the band got its top
   dimmed; the taller chapter title (page 0 of every chapter) was the worst.
   Fix (5c0a7cb's follow-up, this entry): in immersive the body RESERVES the
   measured band height in BOTH `linesPerPage` calls AND the body Column is
   padded down by that same height, so the first rendered line (chapter
   title or body) always starts below the band. The tap-mapping `passageAt`
   offset gains the same inset and the pointer handler re-keys on
   `immersive`. Regular mode keeps the floating overlay unchanged (no
   reserve, no offset). Net immersive line count drops by ~3-4 lines/page —
   accepted: correct text beats marginal density; the reflow place-keeping
   re-clamps on toggle as before.
2. **Transition** (already fixed in 5c0a7cb) — the animated system-bar
   hide/show reflowed the body under a half-faded bar for a few frames; the
   chrome now holds through `SYSTEM_BARS_ANIM_MS = 350` ms in both
   directions (`showChrome`/`showOverlays`/`barsSettled`).

Verified on-device (S22): chapter-start and mid-chapter pages in immersive —
first body row now at y ≈ 200+ with a clear gap under the band; the large
chapter heading fully rendered (y ≈ 146 vs previously peeking into the band
at y ≈ 57-72); page turns and rotation unaffected.

## 124. Realtime capability measured from Preview, persisted tri-state (2026-09-04)

Item 8 of the immersive plan (D2 family): NO dedicated probe step — a probe
pays 25-60 s of engine open + synthesis on exactly the devices where the
answer is negative (HiBreak cold open ≈ 25 s, RTF ≈ 3), and both reference
answers are already measured (S22 0.66-0.77, HiBreak 2.84-3.12). Instead RTF
is measured from real synthesis:

1. **Preview** (the C2 audition) records every sample: wall-clock vs rendered
   audio duration, ACCUMULATED in persistence (`rtf_wall_ms`/`rtf_audio_ms`
   Longs, no migration). A single ~1-2 s preview never crosses the gate — it
   only contributes (#93: short probes overstate RTF).
2. **Live passages** in `PlaybackService` record the same pair on the
   synthesized path while the tri-state is unmeasured — the lazy fallback for
   users who never preview.
3. **Tri-state** (`AppSettings.Snapshot.realtimeCapable`): `null` under 10 s of
   audio (keep today's behavior), `true` at wall ≤ audio (realtime), `false`
   slower. `bufferForPlayback` skips the look-ahead wait when realtime (the
   current passage resolves from the synchronous synthesis; the hard cap
   stays); slow/unmeasured keep today's path byte-for-byte. Degraded
   (system-TTS) is excluded from the gate — its engine is outside the Kokoro
   measurement and the buffer wait treats it as-is. D1 replaces the hook's
   insides later; the seam (settings read + branch) stays.

## 123. Pregen yield is conditional on the engine, not the session (2026-09-04)

Item 5: the G2 blanket yield (decisions #42 family) — pause the whole manual
run for the whole playback session — is superseded by a conditional yield.
Manual pre-generation now advances while playback is fully cache-fed
(buffer/queue/disk resolve the active + look-ahead passages) and pauses at
the next passage boundary only while playback actually HOLDS the shared
engine ([PlaybackActive.engineInUse]: a synchronous buffer synthesis or the
fill job's session). Playback > pregen priority; a cold seek waits at most
for one in-flight pregen passage (per-batch cancellable via
`shouldContinue`). Worst case on a forever-held engine the run waits and
resumes — it never aborts. Runtimes keep their progress notifications; the
run's terminal posts a non-ongoing `NotificationManager.notify` (throttled
~1 s in-run via in-place refresh instead of per-second `setForeground`
re-binds), so a finished run leaves a trace.

## 122. Immersive reader chrome: overlay title + minimal player, reflow accepted (2026-09-04)

Item 1: a middle DOUBLE tap toggles an immersive mode — system bars hide
(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, restored on exit/dispose/rotation),
the top bar and the full PlayerCard drop, and two slim overlays take their
place: the book title (labelLarge, centered, semi-transparent surface,
drawn OVER the page — it never enters `reservedPx`, so pagination is
unchanged by it) and a bottom minimal player (play/pause + the thin
two-tone progress line + the item-4 passage indicator). Dropping the bottom
card GROWS the body: pages re-derive on toggle (accepted reflow — more text
per page), with the reading place preserved by re-deriving the page from the
top visible line of the OLD page (geometry remembered across the toggle).
Middle-tap-play ("listen from here", S3) is superseded: the middle DOUBLE tap
now toggles chrome (a single middle tap does nothing); play-from-here
returns as G2's long-press menu. The
pressed-passage highlight stays (the three-way discrimination surface, G2).
ReaderScreen's gesture doc comments updated in the same change.

## 121. Follow keys on the ACTIVE SENTENCE with a manual-turn grace period (2026-09-04)

Item 3: page-follow previously keyed on the passage's START line — a long
paragraph narrated across a page break pinned the page until the whole
passage ended. Follow now re-keys on the active sentence's line:
`activeSentenceRange` → char offset → `getLineForOffset` →
`TextPagination.pageOf`, extracted as one `activeSentencePage()` shared by
both follow effects (PLAYING/LOADING and the paused-reposition path). A
manual page turn now STOPS playback (item 4: in-chapter turns pause via the
phase-ref'd gesture handler; chapter-boundary turns stop through
`openChapter`), so no grace period is needed — after a hand turn the
paused-follow effect sees the same (chapter, passage, active sentence)
triple and dedupes, leaving the turned page alone. Both follow effects
dedupe on that triple: a chrome toggle or rotation re-measures pagination
and re-fires them, but an unchanged triple makes the restart a no-op, so the
page never yanks back to the highlighted passage (item 1).

## 120. Book-wide passage indicator replaces page numbers (2026-09-04)

Item 4: the reader's footer drops "Page N of M" (per-chapter, churned by
every font/viewport/immersive change) for a book-wide `Passage X/Y (P%)` in
BOTH modes. `PlaybackUiState` gains `bookPassageIndex`/`bookPassageCount`
populated in `PlaybackService.stateCopy()` from the already-resident
in-memory book (chapter prefix sums — no Room query, no cache); the label
format lives in one pure helper (`passageIndicatorLabel`, unit-tested):
0-based index, 1-based line, percent clamps at 100, count 0 → hidden
(cold-open before the book loads). The pagination reserve proxy measures the
widest renderable label, so the footer can never wrap past its reserved
height.

## 119. C1 reversal — voice selection moves after the packs download (2026-09-04)

Owner call with the immersive-reader plan (item 7): the guided setup
now presents **PRIVACY → DOWNLOAD_PACKS → CHOOSE_VOICE → IMPORT_BOOK**
(full plan), reversing C1's original "choose language and voice BEFORE
downloading" (roadmap C1, decisions #102.4).

**Why:** the choice carried no product value before the packs landed — all 54
voices ship in one `kokoro-voices` pack (`voices-v1.0.bin`, 28 MB), so
"pre-download voice choice" was only a persisted preference, and the voice
step needed the pack to enumerate names at all. Moving voice selection AFTER
the download is what makes **Preview work during selection**: the engine is
already open and the selected voice is a cached, instant switch. The degraded
path (PRIVACY → CHOOSE_VOICE → IMPORT_BOOK) has no download step and is
unchanged, as is the presentation-only nature of the change — no asset
difference, no Room migration.

**Implementation:** `SetupState.derive` full-plan branch reordered (core-tts;
the single shared table); `SetupStateTest` full-plan expectations updated
(the 5 existing rules otherwise unchanged); roadmap C1 text updated. The
wizard (item 6) is built against this order.

## 118. F4 — import overlay inside the library: one surface for every entry point, progress + stage; ExternalFileActivity removed (2026-09-03)

Follow-up to #117 (same day): the standalone `ExternalFileActivity` gateway is
deleted. The import surface is now an **overlay rendered on the library
screen**, shared by EVERY entry point — the in-app SAF picker, folder import,
a file-manager "Open with Ayvu" (ACTION_VIEW), and forwarded book-file shares.

**Why:** a separate launcher-less activity felt detached from the library the
import lands in; its "Open library" button also hit the system resolver (an
app-chooser bug on some launchers) because bare MAIN+LAUNCHER intents can
resolve to the gateway itself. Folding the intake into the library removes
both the UX split and the navigation bug.

**Changes:**
- `feature-library/ExternalFileActivity` deleted. MainActivity (launchMode
  singleTop) now carries the ACTION_VIEW + `ACTION_IMPORT_BOOK` filters and
  dispatches the file via the shared activity-scoped `LibraryViewModel`
  (`intakeUri` — extension gate + batch importer, same as the picker).
- `feature-library/IntakeOverlay` — ONE composable for every import:
  determinate per-file progress bar, a **stage status** (reading → parsing →
  saving → indexing) surfaced from a new `ImportCoordinator.ImportStage`
  enum threaded through `import`/`importAll`, the typed batch summary, and
  kfx/DRM/unsupported guidance. No "Open library" button (the library is the
  surface; the standalone activity that needed it is gone).
- LibraryScreen renders the overlay; the old inline progress rows, the
  separate result AlertDialog and the completion snackbar were removed (one
  import surface, one code path).
- `Importing` carries the stage; call sites updated (SetupViewModel,
  ImportCoordinatorTest, LibraryViewModelTest).

**Host:** `:core-ebook:test` + `:feature-library:test` + `:feature-share:test`
+ app unit tests green; `checkFeatureBoundaries` green; baseline-gated
`ktlintCheck` green (baseline refreshed to the edited tree).

**Device (S22, 2026-09-03):**
- `ExternalIntakeInstrumentedTest` 2/2 OK (VIEW import lands a row in the real
  `local-tts-reader.db`, re-import dedupes to one row; kfx imports nothing).
- Manual: VIEW import shows the overlay in-place over the library — "Import
  complete / Added 1", dedupe re-import "Added 0 · Unchanged 1", a fresh second
  book "Added 1"; stays on the library, no resolver/app chooser.

## 117. F4 — external file intake shipped: ACTION_VIEW gateway + book-file share routing through the one importer, device-verified on the S22 (2026-09-03) F4 — external file intake shipped: ACTION_VIEW gateway + book-file share routing through the one importer, device-verified on the S22 (2026-09-03)

F4 ("open from file manager + share a book") is complete. Both entry points
land the file in the library through the ONE existing `BookImporter` batch
(F1/F3 machinery — no second import path), the `EBookFormats.parserFor`
extension gate is the single backstop, and unsupported/`.kfx`/DRM files get
typed guidance, never a silent no-op.

**Routing (pure, host-tested — `core-ebook/IntakeRouting.kt`):** `resolveFile`
gates a gateway file by extension (supported → import; `kfx` → DRM guidance;
else → "format not supported"); `routeSend` triages ACTION_SEND — a stream
with a supported name or an ebook container MIME forward to the import
gateway, everything else (text/image shares) keeps the S2 resolve path.

**Entry points:**
- `feature-library/ExternalFileActivity` — the ACTION_VIEW gateway ("Open with
  Ayvu" in any file manager) and the recipient of forwarded book shares. Exported,
  never in recents; covers epub/mobi-family/md/txt plus octet-stream (file
  managers type books inconsistently — the extension gate is the backstop);
  reuses the gateway's own `LibraryViewModel.import` (F1 progress + typed
  summary) and shows guidance for unsupported formats.
- `feature-share/ShareReceiverActivity` — ACTION_SEND triage: book files
  forward to the gateway via `IntakeRouting.ACTION_IMPORT_BOOK`
  (package-qualified — no share-sheet duplicate, no feature-to-feature edge,
  A6); text/image shares resolve exactly as before (S2).

**Host:** `IntakeRoutingTest` (13 cases: gate, kfx, blank names, SEND triage
text/image/unknown-document, octet-stream with no name); `:core-ebook:test`,
`:feature-library:test`, `:feature-share:test`, `:app:assembleDebug`, and
baseline-gated `ktlintCheck` all green (the spike-tts module's accumulated
formatting debt was also cleared to make ktlintCheck fully green).

**Device (S22, 2026-09-03):**
- `ExternalIntakeInstrumentedTest` (2/2 OK): a real `startActivity` ACTION_VIEW
  with an epub in the app's own files dir imports (row appears in the real
  `local-tts-reader.db`) and re-import of the same content hash dedupes to
  exactly one row; a `.kfx` file imports nothing (typed guidance).
- Manual adb pass: the manifest VIEW filter resolves to `ExternalFileActivity`;
  file-manager-shaped VIEW import → "Import complete / Added 1"; a second VIEW
  and an ACTION_SEND book-file share (epub MIME + `EXTRA_STREAM`) both showed
  "Already in library 1" — one row total across all three entries (content-hash
  dedupe holds across entry points). A shell-grant-unsupported run surfaced the
  typed "could not read file" failure (the pipeline never silently no-ops).

Phase F is now complete (F1–F4).

## 116. D2 — 2-engine parallel pre-generation measured on the S22: serial wins; ORT-android is run-to-run nondeterministic at the PCM level (2026-09-03)

The D2 additions' 2-engine parallel pregen leg (roadmap D2, candela-derived;
"two Kokoro ORT sessions with separate thread pools synthesizing independent
passages vs the serial baseline") ran on the S22 (SM-S908U1, ORT-android
1.29.0, 6 intra-op threads/session, screen-off instrumented). **Verdict: the
adoption bar — ≥1.5× pregen throughput without breaching the memory envelope
or the oracle gate — is NOT met; serial is FASTER and the two-session memory
cost is real.** The parallel-pregen idea is closed as measured.

**Harness** (`spike-tts` only, measurement-only; default deployment unchanged):
`PregenParallelRunner` + `PregenParallelBenchmarkTest`. Corpus
`corpus_pregen.tsv` is host-precomputed (D3 pattern): 16 passages
(8 en-us, 8 pt-br) ≈ 370 s audio from Gutenberg Pride & Prejudice #1342 +
Dom Casmurro #55752, phonemized with the pip `phonemizer` espeak backend
(validated byte-for-byte against the staged 2-passage corpus before scaling).
Host tool: `tools/gen_pregen_corpus.py`.

**Results (S22, wall = min of 2 runs per leg):**

| Leg | audio | wall | RTF | thp (audio-s/s) | VmHWM | PSS |
|---|---|---|---|---|---|---|
| serial vs | 369.9 s | 255 014 ms | 0.689/0.748 | 1.43 | 1.59 GB | 1.50 GB |
| parallel | 369.9 s | 305 732 ms | 0.841/0.826 | 1.21 | 2.86 GB | 2.79 GB |

Speedup serial/parallel: **1.18× (parallel slower)**. Parallel costs +76 %
VmHWM and +84% PSS for the second session — the S22's 8 cores are already
saturated by one 6-thread session; two oversubscribe and contend.

**Determinism finding (surprising, harness-critical):** the serial leg
re-synthesized the corpus twice on the SAME engine; run1-vs-run2 PCM diff was
mean 0.011, **max 0.791** across all 16 passages. ORT-android CPU (mlas
multi-thread) is run-to-run nondeterministic at the sample level: ~2/3 of
passages drift ~0.0025 mean / 0.1–0.23 peak, and a few flip AudioTrim's
silence threshold — shifting those passages by 240–1200 samples (0.46–0.65
peak when compared misaligned). Consequence: the #67 0.001 PCM-oracle gate
cannot be applied at intra-run granularity on this stack — it remains valid
for execution-provider/model-precision comparisons only when the oracle is FRESH
(D2/D3 did exactly that: a fresh CPU oracle per candidate, so those verdicts
stand). This leg's "rejected" oracle was one divergent run vs another, not a
parallel-path bug.

Evidence: `docs/prints/parallel-pregen/` (kokoro_pregen_parallel.json +
worst-passage WAVs serial/parallel + corpus_pregen.tsv). Re-run: generate the
corpus, stage per build.md, `am instrument -e class
…PregenParallelBenchmarkTest`.

## 115. D2 — Hexagon NPU (QNN EP) spike measured: runtime wiring works, the fp32
Kokoro graph is the blocker; Gen-5 CPU already >2× realtime (2026-09-03)

Roadmap D2's accelerator leg (ideas row "Accelerator delegates for TTS"; the
"one runtime" rule stands) got a real device datapoint on the actual target
flagship: **SM-F971B on SM8850 "canoe" (Snapdragon 8 Elite Gen 5, Hexagon v81
— not v79)**, with the S22 (SM8450) re-run as the baseline control. The
on-device NPU path was built end-to-end in `spike-tts` and exercised to the
silicon; the conclusion is a measured negative on speed with a precise,
model-side remaining blocker.

**Harness changes (`spike-tts`, measurement-only; default deployment stays CPU):**

- New D2 provider candidate `qnn-htp` in `KokoroBenchmarkRunner`: stock
  ORT-android 1.29 + Qualcomm's plugin EP
  `com.qualcomm.qti:onnxruntime-android-qnn:2.5.0` +
  `com.qualcomm.qti:qnn-runtime:2.49.0` (both Maven Central — no QAIRT SDK
  login, no custom ORT build; plugin EP registered via
  `registerExecutionProviderLibrary` + `addExecutionProvider`).
- `spike-tts` minSdk 26 → **27** (the plugin AAR requires it; harness-only).
- `useLegacyPackaging = true` — the DSP loader and dlopen-by-path need the
  `libQnnHtp*`/skel `.so` files extracted to the app lib dir, not FUSE-in-APK.
- Manifest `<uses-native-library>` for `libcdsprpc.so`/`libadsprpc.so` —
  without it the HTP stub cannot link in the app's linker namespace and
  `QnnDevice_Create` fails `QNN_DEVICE_ERROR_INVALID_CONFIG` (same symptom as
  onnxruntime-qnn#715 → qualcomm/fastrpc#379).
- Absolute `backend_path` to `libQnnHtp.so` (the EP derives
  `ADSP_LIBRARY_PATH` from it; a relative name yields an empty path).
  `htp_arch` is not set — EP 2.5.0's parser rejects v79/v81 values; the
  backend auto-detects (v81 skel loads on the CDSP, domain 3, confirmed in
  logcat). `soc_model=87` (SM8850 per `soc_utils.cc`) is optional on real
  hardware ("ignoring on real target").

**Measured (fp32 Kokoro, 2-passage corpus, screen-off instrumented, ORT 1.29.0;
JSONs in `docs/prints/qnn/`):**

| Provider | SM-F971B (SM8850) RTF | S22 (SM8450) RTF | Verdict |
|---|---|---|---|
| CPU (intra-op 6) | **0.43–0.66** | 1.17–1.19 | baseline |
| XNNPACK | 0.72–0.74, oracle-rejected | 1.24–1.29, rejected | slower than CPU |
| NNAPI | 0.61–0.63, oracle-rejected | 0.60–0.70 | no win |
| qnn-htp | 0.60–0.63, oracle diff **0** (pure CPU fallback) | same INVALID_CONFIG → CPU | **no NPU offload** |

1. **The 8 Elite Gen 5 CPU alone retires the S22's live-synthesis pain point:**
   RTF 0.43–0.66 vs the S22's 1.17–1.19 — comfortably realtime with ~2×
   headroom before any accelerator. XNNPACK/NNAPI remain slower than plain CPU
   on every device measured; NNAPI keeps the silent-fallback smell.
2. **The QNN stack is proven working to the silicon** (backend loads, skel
   loads on the DSP, per-op validation runs) — the blocker is the model:
   the fp32 export's `StridedSlice` ops (text encoder + iSTFT generator) fail
   `backendValidateOpConfig` (error 3110) and the fusion-constraint check
   rejects the graph, so ORT assigns 100% of nodes to CPU. The qnn-htp RTF is
   exactly CPU + partitioning overhead; oracle diff 0 proves identical output.
3. **Remaining path to a real NPU number is model-side, not runtime-side:**
   a static-shape Kokoro re-export (fixed 512-token windows, Slices folded
   into host-side preprocessing), oracle-gated as usual. Until then the
   accelerator question stays "no measured win", and its only credible payoff
   (battery/thermal on long sessions) remains unmeasured and unblocked-by-us.
4. Translation models stay out of scope for delegation (per #114's cost table:
   small, launch-overhead-bound graphs gain nothing from HTP).

Re-run: `tools/docker-build.sh :spike-tts:assembleDebug :spike-tts:assembleDebugAndroidTest`,
install both APKs, stage packs (build.md §"Kokoro on-device benchmark"),
`am instrument -e class …KokoroDeviceBenchmarkTest`, read
`kokoro_results_qnn-htp.json`. Result archiving: `docs/prints/qnn/`.

**Precision × NPU cross measured same day** (fp16/int8/q8 graphs through the
qnn-htp EP, oracle-gated vs fp32-CPU; `runQnnPrecision` in the runner, JSONs in
`docs/prints/qnn/`): quantization does NOT unblock the NPU. int8 and q8 fail
the identical `StridedSlice` backendValidateOpConfig (error 3110) — the
dynamic-shape Slices survive Q/DQ quantization — and measure as pure CPU
fallback (qnn-htp-int8 RTF 1.11/1.41 ≈ its own int8-CPU leg; oracle diffs
0.60/0.82 are the known quantization divergence, not HTP output). The fp16
graph produced a 0.25 s stub in 30 s on ANY EP (the known broken-upstream fp16
export, #86) — no NPU signal. Side finding: on the SM8850 CPU, int8/q8 are
SLOWER than fp32 (RTF 1.07–1.41 vs 0.43–0.66) — QDQ overhead loses on this
SoC, closing the quantization angle for Kokoro on strong devices entirely.
The single remaining NPU path stays the static-shape re-export.

**Prior-art sweep (2026-09-03):** no ready-made static-shape/QNN Kokoro export
exists — Qualcomm AI Hub has no Kokoro at all; the one "NPU-quantized" HF repo
(magicunicorn) targets AMD Ryzen-AI XDNA, not Hexagon; taylorchu's optimized
exports and kokoro-onnx/sherpa releases are all dynamic-shape CPU/GPU builds.
The equivalent work EXISTS for Apple ANE, which has the same static-shape
constraint: **laishere/kokoro-coreml** (Apache-2.0, active) splits the model
into 7 stage models with pinned dimensions (`--max-frames`, fp16 mainline),
keeps phase-critical stages (SineGen cumsum/sin, iSTFT tail) in fp32 off-accel,
solves the vocoder fp16-accumulation problem with a dual-output graph anchor,
and reports 25× realtime on M4 / 17× on iPhone 16 Pro. That repo is the
transferable blueprint for a Hexagon export: same stage-splitting (our
`StridedSlice`/alignment ops become their own static graph), same fp16-main +
fp32-tail precision surgery, then ORT-QNN AOT to a per-SoC context binary.
Why nobody has done it for Hexagon yet: the kokoro-onnx ecosystem is CPU-first,
the from-app QNN path only became installable this year (plugin EP, Aug 2026)
and the vendor skel/version coupling is fragile (fastrpc#379), and Android
flagship CPUs already run Kokoro realtime — the NPU's only remaining payoff is
battery/thermal on long sessions, which no public project has measured.

## 114. Phase J — offline NMT spike measured; small100 int8 adopted for translate-then-read (2026-09-02)

Roadmap Phase J (promoted 2026-09-02 from the "Later" translation row, decisions
#101) ran its measurement spike on the S22: the per-pair OPUS-MT direction A/B'd
against the single many-to-many M2M-100-418M across four source→target pairs
(it→es, en→pt-br, en→it, es→en), fp32 and dynamic-int8, through the `spike-tts`
harness on ORT-android 1.29.0. Harness: `TranslateProbeRunner` +
`TranslateProbeBenchmarkTest`; host tooling `tools/export_nmt_onnx.py` (export +
parity + int8), `tools/gen_nmt_inputs.py` (FLORES-101: 20 dev sentences per pair
for chr-F, 10 ~120–250-token devtest passages for wall time), `tools/nmt_chrf.py`
(host-side sacréBLEU chr-F over the device's recorded token ids). All model
graphs are runtime downloads (decision #7); the scripts + pins
(`m/nmt/manifest.json`: HF revisions + sha256 + sizes) are reproducible.

**Device cost table (S22, ORT-android 1.29.0; per-pair chr-F on the 20-sentence
FLORES dev slice; passages are the wall-time leg):**

| Leg | int8 disk (MB) | open ms (enc/dec/dec+p) | dec ms/token | leg PSS MB | chr-F |
|---|---|---|---|---|---|
| opus-mt-it-es fp32 | 632 | 1.0k/1.3k/1.2k | 10.7 | 1009 | 52.2 |
| opus-mt-it-es int8 | 159 | | 68.2 | 380 | 52.6 |
| opus-mt-tc-big-en-pt fp32 | 1745 | 2.6k/5.7k/5.7k | 26.9 | 2270 | 66.5 |
| opus-mt-tc-big-en-pt int8 | 438 | | 146.4 | 689 | 67.4 |
| opus-mt-en-it fp32 | 761 | 2.6k/1.8k/1.8k | 13.8 | 1204 | 57.5 |
| opus-mt-en-it int8 | 192 | | 110.1 | 434 | 57.7 |
| opus-mt-es-en fp32 | 668 | 1.1k/1.3k/1.2k | 14.0 | 1037 | 63.2 |
| opus-mt-es-en int8 | 169 | | 95.3 | 380 | 63.1 |
| m2m100-418M fp32 | 4753 | **not stageable — lmkd kill** | | | |
| m2m100-418M int8 | 1201 | 2.6k/1.8k/1.8k | 23.7–30.6 | ~1400 | 52.3–62.2 |

chr-F details (sacréBLEU `--remove_whitespace`): opus fp32→int8 is a wash
(it-es 52.2→52.6, en-pt-br 66.5→67.4, en-it 57.5→57.7, es-en 63.2→63.1);
M2M-100 int8 trails the per-pair baselines on three of four pairs
(en-pt-br 62.2 vs 67.4, en-it 53.3 vs 57.7, es-en 58.3 vs 63.1; it-es 52.3 vs
52.6 roughly tied). Every leg finite, every sentence item reached EOS.
en→pt-br FLORES ref is European pt; the owner's blind read stays the
authoritative pt-BR gate (decisions #101) — the ~5-chr-F gap against the
pt-specialist tc-big model is consistent with that caveat, not a verdict.

**Findings:**

- **M2M-100 fp32 fails the memory gate outright**: 4.75 GB of graphs; lmkd
  killed the probe process mid-leg (the per-leg flush preserved partial
  results — the #93 lesson paying off). It is not a device candidate at fp32.
- **M2M-100 int8 runs** (1.2 GB, ~24–31 ms/token — FASTER per token than the
  int8 Marian graphs, 68–146 ms/token, but with ~3.7× the Marian-base PSS
  at ~1.4 GB) but its chr-F trails the per-pair baselines on 3/4 pairs.
- **Pack-count cost is the decisive asymmetry**: 4 per-pair int8 packs ≈ 958 MB
  now vs 1201 MB for the one many-to-many pack — comparable today, but the
  per-pair cost grows with every future direction (each +~170–440 MB and a new
  quality gate) while the single model stays fixed. Against that, M2M-100's
  memory (≈1.4 GB PSS leg peak, 3.7× a Marian-base leg) and its 3/4-pair chr-F
  deficit are paid on EVERY translation, not amortized.

**Owner answer (measured, 2026-09-02): the single all-language model costs too
much — DEFER M2M-100.** The preference "one model for all languages if it does
not cost too much extra" (owner, in-session) does not survive contact with the
device: fp32 cannot load, int8 costs ~3.7× the memory of a per-pair baseline
leg on every run and loses chr-F on 3 of 4 measured pairs. The per-pair
OPUS-MT direction (decisions #101) stands; the roadmap keeps translation as a
per-pair pack design with a quality gate per direction. If a future model
(NLLB-200 remains license-blocked, #101; SMaLL-100 untested — MIT) measurably
holds per-pair chr-F at Marian-base memory, the spike's harness is reusable
as-is.

Contract note for any future port: M2M-100's HF generate() conditioning is
decoder_start=eos + FORCED first bos = target lang id; feeding the lang id as
the decoder start degenerates to single-token loops ("the the the"), and the
model's own argmax after eos is pair-dependent (often `__fr__`) — the forced
bos must be applied, not predicted. `TranslateProbeRunner` feeds
`decoder_start` tokens sequentially and discards their argmaxes, which also
matches Marian's `[pad]` start; device behavior was reproduced host-side
before the fix (int8 ORT greedy == PyTorch generate, token-identical on 7/8
samples with the eighth diverging only at the 128-token length cap).

**Owner-requested extension (same session): SMaLL-100 legs added even though
M2M-100 int8 passed, for a direct speed/quality comparison**
(`alirezamsh/small100`, MIT). First measurement was INVALID and discarded:
inputs had been tokenized with `AutoTokenizer`/`M2M100Tokenizer`, but small100
requires its repo-local `SMALL100Tokenizer` (MBART-style: the TARGET lang id is
PREPENDED to the source, `[tgt_lang, X, eos]`; AutoTokenizer silently falls
back to `M2M100Tokenizer`, which prepends the SRC lang). The mis-conditioned
outputs produced literal artifacts (translations opening "adget", "adgeting" —
owner-caught) and chr-F 15.6–31.0; that was our harness bug, not the model.

**Corrected measurement** (`SMALL100Tokenizer(tgt_lang=...)`, decoder_start=eos
with no forced bos — the model's own argmax after eos is the first real token;
host parity PyTorch == ONNX int8 greedy, token-identical 8/8 samples across the
four pairs; fp32 3.6 GB / int8 915 MB graphs):

| SMaLL-100 leg | dec ms/token (fp32 → int8) | leg PSS (fp32 → int8) | chr-F (fp32 → int8) |
|---|---|---|---|
| it→es | 25.5 / **8.9** | 3641 / 1082 MB | 52.6 → 52.6 |
| en→pt-br | 26.4 / 9.8 | 3643 / 1061 MB | 63.2 → 62.1 |
| en→it | 28.2 / 9.9 | 3637 / 1072 MB | 52.0 → 51.9 |
| es→en | 27.7 / 9.8 | 3617 / 1059 MB | 58.3 → **59.1** |

With conditioning fixed, SMaLL-100 is a different model than the invalid run
suggested: chr-F 51.9–63.2 (was 15.6–31.0), statistically the same class as
M2M-100-418M (52.3–62.2) — and it **dominates M2M-100 on every axis**: ~2.5×
faster decode (9.8 vs 24–27 ms/token), smaller pack (915 vs 1201 MB), lighter
legs (~1.06 vs ~1.4 GB PSS), equal-or-better quality on every pair. It is also
the fastest decoder measured in the entire spike, beating even Marian-base
fp32. Quality still trails the per-pair Marian baselines on en→pt-br (62.1 vs
67.4) and en→it (51.9 vs 57.7) and ties on it→es (52.6 ≈ 52.6) and es→en
(59.1 ≈ 63.1 with opus ahead; the FLORES en-por reference is European pt, so
the en→pt-br gap vs the pt-specialist is partly ref bias — the owner's blind
read is the authoritative gate there, decisions #101).

**Typed verdict: M2M-100-418M DEFER — strictly dominated by SMaLL-100 (equal
quality class at 2.5× the decode cost, +32% pack, +30% memory). SMaLL-100 is
the single many-to-many candidate worth carrying forward**, but adoption is a
product decision, not a spike outcome: the per-pair OPUS-MT direction still
wins quality on 2/4 pairs at 3× lower leg memory (~0.4 vs ~1.06 GB) and the
pack-count asymmetry flips only if the language count grows well past 4
(per-pair int8 ≈ 958 MB and growing vs one 915 MB pack). The en→pt-br blind
read decides whether SMaLL-100's pt-BR clears the #101 quality bar.

**Solo-run correction (same session, owner request):** the opus-tc-big int8
leg re-measured with ONLY its int8 graphs staged (no fp32 co-residency) gives
**120.2 ms/token** (was 172 co-resident — the probe's multi-session design
inflated ~30%, so per-leg numbers for opus int8 carry that caveat). The
conclusion strengthens, not weakens: opus-tc-big int8 is the slowest decoder
measured — slower than its own fp32 (33 ms/tok), a real ORT-android
MatMulInteger cost on the big Marian architecture. int8 therefore DISQUALIFIES
opus-tc-big on speed (a ~200-token passage takes ~24 s to translate, slower
than the TTS leg it feeds), leaving the pt-BR choice between **opus fp32**
(67.4 chr-F, 1745 MB pack, ~2.3 GB PSS, translation ≈ TTS cost) and **small100
int8** (62.1 chr-F, 916 MB one-pack-all-languages, 1.06 GB PSS, ~2 s per
passage). int8 remains the right precision for Marian-base (faster on-device
than fp32 for those graphs) — the inversion is tc-big-specific.

**GPU test (same session, owner question): NNAPI EP does not change the
picture.** opus-tc-big int8 re-run with `addNnapi()` (flag file `files/ep_nnapi`
staged; graphs partition to NNAPI without error, open times actually shorter):
**167.8 ms/token vs 120.2 solo-CPU** — NNAPI is *slower* for this workload, and
the fp32/int8 op set (`MatMulInteger`, dynamic decode shapes) is a poor fit for
the driver path as expected. Encoder time unchanged (1.5 s vs 1.3 s). No GPU
win at any precision for autoregressive seq2seq decode on ORT-android; the
NPU-class path (QNN/Hexagon HTP) would need shape bucketing + QNN runtime
integration and is out of spike scope — recorded as a possible follow-up if
decode cost ever becomes the binding constraint again.

**QNN EP (owner follow-up: "supported by Qualcomm, wouldn't have to be
built?"): the hardware is covered, but the stock ORT-android binary is not.**
The 1.29.0 AAR bundles the full QNN stack — `libQnnHtp.so`, `libQnnGpu.so`,
`libonnxruntime_providers_qnn.so`, and HTP skels v68→v81 (v69 = SD 8 Gen 1's
Hexagon, so the S22 IS hardware-covered, not just Elite chips). But
`SessionOptions.addQnn()` fails at session creation with
`ORT_INVALID_ARGUMENT: QNN execution provider is not supported in this build`:
the Maven `onnxruntime-android` artifact is compiled WITHOUT `--use_qnn`
(and the required `libonnxruntime_providers_shared.so` registration bridge is
absent from the AAR). Enabling QNN would require a custom ORT build
(`--use_qnn`) — and even then, dynamic-shape autoregressive decode on HTP
needs fixed-shape bucketing; realistic ceiling is encoder-only acceleration.
Recorded as the gate on the NPU path; the flag-file EP selector
(`files/ep_qnn`, `ep_qnn_gpu`) stays in `TranslateProbeRunner` for when a
custom build exists.

Post-#115 update (D2 measured the plugin on silicon, 2026-09-03): the plugin
AAR works as claimed — stock ORT 1.29 + Maven artifacts only, runtime wiring
to the Hexagon proven (backend/skel load on SM8850 v81). But the measured
outcome hardens this record's caveats into conclusions: the fp32 graph's
dynamic ops fail HTP validation (100% CPU fallback, RTF worse than plain CPU),
and the S22/SM8450 fails `QnnDevice_Create` outright — the primary
translate-then-read device cannot use the NPU path at all. Costs for a
small100 NPU attempt: fixed-shape BUCKETED re-exports (AR decode grows the
past cache per step — strictly harder than Kokoro's one static re-export),
bucket-aware parity gates, +minSdk 27, legacy packaging (+~100 MB installed),
dual CPU/NPU ship paths with per-device behavior. Benefits: only
battery/thermal on pre-gen — and translate is ~2 s/passage against a TTS leg
that dominates the job's energy (and on 8-Elite-class CPU, RTF 0.43–0.66
retires synthesis headroom entirely). **Verdict: not worth pursuing for
translate. Reopen only if pre-gen energy data shows translate is a measurable
battery line, or a static/bucketed small100 export exists for other reasons**
(cross-ref #115: translation stays out of scope for accelerator delegation).

**OWNER DECISION (2026-09-02, closing the spike): ADOPT small100 int8 for the
translate-then-read direction.** Explicit trade: quality for simplicity — one
model for ALL languages (one 916 MB pack, fixed cost as languages grow) and the
smallest footprint in the field (int8 915 MB, ~1.06 GB leg PSS, 8.9–9.9
ms/token decode — the fastest decoder measured). The quality compromise is
accepted: chr-F 62.1 vs opus-tc-big's 67.4 on en→pt-br (blind-read texts in
`docs/prints/phase-j/blind-read/`; the European-pt ref bias means the real gap
may be smaller). Per-pair OPUS-MT stays the measured record and remains the
alternative for any pair where a specialist model is later wanted (its tc-big
int8 is speed-disqualified; fp32 is the quality-heavy fallback). M2M-100
remains deferred/dominated. The `core-translate` production slice (SMaLL-100
tokenizer port + SentencePiece on-device + pack integration behind the pre-gen
queue, decisions #101 output-side-only) is the follow-up implementation work;
the spike's host-side export/parity/chr-F tooling and manifest pins are the
reproduction path.

Device evidence: `docs/prints/phase-j/` (translate_results.json, chr-F table);
staging recipe: build.md "NMT spike staging".

## 113. Player notification artist + library title branded "Ayvu" (2026-09-02)

Reported on-device (Z Fold 6): the player notification's now-playing line
still read **local-tts-reader** while the app, channel, and pre-gen
notification already said "Ayvu". Root cause was two stale legacy strings in
`PlaybackService` — the `MediaSessionCompat` session tag ("local-tts-reader")
and `METADATA_KEY_ARTIST` ("local-tts-reader"), which is what the system's
notification/lock-screen now-playing row renders as the "artist" beneath the
book title. The notification's own content title (`book?.title ?: "Ayvu"`),
the `"Ayvu playback"` channel, and the pre-gen `"Ayvu — pre-generating"`
title were already correct.

**Fix:** `METADATA_KEY_ARTIST` → `"Ayvu"` and the MediaSession tag →
`"Ayvu"` (owner-visible branding; the session tag appears in media-controller
records). Library top bar `"Library"` → **"Ayvu library"** (owner request —
"it would be nice to show the name in the library view"); the in-list
`SectionHeader("Library")` under Continue listening is untouched (it labels
the books section, not the screen).

**Evidence:** `:app:assembleDebug` + `:feature-player`/`:feature-library`
test suites green; `:ktlintCheck` clean. Device (Z Fold 6, SM-F971B,
2026-09-02): library top bar reads "Ayvu library"; live media session is
`com.moronigranja.localttsreader/Ayvu/217` state=PLAYING with metadata
`description=A Deepness in the Sky, Ayvu` (was `…, local-tts-reader`).

## 112. First-run setup voice step compacted to a dropdown (2026-09-02)

Owner request after walking the C2 setup card on-device: the tall inline
54-voice selector list made the Choose-a-voice step scroll forever. First-run
setup now renders a compact `ExposedDropdownMenuBox` picker instead — one
read-only anchor showing `af_heart · English (US)` with a chevron; tapping it
opens the whole catalog grouped by language, each row with a radio
indicator, language/gender subtitle, and a muted "needs download" cue while
the Kokoro packs are missing. Selecting a voice persists immediately
(the existing `chooseVoice → AppSettings` path) and dismisses the menu. The
selected voice's action row sits beneath the anchor with the shared row's
exact semantics — `Download this voice's pack` when not ready,
Preview/Stop/Generating…/Retry when ready.

**One shared data source, one override surface.** The dropdown consumes the
SAME `VoiceSelectorUiState`/`buildVoiceSelectorState` builder as Settings and
the reader sheet (decisions #102.4) — no second state convention. The
favorites star is dropped from first-run setup (a picker, not a management
surface); favorites remain a Settings affordance and still flow through the
shared builder. `SetupViewModel.toggleFavorite` became dead and was removed.
The app module gained `material-icons-core` for the chevron (it was the one
Compose host without the icons dependency).

**Evidence:** `:app:assembleDebug` + `:app:testDebugUnitTest` green;
`:ktlintCheck` green (baseline regenerated — SetupScreen carries the repo's
baselined `function-naming` class like every other `@Composable`). Device
(S22, SM-S908U1, 2026-09-02): fresh `pm clear` setup shows the compact card;
the dropdown opens grouped with "needs download" cues; picking `af_bella`
updates the anchor, dismisses the menu and persisted
(`settings: voice = af_bella` in the live Room DB). Settings keeps the full
shared C2 selector untouched.

## 111. E1 — backup & restore phases 2+3 shipped, device-verified (2026-09-02)

Phase 1 (#89) delivered the pure-JVM codec; this entry closes the slice with
the signed-off shape (#109). Snapshot/merge lives in `core-persistence`
(`BackupStore` — one `withTransaction` consistent read of all six tables +
optional book files; merge applies in FK order: books → passages (only for
books whose local cache is missing — never clobber, never re-parse) →
progress (local wins) → bookmarks (idempotent natural-key insert, `""` label
sentinel ↔ null) → history → settings (restored keys overwrite, absent keys
keep local) → book files). The SAF edge + "Backup & restore" settings section
(`BackupViewModel`/`BackupSection`) export via `CreateDocument("application/zip")`
and restore via `OpenDocument` with the shared `ConfirmDialog`; after a merge
the settings mirror reloads and the search index resyncs under `IndexLock`
(mirroring `LocalTtsReaderApp.onCreate`) so restored books are searchable
without a relaunch. Book bytes are captured at import — ONE read reused
for the cover and the sidecar (`files/books/<bookId>.<ext>` via
the new `BookFileStore`, deleteForBook on remove) — the opt-in include-books
source; a re-unreadable source simply yields no sidecar, never a failed
export.

**Design correction vs the plan:** the plan's history rule was "append, then
prune to the ring cap". That alone duplicates rows when a restored ring sits
UNDER the cap (a double restore would grow it) — history now uses the same
idempotent natural-key dedup as bookmarks before pruning, so a second restore
adds zero rows at every size. The shared ring cap moved to
`RoomPlayerStore.RING_CAPACITY` (no magic-number drift).

**Evidence:** `BackupStoreTest` (8, Robolectric + in-memory Room): lossless
fresh-install restore through the codec (re-snapshot DTO-equal), double-merge
zero-duplicates, ring capped under 30-entry archives, progress local-wins,
settings restore-wins + absent-keys-kept, existing cache not rewritten,
book-file round-trip + deleteForBook. Full gates green: `:core-backup:test`,
`:core-persistence:testDebugUnitTest`, `:feature-library` (Added shape
defaults keep call sites source-compatible, no regression),
`:feature-settings`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`checkFeatureBoundaries`, `:ktlintCheck` (baseline regenerated).

**Device leg (S22, SM-S908U1, 2026-09-02):** imported 3 books (sidecars
byte-captured), played/seeked two (progress + ring rows), bookmarked two
passages, set theme dark, exported `backup.zip` to Downloads with include
book files checked — `manifest.json` (v1, appVersion 0.1.0) + all six
sections + `books/<id>.txt` md5-equal to originals. `pm clear` → fresh
setup → restore: **"Restored 6 books, 2 bookmarks, 3 resume points"** (6 = 7
archive books − art-of-war already local from the setup import); live DB
held all rows, `theme_mode=dark` restored, and the setup-chosen
`tts_engine=system-tts` survived (absent from the archive → absent keys keep
local, exercised live). Share flow immediately after restore, no relaunch:
"SEND You have power over your mind…" → **"Found in your library —
meditations · Passage 3 — Match 100%"** (the post-restore index resync).
A second restore of the same zip: **"Restored 0 books, 0 bookmarks,
0 resume points"** — every table count unchanged, sidecars byte-identical.

## 110. D4 — Piper "unintelligible" was a probe bug: missing inter-phoneme `_` tokens (2026-09-02)

D4's blind quality gate failed on the first listen (owner: "both piper audio files
are unintelligible; did the voice and text use the same language?"). Language was
NOT the cause — text (Pride & Prejudice en), voice (`en_US-lessac-medium`), and
phonemization (`espeak-ng -v en-us`) are all English.

**Root cause:** the D4 inputs were built by an uncommitted host script
(`build_inputs.py`/`host_probe.py`) that mapped `espeak-ng -q --ipa` output
**character-by-character** (`ids=[1]; for ch in phonemes: ids+=id_map[ch][0];
ids+=[2]`). That raw CLI stream is NOT what Piper was trained on. Canonical
`piper` 1.7.0 `phonemes_to_ids` inserts the `_` (id 0) token **between every
phoneme** (plus `^`/`$`), i.e. `^ _ ɪ _ ɾ _ … _ θ _ $` — the hand-rolled map omitted
these inter-phoneme boundaries entirely. Result: 715 ids → 18.46 s of unintelligible
audio; canonical 1465 ids → ~36 s of intelligible speech; cross-correlation ≈ 0.
The "0 unmapped chars" + "intelligible" claim in #99 passed to the gate without any
listening check, so the defect reached the owner's ears.

**Corrected re-measure (HiBreak, 2026-09-02):** rebuilt `d4_inputs.json` with
`EspeakPhonemizer` + `phonemes_to_ids` (1465 ids, 0 missing), re-ran
`D4ProbeBenchmarkTest`: Piper **RTF 0.57** (20.0–20.5 s synth for 35.2–35.8 s
audio), open 8.9 s, PSS ~236 MB / VmHWM ~873 MB, finite — still clearly realtime
against Kokoro's 3.01 (PSS ~834 MB). Reference WAVs: `docs/prints/d4/
d4-host-piper-reference.wav` (host) + `d4-hibreak-piper-reference.wav` (device).
Supertonic 3 unchanged (RTF 3.91) — DEFER stands; its "too fast" HiBreak pace
was the already-documented harness artifact (#99: `latent_len × 3072` ≈ 1.7×
shorter than the reference SDK).

**Owner quality verdict:** both corrected reference WAVs (host + HiBreak) sound
fine; inter-sentence pauses are a little short (minor caveat, tunable via
silence insertion later).

**D4 outcome:** Piper's quality gate PASSES the blind listen; it stays the KEEP
candidate for the small tier. Remaining adoption gates are unchanged from #99:
the `PiperEngine : TTSEngine` integration behind the existing seam, per-language
voice pins + hashes, and the es/de/ko coverage check.

## 109. Owner decisions — E0/E1 gate, Phase H capture, G4 speed (2026-09-02)

Resolves the owner-gated roadmap items in one pass (owner request).

**E0 — backup archive location (gate for E1 phases 2/3):**

- **Reach: one-shot SAF export/import.** The user picks a destination on export
  and a source on import — no persistent folder grant, no grant-re-acquisition
  flow. The archive survives reinstall by being a user-held file, not a stable
  app-side grant.
- **Book bytes: opt-in copy, OFF by default.** Restore re-imports from the
  original file unless the user opted to embed bytes in the archive.
- **Generated audio (PCM): excluded.** Derived/recoverable via pre-gen (voice +
  speed + passage key); keeps the archive small and the SAF-throughput concern
  off the hot path.
- Remaining E0 item is a measurement, not a decision — SAF write throughput for
  the export zip (metadata + optional book bytes) — folded into E1 phase 3.

**E1 merge precedence — signed off as documented** (roadmap / post-v1-plan Slice B):
local progress wins over restored; restored settings overwrite matching keys;
bookmarks/history merge idempotently; include-books default off.

**Phase H — reading and listening capture:**

- **Listening ("playing") time:** wall-clock while `PlayerPhase == PLAYING`,
  flushed on every exit (pause/sleep/seek/advance/stop/kill) — unchanged from the
  slice A design (`TimeSpanAccumulator` at the `PlaybackService` edge).
- **Reading time: page-flip-active dwell.** Reader foreground + screen-on
  wall-clock, accumulated only while the user is actively turning pages (a flip
  marks the active window) — NOT raw dwell, so a book left open stops accruing.
  Spans under 10 s are dropped. Whole seconds stored; rounded only for display.
- This resolves the roadmap Phase H "open product decision"; the reader-dwell
  capture is gated behind the flip-activity signal, not foreground dwell alone.

**G4 — speed selector: close #71 permanently.** Playback stays pinned 1.0×;
stored per-book speeds remain ignored; the retained `setPlaybackRate`/pregen
plumbing is kept but the revisit is closed. Pitch-preserving speed stays the only
Later speed item.

**Still open (owner ears, not a click):** D4 Piper blind quality gate — the four
WAVs in `docs/prints/d4/` (host + HiBreak, Piper vs Supertonic 3 vs the Kokoro
baseline) await the owner's listening verdict (keep/defer/drop).

## 108. F3 — folder import via SAF tree (2026-09-02)

F3 (roadmap) promotes the "folder import via SAF tree" idea (ideas.md): a
persisted `ACTION_OPEN_DOCUMENT_TREE` grant feeds supported files through the
existing batch importer. Recursion policy and a per-batch cap are decided
before build; folder scanning is the hostile-input audit's first consumer
(decisions #96.8), not a parallel policy.

**The two "decide before build" items, conservative defaults:**

- **Recursion policy — root + one nested level.** `FolderScanPolicy.MAX_DEPTH =
  1`: files directly in the granted root plus one level of subfolders are
  scanned; anything deeper is pruned. A tree grant is library-shaped (a folder,
  or a folder of per-author/series subfolders), so one level covers the real
  case without an unbounded-recursion surface on an adversarial tree.
- **Per-batch cap — 200 files.** `FolderScanPolicy.MAX_FILES = 200`. Reaching
  it stops the walk and sets `FolderScanResult.truncated`; the library surfaces
  it (snackbar "folder capped at 200 files", dialog "later files were not
  imported") rather than silently shrinking the batch.

**Shape.** The recursion/cap policy lives in a pure, lazily-enumerated
`FolderScanPolicy` (`ScanNode<T>` + `collect`) so the hostile-input decisions
are host-testable without Android; the `FolderScanner` `DocumentFile` adapter
supplies the tree with deferred `listFiles()` — a hostile tree is never
materialized past the depth bound. The extension gate is the shared
`EBookFormats.parserFor` — no second extension list; the importer's
`UnsupportedFormat` path stays the backstop for strays.

**UI/flow.** The library "Import" action is now a two-item menu (Import
files / Import folder). Folder import reuses the F1 per-file progress: a
`Scanning` state (no file count → indeterminate) → the normal `Importing`
batch → the summary. An empty folder surfaces as a typed "no supported book
files found" failure, never a silent no-op. The tree URI gets the same
persistable READ grant as multi-file picks (`takeReadPermission`), so a re-scan
after restart keeps working where the provider allows it.

**Evidence:** `FolderScanPolicyTest` (6 cases: walk order + skipped,
one-level scan, deeper-than-one pruned, cap truncation, default `EBookFormats`
gate, custom predicate) green; `:feature-library:testDebugUnitTest` 19/19 (13
existing + 6 new); `:app:assembleDebug` + `ktlintCheck` green (baseline
refreshed for the line shifts the F3 edits moved).

**Device leg (S22, SM-S908U1, 2026-09-02):** granted a tree on
`/sdcard/Download/F3Books` (root files `book-one.epub` + `book-two.txt` +
`notes.pdf`, plus `series/book-three.md` and a two-level-deep
`series/deep/too-deep.txt`) via the real SAF picker → "Allow Ayvu to access
folder?" → import. Library gained exactly three books — `F3 Book One` (epub
title), `book-two` (txt), `book-three` (md, from the one nested level):
root + one-level recursion works, `notes.pdf` is filtered (no second
extension list), and the two-level-deep `too-deep.txt` is pruned. No crash,
no dialog/progress left behind. Reference: `docs/prints/f3/folder-import-s22.png`.


## 107. A8 — Room reinstall anomaly classified: our E2E teardowns, not Samsung (2026-09-01)

S22 session (SM-S908U1, R5CT119ZTMX) reproducing the open-bugs row: "no
`files/databases/` for hours after `adb install -r`, then an empty 68 B
skeleton; library rendered books from non-Room state" (decisions #88's
app-side trace had already excluded a data-flow defect).

**Reproduced the observation, not the defect — the path was wrong.** The
connected device showed exactly the 08-29 signature (`files/databases/`
absent; `run-as find -name '*db*'` empty; only `cache/local-tts-reader.db.lck`)
— but the DB lives at `<data-root>/databases/` via Room's
`getDatabasePath()`; Room NEVER writes under `files/`. Pulled and
checkpointed live: full schema (`books/passages/progress/settings/
bookmarks/position_history`), 1 book + 1 progress row, and the library
screen renders "Continue listening — Pregen E2E Book, 100%, 2.5 MB
offline" straight from Room. The "non-Room render" reading was the
`files/`-scoped `find`; the `.lck` in `cache/` is an instrumentation
SQLite lock artifact, not a data-dir signal.

**The deletion mechanism is our own instrumentation.** 8 of 10 E2E tests
used `context.deleteDatabase("local-tts-reader.db")` in `@After` —
unlinking the production DB of the same package under the running app's
Hilt Room singleton. `#42` had already named this class (worker starvation)
and fixed it only in `PlaybackE2eTest`/`PregenE2eTest`; the six remaining
tests (Es/It/Pt Voice, OpenChapter, PlayPosition, VoiceSelection) kept
wiping user data every instrumented run — the A6 2026-09-01 session is why
the S22 library holds only `t42-pregen-e2e-book` today (the 08-29 "A
Deepness in the Sky" rows were wiped by that day's E2E pass). The "empty
68 B skeleton after hours" is the post-delete state: the still-running
singleton re-creates the file (fresh empty schema) on next DAO access
while the deleted-but-open inode holds the old rows.

**Samsung install-time handling excluded by experiment.** `adb install -r`
of HEAD: DB + WAL SHA-256 byte-identical pre/post install and after
relaunch; library intact on screen.

**Fix shipped:** the six remaining `deleteDatabase` teardowns removed
(commit `1df3a57`; `:app:compileDebugAndroidTestKotlin` green). No
production-code change warranted — no FS-side DB deletion exists outside
instrumentation, and `CorruptDatabaseGuard` (#88) already covers the
residual "file replaced by garbage" class. A8 closed with evidence; the
Phase C gate (decisions #96) is satisfied.

## 106. C3 — setup recovery and re-entry: the gate IS the contract, host-verified (2026-09-01)

C3's spec (roadmap): first-run state derives from durable facts — required
packs ready, a voice selected, ≥1 book — not a one-shot flag; a user who
skips, loses a pack, clears cached assets or re-enters setup later sees the
actual missing step; every setup action remains reachable from normal
settings after onboarding.

**Owner call:** "skips" means the recorded system-TTS opt-in (decisions
#102) — the degraded path IS the skip. No "Skip for now" affordance is
added; system back stays swallowed mid-setup (C1: the gate owns dismissal).
An offline user's first audio comes from the device voice, and the Kokoro
download plan resurfaces in Settings the moment they are degraded+missing
(C1.5's `PacksPlanCard` route).

**What already held (built C3-compatible by C1, #102.4 — now pinned by
tests, nothing to build):**
- No onboarding flag: `SetupGate.evaluate()` re-derives from disk/settings
  truth (`PackRegistry.refresh()` + `EspeakStager.isStaged` + book count +
  persisted voice/engine) on every cold start and after dismissal
  (`SetupGate.kt`). Deleting the pack files deletes the `.ready` markers
  with them (`PackCache` — the cache IS the pack state), so a lost pack or
  wiped espeak staging reactivates the gate showing the actual missing step.
- Re-entry: packs ready + no books → import-only checklist; every step is
  reachable from Settings post-onboarding (voice selector C2, pack plan C1.5,
  import via the library).
- New `SetupGateTest` C3 cases (JVM, real-file fixture pattern): a completed
  setup reactivates when a required pack's artifacts are deleted, when the
  espeak bundle is wiped, and dismissal is proven non-sticky (re-derivation
  resurrects an incomplete setup). 8 gate cases total, green.

**Toolchain note:** backtick test names must stay ASCII-mappable — an
em-dash in a test name breaks `:app:compileDebugUnitTestKotlin` under the
docker toolchain's POSIX locale (`InvalidPathException` on the generated
`.class` filename).

Remaining device-bound C3 evidence: none required by the spec — recovery is
exercised through the same disk truth the gate reads; a device pass can
fold into the next S22/HiBreak session (delete a pack via `adb shell`,
relaunch, observe the re-derived checklist).
## 105. C2 voice selector shipped + B6/S22 device session (2026-08-31)

C2 (voice selector in the primary flow) landed host-verified and device-
verified on the HiBreak in the same session that ran the A1 debt-register
re-verification. Decisions #102.4's shape holds: ONE selector surface is
built in core-ui (`VoiceSelector` + `buildVoiceSelectorState`) and reused by
first-run setup (`ChooseVoiceCard`), Settings and a new reader voice sheet —
no second convention. core-ui gained the `VoiceSelector` composable and the
pure builder; the selector renders the C2 contract verbatim:

- persistent **Selected voice: _name_** summary; exactly one row carries the
  radio indicator; the star is a separate favorite action (row tap selects,
  star toggles the favorite, never implied selection);
- every ready row exposes **Preview/Stop**; slow synthesis shows cancellable
  **Generating sample…**; missing engine assets replace Preview with the same
  explicit download action used elsewhere (never silence, never an
  unannounced fallback);
- a saved voice absent from the catalog renders as unavailable with a
  download/reselect action, never an all-unselected list.

**Audition** — `VoiceAudition` core contract + composition-root
`VoiceAuditionCoordinator` (app): one sample at a time (starting another
cancels the first; the completion poll is synchronized so stop/finish never
double-resumes), ephemeral audio (played straight to its own
`AudioTrackPassageOutput`; excluded from book progress, history and the
passage disk cache), and narration capture/pause/resume through
[PlayerCommands] only when the book was playing (A5 single-writer).
Fixed during the device pass: engine resolution (the FIRST call cold-opens
the Kokoro model, minutes on the HiBreak) must run off-main inside the job —
devices showed the original design ANR'd.

**Change-voice** — `PlayerCommands.changeVoice` + `ACTION_CHANGE_VOICE`:
the reader voice sheet persists the new voice then sends the command; the
service captures the live playhead, supersedes in-flight synthesis through
the A5 generation model, rebuilds the voice-keyed queue/fill and restarts
once at the same position (paused sessions stay paused). Unchanged-voice and
no-open-book are no-ops at the sender/service respectively.

Host evidence: `:app:assembleDebug`, all unit suites, `checkFeatureBoundaries`
and `:ktlintCheck` (baseline regenerated) green; new tests —
`BuildVoiceSelectorStateTest` (5), `VoicePreviewTest` (3),
`VoiceAuditionCoordinatorTest` (4, Robolectric), `PlaybackServiceChangeVoiceTest` (2).

Device legs (HiBreak B6, serial B6CLR0B2FHFA006000712, build = the C2 HEAD):
- Settings voice selector renders summary + radios + Preview; row-tap
  selected `af_alloy` (summary + exactly one checked radio); star toggled
  favorite without changing selection;
- Settings Preview showed "Generating sample…" with NO ANR (the off-main
  fix), then completed back to Preview;
- reader "Change voice" sheet opened the same selector; selecting `af_bella`
  closed the sheet, kept the reader at the same passage, and the persisted
  voice survived process restart (`Selected voice: af_bella`).
- A1 debt row: `PregenE2eTest` on the B6 **OK (1 test)** in 222 s —
  `PregenWorker` SUCCESS + playback completed over the warm disk tier.

The S22 device legs completed in a follow-up session (same build, S22
serial R5CT119ZTMX), closing the register's A2 / A5-A7 / C1 rows:

- **A2 — stop/kill/reopen (decision #61):** mid-passage STOP persisted the
  live playhead — the Room `progress` row read `(t4-e2e-book, 0, 0, 15.14s)`
  after STOP with the loop PLAYING inside passage 1; `am force-stop`
  mid-play then relaunch resumed at the SAME 0:10 playhead (resume row
  `10.16s`), within the 5 s checkpoint.
- **A5/A7 — MediaSession/notification (decision #62):** pause/play through
  the transport settled deterministically — a rapid double-`ACTION_PLAY`
  command race superseded cleanly (PLAYING → PAUSED → PLAYING → STOPPED with
  no stale republish), MediaSession held `E2E Test Book` metadata with
  `state=PAUSED/PLAYING` matching the UI, and the id-42 playback notification
  (channel `playback`, 4 transport actions) stayed alive through the cycle.
  The pause-cancels-generation path itself is host-pinned by
  `PlaybackServiceA57Test`.
- **C1 (decisions #102/.3):** `pm clear` clean install launched straight into
  the derived first-run flow — privacy → voice selector → the download plan
  card with exact per-pack bytes (Kokoro model 310.4 MB, voices 26.9 MB,
  espeak 9.4 MB), a storage line naming the shortfall/headroom ("622589.5 MB
  free — needs 346.7 MB"), the never-default "Continue with the device voice
  (degraded)" opt-in, and the terminal Import-a-book step. Durable-fact
  derivation confirmed (C3): `pm clear` wiped packs so the plan re-appeared;
  with packs ready it collapses to import-only.

Roadmap register updated accordingly — **A1/A2/A5-A7/C1 device rows are all
now closed**.

### A4 / F2 / A6 S22 follow-up (2026-09-01)

Attempted the remaining register rows on the S22 (R5CT119ZTMX) in one
session. Outcome:

- **A4 (#63) — fill-cap/force-stop/relaunch: DONE (after the display was
  re-woken).** UI whole-book pre-generate on the library card filled the
  tier to 2,613,858 bytes / 8 files (`PregenWorker` SUCCESS); `am
  force-stop` + COLD relaunch left the cache byte-identical (the CR-4
  reopen bootstrap) and playback over it resolved `loop: source=disk` with
  zero synthesis. Eviction-order-at-reopen is host-pinned
  (`PcmPassageCacheTest` 15: reopen evicts the OLD entry not the new one,
  over-cap converges at construction, invalid pairs cleaned).
- **F2 (#90) — library search UI: DONE (after the display was re-woken).**
  UI pass on the S22: a non-matching query (`zzz_nomatch`) filters the
  library list to the deterministic empty-state — `No books match
  "zzz_nomatch"` renders in the accessibility tree — while the
  continue-list keeps the active book's PlayerCard (unfiltered by design,
  decisions #90), and clearing the query restores the row. Matching logic
  host-pinned (`LibraryViewModelTest` 13/13).
- **A6 (#66) — import/share/pregen regression:** **CLOSED — all headless
  legs pass on the S22.** `RealEpubImportProbe` **OK (2 tests)** — the
  real-world P&P (24.8 MB, `files/import-probe/pp.epub`) parses through the
  Android Expat DOM + `BookImporter` (`Added`), and
  `niceGuyEntityEpubImportsOnDevice` ("No More Mr Nice Guy", entity-laden
  OPF metadata, `nmmng.epub`) passes the #53 entity case on-device.
  `SharePipelineInstrumentedTest` **OK (2 tests)** — text share resolves to
  the book passage and image share decodes/OCRs through the real
  `TextIndex` + tess-two. Combined with `PregenE2eTest` green earlier in the
  session (worker SUCCESS + disk-tier playback), the full A6 regression
  surface is exercised. Note: these are headless instrumentation runs — they
  did NOT need the display, which is what made them runnable while the
  screen was physical-off.

A4 and F2 are both closed in this follow-up (the display came back on and
the UI legs ran). No rows remain blocked in the register.

**Also noted:** a pause→resume product observation — the resumed passage
starts a bit earlier than the live playhead (ideas.md; investigate whether
resume should keep the exact playhead or intentionally pull back to the
sentence start).

## 103. C1 guided first-run setup — host slices landed (2026-08-31)

Implemented per the approved C1 plan (local plan doc; owner decisions #102):

- **C1.1** — `AppSettings.reload()` runs at process start alongside the
  index rebuild, so persisted voice/theme/engine are visible to setup and
  cold-start reads (was all-defaults until the first play/share).
- **C1.2** — `SetupState.derive(SetupFacts)` in core-tts: the single
  derivation table shared by the gate, the setup screen and the tests
  (packs-ready+staged+book → COMPLETE; opted-in degraded without packs →
  import or DEGRADED_READY; ready packs win over the opted-in path).
  `StorageProbe` interface stays pure JVM; the Android `StatFs` impl is
  app-side (`StatFsStorageProbe`).
- **C1.3** — static `KokoroVoiceMetadata` (54 v1.0 voices, names read from
  the pinned pack artifact — the pack is the contract; the fixture
  cross-check test locks metadata ⊇ pack, known-family resolution and the
  count). `VoiceCatalog` moved to core-tts (pack/voice enumeration is
  core-tts domain); its Hilt provider + the whole pack wiring moved from
  `feature-settings` to the app's `PackModule` (A6 composition root).
- **C1.4** — `SetupGate` re-derives `active` from durable facts on every
  cold start and after dismissal (no onboarding flag, C3); `SetupScreen`
  renders the derived checklist (privacy → voice → download plan → import),
  the shared `PacksPlanCard` lives in core-ui (also reused by Settings'
  "Speech engine" section when the degraded voice is active and Kokoro
  packs are missing), downloads are cancellable/resumable/retryable via the
  registry, the storage line names the shortfall before any download, and
  the import hand-off reuses feature-library's SAF adapters against
  app-injected `ImportCoordinator` (no feature-VM import).
- **C1.5** — `SystemTtsEngine` (app-side, `TTSEngine` impl) over a
  `SystemTtsSeam` fake-testable adapter: `synthesizeToFile` (WAV) → mono
  16-bit PCM, `segments = null` (passage-level read-along, the recorded
  degradation), named `Failed("device voice unavailable: <lang>")` never a
  silent fallback, zero packs. Engine selection persists under the generic
  `tts_engine` settings key (no Room migration); `EngineSelector` routes
  PlaybackService + PregenWorker through one seam; `PlaybackUiState` gains
  `degraded` and PlayerCard shows a static, reduced-motion-safe "Device
  voice" pill. Settings gains the Kokoro-82M / Device voice radio.

Host verification: `:app:assembleDebug` green; `:core-tts:test`,
`:core-persistence:test`, `:app:testDebugUnitTest`,
`:feature-player:testDebugUnitTest`, `:feature-settings:testDebugUnitTest`
green including the new suites (SetupStateTest 9, SetupGateTest 5,
SystemTtsEngineTest 7, KokoroVoiceMetadataTest 3 — 24 new cases, 0 fails).
Device legs (C1.6) run in a separate session per decisions #102.3/#102.4;
the WAV-parse fallback for system TTS PCM is in from the start (the plan's
device-lottery contingency), pending on-device quirk recording.


## 102. C1 spec decisions — system-TTS fallback in setup, no bundled sample, A8-first sequencing (2026-08-31)

Owner calls when C1 was specced (plan grounded in the code map: PackDownloader/
PackCache/PackRegistry, VoiceCatalog, ImportCoordinator, hand-rolled MainActivity
navigation):

1. **System TTS ships as an opt-in zero-download degraded fallback inside
   first-run setup.** The setup plan screen offers "continue with the device
   voice" as an explicit, never-default action alongside the Kokoro download
   plan. Costed scope: a `SystemTtsEngine : TTSEngine` adapter over
   `android.speech.tts` (async callback → Flow), no read-along word timings
   (no introspection) → passage-level read-along degradation, recorded like
   the Piper small-tier limitation; never auto-selected, clearly badged
   degraded in the player. The device-lottery voice must not read as the
   app's voice — the Kokoro plan stays the primary path.
2. **No bundled public-domain sample.** Onboarding works without it (roadmap
   requirement); first-audio acceptance uses the normal SAF import path.
   Avoids APK weight and a second download row.
3. **A8 device leg runs before C1 implementation.** The Phase C gate
   (decisions #96) is a reproduce-and-classify session on the S22 (no
   `files/databases/` for hours after `adb install -r`), not a code change;
   classify ours vs Samsung install-time handling first.
4. **Plan shape:** first-run state derives from durable facts (required packs
   ready, voice selected, ≥1 book — C3-compatible, no onboarding flag); the
   setup flow hosts in `app` (composition root owns the cross-feature flow,
   A6 boundaries forbid feature-setup → feature-settings edges); pre-download
   voice choice is served by a static Kokoro voice-metadata table in core-tts
   (voice names otherwise only exist inside the downloaded pack); the voice
   selector is built once as shared groundwork so C2 reuses it instead of
   growing a second convention.

## 101. Translation scope widened; custom pre-generation time (2026-08-31)

Owner product-scope decisions:

1. **Translation is no longer pt-BR-only.** The "pt-BR translation" Later-table item becomes "translation to any advertised target language" (it→es, en→pt, …). Italian and Spanish are already native Kokoro voices (hard-facts "8 languages incl. pt-BR" / "9 groups"; CosyVoice3 covers it/es), so it→es needs no new TTS tier — the same output-side `core-translate` decorator behind the pre-gen queue, with the only per-pair cost being one NMT pack (OPUS-MT-class int8, CC-BY-4.0) + one quality gate. Matching/index stay original-language; the single-multilingual-model choice remains a design-time gate, and NLLB-600M stays blocked (CC-BY-NC).

2. **Custom pre-generation duration** is logged as a candidate, tagged into the pre-gen follow-up family (space estimate / per-book usage+delete, decisions #44). UI-only: `PregenBudgetDialog` gains an arbitrary-duration input; the backend already accepts any `PregenBudget.maxTimeMs` (A1). This is the manual offline budget, not the D1 live look-ahead horizon.


## 100. ORT-android pin bumped 1.23.2 → 1.29.0 (2026-08-31)

Owner call ("keep") after the same-day A/B. Evidence at bump time:

- **Main project green on 1.29.0 end-to-end**: the full playback instrumented
  set (PlaybackE2e, VoiceSelectionE2e, PlayPositionE2e, PtVoiceE2e) passed
  **0 failures** on the Z Fold 8 (SM-F971B/SM8850, Android 17) — real
  service, engine, AudioTrack; the JVM suite is build-clean under the pin;
  the app's espeak auto-stage flow worked on a fresh install.
- **Version gaps closed**: `ConvInteger(10)` — the #86 int8 blocker — is
  implemented on 1.29.0 (probe-verified) and missing on 1.23.2; the owner's
  logged ORT-android error is fixed only in **≥1.25.0**, so 1.29.0 clears
  the minimum with headroom.
- **No behavioral regressions observed** across S22 (precision A/B),
  HiBreak (int8 leg), and Fold probes — quantization verdicts unchanged
  (they are model-property-bound, not runtime-bound).

Resolved same-day items and remaining costs:
1. **Ledger re-baselining (ongoing)**: every measurement before 2026-08-31
   ran on 1.23.2 (D2 EPs, D3 engines/precisions, D4 small tier, #86).
   Historical records stay as-measured; new measurements must state the
   runtime version. The batched re-runs join the next device sessions.
2. ~~HiBreak 1.29 memory re-check~~ **DONE (same day)**: int8/oracle
   harness on 1.29.0 → RTF 2.587/2.581 (identical to 1.23.2's 2.621/2.591),
   combined-session PSS 1.65 GB / VmHWM 1.70 GB, completed without the lmkd
   kill (clean-boot condition, same as 1.23.2) — **the upstream 1.24+
   dynamic-shape memory regression (onnxruntime#29538) does not manifest on
   our graphs**. A ~4 min real playback session (1.29 app build, Chrysalis,
   cache-resume → LOADING → PLAYING → pause) held 0.67 → 1.07 GB PSS with
   swap-PSS ≈ 0.1 MB and 0 lmkd/FATAL events. Small-tier memory story
   stable under 1.29.
3. Future pin floors: the owner-logged error's fix boundary is 1.25.0;
   treat 1.25.0 as the hard minimum for any subsequent bump.
4. The Fold SIGILL blocker (bugs.md 2026-08-31 entry) is closed by this
   bump — playback E2E set 0 failures on the affected device/stack.

Pin: `gradle/libs.versions.toml` `onnxruntime = "1.29.0"` (single ref, app +
core-tts JNA host + spike-tts all follow).

## 99. D4 — small tier measured on the HiBreak: Piper passes realtime, Supertonic 3 deferred, Audio8 dropped (2026-08-31)

The D4 comparison ran as real end-to-end pipelines on the Bigme HiBreak
("B6"; spike-tts `D4ProbeRunner` + `D4ProbeBenchmarkTest`, ORT-android
1.23.2, 6 intra-op threads, memory-pattern/arena off per the #93 lesson,
screen-on). Corpus: the Kokoro grain-spike en-US blob (Pride & Prejudice
opening). Inputs host-prepared (D3 pattern): Piper phoneme ids via host
espeak-ng 1.52 → the voice's `phoneme_id_map` (0 unmapped chars after
newline→space), Supertonic text_ids/mask via the reference SDK's
UnicodeProcessor (lang "na"), M1 style vectors, dp-deterministic latent
shape. RTF keys on ACTUAL produced audio (the Supertonic dp duration is
proportional — its vocoder emits ~1.7× dp seconds on the host SDK, exactly
`latent_len × 3072` samples in our device harness).

| Leg | HiBreak RTF | Memory | Verdict |
|---|---|---|---|
| Piper `en_US-lessac-medium` (63 MB, `rhasspy/piper-voices`) | **0.50** — 9.0–9.4 s synth for 18.0–18.5 s audio; open 7.4 s | ~195 MB PSS / ~507 MB VmHWM | **KEEP candidate** |
| Supertonic 3 (`Supertone/supertonic-3` @ `3cadd1ee`, 380 MB, 4 graphs) | **3.92** — 111 s for 28.4 s; opens ~6 s | ~536 MB PSS / ~690 MB VmHWM | **DEFER** |
| Audio8 0.1B INT8 | loop not runnable | closer-look: 5.83 s per slow-AR step | **DROP** |

Against the Kokoro 3.01 RTF / ~834 MB PSS baseline: **Piper is the first
engine measured realtime on this device** — ~6× faster than Kokoro at ~¼
the session footprint, with audio produced on-device (the staged WAVs were
later found corrupt — see the correction below; RTF and memory numbers
stand). Host previews (ORT 1.23.2, 8-thread x86): Piper 0.024, Supertonic 3
0.162.

  **Correction (2026-09-12, owner's listening pass): the staged Piper WAVs were
  garbage — every other sample of the render (18 s files at 2× speed). The
  frontend was never the problem: the recorded ids decode to clean English
  phonemes and match official piper-tts ids head-for-head; the model file
  sha256-matches `rhasspy/piper-voices`; the invocation is bit-identical to
  piper's own session (bare ORT vs `phoneme_ids_to_audio`, max-abs-diff 0.0).
  The `audio_s` fields recorded the FULL render (device runs 35.83/35.16/35.19 s,
  RTF 0.57) while the written WAVs carried half — an ORT-android 1.23.2-era
  artifact (#100 bumped the pin to 1.29.0 that same day). Re-run on
  ORT-android 1.29 on the B6 (2026-09-12): Piper audio 35.35–35.42 s,
  **RTF 0.566–0.575**, ~234 MB PSS / ~880 MB VmHWM, WAV written full-length
  (35.42 s) — "intelligible audio" was an unverified claim in the original
  entry; the RTF numbers stand (the ratio is invariant to the halving). The
  listening set is regenerated in `docs/prints/d4/` (device + host renders,
  both 35 s @ 22.05 kHz); the blind quality gate is REOPENED.

  **Gate verdict (2026-09-13, owner): PASS** — the corrected renders are
  intelligible and the prosody ranks above Android's built-in TTS. The
  comparison baseline the owner used is the platform voice, not Kokoro; a
  blind Kokoro A/B was not run and is not required by the gate as written.
  Adoption is unblocked.

- **Piper** — the direct-ORT VITS port thesis holds: shared espeak-ng
  phonemization (host 1.52 ids are clean against the stock export; no
  sherpa dependency), tiny sessions, per-language packs through the
  existing `TtsPack` flow. **#30b audit result: the stock export exposes a
  single audio output — no alignments, no word timestamps** — so the small
  tier ships **passage-level read-along only** (recorded degradation; a
  custom re-export could surface VITS alignments later). Flat prosody judged
  acceptable by the owner's 2026-09-13 listening pass (verdict above) →
  adoption (a `PiperEngine : TTSEngine` + voice pins + hashes + es/de/ko
  coverage check) is unblocked.
- **Supertonic 3** — vendor RTF claims (~0.3 "on an e-reader") do NOT
  reproduce on the HiBreak (3.92); Kokoro-class speed with ~⅔ the memory.
  Deferred, not dropped: it is the only D4 candidate whose duration
  predictor **passes the read-along introspection gate** (per-token
  durations → character/word timing), it covers 31 languages at 44.1 kHz,
  and a community int8 export exists. Re-test triggers: int8 export
  measured, or strong-device tiering (host RTF 0.16 makes it a plausible
  Kokoro companion on the S22 — a separate decision).
- **Audio8 0.1B INT8** — dropped without running the full loop: the
  closer-look probe measured one slow-AR recurrent step at **5.83 s on the
  HiBreak and the step emits one token position** (logits `[1,1,4097]`);
  a sentence needs hundreds of positions, so the loop is RTF in the
  hundreds. The "full generation-loop leg" the closer-look required is
  arithmetically determined by the measured component cost.

Measured caveats: one voice (lessac-medium), one language, one corpus blob,
one device state; the blind quality gate and multi-language coverage are
the remaining adoption gates. Device WAVs + result JSONs:
`docs/prints/d4/`; staging recipe: build.md "D4 small-tier staging".

## 98. B4 completion — pregen-horizon amber denominator, reduced-motion degradation, delete confirms (2026-08-31)

The HiBreak device pass closed B4's remaining items (S22 pass was #95).

- **Amber segment denominator: pregen horizon (owner pick).** #95's open
  finding — the teal/amber generated segment is sub-pixel on long books
  (45 s cushion vs ~27 h remaining ≈ 0.05% of the bar) — was settled with
  three owner-reviewed options; the owner picked the pregen-horizon
  denominator. `PlaybackUiState.generatedAheadFraction` now divides
  `generatedAheadSeconds` by the fixed `PREGEN_HORIZON_SECONDS = 120.0`
  (~2.7× the service's 45 s look-ahead, so steady state sits ~37% into the
  segment) instead of book-time remaining. The bar stops being a strict
  partition of book time: amber answers "how full is the buffer right
  now", not "how much of the book is it". PlayerCard/SegmentedProgress
  legends updated; callers keep the played+generated ≤ 1 clamp so the bar
  stays valid near book end. Tests: `PlaybackUiStateTest` rewritten to the
  horizon contract (steady-state 45 s → 0.375, clamp, speed/book-length
  independence). Device-verified on the HiBreak: a ~19 s cushion paints
  ~16% of the bar (teal `#0B5F72` played, amber `#7A5200` generated,
  pixel-sampled) — invisible under the old denominator.
- **Reduced motion: explicit gate, degradation without hidden state.**
  Compose animations ignore the system `ANIMATOR_DURATION_SCALE`, so the
  Android "Remove animations" toggle had no effect. New core-ui
  `Motion.kt`: `LocalReducedMotion` (+ `rememberReducedMotion()`, true
  exactly when the scale is zeroed), provided once by `AyvuTheme` so no
  call site repeats the read. Consumers: the PlayerCard and LoadingState
  spinners become a static `StaticRing` (same size/stroke/tint) and the
  library card entrance becomes `EnterTransition.None` — state copy
  ("Generating…") and all controls stay. Device-verified on the HiBreak:
  with the scales zeroed the loading ring is pixel-static (5 s apart diff
  = None) and the card renders complete. Note: the scale is read once per
  composition host — a live settings change lands on the next process
  start, not mid-session.
- **Library "Delete offline audio" now confirms (#95 follow-up).** Both
  menus that deleted directly — the docked card's overflow and the library
  row's — open the shared `ConfirmDialog` ("Frees N for this book. It can
  be regenerated later."), matching the Settings offline rows (#94).
  Device-verified on the HiBreak.
- **HiBreak low-motion pass.** Light and dark themes exercised on device
  (library/reader/settings/player), live playback through a full
  cold-open → LOADING → playing cycle (publish-to-card lands ~40–60 s in
  on this SoC — slow, honest feedback via the spinner, no ANR). Page
  turns stay instant; no animation can strand the UI.
- **Ops finding: staged packs without `.ready` markers.** The HiBreak's
  Kokoro packs had been staged by a plain `run-as` copy (2026-08-29), which
  bypasses `PackCache`'s marker write — the app showed "download required"
  for present, hash-correct artifacts and Play silently no-oped (the pack
  gate never becomes Ready without the marker). Recovery: verify the
  on-device sha256 against the pinned descriptors and write the
  `verified:<sha>` markers (`build.md` staging note added). The one-time
  hash path (`verifyAndMark`) only runs through the download flow, not on
  launch — that asymmetry is deliberate (hashing 325 MB per launch) but
  makes sideloaded staging marker-blind; stage packs through the app's
  download flow on real devices.

Reference screenshots (light + dark, library/reader/settings/player,
reduced-motion loading) captured on the HiBreak: `docs/prints/reference/`.


## 97. Three-tier engine strategy (2026-08-31)

Owner decision: the engine landscape is managed as three tiers behind the
existing `TTSEngine`/ORT seam — S5's per-engine `PregenKey` dimension already
anticipates multiple engines, and `EngineTier`/`PackRegistry` exist.

1. **High-end — voice cloning, multilingual, pregen-only (not necessarily
   realtime).** Incumbent: Fun-CosyVoice3-0.5B (9 langs incl. es/it, zero-shot +
   cross-lingual cloning, pinned pack, measured 3.22 GB VmHWM on the S22).
   Challenger: **Chatterbox Multilingual ONNX** (MIT, 23 langs incl.
   es/it/pt/de/ko, zero-shot cloning, 0.5B AR Llama backbone) — the 2026-08-29
   blanket reject is partially reopened: ONNX exports now exist. Provenance:
   `onnx-community/chatterbox-multilingual-ONNX` is the only pin candidate
   (646 downloads); `textagent/chatterbox-multilingual-ONNX` is a mirror of the
   same export (identical card and conversion script; its sample code still
   points at onnx-community) and is NOT a pin candidate. The official
   `ResembleAI/chatterbox-turbo-ONNX` is English-only (350M Turbo) — fails the
   multilingual requirement. The comparison is **roadmap D5, gated on G0**
   (owner call: the quality gate runs on the narration corpus, not ad-hoc) and
   must pass the provenance gate (pin revision + sha256 + PyTorch output
   parity — the #86 fp16-stub lesson) before any measurement. Known open risks:
   AR KV-cache memory on-device (the MOSS lesson — memory, not speed, kills
   weak-RAM devices), the HF BPE tokenizer as a new tokenization path vs
   espeak-ng (the advertised set en/es/it/pt/de needs no external normalizer;
   zh/ja/he do), 24 kHz output, watermark off by default.
2. **Medium — realtime on strong phones.** Kokoro-82M stands (S22 RTF 0.77;
   0.66–0.76 on the #86 harness). No change.
3. **Small — realtime on weak devices.** Device naming correction (owner,
   2026-08-31): the "Bigme B6" named in bugs.md's 2026-08-27/29 entries and the
   device pending-notes in decisions #60/#61/#62 **is the Bigme HiBreak** — one
   unit, two names. The small-tier baseline therefore already exists and is
   measured: Kokoro RTF 2.84–3.12 (avg 3.01) on the HiBreak (bugs.md B6
   re-measure; #93) — live synthesis cannot sustain playback, pre-generation is
   mandatory. **The tier is confirmed necessary; no further baseline
   measurement.** Owner reopened **Piper** and added **Supertonic 3** as the
   comparison legs (**roadmap D4**): Piper as a direct-ORT VITS port (NOT
   sherpa) to keep the shared espeak-ng/JNA phonemizer and to audit the VITS
   alignment outputs for read-along anchors (#30b — upstream has no word
   timestamps; if introspection proves impossible the tier ships passage-level
   read-along, recorded as a known degradation), with per-language packs
   (14–100 MB/voice, ~20+ languages incl. the de/ko Kokoro gaps) via the
   `TtsPack` flow — tens-of-MB sessions also fit the HiBreak's measured 834 MB
   PSS envelope; Supertonic 3 under its recorded supply-lifecycle gate
   (archived upstream, pin + hashes) and its unverified duration-introspection
   gate. Piper is multilingual as a catalog (one pack per language), which the
   pack registry already anticipates (hard-facts: "engines like Piper would add
   per-language packs").

Every tier stays behind the one inference convention (ORT); no candidate may
introduce a second runtime. Docs updated: roadmap.md (D4/D5), hard-facts.md
(tier table), landscape.md (Chatterbox partial reopen, Piper promotion).

## 96. Roadmap reconciliation — eight gaps closed (2026-08-31)

A gap review of roadmap.md against the decision ledger found eight items the
roadmap did not own. Owner decisions and what changed:

1. **Speed selector** — #71's "revisit planned" had no roadmap home. Added G4:
   a bounded decision item (re-derive why 1.0× was pinned → restore the selector
   and verify read-along at speed, or close the revisit permanently).
2. **MOSS-TTS-Nano dropped** — D3's "pregen-gated candidate" verdict had no
   adoption slice. Owner call: RTF ~3.5 rules out live synthesis, the HiBreak
   cannot hold the decode plateau (lmkd kill at ~2.5 GB RSS on a 3.97 GB
   device), and 0.75 GiB of pack weight buys pregen-only quality while the
   shipped Kokoro baseline (RTF 0.77 S22 / 3.01 HiBreak, adequate blind gate)
   stands. Provenance stays recorded in #92/#93.
3. **Room durability anomaly** — the 2026-08-29 S22 observation (DB absent for
   hours after `install -r`; library rendered non-Room state) had no owning
   slice. Added A8: reproduce, classify ours vs OEM/SQLite artifact, fix or
   close with evidence. Gates Phase C — C1 derives first-run state from
   durable facts.
4. **Device re-verification debt** — A1/A2/A4/A5/A6/A7 closed on host evidence
   with pending device notes. A register was added to the roadmap's
   outstanding-verification section; the runs batch into the next device
   sessions instead of reopening the items.
5. **E0 storage-location gate** — the data-survival review promoted to a gate
   in front of E1 phases 2/3 (#89 shipped the codec only): archive location
   (app storage vs SAF grant incl. re-acquisition), book files copied vs
   referenced, generated audio in/out of the archive, measured SAF throughput
   at PCM-file scale.
6. **Later-table gates refreshed** — the CosyVoice row rewritten to the D3
   verdict (DiT-gated + quality-flagged, disk-only); the auto-delete gate
   restated as the unbuilt eviction design, not the satisfied A4.
7. **G0 narration-quality corpus scheduled** — G1's scope was bounded by an
   unscheduled benchmark. The corpus build is now G0, reusing the spike-tts/D3
   harness; G1's rules are bounded by measured failure classes only.
8. **Docs + ordering notes** — architecture.md §2 brought current (core-ui,
   core-backup, feature-ocr/settings/share rows; F2 search shipped);
   F3 marked the hostile-input audit's first consumer; G2's gesture
   discrimination defined against B3's pressed-passage interaction. The roadmap
   F2 row and the open-bugs search entry corrected to Complete (#90) —
   modules.md was already accurate.

Docs only — no code changes.

## 95. B4 — teal-led light theme; M3-default lavender card surfaces rejected (2026-08-31)

Owner call during the first B4 device pass on the S22. Two findings, one
palette retune:

- **The "light blue" card background was M3 defaults leaking through.** B1
  themed only a subset of roles, so `Card` rendered on
  `surfaceContainerHighest` = M3 lavender `#E6E0E9` against the cream
  background — the owner rejected it on device ("light blue background on
  the player cards"). Fix: the full `surfaceContainer*` ramp is now
  overridden with warm cream shades; the card tone (`surfaceContainerHighest`)
  was deepened a second time after on-device feedback that the first pick
  (`#E6DCC6`) lacked separation from the background — final `#E0D2B6`
  (~1.30:1 against bg `#F5EFE0`, ink ~12:1). Locked by
  `AyvuThemeTest.lightThemeCardContainerIsCreamNotM3Lavender`.
- **Teal-led light theme** (owner pick among retune directions): deep teal
  `#0B5F72` becomes `primary` (docked card play circle, transport pills,
  read-along-adjacent accents), amber `#7A5200` demotes to `secondary`
  (the bar's generated segment, favorites), containers swap accordingly.
  `tertiary` stays teal — the read-along highlight is unchanged. Contrast
  re-verified: white-on-teal 7.3, teal-on-paper 6.3, white-on-amber 6.9,
  amber-on-paper 6.0. Dark theme unchanged. The `SegmentedProgress` legend
  recolors with the roles: teal = listened, amber = generated-unlistened.
  Bar legend docs updated in `SegmentedProgress`/`PlayerCard`.

Found in the same pass: `SegmentedProgress` crashed on device —
`RowScope.weight(0f)` throws (`invalid weight; must be greater than zero`)
whenever a segment measures zero (1% book with no pregen: played+generated
≈ 0; book end: empty = 0). Zero-weight segments are now not emitted.
Lesson: the host-only B3 leg could not catch this — a pure-layout invariant
with no JVM surface. Device visual acceptance caught it within minutes.

Three more device findings from the same S22 pass, fixed on the spot:

- **Weighted segments had zero height.** `SegmentedProgress`'s inner Boxes
  carried only `weight(...)` + background — a bare weighted Box wraps to
  0.dp tall, so the bar rendered as bare track. Segments are now
  `fillMaxHeight().weight(...)` (and the first host build after the palette
  swap showed the played segment correctly).
- **One UI's paint-level font scale ≠ `Density.fontScale`.** At 2.0× the
  measured layout's real line pitch was 148 px while
  `30.sp.toPx()` reported 107 px (fontScale 2.0, density 2.8125) — the
  sp-derived pitch under-counted, pages over-filled, the last line cropped
  and the page indicator was pushed out of the clipped column. Fix: body
  pagination now keys on the pitch measured from `bodyLayout` itself and the
  indicator reserve is measured from a real layout of the indicator text —
  no parallel sp→px math can drift again. Verified: indicator
  "Page 84 of 145" renders at 2.0× with the last line fully visible.
- **The teal generated segment is sub-pixel on very long books.** The
  fraction's denominator is book-time remaining, so a realistic pregen
  cushion (45 s–a few minutes) against ~27 h remaining paints < 1 px on
  Deepness. Played-segment rendering was verified on device; making the teal
  segment visible on long books needs a different denominator (e.g. the
  pregen horizon) — owner decision pending, B4 remains open for it.

Also noted: the library row's "Delete offline audio" menu item deletes
directly (no confirm); the ConfirmDialog added in #94 covers the Settings
offline rows. Wiring the same dialog into the library menu is a follow-up.

## 94. B3 — surface redesign: the Ayvu system on all four surfaces (2026-08-31)

The full retune (user decision: layouts/spacing/cover treatment change, not
just token substitution) covering the roadmap's four ordered steps.
Presentation-only EXCEPT one additive state field. What landed:

- **Cover unification** — new core-ui `BookCover(bitmap, fallbackInitial,
  contentDescription, modifier)`: one portrait treatment (clipped
  `shapes.small`, `surfaceVariant` fill, bitmap `ContentScale.Crop`, else the
  book's initial in `headlineMedium`). Sizing comes from the modifier: 56×80
  on library rows, 48×64 on the player card. The two decode paths stay at the
  callsites (sidecar file vs `viewModel.cover` bytes).
- **Compact player card** — a little less tall by requirement: interior
  paddings 12→`AyvuSpacing.SM` (8.dp), title→bar gap 6→`XS`, transport row
  paddings/gap 12/8→`SM`, cover 64×64→48×64, and the three transport controls
  (−30s / play-pause / +30s) at a UNIFORM 48.dp height (M3 minimum touch
  target; was ~40.dp pills + 52.dp circle). Pills disabled while no book is
  loaded. Card now uses `shapes.large` + named `AyvuElevation.Card`.
- **Two-tone progress bar** — new core-ui `SegmentedProgress(playedFraction,
  generatedFraction)`: 4.dp tall, amber `primary` = listened, teal
  `secondary` = generated-but-unlistened, `surfaceVariant` track = remaining.
  Backing state: `PlaybackUiState.generatedAheadSeconds` (book-time seconds of
  `PregenQueue.aheadSeconds`) published through `stateCopy` (CR-8/CR-9 home)
  with derived `generatedAheadFraction` clamped [0..1] — timeLeftSeconds is
  WALL-clock listening time at speed (BookProgress.remainingSeconds divides
  the 1.0× remainder by speed), so the denominator is `timeLeftSeconds *
  speed` in book time; the plan's assumed `÷ speed` direction was wrong and
  was corrected against the `BookProgressTest` speed cases. The generated
  segment is additionally clamped to
  `1 - played` at the callsite, so it never paints over the played segment;
  disk-tier cached-but-not-queued audio is NOT counted (no cheap book-wide
  cached fraction exists — a per-key `PcmPassageCache.contains` scan would be
  a larger change).
- **Library rows** — new core-ui `LabeledProgress(progress, label)` replaces
  the duplicated bar+percent pattern (bars stay single-segment: they show
  offline disk usage, not ahead-audio); `formatPercent` extracted (was
  duplicated inline); rows get `shapes.large` + `AyvuSpacing.MD`.
- **Reader** — chrome/margins tokenized (page margins 20→`LG`); page
  indicator "Page N of M" (only when >1 page) with a bottom-crop-safe
  reserve: `indicatorReservedPx = ceil(labelSmall.lineHeight) + 2 * XS`,
  passed as `reservedPx` to `TextPagination.linesPerPage` for BOTH page kinds
  (same mechanism as the title reserve, decisions #87) so the last-line
  invariant holds by construction; the title gap + rendered title padding
  both read `AyvuSpacing.MD` and must stay equal. Pressed-passage feedback:
  middle-third touch highlights the passage's char range with
  `surfaceVariant` (no bold — distinct from the read-along
  `tertiaryContainer`+Bold highlight); the string rebuild keys on the press,
  the layout measurement does NOT (no re-measure on press). Reader
  empty/failure states now use core-ui `EmptyState`.
- **State encodings** — `PillButton` gains `enabled` with explicit disabled
  colors (`surfaceVariant`/`onSurfaceVariant`); the share Failed card uses
  `errorContainer`/`onErrorContainer` (palettes keep M3 error defaults — no
  brand error tone was contrast-verified, noted on `AyvuLightColors`).
- **Settings** — all paddings tokenized; failed-pack Retry is a `PillButton`
  (default height — the 48.dp uniform height is player-transport-only);
  offline-audio Delete sits behind a `ConfirmDialog` ("Delete offline
  audio?" — frees <size>, regenerable).
- **Share result** — all three verdict cards `shapes.large`; paddings
  24→`XL`, 16→`LG`.

Audit: zero `RoundedCornerShape`/`CircleShape`/`Color(0x…)` literals remain in
the four feature modules; the only remaining dp/sp literals are the reader's
`30.sp` measurement constant, `SWIPE_PAGE_THRESHOLD`, and PillButton's
content padding. Tests: `AyvuThemeTest` +elevation/`formatPercent` locks,
new `PlaybackUiStateTest` (speed conversion + clamps). Host-verified; device
visual acceptance ran in the same-day B4 pass (decisions #95/#98).


## 93. D3 — first device column: the four-engine comparison on the S22 (2026-08-30)

The D3 comparison ran on the S22 (SM-S908U1, SDK 36, locked/off screen,
instrumented, 6 ORT intra-op threads, CPU EP) over the shared 8-row corpus
(`d3_corpus.tsv`: the two #30/#31 passage blobs + 6 narration probes covering
honorifics/numbers, dialogue, a long compound sentence, scene-break furniture
and one pt-BR row). Tokenization/phonemization is host-precomputed into the
TSV (espeak-ng for Kokoro; espeak-ng + the upstream `TextCleaner` table for
Kitten; sentencepiece ids for MOSS, validated against the manifest's own
gold `text_token_ids`) — the table compares inference only. One blind A/B/C/D
listening pass on the "Ms. Rivera…" probe is the quality gate; the owner
ranked **MOSS > Kokoro**, flagged CosyVoice3 as wrong-language/duplicated and
Kitten as blank (both confirmed by the measurement data below).

| Engine | Engine open | Corpus RTF (per row) | Memory | Quality gate |
|---|---|---|---|---|
| **Kokoro-82M fp32** (baseline) | 1992 ms | 0.67–0.99, avg **0.77**, all rows finite | in line with #86 | **2nd of 4** |
| **KittenTTS Nano 0.8 fp32** | 754–942 ms | 0.28–0.36, avg **0.31** — but **every output contains NaN samples** | session ~60 MB | **4th — blank audio** |
| **MOSS-TTS-Nano 100M** | 6255 ms (4 sessions) | 2.75–4.96 (standalone pass; 6 non-truncated), 3.2–5.4 (unified pass) — **decode-dominated AR**; 375-frame cap truncated both blobs | sessions open ~0.95 GB PSS, decode plateau ~1.4 GB | **1st of 4** |
| **CosyVoice3 0.5B int4** | prompt path 7.4 s | **RTF 12.5–31.1** (first entry ×3: 12.5/13.4/13.8; rest mean 18.5) — flow stage dominates (68–75%), matches #49's 12.6–14.4 on the blobs | VmHWM **3.22 GB**, total PSS 377 MB | **3rd — wrong-language/duplicated audio** on the honorific probes |

- **KittenTTS Nano — measured DROP for on-device use.** The pinned fp32 pack
  (`KittenML/kitten-tts-nano-0.8-fp32`, sha256 `320564d2…`/`8aa7cee2…`,
  byte-identical to the `onnx-community` export) produces deterministic NaN
  audio on the S22's ARM CPU while the identical inputs/bytes are finite on
  x86 (ORT 1.23.2 and 1.24.3 host). Swept and ruled out: ORT-android
  1.23.2 **and** 1.29.0, ALL_OPT/BASIC_OPT, memory-pattern on/off, CPU arena
  on/off, 1/6 intra-op threads. Two harness findings recorded with it: the
  graph's hard sequence cap is **509 tokens incl. framing** (longer inputs
  fail in `/bert/Expand` — the runner now chunks at punctuation boundaries
  like upstream `generate()`), and the `.npy` magic is 6 bytes (`\x93NUMPY`).
  RTF numbers are recorded but the audio is unusable — size never substitutes
  for the oracle (#92 rule). Nano stays never-on-device; HiBreak column
  untested (deferred with the device, #91 pattern).
- **MOSS-TTS-Nano — measured keep-as-candidate, pregen-gated.** Realtime
  fails by 3–5× (RTF ~3.5 avg; decode is ~70–85% of the wall), so it cannot
  do live synthesis — but the quality gate ranks it **first**, pt-BR
  synthesized cleanly (73 frames, finite — coverage measured, not claimed),
  and streaming output fits the pre-generation queue shape. Memory needs one
  recorded deviation from the demo engine: **`setMemoryPatternOptimization(false)`
  + `setCPUArenaAllocator(false)`** — with them the AR decode plateaus at
  ~1.4 GB; without them RSS balloons to 6.6 GB and lmkd kills the process
  (thrashing 305%). Voice `Ava` (first English-group builtin, female, to
  match af_heart). 48 kHz output written as mono (channel-averaged, the demo
  engine's own rule). Adoption decision moves to the pre-generation budget
  slice; the 0.75 GiB pack is a coverage/cloning play, as pinned in #92.
- **CosyVoice3 — stays DiT-gated (#21/#23), now also quality-flagged.** The
  corpus loop reproduces #49's blob RTF (12.5–13.8) but degrades on short
  probes (RTF 15.6–31.1) and the blind gate heard wrong-language/duplicated
  audio: the `probe-miss-rivera` and `probe-dr-chen` outputs are byte-identical
  (same peak 0.70185685, same 9.52 s) — the sarah-cloned pipeline collapsed
  both honorific probes to the same synthesis. Disk-only playback constraint
  unchanged; not an adoption candidate.
- **Kokoro baseline stands.** 0.77 corpus RTF on the merged corpus, finite
  everywhere, 2nd in the blind gate behind MOSS. No adoption signal moves:
  the shipped v1 primary keeps its position.
- **Provenance** (sha256 at staging, full list in the runner log):
  Kitten `kitten_tts_nano_v0_8.onnx` `320564d2…`, `voices.npz` `8aa7cee2…`;
  MOSS TTS repo @ `f52645cb…` (9 files incl. `browser_poc_manifest.json`
  `097d80e9…`), codec repo @ `ceff0d07…` (4 files); CosyVoice3 pack re-staged
  from the `cosyvoice3-pack.md` pinned revision — derived `sarah16/24.wav`
  hashes match the recorded values exactly. Artifacts: `d3_results.json`
  (merged), `d3_results_{kitten,moss,cosyvoice}.json` + per-engine WAVs on
  host and device; corpus + probe WAVs persisted for future gates.
- **Run-to-run variance note**: a second full pass after ~2 h of sustained
  benchmarking shifted Kokoro +1–16%, MOSS −11…+42% (one outlier), Kitten ±5%
  — same orderings, wider spreads under thermal load. The HiBreak column is
  measured below; this table is the **S22 column** of D3.
  Follow-up recheck: the pack's **premade** `classic-zh` voice on the same
  English probe produced distinct, finite audio (3.72 s vs the collapsed
  9.52 s clone) but is unintelligible for English as the language mismatch
  predicts — the pinned pack ships no English premade beyond sarah, so the
  cloned-probe duplication finding stands as the CosyVoice3 quality record.
  RTF ~17–20 on the premade pass, consistent with the loop numbers.
- **sherpa-onnx as-is spot check** (user-requested): their stock Android TTS
  demo (v1.13.6, unmodified `SherpaOnnxTts` app, bundled
  `kokoro-en-v0_19` fp32 + their own libonnxruntime) on the same S22 and the
  same probe text synthesized 4.99 s audio in **9.1 s wall → RTF ≈ 1.83**
  (UI-app measurement, includes AudioTrack streaming; their export is Kokoro
  **v0.19**, not our v1.0 packs). Our core-tts port on the pinned v1.0 fp32
  measures **RTF 0.70** on the same text — ~2.6× faster wall-clock under the
  same 6-thread CPU condition. The comparison is indicative, not
  harness-identical (their graph revision, frontend and runtime differ), but
  no evidence that switching runtimes would beat the shipped pipeline.
- **HiBreak column measured (2026-08-30, Bigme HiBreak, MT6765 8×A53, SDK 34,
  3.97 GB RAM, 14 GB free, same corpus/harness):**
  - **Kokoro**: 8/8 rows finite, RTF **2.83–3.65, avg 3.01** (open 8477 ms,
    total PSS 960 MB) — ~3.9× slower than the S22 on the same harness, in
    line with the B6 expectation that live synthesis cannot sustain playback
    (pre-generation mandatory, `bugs.md` B6).
  - **KittenTTS Nano**: 6/6 en rows, RTF 1.37–1.49 — **NaN again** with
    byte-identical output sizes to the S22 run: the ARM NaN bug is
    ORT-android/ARM-wide, not S22-specific. Drop reinforced.
  - **MOSS-TTS-Nano**: **unavailable — lmkd kill.** The AR decode plateau
    reached 2.54 GB RSS (PSS ~1.1–1.3 GB) before the oom killer reclaimed the
    process ("min watermark is breached even after kill"), twice, in
    standalone and in-pass runs at the same decode step. The 3.97 GB device
    cannot hold the MOSS decode plateau next to the system. No HiBreak RTF
    exists — the wall is memory, not speed.
  - **CosyVoice3**: skipped, recorded — 3.22 GB VmHWM on the S22 exceeds the
    HiBreak's 3.97 GB total RAM before lmkd headroom, and #49 already ruled
    it disk-only.
  - Harness note: `D3CompareRunner` now flushes `d3_results.json` after each
    completed leg (incremental write) — the HiBreak kills would otherwise
    have lost the finished kokoro leg.

## 92. D3 — MOSS-TTS-Nano promoted to a comparison leg (2026-08-29)

The candidate sweep for the D3 engine comparison (landscape.md §"D3 comparison
sweep", primary source: the independent Picovoice on-device TTS benchmark,
2026-07/08, benchmark code Apache-2.0) found one candidate worth adding to the
three-way spike (KittenTTS Nano vs CosyVoice3 vs Kokoro baseline):
**MOSS-TTS-Nano** (OpenMOSS, Apache-2.0, 2026-04).

- **Why a leg**: 0.1B AR audio-tokenizer + LLM; 20 languages including the app's
  advertised es/it/pt (Kokoro's coverage gap); streaming output; prompt-based
  voice cloning — a CosyVoice3-class capability at a quarter of CosyVoice3's
  3.47 GiB; 48 kHz stereo output. Official Android ONNX Runtime Kotlin example
  in-repo, standalone ONNX CPU packs — it fits the existing `TTSEngine`/ORT
  seam with no second inference convention.
- **Pinned, like every pack before it** (decision #23 provenance rule):
  `MOSS-TTS-Nano-100M-ONNX` @ `f52645cb467506d8e18e746ddd59482685b74e58`
  (671.9 MB) + `MOSS-Audio-Tokenizer-Nano-ONNX` @
  `ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae` (90.6 MB) ≈ 0.75 GiB runtime
  total. Footprint correction: "tiny" refers to parameters, not the shipped
  artifact — MOSS competes on coverage/cloning/streaming, not size.
- **Open questions the spike must answer**: pure-AR decode RTF on
  HiBreak-class CPUs (no external benchmark covers it; the Picovoice set has
  no AR-0.1B entry), 48 kHz stereo → 24 kHz mono resample cost, and whether
  the AR decoder's chunked long-text path fits the pregen queue's passage
  grain.
- **Sweep corrections recorded with this decision**: Kitten Nano's external
  benchmark (3.1× core-hour, 10.5 s FTTS, no streaming) downgrades its HiBreak
  RTF premise to a hypothesis — the D3 Nano leg measures, never assumes;
  Supertonic 3's repo was announced for archival (2026-07-23, no further
  open-source development) — the strongest coverage/speed candidate on paper
  now carries a supply-lifecycle gate: pin the HF revision + hashes, treat
  upstream fixes as ours. Supertonic stays OUT of D3's legs (landscape
  2026-08-26 gates — read-along duration introspection unverified — still
  stand), pending the Nano/MOSS device numbers.
- **Rejections recorded in the sweep**: Soprano (4.1×, slower than realtime),
  Neu-TTS-Nano (GGUF — would force a second inference convention), Chatterbox
  (7.5 GB peak), Pocket TTS (~~best mid-tier CPU ratio but no Android runtime
  path — watch, not a leg~~ — **see the correction below**).
  - **Correction (2026-09-11): the Pocket TTS rejection was wrong.** An Android
    ONNX path exists and ships: NekoSpeak v1.4.2 runs pocket-tts as **five ORT
    sessions** (Mimi encoder/decoder, text conditioner, `flow_lm_main`,
    `flow_lm_flow`) with int8 on the heavy graphs — the identical graph split
    the community export publishes (`KevinAHM/pocket-tts-onnx`, which also
    ships int8 + streaming and per-language bundles), with a C++ ORT runtime
    (`VolgaGerm/PocketTTS.cpp`) as a third path. At **100M params / ~176 MB,
    6 languages (en/de/fr/it/pt/es) and zero-shot cloning**, it is a D5-class
    cloning engine ~20× smaller than either incumbent (CosyVoice3 3.47 GiB,
    Chatterbox 3.2 GB) and the first candidate that could plausibly clone
    inside a phone's live budget rather than pregen-only. It was never a D3
    leg and this does not add it to one — the open gates are an unmeasured
    ARM/phone RTF, a **second G2P convention** (Misaki/sentencepiece beside
    espeak-ng, #97), unverified read-along timing, and CC-BY-4.0 gated weights.
    Full row: landscape.md §"D3 comparison sweep".

Docs only — no code, no pack staging. The spike runs in `spike-tts` per the
roadmap D3 acceptance (one comparison table + typed per-engine keep/drop/defer).

## 91. D1 — survive-seek prefill (2026-08-29)

The device-measured seek bottleneck (bugs.md: 60 s dead-owner ensure wait,
fixed in the #78 addendum) had a second half: even with the fill restarted, a
seek CANCELLED the fill via `stopEverything`, then restarted it from the
target — the restart's first `ensure` re-planned and re-synthesized from
scratch, and any in-flight synthesis died mid-passage (cold cache target,
whole cushion rebuilt). D1 lands the survive-seek half of the roadmap's
"let in-flight ensure survive" design; the 30 s-horizon re-parameterization
is a small follow-up.

- **`PregenQueue.ensure(from, rearm)`**: the caller can supply a live-playhead
  reader; between passages the plan yields the moment the current playhead
  overtakes the next planned key, so an in-flight ensure stops synthesizing
  stale audio instead of finishing its old plan. The next tick re-prunes and
  re-plans from the new position (backward/unchanged playhead keeps the plan —
  every planned key is still after it).
- **`stopEverything(stopFill = false)`** (service): seekBy / navigate /
  navigateUndo pass it, so the long-lived follow-playhead fill is NOT cancelled
  on in-place navigation; its ensure re-arms from `machine.position` on the
  next tick (the fill's tick snapshots the playhead; the rearm lambda reads it
  between passages too). True stops (pause, stop, player rebuild, book/voice
  change) keep cancelling.
- **Guarded restart**: the nav paths restart the fill only when it is absent
  (`pregenJob == null`) — a surviving fill is never double-started.

Evidence: `PregenQueueTest` +`in-flight plan yields when the playhead jumps
forward mid-ensure (D1 survive-seek)` (1 synthesis total, stale result
pruned, refill from the new playhead); `PlaybackServiceFillRestartTest`
+`a seek keeps the fill job alive - in-flight ensure survives (D1)` (the same
`pregenJob` instance before/after `seekBy`, not cancelled); full
`:feature-player:testDebugUnitTest :core-player:test ktlintCheck
:app:assembleDebug` green. Device seek-latency re-measurement (S22/HiBreak,
ten ±30 s seeks resolving from `buffer|pregen|disk` with zero sync synthesis)
remains the acceptance — pending the next device session.

**Device addendum (S22 Ultra, 2026-08-29, commit ff46673+):** the seek
acceptance is now measured on-device with the probe build:
`AyvuTap tap-to-audio ms=266 source=pregen action=seek_forward` (first +30 s),
`ms=237 source=disk action=seek_backward` (−30 s), `ms=192 source=pregen
action=seek_forward` (third). All three resolve from the D1 `pregen|disk`
tiers with **zero synchronous synthesis at seek time** — no `buffer: waiting`
log appears on any seek — and consecutive `loop: source=pregen` after each
seek shows the surviving fill still serving the cushion (the pre-#91 path
paid ~1 s command + 60 s dead-owner wait + RTF-scaled cold synthesis, 79.6 s
on the S22). Cold first-play after process start measured 73.5 s
(`AyvuTap ms=73539 source=synthesized action=resume`) — the full 45 s cushion
synthesis the G1 start policy buys; that path is explicitly not an SLO
(cold-path principle). `PregenE2eTest` also green on-device (43 s) covering
the whole-book pregen → playback-over-cache path. HiBreak leg pending the
actual HiBreak hardware.

## 90. F2 — library search shipped (2026-08-29)

Local title/author search on the library home — no network, no index dependency
(content matching stays `TextIndex`'s share-and-identify job; F2 is a UI-level
filter over the Room `books` rows).

- **VM**: `searchResults` = `repository.books` (Room) combined with a
  `MutableStateFlow<String>` query, filtered case-insensitively on title OR any
  author, trimmed; blank query = full list. `setQuery` drives it; the
  continue-list (`recent`) is deliberately NOT filtered — resume stays one tap
  away while filtering the library section.
- **UI**: an `OutlinedTextField` ("Search title or author") above the list in
  `LibraryScreen`, with search/clear icons; the "Library" section renders
  `searchResults` instead of `library`; a non-blank query with zero matches
  shows `EmptyState("No books match…")`. `LaunchedEffect(query)` syncs the
  field to the VM — no debounce needed at this list size (search is
  host-tested; on-device visual verification pending a device pass).
- **Tests**: `LibraryViewModelTest` +4 — blank query shows all; title
  case-insensitive; any-author match; trimming + empty result + clear-restores.

Evidence: `:feature-library:testDebugUnitTest` 13/13 green; `:app:assembleDebug`
+ `ktlintCheck` green (baseline regenerated to match the on-disk file shapes);
RUN-TIME device verification deferred to the next S22 pass (the library screen
is exercised there).

## 89. E1 backup — phase 1: core-backup codec + DTOs (2026-08-29)

First phase of the E1 backup slice (post-v1-plan Slice B): the pure-JVM
`core-backup` module lands the versioned archive codec — DTOs
(`BackupSnapshot` + the six section row types) and `BackupCodec`
(`write(snapshot) → zip bytes`, `read(bytes) → BackupReadResult`). No
persistence or UI wiring yet; phases 2 (snapshot/merge in core-persistence)
and 3 (SAF edge) come in their own slices.

**Format contract (v1)** matches the post-v1-plan layout: `manifest.json`
(version/appVersion/exportedAt), six JSON section files
(settings/library/passages/progress/bookmarks/position_history), optional
`books/<id>.<ext>` OPAQUE bytes. Section names are the format contract.
Serialization is manual no-codegen `JsonElement` (the core-tts pattern; no
serialization plugin, per post-v1-plan). Output is byte-stable for a given
snapshot (deterministic section order + sorted book files) — useful for
diffing/checksumming exports.

**Failure typing — never a partial merge:** `read` validates `version == 1`
BEFORE any section parse (`UnsupportedVersion`), missing sections are
`MissingSection`, a broken section is `MalformedSection`, and a non-zip blob
fails `NotAZip` via an explicit `PK` magic check (ZipInputStream silently
yields an empty archive for garbage — a real hostile-input trap). Book files
are never JSON-parsed. Forward-tolerant within a version
(`ignoreUnknownKeys`). The zip-magic + opaque-bytes handling are precisely the
"hostile-input and resource limits" review items (`roadmap.md` Further reviews)
applied at the format boundary before any restore UI exists.

Evidence: `:core-backup:test` 8/8 green (`BackupCodecTest` — round-trip/empty/
byte-stable/garbage/missing/future-version/malformed/opaque-bytes);
`:core-persistence:testDebugUnitTest :app:assembleDebug ktlintCheck` all green
(the new module's files are NOT in the ktlint baseline — fully enforced);
baseline regenerated to absorb pre-existing violations in
PlaybackService/SettingsViewModel (CI debt from 7d27226/533f2a4).

## 88. Room reinstall observation — code trace + corrupt-db quarantine guard (2026-08-29)

Follow-up to the open-bugs Room row (S22, 2026-08-29): hours with no `files/databases/` yet "Continue listening" cards rendering, then a 68 B fragment at the db path.

**Trace result — no app-side non-Room book path exists.** `LibraryViewModel` sources `library` (`repository.books`), `recent` (`progressDao.observeAll()` ⊗ books) and `readProgress` (`passageDao.chapterCounts()` ⊗ progress) exclusively from Room DAOs; the only `LibraryStore` binding in the composition root is `RoomLibraryStore` (`PersistenceModule`); the provider is a standard `Room.databaseBuilder(..., "local-tts-reader.db")` with `MIGRATION_1_2`; the DB's first open happens at app start (the `LocalTtsReaderApp` index rebuild reads `cachedBooks()`). The cards' render therefore required an open, valid database — the file absence at 17:44 is a filesystem/samsung-data-dir layer event, not an app data-flow defect (the FS-side deletion mechanism was never reproduced on the host; it stays open).

**App-side fragility found + shipped:** a corrupt/truncated file at the db path crashes the launch-time rebuild on every start (Room throws at first DAO access — `LocalTtsReaderApp` reads `cachedBooks()` at app start, with no recovery path). `CorruptDatabaseGuard` (core-persistence, called from `PersistenceModule.provideDatabase` before the builder) moves a non-SQLite file — plus its `-wal`/`-shm` siblings — to `files/corrupt-db/<timestamp>/` (preserved, never deleted: not a destructive fallback, decisions #22), so Room creates a fresh database and the app starts empty but recoverable (re-import, or the backup slice) instead of crash-looping on a 68 B artifact. Valid SQLite files (header + ≥ 100 B) and 0-byte files (SQLite initializes those itself) pass through untouched.

Evidence: `CorruptDatabaseGuardTest` ×4 green (garbage quarantined + path cleared, wal/shm move with main, valid db untouched, empty/missing untouched); `:core-persistence:testDebugUnitTest :app:compileDebugKotlin` BUILD SUCCESSFUL. Any later reproduction on-device should pull the preserved artifact from `files/corrupt-db/`.

## 87. B4 device-pass finds — espeak live status + chapter-title px-as-dp gap (2026-08-29)

Three small fixes from the S22 acceptance pass (B4 font-scale leg + settings pack
verification), each device-verified:

- **espeak-ng status flips live** (`fix(settings)` 533f2a4): the readiness line was
  computed inside the settings `combine` but only re-evaluated when a preference
  changed — the pack registry emits `ready` BEFORE `EspeakStager.stage()` finishes,
  so the filesystem check ran too early and the UI stayed "not staged" until an
  unrelated state change. `espeakStageTick` bumps after successful staging (folded
  into the combine via a nested `packState` to stay within the 5-flow overload);
  verified on-device (S22, API 36): wipe pack + bundle → Download → ready + staged
  without re-entry.
- **chapter-title gap px-as-dp unit bug** (`fix(reader)` 8d7870c): the first-page
  title reservation computed `titleGapPx = 12.dp.toPx()` but the rendered padding
  used `titleGapPx.dp` — at density 2.8125 the rendered gap (~93 px) was ~3× the
  reserved (~33 px), pushing the page body down ~60 px, so the B3 bottom-crop fix
  reappeared at 2.0× font scale. Render `12.dp` directly (reserved == rendered);
  verified on-device at 1.0×/1.3×/2.0× — last line fully visible, and the page
  gains back the phantom-gap line.
- **PregenE2eTest assertion overloads** (`fix(test)` 1961c7d): the sampleRateHz
  check asserted `(Int, Int, String)`; JUnit4 has no such overload. Now
  `(String, Long, Long)` — the rate is 24 kHz integer PCM.

Evidence: the on-device S22 sessions cited above; `SettingsViewModel.kt` /
`ReaderScreen.kt` changes as committed.

## 86. D3 — Kokoro precision measurement: fp16 / int8 / q8 (2026-08-28)

Measured three quantized precisions against the pinned fp32 CPU oracle on
the S22 — the D2 follow-up #67 deferred ("needs X to adopt: a measured
INT8/fp16 candidate that beats CPU RTF without sampling divergence, with
`max_abs_diff <= 0.001`"). Measurement slice only: the harness gained a
model-precision axis (`KokoroBenchmarkRunner.ModelPrecision` +
`runPrecision` → a shared `measure` body), a host-side q8 generator
(`tools/quantize_kokoro_q8.py`, dynamic QUInt8 with the load-bearing
`conv_post` exclusion from kokoro-onnx-export), and per-precision
oracle-gated result JSONs **+ candidate/oracle WAV pairs** for A/B
listening. **No production pack or engine path changed.**

Artifacts (thewh1teagle `model-files-v1.1`, same lineage as the pinned fp32;
q8 generated host-side from the pinned fp32):

| label | source | size (B) | sha256 |
|---|---|---|---|
| fp32 (oracle/pinned) | kokoro-v1.0.onnx | 325 505 369 | beb0d184… |
| fp16 | kokoro-v1.0.fp16.onnx | 163 527 961 | f3a290d3… |
| int8 | kokoro-v1.0.int8.onnx | 114 119 327 | ae315a79… |
| q8 | kokoro-v1.0.q8.onnx (host-generated) | 114 176 961 | a259cd9f… |

Results (S22 Ultra SM-S908U1, freshly rebooted — see device context; all
candidates on CPU EP, oracle = the pinned fp32; corpus = P&P en-us 40.4 s +
Dom Casmurro pt-br 22.8 s):

| device | precision | engine-open ms | RTF (en/pt) | totalPss kB | VmHWM kB | max_abs_diff | mean_abs_diff |
|---|---|---|---|---|---|---|---|
| S22 Ultra | fp32 (baseline) | 1632 | 1.19 / 1.17 | 1 356 299 | 1 405 448 | — | — |
| S22 Ultra | fp16 | 4793 | broken (0.25s) / 1.16 | 2 548 022 | 2 619 480 | 0.723 | 0.041 |
| S22 Ultra | int8 | — | **unavailable** | — | — | — | — |
| S22 Ultra | q8 | 3754 | 1.79 / 1.73 | 2 454 217 | 2 619 480 | 0.700 | 0.044 |

Findings:
- **fp16 — REJECTED.** The en-us passage synthesized as a **0.25 s stub of
  pure silence** (pulled + analyzed on host: peak 0.0, zero nonzero samples);
  pt-br produced full audio but `max_abs_diff = 0.723`, ~3 orders over the
  0.001 gate.
- **q8 — REJECTED.** Full audible output (no truncation), but
  `max_abs_diff = 0.700` fails the oracle gate AND RTF 1.73–1.79 is *slower*
  than the fp32 baseline's 1.16–1.20 (dynamic quant pays runtime re-quant).
- **int8 — CANNOT RUN on the CPU execution provider.** Fails at open with
  ORT `not_implemented`: `Could not find an implementation for ConvInteger(10)`
  node `/text_encoder/cnn.0/cnn.0.0/Conv_quant`. The same-lineage int8 graph
  is pre-quantized (static `ConvInteger`), which ORT's CPU EP does not
  implement for this graph — this is the exact "int8 was regressed to fp32
  on-device" class of loss decision #26 already hit, and #67's "(i) a
  quantized graph that actually beats CPU RTF" remains unmet. Non-fatal:
  `measure` caught it, logged `candidate int8 unavailable`, and the rest of
  the batch completed.
- **Device context / why two earlier runs died:** the first instrumented and
  first foreground runs were reaped by Samsung's lmkd (SigKill) midway —
  device uptime was ~256 days with load 12–23 and triplicated SIM/EPDG
  background churn. After a reboot (load settled to ~6.4) the full six-pass
  batch — CPU, XNNPACK, NNAPI, then fp16, int8, q8 — **completed with no
  kill.** PSS/VmHWM here are cumulative across the whole batch in one process,
  so the per-candidate memory delta is the difference from the CPU baseline
  pass (~+1.1-1.2 GB), consistent with #67's "/roughly double resident
  memory".

Decision per the D2.3 hard rule (adopt only if it beats CPU RTF +
`max_abs_diff <= 0.001` + PSS/thermal not worse):
- **No precision candidate satisfies the rule. Keep production on the pinned
  fp32. No pack change in this slice.**
- fp16 fails the oracle gate (0.723) and is outright broken (silent en-us).
- q8 fails the gate (0.700) AND is slower than fp32.
- int8 is non-runnable on the CPU EP (no `ConvInteger` implementation).
- This is consistent with, and conclusively re-confirms, decisions #26/#67:
  the quantized family is too lossy for the 0.001 oracle gate, and none beats
  the fp32 CPU baseline on this device. The host q8 generator and the
  precision harness axis remain in-repo for any future quantized candidate
  (e.g. a full static-quant INT8 whose graph the CPU EP can actually run) —
  the same measurement leg reapplies.

**Re-run (2026-08-31): int8 on ORT 1.23.2 vs 1.29.0, same S22** — prompted by
the question "would an ORT update viabilize int8?". Two findings:

1. **The #86 blocker is version-bound and is GONE on 1.29.** A minimal
   single-node `ConvInteger(10)` graph fails to open on 1.23.2 with the
   exact #86 error (`ORT_NOT_IMPLEMENTED … ConvInteger(10)`) and **runs on
   1.29.0** (`convinteger: implemented`; `Int8OpsProbe` +
   `Int8ConvIntegerProbeTest`, results self-labeled via the
   `ort_version` instrumentation arg). A static QOperator int8 graph of
   the #86 shape is now *runnable* on the CPU EP.
2. **The realistic int8 candidate still fails the oracle gate — on BOTH
   runtimes.** The #86 int8 artifact's source (`thewh1teagle/kokoro-onnx`)
   has since gone **gated/401** — the pinned-lineage file (ae315a79…) is no
   longer publicly obtainable, a provenance event to record. The current
   public int8-class export is `onnx-community/Kokoro-82M-v1.0-ONNX`
   `onnx/model_uint8.onnx` (177 464 632 B, sha256 `6607a397…`) — a
   **QDQ-format static quant** (51 `QLinearConv` + 39 `QLinearMatMul`, no
   `ConvInteger`), a different op surface from #86's file. Oracle-gated on
   the S22 (fp32 oracle, host-precomputed corpus, 6 threads):

   | ORT | engine open | en-us RTF | pt-br RTF | max_abs_diff | gate |
   |---|---|---|---|---|---|
   | 1.23.2 | 1.7 s | 0.582 | 0.594 | 0.799 | REJECTED |
   | 1.29.0 | 1.5 s | 0.607 | 0.596 | 0.911 | REJECTED |

   Speed is fine (slightly faster than the fp32 oracle on the same
   session; S22 fp32 is realtime anyway) — **quality remains the wall**,
   now measured across two quantization formats (QOperator ConvInteger =
   unrunnable-then, QDQ QLinear = 0.8–0.9 diff) and two runtime versions.
   Same conclusion as q8 (0.700), fp16 (0.723), and candela's production
   int8→fp32 regression: **the 0.001 waveform gate rejects Kokoro
   quantization regardless of runtime version.** Runtime updates change
   op coverage, not quantization error.

Verdict unchanged and now version-robust: production stays on pinned fp32.
If int8 is ever revisited, the remaining untested surface is **weight-only**
(`MatMulNBits`, the chatterbox-q4 op class — verified working on
ORT-android 1.23.2 in the closer-look probe), with the vocoder kept fp32.

**HiBreak leg (same day, ORT 1.23.2, post-reboot clean run):** int8 RTF
**2.621 (en-us) / 2.591 (pt-br)** vs the fp32 oracle at **2.89** in the same
session (bugs.md baseline 3.01) — **only ~11–14% faster on the A53**, vs
Piper's 6×. Engine open 10.2 s. The combined candidate+oracle harness drove
the 3.9 GB device into 200% swap thrash on the first attempt (lmkd killed
the foreground process at 794 MB RSS; a reboot let the batch finish at
1.63 GB PSS / 1.67 GB VmHWM). `max_abs_diff` 0.804 — gate rejected, same as
the S22. **Int8 does not change the small-tier verdict on any axis: the
speed win is marginal where speed matters, and Piper dominates the tier.**
Its only real HiBreak value would be the ~150 MB model-size memory delta.

Owner observations recorded (not gate-changing): blind listening on the S22
pair heard "quality seems similar", with the fp32 oracle slightly quieter —
confirmed on host (int8 RMS 0.074 vs fp32 0.060, ≈2 dB louder, no clipping);
the 0.001 waveform gate is a strict proxy and a perceptual pass heard no
damage. Amending the gate remains an owner decision; the measured record
stands as-is. The runtime-fix boundary for the owner's logged ORT-android
error is **1.25.0** — any future pin bump has its minimum there, not 1.29.

**Fold 8 data point (2026-08-31, ORT 1.29.0, SM-F971B / SM8850, Android 17,
11.3 GB)** — the first device whose cores have the int8 kernel features
(dotprod/i8mm): uint8 RTF **0.36 / 0.50** (en/pt) vs the fp32 oracle's
**0.52** same-session (~30% faster — the int8 speedup scales with the
silicon, unlike the A53's ~13%); `max_abs_diff` **1.119** — gate still
rejected. The main project (`:app`, full instrumented playback set:
PlaybackE2e, VoiceSelectionE2e, PlayPositionE2e, PtVoiceE2e) passed **0
failures** on 1.29.0 on this device.

A/B WAVs + result JSONs: `docs/prints/int8/` (S22 + HiBreak + Fold pairs;
the S22 WAVs are the 1.23.2 run — the 1.29 run overwrote them after its
JSON was pulled).


Evidence: `tools/quantize_kokoro_q8.py` ran (excluded
`/decoder/generator/conv_post/Conv`, 114 176 961 B); `:spike-tts:assembleDebug
:assembleDebugAndroidTest :core-player:test :feature-player:compileDebugKotlin
:app:assembleDebug` BUILD SUCCESSFUL; S22 instrumented+foreground run wrote all
six result JSONs + candidate/oracle WAV pairs (fp16/q8); WAVs pulled and
analyzed on host (RMS/peak + diff).

## 85. Dedicated player thread + session gate — boundary pub collapse (2026-08-28)

The remaining boundary gap after #82/#84 was `pub` (7-45 ms) — but with the notify
(#82) AND the MediaSession updates both gated (only book+phase changes re-publish;
verified 1 session update across a full run), the cost was NOT the IPCs. It was CPU
CONTENTION: the play loop and the prefill's synthesis share [Dispatchers.Default]
(Default runs on min(2, cores) threads, so the boundary path queues behind
inference). Fix: a dedicated single-thread `playerDispatcher` (max priority) runs
the loop + ticker (`runLoop` + `withContext(playerDispatcher)`); synthesis and
persists stay on Default; the dispatcher closes in onDestroy.

- **Measured (S22): `pub` collapsed to 5-11 ms** (was 7-45 ms) — the contention
  was real. The call-site is `startLoop` → `runLoop` (the loop body) + the ticker,
  both on the player thread; the machine's single-writer edge is hardened by the
  single thread.
- The session gate (#85a) + notify gate (#82) are kept: they eliminate IPC churn
  (1 session update, 1 notify per run) even though the gap post-thread is now
  dominated elsewhere.
- **Remaining**: steady-state gap 24-92 ms (median ≈ 68, ~half under 50) — the
  SLO is still not reliably met. Drivers: pre-arm misses at the boundary (the
  rebuilds: `out` 17-82) and the Room progress write on the player thread (`adv`
  8-35, a slow write directly stalls the boundary). Next candidates: raise the
  pre-arm hit rate (arm from the disk tier + queue more aggressively) and/or move
  the progress write off the hot thread (harder — CR-2 ordering).

Evidence: `AyvuBoundary pub=5/7/7/8/6/11/7/5/5` + `AyvuGap 24-92` on the S22;
`./tools/docker-build.sh :feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

## 84. Pre-armed static track — boundary handoff (2026-08-28)

The fallback per the owner's clause ("we have the option to go back to a"): the
streaming experiment (#83) proved MODE_STREAM inert on the S22, so playback stays on
the static model WITH PRE-ARMING.

- **Pre-arm**: [PassageOutput.prearm] (default no-op; implemented in
  [AudioTrackPassageOutput] as a staged spare track) is called by the play loop right
  after `output.play(...)`, targeting the NEXT passage (peeked from `PregenQueue.peek`
  — new non-consuming accessor — then the `PcmPassageCache` disk tier). At the next
  boundary, `play` swaps to the staged track when it matches (rate + exact static
  capacity) — no rebuild on the critical path.
- **Measured (S22)**: the swap path's `out` (the `output.play` call) dropped to
  11-16 ms (was 29-55 ms rebuild); boundary gaps 43-66 ms when the pre-arm had the
  next passage, with rebuilds on a pre-arm miss (queue/cache still filling). The gap
  improved but the SLO is still not met — the ~20 ms publish + ~13 ms advance +
  miss-rebuilds remain.
- **Cleanup**: the MODE_STREAM implementation and its test were deleted (dead; the
  finding lives in #83); the loop's stall diagnostic was simplified to a generic
  pos/target probe.

Evidence: `AyvuPlay out=11/12/16` on swaps, `AyvuGap 43/60/66` when engaged, on the
S22; `PassageOutputReuseTest` prearm-swap + mismatch-fallback tests;
`./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.

## 83. MODE_STREAM output — inert on the S22, reverted (2026-08-28)

Per the owner's decision, implemented a one-MODE_STREAM-track-per-session output to
kill the measured 29-55 ms per-passage rebuild: FAT FIFO (first 2.88 MB, then 1 MB),
relative-head/absolute-marker translation for the cumulative stream, write guard +
debug state.

- **Fails on-device**: the stream track is created and reports `state=started`
  (PLAYING) at the audio-server level, but `playbackHeadPosition` stays 0 forever —
  the server never pulls. Two FIFO sizes (2.88 MB, 1 MB) stall identically, so it is
  MODE_STREAM itself (or this Samsung's stream handling of the config: mono, 24 kHz,
  USAGE_MEDIA/CONTENT_TYPE_SPEECH), not the buffer size. `dumpsys audio` confirmed a
  registered, started track with a frozen head. Reverted to the static model (which
  has played correctly across every earlier run) and pursued pre-arming (#84).
- The write/play guard logged nothing — the writes succeeded; the track was simply
  never drained. This is a device-level behavior, not a logic bug in the streaming
  implementation.

Evidence: `AyvuStall pos=0 … out=state=3 head=0` repeating on the S22 with both FIFO
configs; `dumpsys audio` `AudioPlaybackConfiguration … state:started … sampleRate=24000`.

## 82. Boundary-gap attribution — the AudioTrack rebuild is the cost (2026-08-28)

Follow-up to #81 (marker-accurate gap ≈ 73 ms median, above the ≤ 50 ms SLO). Two
interventions + stage timing on the S22:

- **Notification IPC ruled out.** Per-boundary re-`notify` (an IPC to system_server
  on the player coroutine) was skipped via a transport-key gate (`buildNotifyKey` =
  book + play/pause action; verified: exactly 1 `notify(42)` across an entire run).
  The gap did NOT move — the notify was never the dominant cost. The skip stays:
  fewer IPC wakeups for free, with the documented caveat that the shade's passage
  ordinal lags until the next phase/transport change.
- **Stage attribution** (`AyvuBoundary adv=… pub=…` + `AyvuPlay out=…`): the
  marker-to-dispatch gap decomposes into
  `out` (the `output.play` call: 29-55 ms, avg ≈ 40 ms) +
  `pub` (structural publish, session updates only: 5-42 ms, avg ≈ 20 ms) +
  `adv` (machine advance + Room progress write: 4-23 ms, avg ≈ 13 ms).
  `out` is THE cost: `AudioTrackPassageOutput.play` rebuilds a fresh MODE_STATIC
  track per passage (release + build + full static write + setPlaybackRate + play).
  The S4 reuse rarely engages because most passages differ in size, and a static
  track's capacity is part of its identity (a shorter re-write would replay stale
  tail audio — AOSP static server plays to the constructed frame count), so the
  rebuild is paid at nearly every boundary.
- The probes remain debug-gated (`AyvuBoundary`/`AyvuPlay` join `AyvuGap`/`AyvuTap`).

Evidence: `AyvuPlay out=29/40/49/55`, `AyvuBoundary adv=4-23 pub=5-42`,
`AyvuGap 54-90 m=1` on the S22; `./tools/docker-build.sh :feature-player:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.

**Next (decision pending)**: the fix direction is the output model — (a) pre-arm the
next passage's track during playback (overlap the rebuild off the critical path),
(b) a single streaming track per book (reverses the S4 static-track verdict, justified
by the measured 29-55 ms rebuild), or (c) accept ~70 ms as the shipped boundary until
a rewrite. Not started — output-model change with real tradeoffs, owner call.

## 81. Marker-based boundary-gap measurement — S22 verified, GAP1 unmet (2026-08-28)

Follow-up to #80's GAP1 over-target flag: replaced the poll-only completion with an
`AudioTrack.setNotificationMarkerPosition` end marker (`PassageOutput.setCompletionMarker`,
default no-op so test fakes compile unchanged; `awaitPlaybackOrStop` races the marker
against the 50 ms poll fallback via `select`, so marker-less devices keep working).

- **Markers DO fire on this device's MODE_STATIC tracks** — the old "static tracks park
  the head without a reliable marker on some devices" concern (PassageOutput KDoc) does
  NOT apply on the S22: 5/6 boundary gaps used the marker (`m=1`).
- **True audible gap ≈ 46-98 ms, median ≈ 73 ms — ABOVE the ≤ 50 ms SLO.** The #80
  probe's ~95 ms overstatement was mostly the 50 ms poll quantization; the residual is
  REAL per-boundary processing: the S4 track rebuild (capacity mismatch — most passages
  differ in size, so reuse rarely engages; unchanged from pre-S4), the machine's Room
  progress write, and `publish()`'s per-boundary MediaSession reset + notification IPC.
- The first-gap fallback (`m=0`) flooring at ~33 ms confirms the poll path is not the
  bottleneck once a marker is available.

Evidence: `AyvuGap gap-ms=33 m=0, 46/98/158/73/64 m=1` on the S22 with the tagged
probe build; `./tools/docker-build.sh :feature-player:testDebugUnitTest :app:assembleDebug`
→ BUILD SUCCESSFUL.

**Next (flagged, not started)**: boundary-path optimization — defer/async the per-boundary
notification (the passage ordinal in the notification text forces a re-notify every
passage, an IPC to system_server on the player coroutine), and re-measure. Marker support
is worth keeping regardless of the outcome (completion accuracy).

## 80. Device-leg results — S22 Ultra (2026-08-28)

The measurements the goals doc (G1/G2/G3) and QW2/QW4 acceptance depend on, collected
on the S22 Ultra (`SM_S908U1`) with the probe-enabled debug build.

- **L1 warm tap-to-audio: 230 ms — PASS** (`< 300 ms`; `AyvuTap ms=230 source=disk
  action=play`). Cold first-play in a fresh process (engine open + disk fetch):
  2 563 ms — informational, no target; the SLO is the warm path.
- **GAP1 probe: ABOVE target.** Dispatch-to-dispatch boundary gap (logcat `AyvuGap`
  avg over consecutive same-loop plays): on-screen p50 ≈ 95 ms / p95 ≈ 111 ms,
  screen-off ≈ 80 ms — over the `≤ 50 ms` SLO. **Measurement caveat**: the probe
  measures play-dispatch to play-dispatch and includes the 50 ms completion-poll
  latency and loop re-entry, so the TRUE audible gap is lower than the probe reports
  (the historical 20 ms steady-state was measured differently). Adjudicating the
  SLO requires marker-based completion (not the polling approximation). Flagged:
  marker-based gap measurement is the follow-up before calling G2 met or missed.
- **QW4 post-stop fill: PASS** — STOP dispatched via the media session; the merged
  fill job started at the last playhead, found 46.0 s already queued, logged
  `postStop: fill done … self-stopping`, and the service record dropped to 0 — no
  runaway, exactly the QW4 acceptance (the merged fill + G2 session-window mark
  both work on-device).
- **Screen-off sanity: PASS** — `mWakefulness=Dozing`, playback continued, boundary
  gaps 61-97 ms with no 50 ms-poll stall; resolves the deep-sleep poll [INFERENCE]
  that had been open since the overview (structure §5).
- **QW2/L3 post-death notification tap: NOT REPRODUCIBLE on-device.** A
  force-stopped process removes the FGS notification with it (notification list
  empty after `am force-stop`), so there is nothing to tap after death on this
  Android build. The in-process halves (action intents carry `EXTRA_BOOK_ID`;
  `onPlay` rebuilds the machine from the holder id) remain Robolectric-proven
  (#72); the headset/media-button post-death path remains a system limitation (no
  live MediaSession after process death). The L3 `< 5 s` number therefore stands
  as the code-path contract, not a device-observed measurement.

Evidence: the logcat probe lines quoted above; `dumpsys power`/`dumpsys audio`/
`cmd notification list` observations during the run.

## 79. S4 — AudioTrack reuse (2026-08-28)

S4 lands the micro-lean the structural verdict kept open (lean-up §4): `AudioTrackPassageOutput` now retains ONE MODE_STATIC `AudioTrack` across passages instead of building a fresh track per boundary.

- **Reuse identity** (`shouldReuse`): keep the retained track only when the new passage matches every property that defines a static track — sample rate, channel config (mono 16-bit), and EXACT static capacity (`bufferSizeInFrames * 2` bytes). A static track's frame count is fixed at construction and the audio server plays to it; a smaller re-write would replay stale tail audio and a larger one cannot be written, so only an exact-capacity re-feed is faithful. Any mismatch rebuilds (stop + release + `buildTrack`).
- **Re-feed path**: `stop()` resets the static head to buffer start (AOSP-documented head reset; `flush()` is a native no-op for static tracks but keeps the Robolectric shadow head honest), then `write()` copies the new passage from offset 0 — the head counts the NEW passage from 0, so an exact-capacity re-feed is verified faithful.
- **Speed stays OUT of the identity**: applied per play via `AudioTrack.setPlaybackRate` (decisions #52), so one retained track serves every speed of a matching passage.

Evidence: `PassageOutputReuseTest` (format match reuses one retained track; sample-rate change rebuilds; size change rebuilds; position/state contract holds across reuse; speed applied per play and not part of the identity; pure `shouldReuse` decision covers rate/channel/capacity); `./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

## 78. S3+QW4 — publish details/snapshot split; one fill job (2026-08-28)

S3 (publication-surface split) and QW4 (one fill job) land together; the QW1 field-set guard test (decisions #72) is the parity guard the split leans on.

- **S3**: `publish()` is now the STRUCTURAL snapshot — full state copy via `stateCopy` + MediaSession metadata/playback-state + `notify(42)` — and `publishDetails()` is the per-second feed: StateFlow-ONLY, no session rebuild, no notification. The 1 s ticker calls `publishDetails` while settled, so the notification is no longer re-posted and the session state reset every second (G1/G3; `segments`/`offsetSeconds`/`activeSentenceIndex` stay on the per-second path). Both paths drive through the single `stateCopy` field computation — the two paths cannot drift (CR-8/CR-9 family; the guard test stays green).
- **QW4**: one parameterized fill job `startFill(from, followPlayhead, deadlineMs, onDone)` replaces the triplicated `startPrefill` / `startPostStopPrefill` / `bufferForPlayback`-ensure shapes. Prefill = followPlayhead, no deadline; post-stop = pinned `from`, `POST_STOP_MAX_MS`, `onDone` clears the G2 window and self-stops. `bufferForPlayback` keeps its bounded pre-start wait but only polls — the long-lived fill job already ensures toward the same target (no contended per-50 ms ensure).
- CR-2 untouched: `captureAndStop`/`teardownWrite`/`finalStopJob` byte-identical; the CR-5/CR-7 publish-ordering suites stay green.

Evidence: `PlaybackServicePublishGuardTest` (`publish populates the full historically-dropped field set`, `publishDetails feeds per-second state without re-notifying`), `PlaybackServiceA57Test` (`session window stays engaged through STOP until the post-stop fill completes`); `./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

**Addendum (2026-08-29, device-measured):** the one-fill contract implies a fill owner on EVERY loop-restart path — the seekBy / navigate / navigateUndo commands cancelled `pregenJob` via `stopEverything()` and restarted the loop without restarting the fill, leaving `bufferForPlayback` to poll `q.aheadSeconds` for the full `PLAY_BUFFER_TIMEOUT_MS` (60 s) at 0 ahead, then sync-synthesize: the device-observed `buffer: waiting for 45.0 s ahead` → `ahead=0.0s after 60041ms` → `loop: source=synthesized` loop repeating across whole chapters on both the S22 and Bigme HiBreak (seek tap-to-audio 79.6 s / 107.0 s, decompose into ~1 s command + 60 s dead-owner wait + RTF-scaled synthesis). Fixed: `startPrefill(position)` before `startLoop()` in the three `!wasPaused` tails — the same shape `startPlayback`/`resumePlayer` use; CR-5/CR-7 single-writer preserved (restart inside the command job after the `active(generation)` check), A7 paused branches untouched. Regression: `PlaybackServiceFillRestartTest` (engine gated to FAIL while the front-load fill runs, so the seek-target passage is provably unmasked; asserts playback reaches audio within one budget AND the restarted fill built ≥45 s ahead) — RED pre-fix, GREEN post-fix; suites ×3 reruns green.

## 77. S5 — engine dimension + rate-aware playhead/estimator (2026-08-28)

S5 lands the engine-swap PREP (the swap itself stays decisions #54): the two documented blockers — kokoro-rate math in the service and a no-engine cache key — are gone.

- **`PregenKey` engine dimension**: keys gain `engine` (default `kokoro`); disk path v2 is `<bookId>/<engine>/<voice>/<speed>/c<ch>p<passage>` — the engine segment sits directly under the `bookId` subtree, the delete/usage unit (decisions #11), so the same voice name can never collide across engines. `parse` also accepts the pre-engine v1 layout `<bookId>/<voice>/<speed>/…` as `kokoro`, and `PcmPassageCache.pcmFile` resolves v1 files for kokoro keys ONLY — legacy entries stay genuinely addressable and are never treated as disk artifacts (CR-4 deletes only unparseable paths; an over-cap v1 tier still converges; a put on a legacy key replaces its v1 slot without double-counting).
- **`PregenSpaceEstimator`**: the core-tts import is gone — a per-engine sample-rate map lives in core-player (unknown engines fall back to the documented 24 kHz default); estimates key per engine so one engine's estimate never sees another engine's bytes.
- **Threading**: `PregenPlanner`/`PregenQueue`/`OfflinePregen` carry the engine dimension through every key construction.
- **Rate-aware service**: `liveOffsetSeconds` divides by the last rendered sample rate (kokoro 24 kHz only until the first passage renders), and `frameMargin(rate) = rate / 100` (10 ms completion margin) replaces the fixed 240-frame constant.

Evidence: `PregenQueueTest` (engine-dimension round-trip, legacy paths parse as kokoro, entries keyed per engine), `PcmPassageCacheTest` (legacy pre-engine paths bootstrap as kokoro, over-cap v1 convergence, v1-slot replacement), `PregenSpaceEstimatorTest` (rate map pins kokoro + safe fallback, per-engine cache keys); `./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

## 76. G2+S1b — full-session admission window; overnight arm removed (2026-08-28)

G2's admission rule (goals-doc decision) lands with S1b (overnight arm removal), reversing decisions #42's "manual runs do not yield" for the worker's runtime behavior (the #42 design note stays on record for a possible overnight return).

- **Session window**: `PlaybackActive` is set from session start (the play/resume command paths) and cleared only when the POST-STOP fill completes — `markStopped` moved to the fill's `onDone` (before `stopSelf`), so STOP alone does not end the window and a yielding worker stays paused while the service synthesizes the post-stop buffer (lean-up "edge interactions" #1). `onDestroy` keeps a safety-net `markStopped` for the no-fill/die-mid-session paths (a fill that already cleared it makes the repeat a harmless no-op).
- **Worker**: `PregenWorker` is single-mode manual and yields to an engaged session for ALL runs — `break` before the next book plus `shouldContinue = { !PlaybackActive.isActive }`; `PregenTerminal.Yielded` settles as success (CR-1 mapping unchanged).
- **S1b**: overnight arm deleted — `PregenManager.ensureOvernightScheduled` + the `MODE_OVERNIGHT` budget/yield/notification variants are gone; `OVERNIGHT_NAME` is retained for the QW5d startup cancel (decisions #74), a no-op on fresh installs.
- **Single boolean**: `PlaybackActive` stays one flag — exactly one playback surface today; a refcount for future concurrent surfaces remains a deferred item.

Evidence: `PregenWorkerTest` (`manual pregen breaks before any synthesis while playback is engaged`, `manual pregen yields mid-book when playback engages and never resumes`), `PlaybackServiceA57Test` (`session window stays engaged through STOP until the post-stop fill completes` — `FakeEngine` made open so `GatedSessionEngine` can gate synthesis mid-session); `./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

## 75. S1/O3 — shared PregenPlanner (2026-08-28)

S1/O3 lands a single spine-order passage walk in core-player: `PregenPlanner`
(pure — no cache, no engine, no launch/cancel) owns the walk and the `PregenKey`
construction that both pre-generation executors previously re-implemented
(`OfflinePregen` with nested chapter/passage loops over `BookLayout`,
`PregenQueue` with its own cursor).

- `plan()` (non-suspend) serves the queue's look-ahead: built inside `ensure`'s
  critical section with the exact prior stop decisions — stop at the first
  in-flight key (contiguous prefix; never plan past an unsynthesized near gap),
  plus the `lookahead` count and `lookaheadSeconds` bounds; the queue's
  callbacks never suspend.
- `walk()` (suspend) serves the whole-book run: chapter hooks + suspendable
  `onCandidate`, halting on a `false` return; the stopping reason is captured
  and stamped on the final `PregenTerminal` progress.
- Both executors are kept — their lifecycles are Android-mandated (the service
  coroutine dies with the service and `START_NOT_STICKY` self-stop; WorkManager's
  persistence + KEEP-dedup is the library UI's backstop; lean-up O1/O2 remain
  rejected). Public executor contracts and behavior are unchanged: existing
  OfflinePregen/PregenQueue tests pass as-is.
- NOT included: the G2 admission rule (offline pregen suspends while playback is
  active — goals doc decided it, decisions #42 reversal), which stays its own
  entry when implemented.

Evidence: `PregenPlannerTest` (10 tests — spine order, strictly-after start,
stop/shouldVisit hooks, chapter hooks, key construction);
`./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` →
BUILD SUCCESSFUL (core-player 100 incl. the 10 new planner tests and the
refactored offline/queue suites; feature-player incl. the 3 new batch test files).

## 74. QW3/QW5c/QW5d — engine retry seam, close() docs, overnight leftover cancel (2026-08-28)

Second lean-up batch (docs/generate-play-lean-up.md §3); none of the three
touches CR-1/CR-2/CR-5/CR-7.

**QW3 — `KokoroRuntime.engine()` stops latching a failed open for the process.**
Previously the `failure?.let` fast-path (before and inside the lock) froze
"engine unavailable" until process restart — a first play before the async pack
staging lands (SettingsViewModel auto-stage) permanently failed. Now:
- prerequisite-missing failures (the `missingPrerequisites()` model/voices/
  espeak-file guards) re-check on every call and open once the files exist —
  no cap burn, no latch.
- genuine open failures (files present, open threw — corrupt model, bad espeak
  lib) count against `MAX_FAILED_OPEN_ATTEMPTS = 3` per process, so play taps
  cannot hot-loop the 325 MB graph open; a successful open clears the failure
  and resets the counter.
- `missingPrerequisites()`/`openEngine()` become protected seams for the retry
  test.
- Relaxes the decisions #25/#32 "opened exactly once" wording: opening stays
  lazy and one-engine-per-process, but prerequisite-missing opens retry and
  genuine failures are capped — the once-wording is now retry-qualified.

**QW5c — close() doc notes.** `KokoroEngine.close()`/`OrtKokoroSession.close()`:
"process-scoped, never closed in production; kept for tests/benchmarks" — the
engine is process-lifetime by design (decisions #25/#32); only tests/benchmarks
close.

**QW5d — overnight leftover cancel at startup.** The app-start scheduling hook
is gone, but a previously-enqueued overnight `PeriodicWorkRequest` survives in
WorkManager's DB and can still fire once after an upgrade —
`LocalTtsReaderApp.onCreate` now calls `PregenManager.cancelOvernight()`
(`workManager.cancelUniqueWork(PregenWorker.OVERNIGHT_NAME)`), the deterministic
fix for a recurring CPU spike; fresh installs no-op. `ensureOvernightScheduled()`
itself stays until the S1b decision removes the arm.

Link: goals doc — the QW5d cancel keeps GAP1 (G2) measurements clean and off the
L3 resume path; the QW3 de-latch keeps L1/L2/L3 measurable without a process
restart.

Evidence: `KokoroRuntimeRetryTest` (prereq-missing → files staged → `engine()`
non-null; corrupt model exhausts the cap; success clears failure; prereq misses
do not burn the cap), `PregenManagerCancelTest` (startup cancel reaches
`cancelUniqueWork(OVERNIGHT_NAME)`); `./tools/docker-build.sh :core-player:test
:feature-player:testDebugUnitTest` → BUILD SUCCESSFUL.

## 73. Instrumentation probes — goals §Measurement first slice (2026-08-28)

First slice of "measure now" (docs/generate-play-goals.md §Measurement), landing
before the remaining lean-up PRs: `PlaybackService` emits two debug-gated logcat
probes, log-only by construction — they never block, publish, or reorder, so the
50 ms poll loop and CR-2/CR-5/CR-7 ordering are untouched.

- **AyvuTap** (tap-to-audio, L1/L2/L3): timestamp armed at command dispatch
  (`probeTap` in `onStartCommand` and the media-session `onPlay` arm — covering
  in-process AND post-death rebuild resumes) and consumed at the first frame
  written to `AudioTrack`.
- **AyvuGap** (boundary-gap, GAP1): consecutive same-loop plays only —
  `computeGapMs` = play(N+1) dispatch minus (play(N) dispatch + rendered
  frames/sample-rate), an approximation of the true passage end (no AudioTrack
  marker callback); resume/seek/stop deliberately break consecutiveness so the
  next play is a fresh start, never a gap.
- Gates: `probesActive` = app debuggable runtime flag AND the companion
  `gapProbeActive` master toggle (feature-player has no BuildConfig); one
  `probe()` emit point; `clock()` seam for deterministic host tests. Logcat
  tags consumed by a dev script; no UI.
- Device collection of the L1/L2/L3/GAP1 numbers is **PENDING** — no device this
  round; the probes are the harness the SLO acceptance runs on.

Evidence: `PlaybackServiceProbesTest` (tap arming survives to the first play and
logs AyvuTap with the dispatch action; same-loop consecutive plays log AyvuGap;
resume/seek/stop reset the baseline; `probesActive` false → no probe logs);
`./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` →
BUILD SUCCESSFUL.

## 72. PR-0: chapters publish restore + notification book-id resume path + dead code (2026-08-28)

First slice of the generate/play lean-up (`docs/generate-play-lean-up.md` §7, PR-0):
QW1, QW2 and QW5a/b land together; QW3/QW4/QW5c-e and S1-S5 stay as proposed.

**QW1 — `publish()` restores `chapters` (third copy-move dropout).** Commit
`3bc2057` (the CR-9 fix) replaced the line `chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList()`
with the `chapterPassages` block — the same collateral-drop class that hit
`segments`/`offsetSeconds` in `3e01cd3` (CR-8) and `chapterPassages` in `26a3272`
(CR-9). `PlaybackUiState.chapters` stayed in the contract but no production path
wrote it, so the reader's chapter selector (`enabled = state.bookId != null &&
state.chapters.isNotEmpty()`, ReaderScreen.kt), the "Ch X/Y" label, the chapter
menu and the top-bar chapter title were dead. New `PlaybackServicePublishGuardTest`
(feature-player) runs a real publish against a positioned machine + book and
asserts the full historically collateral-dropped field set — `chapters` (dead since
3bc2057), `chapterPassages` (26a3272), `segments`/`offsetSeconds` (3e01cd3); it
failed against the pre-fix publish and passes now — so any future copy-block edit
that drops a field fails the suite.

**QW2 — notification actions carry the book id (post-death resume).** Every
notification action `PendingIntent` now carries `EXTRA_BOOK_ID` (the
`buildNotification` `action()` helper), and `mediaCallback.onPlay()` resumes with
`PlaybackStateHolder.state.value.bookId`. Pre-fix the intents had no id and onPlay
called `resumePlayer()` bare, dead-ending at `val id = bookId ?: return` when the
restarted service had `machine == null` (the service is `START_NOT_STICKY`). The
holder survives in-process, and the existing machine-less rebuild
(`startPlayback(id, explicit = false)`) carries the resume; the goals doc's L3
(< 5 s notification resume after process death) depends on this path. Host tests
cover the in-process halves (intents carry the id; onPlay rebuilds the machine
from the holder id). The device acceptance — kill process → notification Play
resumes — is **PENDING**: no device was available this round.

**QW5a/b — dead code removed.** `PregenQueue.clear()` (no callers) and its test;
`PlaybackService.playerJob` (never assigned by any production path) and its
cancellation in the stop path.

Evidence: `PlaybackServicePublishGuardTest` 3/3 (full field-set guard, action
intents carry `EXTRA_BOOK_ID`, media-session play rebuilds from the holder id);
`./tools/docker-build.sh :core-player:test :feature-player:testDebugUnitTest` →
BUILD SUCCESSFUL (core-player 91, feature-player 20 incl. the 3 new guard tests).

## 71. Speed selector removed — playback pinned 1.0×, model kept for revisit (2026-08-28)

The reading-speed selector UI and its command chain are removed; playback always
runs at 1.0×. This reverses the *live selector*, not the model: the speed machinery
stays intact so the feature can be revisited cheaply (owner plans a future revisit).
Link: goals doc (docs/generate-play-goals.md, G1.4 "Speed policy" — section rewritten
for pinned 1.0× playback; SLOs were already 1.0×-only).

**Removed:**
- `PlayerCard` speed pill (`PillButton("${formatSpeed(state.speed)}×")` +
  `commands::cycleSpeed`) and its `formatSpeed` helper (core-ui PlayerCard.kt).
- `PlayerCommands.cycleSpeed()` (core-player PlaybackCommands.kt); callers dropped:
  `PlaybackCommandSender` (app PlayerAdapters.kt), `LibraryViewModel.cycleSpeed`
  (+ the no-op test patch), `ReaderViewModel.cycleSpeed` (feature-player).
- `PlaybackService`: `ACTION_SPEED` dispatch, the `cycleSpeed()` body, the
  `SPEED_PRESETS` constant, the `ACTION_SPEED` companion constant, and the
  speed fragment in the notification content text (now `Chapter N · Passage M`).
- `PlayerStateMachine.resume()` no longer restores `stored.speed` — pinned to 1.0.

**Kept (revisit without migration / cache invalidation / engine-contract change):**
- `PlayerProgress.speed` column (core-persistence schema) and its math.
- `PregenKey` speed dimension + cache path layout (`<bookId>/<voice>/<speed>/…`),
  byte-identical toString/parse.
- `SynthesisRequest.speed` engine input; `PassageOutput.play(pcm, sampleRate, speed)`
  + setPlaybackRate logic; `PlayerStateMachine.setSpeed`/`MIN_SPEED`/`MAX_SPEED`.
- `PregenWorker` `KEY_SPEED` input (default 1.0) and `OfflinePregen` speed param.

**Stored rows:** per-book speeds are IGNORED on resume (rows left untouched); they
normalize to 1.0 on the next progress write since the machine commits
`_state.value.speed = 1.0`.

**Suspension:** decisions #29's "per-book speed preset restore" acceptance is
SUSPENDED (not deleted) until the revisit; decisions #33 (book-time semantics) and
#52 (setPlaybackRate) stay correct — the speed argument never leaves 1.0 in
production.

Evidence: `PlayerStateMachineTest` — `resume loads the stored position and pins
speed to 1.0` (stored 1.25 resumes at 1.0); the other machine speed tests
(setSpeed preserves point / clamps) keep guarding the retained contract; targeted
test runs for core-player, core-persistence, feature-library, feature-player green.

## 70. I2 — Smart chapter detection in monolithic books (2026-08-28)

Shipped with I1 in one core-ebook commit: `BookSegmentation.splitChaptersByHeading` gives a
book parsed to exactly one chapter — MOBI7 without an NCX, a one-entry EPUB spine, plain TXT
(Markdown ATX already splits) — a fallback split on credible headings.

- Runs only when parsing produced exactly one chapter: NCX/nav/ATX boundaries always take
  precedence, and the split stays inside `BookSegmentation` (single segmentation path, no
  second convention).
- Detects headings from passage text: chapter/part keywords (`Chapter N` / `CHAPTER N`,
  `Part/PART N`, plus the en/fr/es/pt/it/ja/zh/hi forms and CJK/Devanagari chapter words),
  all-caps Latin title runs, and leading-numeric "N. Title" lines. Heading text becomes the
  chapter title (TTS skips the title field); heading passages are removed from the bodies so
  they are not read aloud twice; chapters renumber contiguously.
- Evidence gates: at least two headings of one uniform kind for any split — a lone "Chapter 1"
  amid prose or mixed heading kinds stays one chapter; a book of only headings never divides
  into empty chapters. Deterministic and stable across re-parses (same stable-index contract
  as `BookSegmentation`).

Evidence: `BookSegmentationTest` — `monolith splits on Chapter N`, `roman and name-case
headings split`, `numeric heading lines split when consistent`, `a book with one chapter
heading and prose stays one chapter`, `mix of chapter-numeral and all-caps headings does not
split`, `book of only headings does not divide into empty chapters`, `chapter indexes
contiguous after split`, and the en/fr/es/pt/it/ja/zh-cmn/hi heading splits
(`:core-ebook:test` green at commit).

## 69. I1 — Book start detection (skip cover, TOC, index) (2026-08-28)

Shipped with I2 in one core-ebook commit: `BookSegmentation.stripPassageMatter` extends
furniture stripping from chapter-title granularity to passage level, so a single-chapter
source — MOBI7 without an NCX, a one-entry EPUB spine, a plain TXT — starts the listener at
the first story passage instead of the cover.

- Drops a contiguous leading run of front-matter passages (cover, half title, title page,
  copyright, contents, dedication, epigraph) on the first kept chapter and a contiguous
  trailing run of back-matter passages (about the author, index, advertisements) on the last
  kept chapter, by the same containment rules already used for chapter titles (cover and half
  title are in `FRONT_MATTER`).
- Invariants hold: a *middle* chapter named "Index" or "Copyright" is untouched
  (containment, not position), and stripping never removes the whole book — a chapter emptied
  by the strip restores its original passages, preserving deterministic re-parse stability.

Evidence: `BookSegmentationTest` — `single chapter front matter passages are stripped`,
`single chapter back matter is stripped`, `middle chapter mentioning index or copyright is
NOT stripped`, `single chapter with only furniture stays unchanged`; the acceptance scenario
(single-chapter EPUB resumes at the first story passage; re-import reproduces the identical
kept set) is the tested behavior (`:core-ebook:test` green at commit).

## 68. B1+B2 — AyvuTheme tokens + shared component set (2026-08-28)

Phase B's first two slices (roadmap B1/B2, planned as decision #58): the branded
visual system lands in `core-ui` with zero visual change — every stylable surface
already rendered exclusively through `MaterialTheme.*`, so the token swap alone
recolors the whole app.

- **Tokens (`Theme.kt`, new).** `AyvuLightColors`/`AyvuDarkColors` override only
  the brand roles; unlisted roles keep the M3 defaults. Light: dark-amber primary
  `#7A5200` / dark-teal secondary+tertiary `#0B5F72` on warm-cream
  `background`/`surface` `#F5EFE0` with ink `#1B2430` on it. Dark: bright amber
  `#E8A33D` / light teal `#66C8E1` on ink, cream text. Palette committed as
  written; verified pairs: ink/paper 13.6, white-on-#7A5200 6.9,
  #7A5200-on-paper 6.0, #0B5F72-on-paper 6.3, #E8A33D-on-ink 7.3, ink-on-#FBE0B8
  12.3. `AyvuTypography` (M3 defaults — the single future override point),
  `AyvuShapes` (4/8/12/16/28 dp), `AyvuSpacing` (XS…XXL = 4…32 dp),
  `AyvuMotion.STANDARD_MS = 300`, and the `AyvuTheme(darkTheme, content)`
  wrapper. No `isSystemInDarkTheme()` default — the theme is dumb; both hosts
  resolve `ThemeMode`.
- **Both Compose hosts wrapped.** `MainActivity` replaced its direct
  `MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme())`
  with `AyvuTheme(darkTheme = dark)`; `ShareReceiverActivity` gained the same
  ThemeMode resolution (`SYSTEM → isSystemInDarkTheme()`, LIGHT → false, DARK →
  true) and wraps its share surface in `AyvuTheme` (feature-share now depends on
  core-ui) — the share gateway is no longer an unbranded default scheme.
- **Shared components (B2).** Five pure extractions of existing ad-hoc
  composables in `core-ui`: `SectionHeader(title, modifier)` — padding stays at
  call sites because the flows bake different paddings; `PillButton(label,
  onClick, modifier)` — the private PlayerCard pill moved byte-for-byte,
  `modifier` added; `ConfirmDialog(title, text, confirmLabel, onConfirm,
  onDismiss, dismissLabel = "Cancel")`; `EmptyState(title, modifier)`;
  `LoadingState(label, modifier)` — its internal `AyvuSpacing.LG` (16 dp) gap
  reproduces the share screen's spinner→label spacing. No new runtime deps;
  `material-icons` untouched.
- **Call sites relocated, identical visuals.** LibraryScreen: both remove-confirm
  AlertDialogs → `ConfirmDialog` with the exact strings ("Removes \"$activeTitle\"
  with its progress…", "$title" twin), empty-state Box/Text →
  `EmptyState("No books yet — import your first ebook")`, both `SectionHeader`
  call sites gain `Modifier.padding(top = 8.dp, bottom = 4.dp)` (the deleted
  private one's padding, now at the call sites), `tween(300)` →
  `tween(AyvuMotion.STANDARD_MS)`, private SectionHeader deleted. SettingsScreen:
  private SectionHeader deleted, all six call sites → the shared one with
  `padding(top = 16.dp, bottom = 4.dp)`; feature-settings gains
  core-ui. PlayerCard: private PillButton deleted (its three calls now resolve to
  the public same-package one). ShareResultScreen: the Idle/Resolving
  spinner+label branch → `LoadingState(…, Modifier.fillMaxWidth())`, unused
  imports dropped.
- **Verification.** New Robolectric `AyvuThemeTest` in core-ui (test config
  mirrors core-persistence: junit4 + vintage-engine + robolectric +
  platform-launcher) — 9/9 green: both schemes' primary/background/onBackground,
  `AyvuShapes.large == 16.dp`, `AyvuSpacing.LG == 16.dp`,
  `AyvuMotion.STANDARD_MS == 300`. Robolectric ran cleanly under AGP 9.0.1/
  Kotlin 2.4.10 — the planned plain-JUnit fallback was unnecessary.
  `:app:assembleDebug` green (197 tasks): both hosts wrap `AyvuTheme`, every
  shared-component call site resolves, no private duplicates remain. Device
  visual acceptance (light/dark S22 + low-motion HiBreak) is Phase B4 — not
  performed in this slice (no device here).

## 67. D2 — ONNX execution-provider measurement: keep CPU default (2026-08-28)

Measured the only Android-deployable ONNX Runtime execution providers
(`onnxruntime-android` 1.23.2) against the CPU baseline on both devices,
screen-off and media-volume 0 (the realistic player condition). This was a
measurement slice only — the harness gained a cold-engine-open probe and an
oracle gate; **no production engine path changed, and the deployment default
stays CPU at the end of the slice.**

Method (D1 seam, harness only): `core-tts` `OrtKokoroSession.open` gained an
additive `sessionFactory` parameter (default `{}` ⇒ existing callsites
byte-for-byte unchanged; `core-tts` JVM suite stays green). `spike-tts`
`KokoroBenchmarkRunner` now iterates `OrtProvider {CPU, XNNPACK, NNAPI}`,
writes `kokoro_results_<label>.json`, records engine-open ms, and for each
candidate synthesizes the pre-phonemized corpus twice — candidate vs a fresh
CPU oracle engine — and reports peak (`max_abs_diff`) and mean
(`mean_abs_diff`) absolute PCM float-amplitude deltas. `spike-tts` also now
pins the repo-root `debug.keystore` so the androidTest + target share a
signature on every container build (previously the toolchain's regenerated
default key made instrumentation fail with "signature matching" denial).

Results (corpus = 2 passages: Pride & Prejudice en-us + Dom Casmurro pt-br,
40.4s + 22.8s audio):

| device | provider | engine-open ms | RTF (en/pt median) | totalPss kB | VmHWM kB | max_abs_diff | mean_abs_diff |
|---|---|---|---|---|---|---|---|
| S22 Ultra (SM-S908U1) | CPU | 1500 | 0.766 / 0.752 | 1 321 349 | 1 413 148 | — | — |
| S22 Ultra | XNNPACK | 1900 | 0.812 / 0.823 | 1 996 271 | 2 101 232 | 0.102 | 0.00082 |
| S22 Ultra | NNAPI | 2400 | 0.964 / 0.938 | 2 542 312 | 2 648 688 | 0.0477 | 0.00061 |
| HiBreak (Bigme) | CPU | 8500 | 2.97 / 2.90 | 1 333 340 | 1 417 520 | — | — |
| HiBreak | XNNPACK | 9000 | 3.17 / 3.17 | 2 004 914 | 2 048 848 | 0.070 | 0.00075 |
| HiBreak | NNAPI | 8100 | 3.53 / 2.98 | 2 519 767 | 2 048 488 | 0.069 | 0.00070 |

CPU = baseline oracle (no diff). ThermalManager is absent on these device
builds (`thermal_status_max = -1` recorded); PSS/VmHWM come from
`Debug.MemoryInfo` + `/proc/self/status`.

Decision per the D2.3 hard rule — ADOPT a delegate only if it (a) materially
improves RTF/first-audio, (b) `max_abs_diff <= 0.001` with no failed/unavailable
passages, and (c) thermal/PSS not worse:
- **No candidate satisfies all three on either device.** Both are *slower* on
  both (CPU is fastest everywhere), both blow the `max_abs_diff <= 0.001`
  oracle gate on every run (0.048–0.102), and both roughly double resident
  memory.
- **FACT: keep production on CPU today** (the existing default). No change to
  the `core-tts` default provider in this slice.
- **Deferred: needs X to adopt** — a measured candidate would need (i) an
  INT8/fp16 quantized graph that actually beats CPU RTF without sampling
  divergence (the fp32 graph under XNNPACK/NNAPI is 10–30% *slower*), and
  (ii) oracle `max_abs_diff <= 0.001`. Nothing in this slice ships an
  accelerator; the probe seam remains for a future pass.

Evidence: `:core-ebook:test` (BookSegmentation I1+I2) and `:core-tts:test`
green; spike `assembleDebug` + `assembleDebugAndroidTest` build in-container;
both devices' instrumented run logged `DONE` per provider and wrote
per-provider JSONs (S22: `cpu`, `xnnpack`, `nnapi`; HiBreak: same + full
CPU run).
## 66. A6 — Composition root + feature boundaries (CR-6) (2026-08-27)

Turned `app` into the effective composition root and removed every
`feature-* → feature-*` project edge:

- **Contracts moved down.** `PlaybackStateHolder`/`PlaybackUiState`,
  `PlayerCommands` (widened with `play`/`playAt`/`stop`), `EspeakStager`,
  `formatBytes` and the new `PregenScheduler`/`OfflineStorage`/`PregenJobState`
  contracts live in core-player; `TessDataStager` moved to core-ocr (both
  were pure File logic all along); the shared `PlayerCard` composable moved
  to the new `core-ui` Android library (the roadmap's B2 "shared component
  home" decision, resolved during A6 as the record suggested).
- **Bindings moved up.** `PersistenceModule`, the import-core providers
  (`TextIndex`, parse-only `BookImporter`, `ImportCoordinator`, `IndexLock`,
  `IndexRebuilder`, `appScope`, `@IoDispatcher` — the qualifier now lives in
  core-player so features share it) and `OcrModule` are in `app.di`;
  `app` binds `PlayerCommands` (the intent `PlaybackCommandSender`),
  `PregenScheduler` (WorkManager adapter via callbackFlow over
  `PregenManager.workInfo`, mapping to `PregenJobState`), and
  `OfflineStorage` (feature-player's `PregenStorage` now implements the
  contract directly). feature-library consumes the contracts; its
  `PregenManager`/`PregenStorage`/`PlaybackService` imports are gone and its
  pregen row observes `Flow<PregenJobState>` via `collectAsState`.
- **Build check.** Root `checkFeatureBoundaries` task scans every
  feature module's `implementation` project dependencies and fails the
  build on any `feature-* → feature-*` edge.
- **Testability payoff.** LibraryViewModel takes an injected
  `PlayerCommands` fake + the coordinator; settings binds `OfflineStorage`
  through the same contract the library uses.

Evidence: zero feature-to-feature edges (`checkFeatureBoundaries`), all
feature host suites (library 9, player 18, settings, share, core-ocr)
green after the cutover, app + androidTest compile. Device regression
(import → share → pregen on one session) pending on the S22.

## 65. A3 — Room/index consistency (CR-3) (2026-08-27)

Closed the CR-3 divergence family with one orchestration boundary:

- **One `ImportCoordinator` (core-ebook).** `BookImporter` became a pure
  parse core (no `TextIndex` — no `Unchanged`, no `importAll`): format
  gate + content hash (`sourceId`) + parse/segment/cover. The coordinator
  owns the full order — parse without publication → `store.add` (durable,
  Room) → `indexLock.withExclusiveIndex { index.add }` — and the batch
  loop with the F1 progress/cancel semantics moved with it.
- **Room is the duplicate truth.** `LibraryStore.contains(bookId)` (new
  contract member; `RoomLibraryStore` via `BookDao.byId`, in-memory
  reference) gates re-imports BEFORE parsing. Failure A is closed: a failed
  durable commit returns typed `ImportFailureReason.Storage` with the index
  untouched, and a retry of the same bytes re-parses, re-commits and
  indexes exactly once — the index can no longer hide an uncommitted id.
- **Lock-serialized reconciliation.** New `IndexLock` (core-locate, Mutex)
  is the single mutation serialization point. The launch-time rebuild in
  `LocalTtsReaderApp` now reconciles INSIDE the lock, reading its Room
  snapshot fresh, so no stale snapshot can purge a book committed during
  its critical section (Failure B); delete runs durable-first with the
  index removal only after the Room delete succeeds (Failure C — the
  ViewModel guards the delete with `runCatching` so a failed removal
  leaves Room and index both intact).

Evidence: `ImportCoordinatorTest` (6 — durable-then-index, store-based
duplicate gate, failed-commit retry, barrier-controlled stale-rebuild race,
batch progress + cancellation), `LibraryViewModelTest` (9 — incl. a failed
durable delete keeping the surviving book indexed), `BookImporterTest`
(11 — parse-only contract; typed failures; `sourceId`), Room store/persistence
suite green. Device regression (share resolves a snippet right after a
cold-start import; import → force-stop → relaunch reattaches search) pending
on the S22.

## 64. F1 — Import progress and cancellation (2026-08-27)

Closed the user-reported "import looks hung" defect: `importAll`'s progress
callback fired only AFTER each file completed, so a single large file showed
nothing until its parse landed, and nothing could cancel a batch.

- **Pre-parse progress.** `BookImporter.importAll` (now suspend) fires
  `onProgress(current, done, total)` before each file's parse AND after
  completion — "Importing 0/1 — book.epub" is visible the moment the batch
  starts. `LibraryViewModel.import` publishes `Importing(0, total, name)`
  before launching, so even the first file's parse period is covered.
- **Clean cancellation.** The batch boundaries are 1 ms cooperative delays —
  a real suspension (noise against multi-megabyte parses) so a cancelled
  batch stops between files and never indexes a file it never started.
  `importJob` tracks the batch; `cancelImport()` cancels and publishes
  `Idle`; the Done transition is guarded by `coroutineContext.ensureActive()`
  so a racing cancel can never land a partial `Done` summary (a cancelled
  non-suspending tail would otherwise run to completion). The library row's
  import progress gains a Cancel button. Per-file failure isolation stays
  typed (`ImportOutcome.Failed` + reason mapping).
- **Contract note (A3 handoff):** a cancelled batch can leave files parsed
  and INDEXED but not persisted (the ViewModel commits Room rows only in
  `buildSummary` after the loop). This is exactly the CR-3 divergence the
  A3 reorder (commit Room in the loop, publish index after) will close.

Evidence: `BookImporterTest` (13 — 6-event progress sequence incl. pre-parse
events; mid-batch cancel at the file boundary leaves the index untouched for
later files) and `LibraryViewModelTest` (8 — start state `Importing(0,2)`;
mid-batch cancel settles Idle and a later import still works; scheduler
advances replaced eager no-op expectations now that batches park on their
1 ms boundary). `core-ebook` gained `kotlinx-coroutines-core`/`-test` for
the suspend contract.

## 63. A4 — Cross-process PCM LRU bootstrap (CR-4) (2026-08-27)

Closed the CR-4 frozen-cache failure: `PcmPassageCache` built its eviction
map only from in-process `put`/`get`, so after a restart the old on-disk
entries counted toward the byte cap but were never eviction candidates —
near the 4 GiB cap every new passage evicted itself and replacement froze.

- **Bootstrap on open.** Construction scans the tier, parses each `.pcm`'s
  [PregenKey] path, validates the `.meta` sidecar, and loads valid entries
  into the access-ordered map by pcm mtime, oldest first. Between restarts
  the eviction order is a deterministic approximation of true LRU (the
  repair record's accepted option; in-process reads still refresh exact
  order). The persisted-logical-order alternative was rejected: a write per
  `get` for the same approximate benefit.
- **Converge at construction.** An over-cap cache (old entries alone above
  the cap) is converged below it at open — not lazily on first mutation —
  because the pregen planner gates on `bytesRemaining() == 0` BEFORE any
  put; lazy convergence would have kept pre-generation frozen.
- **Invalid-artifact cleanup.** Stale `.tmp` writes, PCM without a valid
  sidecar, metadata without PCM, and paths that do not map to a key are
  deleted at open — `contains` can no longer report a permanent false hit
  that makes `OfflinePregen` skip a passage forever.
- **Oversized policy (explicit).** An entry alone larger than the cap
  cannot be retained; it is evicted like any other overflow (regenerable).

Evidence: `PcmPassageCacheTest` now 12 tests — reopen evicts the OLD entry
(not the new one), over-cap open converges at construction, invalid pairs
are removed and the passage regenerates, oversized-entry policy, all
pre-existing round-trip/LRU tests unchanged. Device acceptance (fill a
small cap, force-stop, relaunch, play an uncached passage, confirm an old
entry is reclaimed) pending on the S22.

## 62. A5+A7 — Single-writer player commands + state agreement (CR-5/CR-7) (2026-08-27)

Closed the CR-5/CR-7 control-plane races: book loads ran in untracked
coroutines (`stopEverything()` had no handle on them; the declared
`playerJob` was never assigned by any path), so an older load could publish
or drop the foreground after a newer command. On the device, pause during
first-audio generation left `dumpsys media_session` at `PLAYING(3)` with a
“Pause” notification, and ±30 s seeks re-synthesized instead of repositioning
a stopped playhead.

- **Tracked, generation-guarded commands.** Every control-plane command
  (open, openChapter, play, resume, pause, navigate, seek, undo, speed) now
  runs via `launchCommand` as a tracked `commandJob` under a monotonic
  `commandGeneration`. `stopEverything()` bumps the generation FIRST, then
  cancels: a command captures its generation at launch and re-checks it
  before ANY `publish`/`startForeground`/`stopForeground`/`startLoop` side
  effect. Cancellation alone is insufficient — the machine's `storeOp`
  swallows `CancellationException`, exactly how a stale load kept running to
  its publish tail (the CR-7 mechanism).
- **Pause during LOADING/“Generating…”** now cancels the in-flight job via
  the command model and publishes `PAUSED` on UI, MediaSession and the
  notification; a superseded generation loop cannot republish `PLAYING`.
- **Navigation never resumes a paused playhead.** `seekBy`/`navigate`/
  `navigateUndo` capture `wasPaused` before teardown and re-`pause()` after
  repositioning; a paused ±30 s seek moves the playhead, writes the row and
  stays silent (the device evidence showed re-synthesis).
- **Boring over actor:** the accepted “smaller repair” instead of a command
  actor — one launcher + generation checks, no command queue; commands stay
  responsive and the long-running synthesis/play loop remains cancellable.

Evidence: `PlaybackServiceA57Test` (6 — pause during generation publishes
PAUSED and nothing synthesizes; a superseded publish-loop is cancelled and
stays dead; paused seek and paused skip reposition without resuming; a
superseded OPEN never publishes; launched commands are cancelled by
`stopEverything`). All prior feature-player host tests and the instrumented
compile stay green. Device re-verification (MediaSession/notification during
generation-pause; two-library-row play stress) pending on the B6 / S22.

## 61. A2 — Live playhead persistence (CR-2) (2026-08-27)

Closed the CR-2 progress-loss: STOP and service teardown rewound the
persisted resume row to the current PCM slice's start because
`stopEverything()` releases `PassageOutput` (its head reads 0 after stop)
before `machine.stop(liveOffsetSeconds())` sampled it; `stopPlayer()` also
raced `onDestroy()` for the final write, and no checkpoint persisted the
live intra-passage playhead between transitions (abrupt death lost the whole
current passage).

- **Capture-before-teardown.** `stopPlayer()` now calls `captureAndStop()`,
  which computes `liveOffsetSeconds()` FIRST, then tears down output/loops,
  then performs the single final write (`machine.stop(finalOffset)`) in a
  tracked `finalStopJob`. `onDestroy()` runs `teardownWrite()`: it joins a
  graceful stop's in-flight write (never double-writing with a stale
  post-release offset), or else captures the live playhead itself before
  tearing down and writes exactly once. The book-time offset is captured
  verbatim — `positionSamples` is book-time at every speed, no speed math
  (decisions #52 preserved).
- **Throttled checkpoint.** While a passage plays, the player coroutine
  (the machine's single-writer edge — cannot race the machine's own passage
  transitions) commits the live playhead when `dueCheckpoint(clock())`
  passes: at most one write per `CHECKPOINT_MS = 5 s`. Abrupt process death
  therefore loses at most one interval, not a whole passage; the 1 s UI
  ticker still publishes live offsets but never persists by accident.
- **Test seams.** `output`, `machine` and `baselineOffset` became `internal`
  so host tests drive the real service with a fake `PassageOutput` (zeroing
  its head on stop, mirroring `AudioTrackPassageOutput`) + the real machine
  over `InMemoryPlayerStore`/a commit-counting wrapper.

Evidence: `PlaybackServiceCr2Test` (4 — STOP persists baseline 10 s + 5 s
live = 15 s; teardown writes exactly once at the captured playhead;
teardown joins a graceful stop without a second write; the checkpoint gate
allows one commit per 5 s). All prior feature-player host tests and the
instrumented compile stay green. Device stop-mid-passage/kill/reopen and
abrupt-death acceptance pending on the Bigme B6 / S22 (one session after
A5+A7).

## 60. A1 — Pre-generation terminal truth (CR-1) (2026-08-27)

Closed the CR-1 false-success: choosing “Whole book” in the pre-generation
overlay silently did nothing and settled as a successful job. Two defects and
their repairs:

- **Deadline conflation.** `PregenWorker` computed
  `budget.maxTimeMs?.minus(elapsed)?.takeIf { it > 0 } ?: break`, so a null
  deadline (“whole book”) was treated as an already-expired deadline and the
  loop broke before constructing `OfflinePregen`. `PregenBudget` now exposes
  `remainingTimeMs(elapsed): Long?` (null = unbounded by construction) and the
  worker breaks only when the value is non-null and `<= 0`, passing null
  through as an unbounded `maxTimeMs`.
- **False success.** `doWork` returned `Result.success()` unconditionally,
  hiding every `OfflinePregen` stop. `PregenProgress` now carries a
  `PregenTerminal` (`Completed`, `BudgetExhausted`, `CacheSaturated`,
  `Yielded`, `Unavailable`, `FailureCap`) set by `run()` on every return path
  (the terminal event is the final progress event, so the observed tail equals
  the result). The worker maps `Unavailable`/`FailureCap` to
  `Result.failure` with `KEY_ERROR` + per-run counts (overnight stops the whole
  job: engine conditions are global), and `LibraryScreen.BookRow` surfaces the
  error text. A `null` terminal is itself a failure, never a silent success.

Testability: `KokoroRuntime` became `open` with `engine(): TTSEngine?` (the
only surface any caller uses — zero behavior change) so host tests inject a
fake engine; `feature-player` gained Robolectric + `work-testing` (+
`vintage-engine`, `kotlinx-coroutines-test`) and `PregenWorkerTest` drives the
real worker over in-memory Room (`RoomLibraryStore` + `AppSettings` over an
in-memory `LibraryDatabase`) and the real `PregenCache` tier. The book loop
lives in `runBooks` with an injectable clock for deterministic budget tests.

Evidence: `PregenWorkerTest` (6 — whole-book without budget synthesizes and
caches, expired finite budget does nothing, unbounded is not expired,
`Unavailable`/meltdown fail with typed errors, missing engine fails),
`PregenBudgetTest` (4), `OfflinePregenTest` (13, terminals asserted). Device
`PregenE2eTest` (no-budget whole-book path) re-run pending on the Bigme B6 /
S22.

## 59. Fresh-install journey, primary-flow voice selector, review backlog (2026-08-27)

The roadmap gains Phase C after the visual system and before performance/data feature
work. A clean install has no books or speech assets; successful first audio must be a
designed journey rather than a sequence the user infers from Settings.

- **Guided setup:** explain offline/privacy behavior, choose language + voice, present
  exact required-pack/storage cost, coordinate Kokoro model + voices + espeak-ng
  downloads, keep OCR optional, then import a book and reach first audio. Network loss,
  cancellation, low storage and process restart are acceptance cases.
- **Durable facts, not a completion bit:** setup derives from ready packs, selected
  voice and library contents. Missing or cleared assets reopen the actual missing step;
  every action remains reachable later.
- **Voice selector:** the existing Settings picker/favorites remains the management
  surface, but one shared selector also appears in first-run and the primary
  player/reader flow. Initial scope is one global voice; per-book voice is not implied.
  Selection is explicit: a persistent "Selected voice: …" summary plus a radio/check on
  exactly one available row. Stars mean favorite only; row taps select and star taps
  only toggle favorite. A saved voice missing from the catalog is shown unavailable
  with a download/reselect action, never as an all-unselected list. A switch preserves
  the playhead, supersedes stale synthesis after CR-5/A5, uses the voice-keyed cache,
  persists through `AppSettings`, and never silently falls back when an asset is missing.
- **Voice sampling:** every ready voice row has Preview/Stop using a short fixed phrase
  appropriate to its language. Auditioning never selects/favorites the voice and never
  writes book progress, history or passage-cache entries. One sample owns the audition
  path; a newer preview cancels the old one and slow generation is visible/cancellable.
  Active narration pauses at a captured playhead and resumes only if it was previously
  playing, serialized through CR-5/A5. Missing assets show the normal download action.
- **Sample content:** not decided. First-run must work with a user-imported book; a
  public-domain sample can be reviewed independently.
- **Further reviews recorded, not scheduled:** data survival/user-owned storage,
  hostile-input/resource ceilings, release readiness, narration-quality corpus,
  Android lifecycle/interruption matrix, OCR replacement, library metadata, local
  diagnostics and battery/storage policy.

The former roadmap phases C–G shift to D–H. Promoted-idea destinations now point to D2,
F3 and G1–G3.


## 58. Material 3 design-system and UI-redesign slice (2026-08-27)

The roadmap gains Phase B after stabilization and before new product screens. Ayvu
already depends on Compose Material 3; the gap is a shared visual system and deliberate
surface design, not the absence of a component toolkit.

- **Foundation:** keep Material 3; centralize branded light/dark color roles,
  typography, shapes, elevation, spacing and motion in `AyvuTheme` instead of applying
  default schemes directly in `MainActivity`.
- **Components:** build only the shared cards, rows, controls, progress, dialogs and
  state panels required by real screens. Shared UI owns no business logic, navigation,
  stores or ViewModels.
- **Boundary:** settle the component home during CR-6. A small Android `core-ui` module
  is allowed only when it prevents feature-to-feature dependencies; it is not a new
  application layer.
- **Redesign order:** player card/library, reader, settings/storage, then share/import
  states. Backup, folder import and stats reuse the resulting system rather than adding
  one-off styling.
- **Third-party rule:** adopt a Compose library only for a named missing component after
  accessibility, maintenance, license and APK-cost review. No wholesale UI-kit swap.
- **Acceptance:** approved reference screenshots plus light/dark, font-scale, TalkBack,
  touch-target, reduced-motion and contrast checks on the S22; low-motion/e-ink behavior
  on the HiBreak.

The former roadmap phases B–F shift to C–G. Idea dispositions now point to C2, E3 and
F1–F3.


## 57. Roadmap reset: stabilization first + five idea promotions (2026-08-27)

The completed build-era phase plan was no longer an active roadmap: it mixed shipped
implementation history, stale estimates and unfinished post-v1 work. `roadmap.md` is
now a current sequence with the v1 phases compressed to a reference table.

- **First gate: shipped-contract stabilization.** CR-1 through CR-5 are release-blocking
  correctness work; CR-3 + CR-6 form the Room/index ownership and feature-boundary
  cutover. Instant seeking waits for cache/command correctness; backup waits for durable
  Room/index consistency; cross-feature work waits for clean composition boundaries.
- **Next sequence:** instant-seek + weak-device performance, backup/restore, library
  completion, narration/reader controls, then TODAY stats. Translation, CosyVoice,
  Kindle sync and timing-heavy reader features remain later strategic work.
- **Promoted from `ideas.md`, superseding #29's ideas-only disposition:**
  (1) accelerator/quantization/power measurement gate,
  (2) folder import paired with import progress/cancellation, (3) general TTS
  pronunciation replacements, (4) paragraph long-press Play/Copy menu, and (5) the
  narrow hardware/listening gesture subset. A fully configurable tap-zone editor is
  explicitly not promoted.
- **Safety boundaries:** pronunciation changes are output-side only; volume keys keep
  normal system behavior by default; accelerator changes ship only after device and
  audio-quality evidence.
- Historical estimates and completed player-card specifications remain recoverable in
  this ledger and git history; they no longer obscure active work.


## 56. Player card refinement: in-list on the library, channel cuts (2026-08-27)

User design review of #53 card placement + controls; shipped + verified.

- **Library placement**: the card now REPLACES the top "Continue listening"
  row in place (no bottom dock — the dock was my scope, the user's original
  "modified library book card" was the brief) and expands in place when the
  session starts (`AnimatedVisibility` expandVertically+fadeIn, 300 ms).
- **Library-only extras** (the replaced row's info: `topRight`
  overflow menu — pre-generate/delete-offline/remove-from-library — and the
  `badge` offline disk usage) are parameters on the shared `PlayerCard`;
  the reader passes nothing.
- **Card→reader**: adding the extras removed the only tappable row for the
  playing book — the card's cover/title area now opens the book (`onOpen`),
  restoring navigation (verified: tap card → reader at the resume point).
- **Chapter skip cut to core** (user: "just 30s skips for now"): card loses
  ◀Ch/Ch▶, the 4 service actions + both VMs' overrides are deleted; the
  tested core stays (`machine.skipChapter`, `BookLayout.nextChapter/
  previousChapter` + tests) for future need.
- **Verified on S22**: in-list card (Generating… spinner, elapsed/%/
  remaining, −30s/+30s/1×), ⋮ menu ("Pre-generate (≈8532.3 MB)", "Remove
  from library"; delete-offline shows only when usage>0), reader card
  without extras, open-book via card tap, no bottom dock.
- **Weak-device pass**: a second, weaker device is being plugged in; perf
  findings are LOGGED, not fixed — docs/bugs.md created (2026-08-27),
  entries (latest first), with the perf-vs-functional split.

## 55. App-wide player card shipped + device-verified (2026-08-27)

#53 built end-to-end; unit suite green; S22 device pass DONE.

- Verification captured live: card docks on the library (with the row-bounds
  entrance animation) and on the reader (old transport row + footer gone;
  sleep timer + undo stay in the top bar); spinner + "Generating…" shown
  while the engine loads; elapsed / % / remaining track correctly
  (1:52:16 → 2:14:43, 13%→17%, ≈8h 50m→8h 27m at 1×); play from the card
  resumes the saved position; speed pill + chapter/seek buttons present.
- **Instant-seek work (user-prompted "keep 30s ahead cached")**:
  - **Buffer reuse**: the loop keeps the last rendered passage
    (`lastAudio`, keyed by book/chapter/passage/voice/speed) and resolves it
    FIRST — a seek within the same passage replays with zero synthesis. On
    this book passages run ~6–24 s (< 30 s), so ±30s always crosses a
    boundary; the buffer path is code-verified, long-passage books get it.
  - **Deterministic disk**: first-listen persists are tracked and never
    cancelled (a new first-listen used to cancel the previous write, dropping
    passages from the tier); every seek path joins in-flight writes, so a
    played passage is always on disk. Verified: −30s hit `source=pregen`
    (instant, queue) on a recent passage.
  - **Command serialization**: transport commands now take a `commandLock`
    (state mutation inside, `startLoop` outside — the loop must never hold
    the lock) and `stopEverything` cancels the tracked `loopJob` directly.
    Rapid ±30s taps no longer race: an 8-tap burst previously produced
    corrupted `PassageAdvanced` (stale loops finishing after the position
    moved); now each seek is a clean serialized move.
  - **Honest residual**: a cross-boundary ±30s to a passage in neither the
    RAM queue (lookahead=2) nor the disk tier still synthesizes (~5–25 s on
    the S22, RTF ≈ 0.5); the spinner holds through it. The real fix for
    "±30s always instant" is the roadmap follow-up: time-bounded look-ahead
    (~30 s of audio queued), and NOT cancelling `queue.ensure()` on seek so
    the in-flight pre-generation survives the jump.
- Evidence: /tmp/pc5-…/pc6/pc8/pc9 (library + reader card states).

## 55b. App-wide player card — design and implementation detail (2026-08-27; device pass reported in #55)

User-scoped during design discussion (mockup-driven); roadmap entry carries
the full build detail; tree clean at HEAD `85bc5af`.

- **Scope chosen over the reader-only option**: one shared `PlayerCard`
  docked at the bottom of BOTH the library and the reader (replaces the
  reader's transport row); opening a book from the library animates the card
  from the tapped row into the docked slot (library-first appearance; the
  slot is identical on both screens, so it persists through navigation).
- **Loading feedback**: synthesis latency is surfaced as a spinner inside the
  play button + a "Generating…" subtitle on the card (chosen over an
  indeterminate bar).
- **±30s seeks roll across passages**: from the edge, convert the current
  position to global book-time (chars/15′ speech model), apply the delta,
  clamp, walk back to `(chapter, passage, offset)` via pure
  `BookProgress.positionAt`; jumps push the undo ring. Rejected: clamping
  inside the current passage.
- **Chapter skip** enters via `BookLayout.nextChapter/previousChapter` (skip
  empty chapters), tail-collapsed ring push, publisher disabled mid-flight.
- **Known tooling hazard re-confirmed**: this environment's `edit` `＋`
  insertions intermittently replace adjacent lines AND, in one blocked
  attempt, wrote the full-width `＋` marker literally with `+` prefixes —
  re-verify every insertion and prefer full-line python rewrites for
  multi-line inserts (this session: restored `android.app.*` imports,
  `resumeOnGain`/`ducking`, then reverted the aborted core insert via
  `git restore`).

Roadmap #53 built end-to-end; unit suite green; on-device verification
PENDING (S22 not connected).

- **Shared `PlayerCard`** (feature-player/ui/PlayerCard.kt) + `PlayerCommands`
  interface: cover thumb (`files/covers/<bookId>`, decoded in-card), title,
  subtitle (authors · Ch · P or **"Generating…"** while `LOADING`),
  book-wide progress bar, times row (book-time elapsed · % · remaining at
  current speed — `BookProgress.elapsedSeconds`/`totalSeconds`/`positionAt`
  added, chars/15 model shared with `PregenSpaceEstimator`), transport row
  (−30s · ◀ Ch · play/pause with spinner · Ch ▶ · +30s · speed pill).
- **Both screens dock it**: reader `bottomBar` (DockedControls + footer
  removed; sleep timer + undo stay in the top bar), library `bottomBar`
  whenever a session is active, with an entrance animation from the tapped
  row's bounds (`onGloballyPositioned` row-centers → graphicsLayer translate
  + scale, `Animatable`, 360 ms fast-out-slow-in).
- **Rolling ±30s seeks** (service `seekBy`): playhead → global book-time →
  delta → clamp → `BookProgress.positionAt` → `machine.seekTo` (ring push).
- **Chapter skip**: `BookLayout.nextChapter/previousChapter` (empty-chapter
  gaps) + `machine.skipChapter(dir)` — one ring entry, undo restores the
  exact playhead (`notePlaybackOffset` test).
- **Service/state**: `PlaybackUiState.authors` + `elapsedSeconds`;
  `ACTION_SEEK_FORWARD/BACKWARD`, `ACTION_CHAPTER_FORWARD/BACKWARD`;
  `LibraryViewModel.playerState` + full command surface.
- Tests: `BookProgressTest` (elapsed/total/positionAt round-trips + bound
  clamps), `PlayerStateMachineTest` (skipChapter fwd/back/bounds/empty-gap/
  undo-exact-playhead). All green.
- Edit-tooling note: `PlayerCard.kt` first draft carried drafting garbage
  (stray Box/Spacer, double-wrapped play button) — full-file rewrite fixed;
  two python scripts failed to write because an assert died BEFORE the write
  (partial-apply illusion) — scripts now write after ALL asserts.

## 54. CosyVoice as a pre-gen engine (2026-08-27) — designed, not started

Plan on the roadmap (full detail there); grounded in the shipped seams.

- **Scope**: CosyVoice (2-0.5B first, 3-0.5B only if the research gate
  prefers it) as a pre-generation-only engine — output goes to the disk
  tier, never live playback (RTF gate #21 stands; the #42 overnight window
  was sized for this class).
- **Zero-shot voice cloning is in scope for the first slice** (user choice,
  over bundled-prompts-only): SAF audio picker (3–10 s, decoded to 24 kHz
  mono PCM) + pasted transcript → local prompt store → cloned-voice
  pre-gen; one bundled prompt voice ships so the slice works standalone.
- **Architecture reuses the Kokoro port pattern** (raw onnxruntime Java,
  decisions #25 — no new native dependency); `TTSEngine` seam already
  anticipates the segment-less tier (`SynthesisOutcome.Audio.segments =
  null` → no read-along highlight on those passages, accepted).
- **`PregenKey` gains an `engine` dimension** (legacy paths parse as
  `kokoro`), and voice slugs namespace per engine — the current
  voice-only slug would otherwise collide across engines on the disk cache.
- **Research-gate decision deferred**: CosyVoice2 vs 3 pinned by ONNX
  availability + S22 RTF at the start of the slice (cosyvoice3-0.5b stays
  the metadata stub in `DefaultEngines`).
- Cost reality: ~300–500 MB int8 pack; RTF 8–17; those numbers get pinned
  at the gate, not planned from.

## 54b. Backward chapter turn lands on the previous chapter's ending (2026-08-27)
User-reported: tapping the reader's left zone (or swiping back) at a
chapter's first page sent you to the PREVIOUS chapter's beginning — the
reader shows the previous chapter's first page, not where it ended.

The turn repositions without playback (decisions #52: open ≠ auto-play), so
"beginning" was the landing passage *and* the displayed page. Both halves
fixed:

- **Service** — `PlaybackService.openChapter` presents the neighbor chapter
  at its LAST passage index for `direction < 0` (was always passage 0). This
  mirrors `PlayerStateMachine.previous()`, which already crossed a passage
  start onto the previous chapter's last passage — the turn contract and the
  navigation contract now agree. Forward turns unchanged (neighbor's first
  passage); empty spine slots and book-edge no-ops unchanged.
- **Reader** — a chapter opened WITHOUT playback (boundary turn, share-open,
  resume row, bookmark jump) now pages to the presented passage's page
  instead of always page one: a second `LaunchedEffect(state.chapterIndex)`
  beside the playback-follow effect. This also fixes the same stale
  page-one display for any idle position presentation at a non-first passage.

Tests: `PlaybackServiceA57Test.backward openChapter lands on the previous
chapter's last passage` (Robolectric, real service, multi-chapter book with
an empty spine slot) and `OpenChapterE2eTest` — chapter 0 gained a second
passage so the backward landing assert is passage 1, not vacuously 0.

## 53. EPUB import: XML-valid entities killed the parse (2026-08-27)
User reported "No More Mr Nice Guy" (from a real bundled epub) failing to
import on the device. Reproduced on host with the exact pipeline
(`BookImporter.import` → parse → segment → index): `ParseError(ParseError(
message=malformed content.opf))`, SAX fatal "The entity name must
immediately follow the '&'" at the OPF's `&amp;`.

Root cause: `parseXml` decoded ALL entities (`&amp;` → `&`) before handing
the document to the DOM parser (the 2026-08-26 HTML-entity pre-pass for
real-world OPFs). A legitimately-escaped `&amp;` in metadata text became a
bare `&` — invalid XML — and the parse died. Any OPF/NCX with `&` in a
title/creator/subject failed, regardless of parser (host or Android).
Books without `&` in metadata were unaffected, which is why earlier
imports passed.

Fix: the pre-parse pass now decodes ONLY what the XML parser cannot —
HTML-named extras (`&nbsp;` `&mdash;` …) become their characters and a
bare `&` is escaped to `&amp;`. XML-valid entities (`&amp;` `&lt;` `&quot;`
`&apos;` `&gt;`, numeric refs) stay for the parser, which resolves them
into `textContent`. `decodeEntities` (post-tag-strip chapter text, MOBI
NCX labels) is unchanged. Regression: `XmlPreprocessingTest` — "Love
&amp; Romance &mdash; R&amp;D" parses with correct textContent; bare "a &
b" survives. Verified: the real epub imports Added — title "No More Mr
Nice Guy", authors [Robert A. Glover], 3 chapters / 1809 passages; full
core-ebook JVM suite green (host) — and on the S22, the rebuilt APK +
the new `RealEpubImportProbe.niceGuyEntityEpubImportsOnDevice` case
(pp.epub + nmmng.epub staged) both pass: 2 tests OK.

## 52. Reader pagination, speed, open-without-play, library play, bookmarks (2026-08-27)
User-review batch #2 — five items, all verified live on the S22:
- **Real pagination, no scroll**: the chapter's text flows and breaks
  exactly where it would overflow the viewport; a new chapter always starts
  on a fresh page. Lines are fixed-height (30 sp); pagination is measured
  with `TextMeasurer` over the joined chapter text, pages are contiguous
  line ranges sliced at `multiParagraph.getLineStart/End` (greedy wrap
  reproduces the slice identically). Playback turns the page only when the
  spoken passage leaves it. `TextPagination` (core-player) holds the page
  math, unit-tested.
- **Speed actually works** (was a no-op). `sliceForSpeed` never resampled —
  only offset-skipped with an inverted `× speed`. Speed is now
  `AudioTrack.setPlaybackRate(sampleRate × speed)` (hardware rate
  conversion; frames stay book-time), the offset skip is exactly
  `offsetSeconds × sampleRate`, and `liveOffsetSeconds` no longer divides by
  speed. Device pass also caught a regression: an earlier publish() edit had
  eaten `speed = state.speed` — the UI label stuck at 1.0× while audio
  cycled; restored (label now shows 1.5×, time-left ÷1.25/1.5).
- **Opening a book no longer auto-plays**: new `ACTION_OPEN` →
  `openBook()` — stops current audio, positions the machine at the resume
  point or the start (`openPosition()`/`present()`, no commit, phase/ring
  untouched), publishes the text, drops the foreground. `resumePlayer` gained
  a fresh-book fallback; `positioned` is true whenever a passage is shown
  (bookmark/skip were disabled at open until the fix).
- **Library play button**: each row gets a Play icon → `ACTION_PLAY`
  (resume audio without opening the reader). First layout stacked the icon
  under the ⋮ (two IconButtons in a Box) — wrapped in a Row; verified: tap →
  `loop: source=synthesized`.
- **Bookmarks**: the top-bar bookmark opens a menu — add at playhead + list
  all bookmarks (label or Ch·P) and jump via playPosition; `PlaybackUiState`
  gains `bookmarks` (fetched after machine setup and after each add).
- Verified live: pagination (page turns at overflow, chapter fresh page,
  no scroll), open shows the resume position with Play not Pause, bookmark
  add/list/jump plays, speed label + rate, library play, continue-list
  (#51); 0 FATAL; full unit suites green.
- **Notification branding (user review)**: the player notification now
  shows the book cover (`files/covers/<bookId>` decoded, downsampled to
  ≤512 px, cached per book) as MediaStyle album art, the app name is "Ayvu"
  (fallback title + "Ayvu playback" channel; the launcher label was already

  Ayvu), and the book title remains the content title. Verified on-device:
  `largeIcon=Bitmap`, title=Impulse, MediaStyle template.

## 51. Library gap pass — remove book, reader %, time-left, continue-list (2026-08-27)
Three items from the R7 ereader comparison (kindle et al.) + the #50
follow-ups, chosen by review:
- **Remove book from library**: menu → confirm → deletes the book's cached
  passages, progress row, bookmarks, undo ring, covers, offline-audio
  subtree (pre-gen cancelled first) and the search-index entry, and stops
  live playback (the service holds its own book reference). Re-importing the
  same file re-creates the content-hash row — which is how a manually
  re-parsed book picks up the #50 segmentation fix. `LibraryStore.delete`
  + the per-table deletes (progress/bookmarks/history/passages/book) run in
  one Room transaction; `InMemoryLibraryStore` mirrors it; `TextIndex.remove`
  already existed.
- **Reader footer: % + time-left**: the Ch·P·offset line now reads
  `Ch 4 · P 4 · 0.1% · ≈10h 41m left` — position fraction (passage-granular,
  same as the library progress bar) plus estimated remaining listening time at
  the current speed, computed by the new pure `BookProgress` helper
  (core-player; same chars-per-second model as PregenSpaceEstimator, so the
  estimate is consistent with the pre-gen sizing). Verified live on the S22.
- **Continue-list / recent-reads**: the library shows a "Continue listening"
  section (books with a resume row, most recent first, capped at 5) above the
  main import-ordered "Library" list — no duplicates (recent ids are filtered
  from the main list).
- Unit tests: `BookProgressTest` (fraction incl. current passage, offset
  handling, speed scaling, empty book). Full unit suite + assemble green;
  remove-book and continue-list verified by code-path + pending a live pass
  once the S22 is free (verified live: the reader footer).
- **Follow-up (same day): the reader stitches the chapter.** Instead of one
  passage per page with a scroll reset on every advance, the current
  chapter's passages render as one continuous scrollable surface
  (`PlaybackUiState.chapterPassages` — the service publishes the whole
  current chapter; the page-view gestures survive). The read-along follow
  animates only when the active passage actually leaves the viewport
  (24 dp slack) so browsing ahead of the narration never yanks; a middle
  tap starts playback at the passage under the finger; chapter changes
  return to the top. Verify note: Compose 1.12 removed
  `positionInParent()` — passage offsets now come from
  `localPositionOf(column, Offset.Zero)`. Verified live on the S22: six
  prose passages visible in one scroll, footer intact, 0 FATAL — replaced by
  real pagination in #52 (user review: page breaks at overflow, not scroll).
## 50. User-review batch — espeak-ng download, navigation, paging, progress, pre-gen budget (2026-08-27)
Seven small items from a live review pass on the S22 + the host:
- **espeak-ng is now a downloadable pack** (was: manual adb staging; the
  settings/reader kept telling the user to build it). Pinned descriptor
  `espeak-ng` under KokoroPacks — url = `moronigranja/local-tts-reader`
  release `espeak-ng-1.52.0`, zip of arm64 `libespeak-ng.so` +
  `espeak-ng-data`, 9,857,162 B, sha `6b2edca7…` (the staged lib's sha
  matches the #38 pin `734cc95a…`). Flows through the existing verified-pack
  machinery (decision #7) and `EspeakStager` (feature-player) extracts into
  `files/espeak/` — the layout KokoroRuntime already reads. Settings gains
  the pack row; the espeak status is now live (filesystem check) instead of
  an injected snapshot, so it flips to ready right after staging.
  `EspeakBundleStatus` + `EspeakModule` binding removed.
- **Front-matter stripping is run-based, not window-based** (book
  navigation): a contiguous leading run of front matter (Title Page,
  Copyright*, Dedication, Contents — at any spine depth) and a trailing back
  matter run are dropped; kept chapters are renumbered densely from 0. The
  test book (Impulse) had its TOC at spine index 4 — past the old 3-window —
  so the reader landed in the Contents chapter and "Next" marched through
  TOC lines, never reaching "1. Millie: The Underlying Problem".
  `BookLayout.next/previous` now skip zero-passage chapters and gain
  `first()`; a fresh start uses `firstPosition()` instead of a hard (0,0)
  `require` (which would have crashed on sparse layouts). Titles match
  furniture by containment ("Copyright Notice"), not equality.
- **Reader paging**: swipe (≥ 64 dp horizontal) or tap the left/right third
  of the passage pages forward/backward at passage grain; the middle DOUBLE
  tap toggles the immersive chrome (a single middle tap does nothing, and
  pressing Play always starts from the top of the visible page — decisions
  #122). The system
  back gesture on the reader and settings returns to the library instead of
  exiting the app (`BackHandler` mirrors the top-bar arrows).
- **Library read/listened progress bar**: fraction from the resume rows over
  the cached passage counts (`PassageDao.chapterCounts()` +
  `ProgressDao.observeAll()`), shown as a bar + % when started
  (passage-granular, the player's resume unit).
- **Pre-generate budget overlay**: the library row's Pre-generate offers
  30 min / 1 h / 2 h / 3 h / whole book (each with the linear byte cost,
  ≈ 2.88 MB/min at the #44 estimate rate); `PregenWorker` takes a
  `budgetTimeMs` input, whole book stays the default. Delete-offline-audio
  only shows when usage > 0, and the label is always "Pre-generate" (the
  "Pre-gen again" distinction is gone).
- **Opus encoder forensics (#46 follow-up)**: regenerated device evidence on
  the S22 (`OpusDriftInstrumentedTest#opusRoundTripDriftOnDevice`,
  `files/opusdrift/{input.pcm,device.opus}`, payload 35,613 B, sha
  `5febd75a…`). Reference libopus (host 1.4) parses only 2 packets of the
  stream and refuses to decode the rest (150-byte leading `d8fffe` pattern +
  mis-sized packet headers; the decoder even SIGSEGVs on part of it). **The
  c2 opus encoder output is also non-conformant** — the #46 "encoder is
  size-plausible, maybe savable with a hand-written OpusHead" read is now
  falsified. MediaCodec is not a dependency for Opus in either direction on
  this device; any Opus cache would need bundled libopus for encode AND
  decode.
- Verified: the JVM unit suites across modules (ebook/player/tts/
  persistence/library/settings/app/share/ocr/locate/model) and the
  instrumented encoder test are green; the new APK installs.
- Verified on-device (S22, unlocked pass): the espeak-ng pack downloads,
  verifies, and auto-stages into `files/espeak/`; settings flips to
  "staged (lib + data)"; the engine opens and playback synthesizes
  (`loop: source=synthesized`); 0 FATAL. Library shows the read-progress %
  and "N MB offline"; the pre-generate dialog renders all five budget
  options with byte estimates; the reader pages on swipe/tap-zone taps and
  the system back returns to the library from settings and reader.
- **Device-pass finding A — Dagger File type collision (pre-existing).**
  `OcrModule.provideTessDataDir` returns an unqualified `File` (the
  tess-two data dir), so Hilt bound ANY bare `File` request to
  `files/tesseract`. `SettingsViewModel`'s injected `filesDir` silently
  resolved there: OCR staging would nest `tesseract/tesseract`, and the new
  espeak staging extracted into `files/tesseract/espeak/` (settings said
  "ready"; the engine, which reads `context.filesDir`, never saw it).
  Fixed by qualifying the app files dir (`@Named("app_files_dir")`
  provider in SettingsModule; VM param qualified). Staging roots are now
  absolute.
- **Device-pass finding B — ForegroundServiceDidNotStartInTimeException.**
  `startPlayback` returned early when the engine was unavailable
  (packs/espeak missing) without calling `startForeground`, so opening a
  book crashed ~10 s later. Fixed by entering the foreground FIRST in
  `onStartCommand`; early returns can no longer trip the timeout.
- **Self-heal**: a verified-but-unstaged espeak-ng pack (reinstall after
  download, or a failed extract) auto-stages when the settings screen
  opens — the pack can't be "already ready" and stuck behind a missing
  extract.
- Known follow-ups from the same pass: sub-1% read progress truncates to
  "0%", and there is still no remove-book-from-library action (re-importing
  an existing book hits "Unchanged" and keeps its old parse — the
  front-matter fix reaches books on the next fresh import).
## 49. T3 device pass — CosyVoice3-0.5B int4 ONNX on the S22 (2026-08-27)
First full on-device run of the `spike-tts` harness (14 graphs, jiangzhuo9357
int4 export, `voices/sarah` prompt) on the S22 (SM-S908U1, SDK 36, 6 ORT
threads; models 3.7 GB staged ad-hoc — the repo still carries no URL for the
bundle, the snapshot uses HF `jiangzhuo9357/cosyvoice3-0.5b-onnx` + locally
derived `sarah16/24.wav`):

- **RTF**: 12.64 / 14.37 / 13.16 (mean ≈ **13.4**) for 10.1/8.9/13.1 s of
  audio — inside decision #21's 14.7–17.5 gate estimate, so the CosyVoice3
  fallback tier stays viable at the #42 overnight sizing (3 h wall ≈ **3.5+ ch
  per night** at this RTF). Stage breakdown: flow 92–121 s dominates (68–72%),
  llm 28–42 s, hift 6–10 s.
- **Memory**: VmHWM **2.27 GiB** peak native, total PSS 336 MiB — the
  per-stage session release keeps peak ~2.3 GiB on the 8 GiB device, well
  inside lmkd limits; a production player using the tier would budget the
  same way.
- **Thermal**: peak status -1 (no throttling trip across the ~7 min run),
  headroom 0. Prompt path 6324 ms; `spkNorm` 15.28 and mel mean −5.63 —
  host-comparable sanity (sokuji-verified melodics) for the pipeline.
- All runs finite, audio written (`out_run1–3.wav`); artifacts pulled to
  `/tmp/t3/`. Bundle provenance: HF snapshot + ffmpeg-derived 16/24 k prompt
  wavs; worth revisiting whether the repo should pin the exact URLs.
Pinned derivation (the reproducibility record, `docs/cosyvoice3-pack.md`):
`ffmpeg -i sarah.wav -ac 1 -ar 16000 sarah16.wav` (208,078 B,
sha256 `654497c2…fa2`) and `ffmpeg -i sarah.wav -ac 1 -ar 24000
sarah24.wav` (312,078 B, sha256 `9f83deef…5360`), source `sarah.wav`
(312,044 B, sha256 `c590d415…b8`) from the pinned revision.
## 48. UI polish batch (2026-08-27, user review)
- **Library covers**: `EpubParser.coverOf` extracts the standard EPUB2/3 cover
  (content-hash book id → `files/covers/<bookId>` sidecar, written at import;
  no schema change) and the library row shows a 56×80 thumbnail with a
  title-initial fallback. TXT/MD/MOBI have no covers (placeholder).
- **Overflow menu**: the library row's Pre-generate (with space estimate) and
  Delete-offline-audio moved into a ⋮ `DropdownMenu`; progress stays inline.
- **Reader**: docked transport is icons + labels (prev/play-pause/next, speed
  cycle kept as text); Back/Bookmark/Undo are icons; a chapter selector
  (`Ch n/N` dropdown from `book.chapters`) jumps with `playPosition(ch, 0)`; a
  passage progress bar renders `offsetSeconds / passageDurationSeconds`
  (duration published from the last segment anchor); the view scrolls to the
  top on passage change (auto-advance already existed in the service loop).
- **Settings**: OCR languages moved to a sub-screen (back-navigating pane);
  the root row shows a chevron entry. Material icons extended added to
  feature-player for SkipNext/SkipPrevious/Bookmark/Pause/PlayArrow.
- **Import result consume-once**: `LibraryViewModel.consumeImportResult()`
  resets the batch summary after the snackbar/dialog, so revisiting the
  library no longer re-shows "Added N · Unchanged M".
Verified visually on the S22 (cover, overflow, reader chrome, settings panes)
plus the JVM suites (core-ebook cover tests; feature-library). Playback
progress/auto-advance require a book + packs on device; the service advance
path is covered by `PregenE2eTest` (decisions #45).
## 47. Download hardening: INTERNET permission + typed network failure (2026-08-27)
Two bugs made the settings pack download crash the app on-device:

- **Missing `android.permission.INTERNET`**: the app manifest never declared it,
  so every HTTP call failed at DNS with an unchecked `GaiException` (EPERM)
  that escaped the download coroutine and killed the process. Added to the app
  manifest (decision #7's explicit-download path is the app's only network use).
- **Unchecked runtime failures in `AndroidHttpTransport.open`**: DNS/resolve
  failures are not `IOException` on this Android — the transport now maps any
  non-IO, non-cancellation exception to a typed `IOException` so
  `PackDownloader` surfaces `IoError`, and the settings row shows
  `failed: network error: …` with Retry instead of crashing.
Verified on-device: offline Download no longer crashes (typed failure + Retry);
with network, the voices pack (28 MB) and the Kokoro model (310 MB) both
downloaded and staged ("ready") through the UI.

## 46. Opus storage spike — host profile + S22 MediaCodec finding (2026-08-27)
Host (`tools/opus_drift_spike.py`, libopus via ffmpeg, real Kokoro passages):
24 kbps 24 kHz mono round-trip is duration-exact (0.0 ms drift), worst
sentence-boundary residual +20 ms = one Opus frame — inside the engine's own
anchor jitter (31–37 ms) and the 250–670 ms sentence pauses. Storage ~11 MB/h
vs 170 MB/h PCM (15–16×). Anchors are seconds-based in the `.meta` sidecar, so
a 48 kHz decode would only need a ×2 sample map.

Device (S22 SM-S908U1, BP2A.250605.031.A3): the MediaCodec opus DECODER is
broken at the native level — every stream (device-encoded and canonical host
libopus) and every decoder (`c2.android.opus.decoder`, `OMX.google.opus.decoder`;
sync and async API) errors; the async path SIGSEGVs inside the codec's memcpy.
The encoder (`c2.android.opus.encoder`, 24 kHz native) emits size-plausible
payloads with a non-standard 83-byte csd-0 (OpusHead + 64 trailing bytes).
Consequence: on-device Opus via MediaCodec is not a dependency; an Opus cache
would need a bundled libopus (native seam) or stay PCM. The host drift numbers
stand as the codec truth; `OpusDriftInstrumentedTest` pins the encoder behavior
and stages evidence files (`files/opusdrift/{input.pcm,device.opus}`).

## 45. Pinned debug keystore + device pass findings (2026-08-26)
The S22 device pass for #42 surfaced three things worth logging:

- **Debug signing is now pinned (`debug.keystore`, repo root, gitignored-exempt).**
  AGP's default `~/.android/debug.keystore` lives per-toolchain: every Docker
  container on a fresh machine recreates it, so each docker-built APK carried a
  new signature and `adb install -r` failed with
  INSTALL_FAILED_UPDATE_INCOMPATIBLE. A committed debug key (password
  android/android, debug-only, not a secret) makes debug builds signature-stable
  across hosts. Release signing stays out of the repo.
- **Hilt workers need explicit wiring — there is no auto-initializer.**
  androidx.hilt:hilt-work 1.2.0 ships only `HiltWorkerFactory` (+ a Hilt module);
  the app must implement `Configuration.Provider` (note: `workManagerConfiguration`
  is a Kotlin *property*, not `getWorkManagerConfiguration()` — the method form
  fails compilation on this metadata), and the default
  `WorkManagerInitializer` must be removed from the startup graph or it wins the
  once-only init with the reflection factory. `PregenManager` constructs its
  `WorkManager` lazily — a constructor-time call reads the provider before Hilt
  finishes injecting.
- **FGS: manifest type alone is not enough on this API-34 device.** Even with
  `android:foregroundServiceType="dataSync"` merged onto WorkManager's
  `SystemForegroundService` (PM records type=1 correctly), `setForeground` died
  with `InvalidForegroundServiceTypeException: type none` — implicit MANIFEST
  resolution is rejected. The fix: pass
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` explicitly in every
  `ForegroundInfo` (#42 doWork). Also needed for targetSdk 34+: the manifest
  merge of `SystemForegroundService` with the dataSync type.
- **Device e2e basics:** instrumented tests share the app's live Room file; a
  tearDown `deleteDatabase` unlinks it under the app's Hilt singleton and
  silently starves the worker in later tests of the same run — e2e tearDowns
  now keep the DB (PlaybackE2eTest + PregenE2eTest stop services and close
  their own instances only).

**SHIPPED (2026-08-27, #44):** `PregenSpaceEstimator` in core-player (cached
passages count exact on-disk bytes via the new `PcmPassageCache.sizeOf`;
uncached passages estimate at ~150–180 wpm English, speed-scaled;
`usageByBook()` sums the per-book subtrees). `PregenStorage` (feature-player)
is the façade — one-pass `estimateAll()` over the cached parses at the active
voice, per-book usage, and `deleteBook` = cancel the book's WorkManager work
FIRST, then delete the subtree (the fast-path invariant: a running worker
would re-write passages right after the delete; active playback is unaffected
— a disk miss falls back to synthesis). UI: the library row states the
footprint in the Pre-generate label (`Pre-generate (≈1.2 MB)`), shows live
usage + one-tap Delete, and refreshes the disk facts when a run settles;
settings gains an "Offline audio" section — per-book rows, total, one-tap
delete (`formatBytes` shared from feature-player). New tests: estimator math
(cached-exact vs formula, speed scaling, custom rates), `sizeOf`,
`usageByBook`. Host suite green; Docker lane green; visual verification on
the S22 pending reconnect (the rows are thin state + the tested core).

**#42 follow-up (2026-08-27, whole-book storage):** the disk-tier byte cap
was 256 MiB and the manual run 60 min — both too small to persist a whole
large book (a real ~5.3k-passage book needs ≈0.9 GiB ≈ 4.4 h at the measured
~20 passages/min). `PcmPassageCache.DEFAULT_MAX_BYTES` raised to 4 GiB
(≈24 h of audio; LRU still bounds growth; the S22 has 544 GB free) and
`MANUAL_BUDGET` is now unbounded time — a tap runs until the book is fully
cached, the tier saturates, or the job is cancelled. Overnight stays 3 h.
## 44. Storage transparency on pre-generated audio (2026-08-27)
Owner idea-batch follow-ups to shipped pre-gen (#42) and settings (#36). The
pregen disk tier is real storage the user currently can't see or reclaim.

- **Pre-generate space estimate**: the library-row Pre-generate flow states the
  expected footprint before enqueuing, computed without synthesizing — bytes =
  24_000 Hz × 2 B × estimated duration (segmented text length → speaking time at
  the active voice/speed); exact for chapters already cached (the cache is the
  source of truth, #42).
- **Per-book audio usage + delete**: settings lists a per-book breakdown and the
  total; delete is one tap per book (settings row, mirrored on the library row
  next to "Pre-generate"). Exclusion: never evict passages queued or currently
  playing (the fast-path invariant) — cancel the book's queued work first.
  Eviction only, no migration: cache keys are engine+voice+speed, so a settings
  change invalidates naturally (#35).
- Same review, left in the ideas pool: auto-delete of listened passages (needs
  undo-ring semantics against `position_history`), habit-driven pre-generation
  (needs the post-v1 session log), translate-then-read language coverage beyond
  pt-BR (settle when `core-translate` starts).
- Consequences: estimates keep the user aware that one listened hour ≈ 170 MB
  (24 kHz 16-bit mono) on disk; per-book delete makes re-listen cache retention
  a user choice instead of an LRU-only policy.

## 43. Brand icon: owner's leaf+arcs trace, ink palette, vector-only packaging (2026-08-27)
The launcher icon pick: the owner's own SVG trace of the leaf + sound-arcs
concept (supersedes every procedural draft; the trace's organic S-midrib,
curled stem, and natural arc ends won). Locked with the brand: ink `#1B2430`
background, amber `#E8A33D` leaf/dot/inner arcs, teal `#1FA8C5` outer arc —
the source image's gold harmonized to amber, no wordmark (dies below 64px;
the name lives in the launcher label / store listing).
Packaging is adaptive-only (`minSdk = 26`): vector foreground scaled ×0.5 into
the 66dp safe zone (measured 60.1% of the 108dp layer), vector background,
Android-13 monochrome with alpha steps (leaf+dot 1.0, inner arcs 0.67, outer
arc 0.43). The trace's off-center dot (37px left of the fitted ring center)
was re-centered during review, then reverted at the owner's eye — the
as-traced dot ships. The arc's extra sliver gap at 285–290° kept — reads
organically. Canonical source `docs/assets/ayvu-icon-master.svg` (==
production geometry); res vectors are generated from it, not hand-edited.
Alternatives: pixel-copying the JPEG (AI-loose geometry — off-center arcs,
thick bands), legacy PNG density packs (unneeded at minSdk 26), wordmark
variant (illegible at launcher sizes).
Verified: docker-lane `:app:assembleDebug` green; `aapt dump badging` shows
`application: label='Ayvu' icon='res/mipmap-anydpi-v26/ic_launcher.xml'`.
## 42. Offline chapter pre-generation: WorkManager job core over the tested cache (2026-08-26)
The roadmap's post-v1 T5 slice (decisions #29) lands as pure scheduling over
the #35 disk tier, plus its playback wiring:

- **core-player `OfflinePregen`**: spine-order walk over a book, skipping
  cache hits (the cache is the source of truth — a run resumes anywhere),
  with run budgets (passages/chapters/time) and two stops that prevent
  thrash: the cache's byte cap (free space below the last synthesized
  passage size — a put would only evict) and a consecutive-failure cap
  (isolated failures are counted and skipped; `Unavailable` stops at once —
  missing packs won't heal mid-run). Cooperative cancellation per passage;
  `onProgress` fires per passage.
- **Disk tier in the play path**: PlaybackService fast path is now queue →
  disk cache → synchronous synthesis; a first listen of any passage persists
  it (async IO put), so normal use fills the offline cache for free. Saved
  audio is the full pre-slice passage (speed-keyed like the queue: #35).
- **`PregenWorker`** (`@HiltWorker`, foreground dataSync with a progress
  notification): manual mode = one book — wall budget **unbounded from
  2026-08-27** (whole-book runs; ends when cached, saturated, or cancelled) —
  unique-name KEEP
  (a second tap is a no-op); overnight mode = 24h periodic, charging +
  battery-not-low, 3h wall budget, yields to an active playback session
  ([`PlaybackActive`]) and to WorkManager cancellation. Voice from settings,
  speed 1.0; cache keyed engine+voice+speed means other speeds synthesize on
  demand as always. WorkManager + androidx.hilt added to the catalog
  (work-runtime-ktx 2.10.1, androidx-hilt 1.2.0).
- **UI**: library-row "Pre-generate" action with live WorkManager progress
  (KEEP-deduplicated; flips to "Pre-gen again" after a success); the app
  schedules the overnight job once at start.
- Budget sizing: a Kokoro book fits a night (~60–90 min audio ≈ RTF 0.7
  → ~1–2 h CPU at 1.0×); CosyVoice3's fallback tier gets its ≈3 ch/night.
- Verification: 13 new core-player tests (49 total; order, resume, budgets,
  saturation, failure caps, cancellation, progress). Host JVM suite green;
  Docker lane green (both APKs + all Android unit tests incl.
  feature-player/library). **S22 device pass DONE (2026-08-26):** new
  `PregenE2eTest` runs the real manual worker end-to-end — tier filled
  (PCM + sample rate + anchors round-trip, all 4 keys), playback completes
  over the warm cache, and logcat proves the fast paths: `source=disk` and
  `source=pregen` served every passage, zero live synthesis. `PlaybackE2eTest`
  re-verified over the new loop. Findings logged as decisions #43.

## 41. V2: CI wiring (2026-08-26)
The roadmap's V2 gate is in place up to the device: `.github/workflows/ci.yml`
with two lanes matching the repo's tooling split:
- **jvm-tests** (every push/PR): the pure-JVM modules — core-model/ebook/locate/
  tts/player/ocr — no Android SDK on the runner. Real-model pack-dependent
  tests (PtBrVoiceHostTest) skip via JUnit assumptions instead of failing on a
  clean cache (the on-device PtVoiceE2eTest covers the same path).
- **android-build** (every push/PR): `docker build -t localtts-android .` then
  the docker-build.sh gate — both APKs (app + androidTest) plus every Android
  module's unit tests (app, core-persistence, feature-settings/share/player/
  library). Instrumented tests stay on the connected S22 (the staging + per-
  class invocation workflow in build.md); CI compiles them.
- **assemble-on-tag**: after both lanes, `:app:assembleDebug` +
  `:app:assembleRelease` — the "assemble on tag" acceptance.
Docs updated with it: modules.md stale "planned" lines removed (feature-player/
reader/share now LIVE; app line rewritten; header reworded), roadmap carries
the #39/#40/#41 markers. Both lanes verified locally command-for-command:
host JVM 255 tests 0 failed; docker lane + release assemble BUILD SUCCESSFUL.
Remaining for V2: nothing in CI scope — what stays manual is device-side
instrumentation (no runner hardware), deliberately.

## 40. pt-BR spot check (2026-08-26, user request)
The pinned Kokoro pt-BR voices were never exercised; both sides now are
(core-tts test + instrumented test only — no main-code changes, the
voice→language map already routed `pf_`/`pm_` to pt-br):
- **Host** (PtBrVoiceHostTest, real model + espeak-ng pt-br G2P): `pf_dora`
  and `pm_alex` synthesize "O rato roeu a roupa do rei de Roma." into Audio
  (~24 kHz, sane duration, sentence anchors present for the read-along);
  a wrong-family name (`pf_dora_typo`) is rejected typed with "unknown voice".
- **Device** (PtVoiceE2eTest, S22): the pt-BR voice selected via settings
  plays a Portuguese book through the real service to COMPLETED —
  `voice=pf_dora` in the loop logs.
Verified the pt-br espeak-ng identifier resolves in the staged bundle
(phonemizer scan) through the successful synthesis itself. The post-v1
pt-BR translation DECORATOR (core-translate, NMT output-side) remains a
separate deferred slice — this test only covers the voice path, not it.

## 39. S-debug: three manual-test regressions fixed (2026-08-26)
The user's manual smoke found three real defects — the very class V2 exists to
catch; all fixed with regression coverage:

1. **Epub import: "could not read container.xml"** — root cause found ON DEVICE
   with a real 24.8 MiB Gutenberg EPUB (staged via adb): Android's Expat-backed
   `DocumentBuilderFactory` **throws `UnsupportedOperationException` on
   `setXIncludeAware(false)` / `setExpandEntityReferences(false)`** (host
   Xerces accepts them) — every OPF/NCX parse died in "could not read
   content.opf" after the container itself failed or passed. Fixes: the two
   factory configs are now tolerance-guarded (doctypes are stripped up front,
   so entity/external expansion is unreachable either way); a single-quoted
   XML declaration is normalized (Gutenberg publishes `<?xml version='1.0'
   encoding='UTF-8'?>`); container.xml's full-path is extracted by regex (no
   XML parse on that path at all). Verified: RealEpubImportProbe OK on-device
   — 8 chapters / 2413 passages import, BookImporter lands Added.
2. **Settings → download voices crashes the app** — `StackOverflowError` from
   the private `operator fun Map.minus`/`plus` extensions in
   SettingsViewModel: they shadow the stdlib operators and recurse on
   themselves (line 193, confirmed in logcat twice). Deleted — the stdlib
   map operators serve. Regression: SettingsViewModelTest (fake transport +
   pinned descriptor) — download completes, clears progress, Ready; a
   corrupt payload surfaces "checksum mismatch" typed, no recursion.
3. **Theme radio doesn't reflect the change** — the settings screen polled
   `settings.reload()` on a 2 s loop, so the radio lagged (the global palette
   followed via a proper flow and changed instantly). AppSettings is now
   push-based: one `StateFlow<Snapshot>` updated on every write; the screen
   and MainActivity observe it — no polling anywhere. PlaybackService/Share
   hot paths read `state.value.*` (still non-suspending). Regression:
   setTheme is observed immediately (host VM test).
Also fixed along the way: `SettingsViewModel.setOcrLanguage` read a removed
field; AppSettings readers migrated (voice=`state.value.voice`, etc.).
Verification: 4 device tests green (RealEpubImportProbe, VoiceSelectionE2e
`voice=bm_george` ×2, PlayPositionE2e, PlaybackE2e); host JVM 240+; Docker
unit sets green including the 3 new VM regression tests. docs: none beyond
this entry; V2 remains the missing gate (CI wiring + running this growing
instrumented set as a job) — the user's question, answered: testing is NOT
done before v1 is done, and this smoke showed exactly why.

## 38. S3: match → open at passage + "listen from here" (2026-08-26)
The resume wiring closes the S → T loop; the share feature is now actionable.

- **Share "Listen here"**: the found card carries a Listen action →
  [ShareOpenHandler] (app-owned navigation seam, feature-share never names
  MainActivity) → MainActivity with the [OpenTarget] extras contract
  (bookId/chapter/passage, owned by feature-share, pure fromExtras parse).
  MainActivity routes any such intent (onCreate, onNewIntent, process-death
  replay) to ReaderScreen with startAt = the passage; READER plays there via
  the existing ACTION_PLAY_POSITION and completes through the book.
- **Reader gesture** (ideas #2 → "listen from here"): tapping the passage
  text (re)starts playback at that passage — the single-passage reader makes
  the ideas.md long-press moot (tap is discoverable; deviation recorded).
  The docked path reuses Resume-style semantics.
- **Verified on the S22** (PlayPositionE2eTest, OK 1 test): ACTION_PLAY_POSITION
  1/0 on a 4-passage book lands `playing 1/0` first (not the book start),
  runs 1/0 → 1/1 → 1/2 → PlaybackCompleted. The service seam the share gate
  and the gesture both drive is measured; the UI glaze (button → activity
  route) is compile-verified + extras-contract host-tested (fromExtras
  round-trips, blank bookId rejected, absent chapter/passage → 0).
Consequences: S or T are now reachable from each other; the roadmap's
S-column is functionally complete (share + resume + gestures). Free-riders
noted: ReaderScreen gained a startAt param (default null — no behavior
change for normal opens); MainActivity consumes the extras exactly once
via compose state. Open: none for this slice.

## 37. S2: the share receiver (2026-08-26)
The share gate from the roadmap, exactly scoped: an ACTION_SEND receiver
(text/plain + image/*), a found/not-found result UX with the threshold from
settings, and a typed pipeline. S3 (match → open book at passage) is next.

- **feature-share**: `ShareReceiverActivity` — launcher-less, exported,
  excludeFromRecents — with two SEND intent-filters. `ShareViewModel` reads
  the intent once (config changes re-deliver the verdict, never re-run the
  pipeline). Text goes straight to the index; images go [ImageDecoder]
  (bounds-first sampled decode, then ARGB) → core-ocr downscale → the real
  tess-two engine → the index.
- **ShareSnippetResolver** (pure JVM, host-tested): normalizes, awaits the app's
  async index rebuild (cold-start shares otherwise race an empty index) for up
  to 10 s, then queries with the settings threshold (AppSettings mirror, V1).
  Resolution is a sealed type: Found(book·chapter·passage·confidence) /
  NotFound(reason + closest-candidate hint, dimmed) / Failed(message).
- **core-locate additions**: `IndexRebuilder.readiness` (CompletableDeferred,
  completes after the first rebuild) and `TextIndex.best()` (threshold-free
  closest candidate — feeds the not-found hint; query() is now best() + gate).
Three on-device findings:
- tess-two/harness quirks consumed most of the verification budget: the Hilt
  instrumented-test application override (test-manifest HiltTestApplication /
  @CustomTestApplication) does not take effect in this AGP 9 project — the
  share pipeline test builds the real components manually instead; the hilt
  androidTest deps were removed again.
- `BitmapFactory.decodeStream(inJustDecodeBounds=true)` returns **null by
  design** — the decoder must not treat it as a read failure (fixed).
- JUnit4 rejects non-`void` test methods: a trailing Boolean expression in a
  runBlocking body (file.delete()) fails validation.
Verified: 9 host tests (text/image/threshold/gate/cold-start) in Docker +
real-engine runs green; on device `SharePipelineInstrumentedTest` OK (2 tests)
— text branch resolves the quote, image branch decodes a rendered screenshot,
OCRs it with legacy eng tessdata and resolves back to the passage. Host JVM
suite 240 green. The manifest carries the activity; the app builds.

## 36. V1 + S1: settings UI and the OCR core (2026-08-26)
V1's settings surface and S1's OCR core land together, both fully verified:

- **Settings UI (V1)**: SettingsScreen (engine + pack download/progress/error,
  voice picker + favorites, share match threshold, OCR languages, theme
  system/light/dark) routed from the library top bar; theme palette owned by
  MainActivity off AppSettings.themeMode; match threshold and voice/favorites/
  ocr-langs persisted in the existing `settings` table (SettingsStore extended,
  Room schema untouched). **AppSettings** (core-persistence, pure JVM) is the
  hot-path mirror: play loop + theme read fields/flows, writes go through the
  store; PlaybackService reloads it at every play/resume/speed action, so
  settings written by the UI apply at the next transport action.
- **Packs (V1)**: the settings download UI drives the repository PackRegistry
  over a new AndroidHttpTransport (HttpURLConnection, Range-resumable,
  canonical redirect-following — decision #7 consent/resume/verify semantics,
  the only sanctioned socket use on-device). Kokoro model/voices download to
  the shared PackCache(filesDir) used by KokoroRuntime; the espeak-ng bundle
  stays a staged artifact (no pinned host exists — status shown, manual path
  documented). tessdata languages download to the same cache and stage into
  the tess-two data path.
- **core-ocr (S1)**: OcrEngine seam + OcrImage/OcrResult + bilinear
  ScreenshotDownscaler (1600 px long-side cap, per-channel, edge-clamped) + the
  six pinned traineddata desciptors — pure JVM, host-tested. feature-ocr wraps
  tess-two 9.1.0: TessTwoOcrEngine (fresh TessBaseAPI per pass, IO dispatcher,
  typed missing-tessdata failures) + TessDataStager (idempotent copy from the
  pack cache into `<filesDir>/tesseract/tessdata/`).
Consequences/tuning found on the S22:
  - **tessdata_fast 4.0.0 LSTM models FAIL init on tess-two 9.1.0** (native
    build is pre-LSTM; `init` returns false) — the pinned packs are now the
    **legacy tessdata 3.04.00** artifacts (eng 21.9 MB … ita 14.2 MB; real
    SHAs produced by one-time downloads; on-device ocr smoke test reads
    rendered glyphs, and the device hash matched the pin exactly). Revisit
    LSTM (accuracy) with a newer binding in a future slice.
  - A test-only wiring fix surfaced a real bug: the service must reload
    AppSettings — settings written by the UI were silently ignored by a
    service that never refreshed its singleton.
  - Instrumented verification: PlaybackE2eTest (full-book pregen completion),
    VoiceSelectionE2eTest (persisted voice reaches the engine —
    `voice=bm_george` in logs), OcrSmokeInstrumentedTest (tess reads
    "HELLO WORLD 123" from rendered pixels); each ran as its own instrument
    invocation (test classes sharing one process tripped Room-reopen races in
    the harness — signature pairing across app/test APKs demands one build+run
    invocation anyway, decision #34).
Open: LSTM models via a maintained binding when accuracy demands it — the only
remaining #36 item (S2/S3 shipped, #37/#38).

## 35. T5-core: pre-generation queue + cache keying (2026-08-26)
The in-v1 pre-generation core (roadmap T5) lands in `core-player` — pure JVM,
engine-agnostic, fully unit-tested:

- **PregenQueue**: bounded (default lookahead 2) in-memory look-ahead —
  synthesizes the passages after the playhead while the current one plays, so
  a passage change is a `take` fast path, not a synthesize-then-play gap.
  Prunes entries at/before the playhead on re-anchor (a jump forward drops the
  stale look-ahead and refills), dedups, is single-flight, and stops at the
  first failed synthesis (the player's synchronous path is the typed-failure
  fallback). `take` runs lock-free against the map — the play loop never waits
  on synthesis.
- **PregenKey** = bookId + spine + voice + speed; its stable path form is the
  post-v1 disk cache layout (engine + voice + speed + passage keying per #31/
  #34; content-hash book ids make book removal a subtree delete, #11).
- **PcmPassageCache** (the post-v1 disk tier's logic, now): raw PCM + `.meta`
  sidecar (sample rate + sentence anchors) under `<root>/<bookId>/<voice>/
  <speed>/c<ch>p<passage>.pcm`, atomic tmp+rename writes, LRU eviction by a
  byte cap tracked in-process (filesystem mtime was unreliable on tmpfs),
  book-level delete.
- Wired into PlaybackService: the play loop takes from the queue when warm
  and launches look-ahead after playback starts (`ensure(position)` per
  passage, cancelled on jumps); a speed change rebuilds the queue at the new
  speed (the key carries speed). App builds; the two-call sequence that
  previously gated on a ~synthesize-then-play gap is now a take.
Consequences: the audible inter-passage gap closes (device re-verification
pending the S22 reconnecting); the post-v1 WorkManager slice is now pure
scheduling over a tested cache. Host suite: 36 core-player tests + 216 total
green; feature-player excluded core-tts's jar JNA at the new core-player edge
(the app AAR seam, #25/#32).

## 34. T4-2 landed: the player surface (PlaybackService + docked reader) (2026-08-26)
The Android half of T4 rides on the #33 machine and the #31 anchors:

- **PlaybackService** (feature-player, foreground `mediaPlayback`): runs the
  machine against the engine + an AudioTrack (`PassageOutput` seam),
  MediaSessionCompat (transport + prev/next), audio focus/ducking
  (GAIN/TRANSIENT/LOSS/CAN_DUCK) with auto-resume after transient loss,
  becoming-noisy pause (route switch), 1 s sleep-timer tick + 500 ms state
  publish, media notification.
- **Speed is per-request now**: `SynthesisRequest.speed` reaches the graph;
  positions/anchors stay book-time and sample math scales by speed
  (`sliceForSpeed`); a speed change pauses at the live offset and re-synthesizes
  from it — the play point is preserved (decisions #29 acceptance).
- **Read-along docked panel** (ReaderScreen): the passage text with the active
  sentence highlighted from engine segments (#31), transport dock (play/pause,
  prev/next, speed cycle, sleep cycle, undo-skip, bookmark at playhead).
  App navigation: LibraryScreen → ReaderScreen on book tap.
- **Engine/JNA wiring on device**: `KokoroRuntime` opens the engine lazily
  over `PackCache(filesDir)` + the staged espeak-ng bundle (#32); feature-player
  excludes core-tts's jar JNA and ships the `@aar` (the seam in practice).
- **Two device bugs found & fixed while wiring:** (1) the machine's natural
  advance kept `phase = PLAYING`, so the service loop exited after the first
  passage — `onPassageFinished` now marks the advanced passage `LOADING` (it
  needs its audio); (2) static AudioTrack completion via `onMarkerReached`
  never fires on this S22 build — completion is now head-position polling
  against the buffer frame count.
- **Verified on the S22** (locked, instrumented `PlaybackE2eTest`): a
  two-passage book plays through the real service/engine/AudioTrack to
  `COMPLETED`, segments surface for the read-along, and the resume row lands
  on the ending. Packs + espeak bundle staged per build.md (the V1 download
  UI owns the consent flow later).
Consequences: T4's player UX is functional and measured on-device; the
remaining free-riders (speed presets UI polish, Android Auto confirmation,
sleep-timer UI text) are V1 surfaces, not architecture. T5 (pre-gen queue)
keys off the passage blob + speed (+ voice) as designed.

## 33. Player state machine: single transactional write point + position ring (2026-08-26)
T4-1 lands `core-player` (pure JVM) — the logic half of the v1 player
(decisions #29); audio/synthesis stay with the engine (#31) and the Android
edges (T4-2):

- **The machine is the ONLY writer of player state.** Every write goes
  through `PlayerStore.commitProgress` = progress row + optional ring push in
  **one transaction** (Room `withTransaction`) — the resume row and the undo
  ring can never drift (T4 carry-over note 3). `PlayerStore` is a
  core-player contract with a Room impl (`RoomPlayerStore`) and an in-memory
  impl for tests.
- **Ring semantics:** a user-directed move **away** from the current position
  (skip forward/backward, seek, play-from-elsewhere, accidental play) pushes
  what is being left; natural forward advance never pushes; cap 10/book;
  `popRing` = one-shot undo; completion pushes the ending so undo replays it.
- **Positions are book-time** (offset at 1.0×): speed changes never move the
  play point, and the per-book speed persists in the progress row (preset
  restore, decisions #29 free-rider).
- **Sleep timer:** Off / EndOfChapter (pauses at the chapter's last passage,
  before the new chapter) / Duration (wall clock, fires once);
  `advance(now)` is the tick.
- **Bookmarks** snapshot the machine's position, so a bookmark is always
  consistent with the resume row.
- **Schema v2:** `progress` gains `offsetSeconds` + `speed` (backfilled 0 /
  1.0 in the migration); new `bookmarks` + `position_history` tables;
  forward-only `MIGRATION_1_2` (decisions #22). Verified by a legacy-DB
  migration test: exact v1 DDL → open v2 → Room's post-migration TableInfo
  validation + defaults + end-to-end store writes.
- The edge contract is the events: `PassageAdvanced` / `PauseRequested` /
  `PlaybackCompleted` + `LOADING→PLAYING` (`onAudioStarted`); T4-2 wires
  MediaSession/audio/Compose to it.
Consequences: T4-2 is thin wiring; the read-along highlight consumes
`segments` (#31) against `position.offsetSeconds`. Test surface: 20
core-player (transitions, ring, sleep, speed, bookmarks, failures) + 17
persistence (store round-trips, cap, migration) — 37 new, all green.

## 32. espeak-ng Android bundle: 1.52.0 tag + matching data, flat pack (2026-08-26)
The raw port's open scar (landscape.md "Android packaging of espeak-ng
data/library") is closed: cross-compiled `libespeak-ng.so` with the NDK
(arm64-v8a, ~2.1 MB) at the **1.52.0 release tag** — the exact version the #28
ground-truth phonemizer oracle is frozen against — paired with the version's
compiled `espeak-ng-data` (19 MB, arch-independent; taken from the system
1.52.0 installation, byte-for-byte the data the host tests use).

- Data generation cannot run in a cross build (the compiled generator is an
  arm64 binary), and master's committed data dir is a 1.4 MB stub — hence
  tag-pinned lib + tag-matched data.
- Ships as a flat pack per #23/#26 (app loads `libespeak-ng.so` by explicit
  path with `espeak-ng-data` staged next to it); pinned SHA-256 for the pack
  descriptor: `734cc95a93217a68…` (lib), committed when T4 wires the download.
- `tools/build-espeak-android.sh` reproduces the build in the toolchain image
  (root container, apt cmake/ninja/git, NDK 27.2.12479018; `build/` outputs
  gitignored).
- 1.52.0's CMake fetches `sonic` via FetchContent (needs git in the
  container); the library target builds clean, the data target must be
  skipped in cross builds (it executes the arm64 binary on the host, fails).
Consequences: T4's Android phonemizer adapter has no packaging risk left; a
device re-run with phonemization on-device is a one-command exercise once the
pack is wired.

**Addendum (same day): the bundle RUNS on-device, phonemes byte-parity.**
Wired into the spike harness (JNA + `libespeak-ng.so` staged under
`files/espeak/` with `espeak-ng-data` next to it): on-device phonemization
reproduces the host output exactly (en 730 chars, pt 496 chars — the #28
oracle survives the platform), and the full-pipeline RTF (phonemization
included) is 0.774 (en) / 0.754 (pt) on the S22 — the realtime bar still
clears end-to-end. espeak-ng cost: ~10–40 ms per passage.

**JNA-on-Android finding (load-bearing for T4):** the plain `jna` jar (5.17.0)
ships NO Android natives (its 21 `jnidispatch.so` are all desktop), so T4's
Android phonemizer adapter must depend on the **AAR**
(`net.java.dev.jna:jna:5.17.0@aar`, ships `jni/<abi>/libjnidispatch.so`,
resolved via `System.loadLibrary`) and exclude the jar that core-tts brings —
the spike-tts wiring
(`implementation(project(":core-tts")) { exclude(group = "net.java.dev.jna") }`
+ `@aar`) is the reference for the app module.

**Landed (2026-08-26): the T4-0 contract is code + tests.** `SynthesisOutcome.Audio.segments: List<SegmentAnchor>?` (contiguous sentence spans in seconds; `SegmentAnchor(startSeconds, endSeconds)`); `KokoroTimings.sentenceSegments` groups pause-shifted phoneme timings at `.!?…` marks — span *i* runs to the next sentence's first phoneme, the last to the audio end, so boundaries are gap-free and exact in the final audio; null for graphs without a duration output. Engine threads the shifted timings out of `insertPauses` (previously discarded). Tests: 3 new engine tests (mark split + contiguous spans, null without durations, single-span unmarked text) + real-model validation in `kokoroGrainSpike` (anchor count == sentence count; each interior boundary sits at the end of a rendered ≥150 ms pause run within 37/31 ms — 1–4 frames of the documented ±1-frame tolerance, no silence scanning needed). CosyVoice3 tier: null → read-along degrades to no per-sentence highlight, never estimated.

## 31. T4 synthesis grain: one passage blob + engine-computed sentence anchors (2026-08-26)
Spike A settled the T4 carry-over question ("sentence-grain synthesis vs one PCM
blob + anchors" — roadmap note 2) by measurement on the pinned real model
(`:core-tts:kokoroGrainSpike`, two passages: 6-sentence en/af_heart, 4-sentence
pt-br/pf_dora):

- **Per-sentence calls inflate audio.** Joined sentence audios vs one blob:
  en +0.07 s (+0.2 %, blob = 2 windows vs 6 sentence calls); pt +2.47 s
  (+10.8 %, 1 window vs 4 calls) — each call re-renders window-start
  context and its own prosodic contour. Listen-time drift is unbounded per
  call and per text.
- **No compute win.** Total synthesis ms: en blob 7.6 s vs joined 8.1 s
  (+6.7 %); pt 3.7 s both (~±4 %) — extra windows/pads cancel any savings;
  per-sentence RTF looks better only because the inflated audio is divided
  into the same total compute.
- **Seam behavior differs.** Sentence calls force uniform 241–273 ms pauses at
  every boundary; the blob renders natural 250–670 ms pauses at sentence and
  clause marks (the model's own phrasing, only topped up by insertPauses).
- **Anchors.** The blob's boundary pauses are NOT reliably alignable to
  sentences by silence scanning alone (clause marks render ≥200 ms pauses
  too) — the engine's `KokoroTimings` (per-phoneme, already computed and
  pause-shifted inside `insertPauses`, 7 tests; currently discarded) are the
  only reliable boundary source. Sentence calls give trivial anchors but at
  the seam cost above.

Decision: **T4 synthesizes one passage per request (blob) and the engine exposes
sentence-grain anchors in the outcome** — the player never re-splits or silence-
scans. Concrete contract to land with T4:

- `SynthesisOutcome.Audio` gains an optional `segments: List<SegmentAnchor>?`
  (sentence spans in seconds produced from the phoneme timings each mark
  boundary; null for engines without duration output — CosyVoice3 tier
  degrades to no-read-along highlight, never estimated highlights).
- Pause-shifted timings from `insertPauses` thread through instead of being
  discarded.
- Speed changes stay engine-side (timing anchors scale with the speed input).
- Post-v1 pre-gen cache keys per passage (blob), not per sentence (cache keys
  would otherwise freeze the seam artifacts).

Consequences: T4's player contract and T5's cache keys are fixed before the
player slice starts; the read-along highlight derives from one source of truth
(the engine), not text re-segmentation. Spike files: `kokoroGrainSpike` task +
`KokoroGrainSpike.kt` (measurements reproducible with `-PkokoroCache`); the same
run wrote the on-device corpus for the #30 device spike (`kokoro-device-corpus.tsv`,
raw pre-vocab-filter espeak-ng phonemes per corpus text).

## 30. DRAFT (pending owner ratification) Engine layer: resolve raw-port vs sherpa-onnx before T4 (2026-08-25)

The #25 pivot question ("sherpa-onnx the pivot if the V3 pass misses") was scheduled
to be answered at the V3 device gate — after the player slice. T4 would build on the
raw port's contracts with the espeak-ng Android-packaging scar still open (landscape
open item: "the Android packaging of espeak-ng data/library is the open piece").
Draft: close that scar with a focused on-device spike BEFORE T4, and pre-commit the
fallback so the choice cannot drift.

Evidence:
- sherpa-onnx's Kokoro bundle ships the phonemizer as packaged assets (espeak-ng-data
  ~26 MB, lexicons, tokens, voices.bin, rule FSTs — landscape); candela proves the
  in-process path on Helio P22T-class hardware. The raw port's remaining edge is our
  ground-truth phonemization oracle (byte-identical refs, #28) — fidelity insurance,
  not product value.
- The raw port is otherwise finished and measured on host (RTF 0.15–0.23; 81 core-tts
  tests incl. oracle comparisons). The open risk is device-side: Android espeak-ng
  packaging + S22 Ultra RTF/RAM/thermal + APK-size/ABI cost — all three measurable by
  a small spike, none need T4.
- Post-#27 both paths are license-clean: espeak-ng GPL-3.0 into a GPL-3.0 app;
  sherpa-onnx Apache-2.0 one-way into GPL-3.0.

Alternatives:
- A. Status quo — V3 gate after T4: T4's engine-facing contracts, pre-gen queue keys
  and Android TTSEngine adapters land on the raw port; a V3 miss refunds that wiring.
- B. Pivot to sherpa now: closes the open scar immediately, but discards/relegates the
  finished oracle-verified port and adopts a new native AAR surface before any
  measurement says the port fails.
- C. Draft choice — a pre-T4 "engine-on-device" spike (bundle espeak-ng for the
  target ABI, run the port's Kokoro on the S22 Ultra, measure RTF/RAM/thermal +
  APK-size/ABI), with the sherpa pivot pre-committed if it misses; T4 proceeds on
  whichever engine the spike vindicates. The spike also carries a hard capability
  check: sherpa's Kokoro path must expose per-phoneme timing anchors — the
  sentence-sync read-along (the v1 thesis, #29) depends on them, and the raw
  port's `KokoroTimings` (7 tests) are the current source. If sherpa cannot, the
  pivot fails on that ground alone, regardless of RTF.

## 30b. Device half MEASURED — raw port passes on the S22 Ultra (2026-08-26)
The #30 spike ran the raw port on the S22 (SM-S908U1, arm64, screen off/locked)
via a new instrumented harness in spike-tts (`KokoroBenchmarkRunner` +
`KokoroDeviceBenchmarkTest`, 2 passages — 40.4 s en/af_heart + 22.8 s pt-br/
pf_dora, 3 runs, ORT with ALL_OPT + 6 intra-op threads):

- **RTF 0.66–0.76 across runs** (en 0.69–0.74, pt 0.66–0.76) — the realtime bar
  clears with ~35 % headroom; engine open 1.5 s. vs CosyVoice3's 14.7–17.5
  (decisions #21): the engine-order verdict the draft was waiting for.
- **Platform parity:** device audio matches the host render (en sample count
  exact 970431 = 970431, correlation 0.996, RMS identical; pt off by one 10 ms
  pause-frame = the documented #28 variance). Phonemization was the only
  excluded stage (host-precomputed corpus; espeak-ng cost is ~ms/sentence) —
  closed by #32.
- **RAM:** VmHWM 1.41 GB / totalPss 1.31 GB (fp32 325 MB weights + ORT arenas);
  V3 tuning candidates `enableCpuMemArena`/`doCopyInKernels`, fp16/int8 only
  past the #26 measured gate.
- **APK-size/ABI:** arm64-only debug APK 21.1 MiB (onnxruntime-android +
  engine; models stay runtime downloads, decision #7).
- Two harness findings: (1) a launched-but-keyguarded Activity freezes at
  `__refrigerator` — benchmarks must run as instrumented tests or on an
  unlocked screen; (2) ORT's no-options `createSession` stalled on the 325 MB
  graph on device while the optioned path loads in ~1.5 s —
  `OrtKokoroSession.open` now sets explicit `SessionOptions` (ALL_OPT + 6
  threads, the T3-verified settings); host benchmarks shift slightly from #28.

Gate conclusion: the raw port passes; the sherpa pivot is not needed for v1
(and its TTS path cannot serve the read-along anchors yet — #3705/#3727 still
open upstream). T4 proceeds on the raw port; #32's bundle closes the packaging
scar; V3's device pass is now a routine RTF/RAM re-check, not a gate.

## 29. Idea review: what graduates to the roadmap (2026-08-25)
Walked the ideas.md pool with the owner; each candidate got a disposition, and the
pool now carries a decision-status table:

- **Docked player with sentence-sync read-along = the v1 player UX.** T4 is
  reshaped into reader+player in one slice (docked playback panel on the reading
  page; narration highlights the current sentence). Feasible, not speculative: T2
  already computes per-phoneme timings from the graph's duration output. This is
  the product thesis ("import, open the page, tap Listen"), so it anchors the v1
  UI instead of competing with a plain player.
- **C7: TXT + Markdown import lands in v1** — a small sandbox-doable text parser
  through `BookSegmentation`; PDF stays deferred (both sibling apps agree).
- **Free-riders folded into existing slices as acceptance criteria** (no new work
  items): read/listen progress single-source (one `progress` row per book), speed
  changes preserve the play point, output/route-switch robustness, Android Auto
  verification, sleep timer incl. end-of-chapter, speed presets + per-book restore
  (T4); "Listen from here" passage gesture (S3); theme-follows-system + voice
  picker with favorites (V1; quality tiers parked until a second engine exists).
  Review follow-ups folded into T4: **user bookmarks** (migration v2 `bookmarks`
  table, long-press add, reader menu) and a **per-book position ring with an
  undo-skip action** (`position_history`, capped rows/book) — undo over confirm
  dialogs for accidental plays/skips.
- **Post-v1 roadmap markers:** offline chapter pre-generation (T5 extension —
  WorkManager job core + config-keyed PCM cache; the lever that makes the
  CosyVoice3 tier viable despite #21), pt-BR translation decorator (new
  `core-translate`, CC-BY-4.0 NMT with attribution, degrades to the original),
  TODAY stats dashboard (new local per-day minutes table), Kindle official-export
  sync (already deferred), **app export/backup + restore** (positions, library,
  settings, optional books — added at review follow-up; small versioned-zip
  `core-backup` slice; content-hash book ids make restore idempotent), **full
  read/listen session log** (the T4 position ring grows into a timeline; the same
  table feeds the stats dashboard), RSVP, classics bundle, auto language
  detection.
- **Ideas-only:** accelerator/int8 delegates (do not assume — measure at V3) and
  multi-engine parallel tuning (design reference until the S22 pass).
- Consequence: pt-BR moves from "fallback-engines nice-to-have" to first-class via
  the v1 primary — the pinned Kokoro pack ships pf_dora/pm_alex/pm_santa (hard-facts
  updated); the roadmap's stale assumptions (CosyVoice3 primary) are corrected to
  the #21/#25 outcome.
## 28. T2 landed: Kokoro-82M engine + first pinned pack descriptors (2026-08-25)
T2 ships the Kokoro engine behind `TTSEngine` in core-tts and closes the
"hashes never fabricated" debt of #23. The engine is a JVM port of the
kokoro-onnx `SpeechPipeline` (the #25 decision), with the following
implementation facts worth keeping:

- **Phonemization = phonemizer's espeak backend, ported.** espeak-ng is driven
  through JNA with the exact phonemizer semantics (punctuation preserve/restore
  with positions B/E/I/A, decimal-separator protection, stress kept, espeak's
  line post-processing, `_`-separator removal). Ground-truth tests freeze the
  reference strings (phonemizer 3.4.0 + system espeak-ng 1.52). Two port traps:
  Kotlin's `String.split(String)` is literal — `Regex.escape` produces `\Q..\E`
  quoting that silently never matches; and empty chunks MUST be filtered after
  punctuation stripping or stray lines leak into the output.
- **The graph contract is introspected, not assumed** (input_ids vs tokens,
  int vs float speed, duration output presence, embedded `kokoro_config` vocab)
  — the v1.1 export uses `input_ids`/float speed and carries durations + vocab;
  packaged `config.json` was validated identical to the embedded metadata.
- **JNA 5.17 moved `PointerByReference` to `com.sun.jna.ptr`** (the old package
  is gone, not deprecated); JNA `Structure.newInstance` needs a public no-arg
  class, so the espeak `VoiceStruct` is top-level, not a private inner class.
  ONNX Runtime 1.23's `OrtSession.Result.get` returns `Optional`.
- **Kotlin 2.4 dropped `kotlin.math.round(Double)`** — use `roundToLong()`.
- **Pause insertion is ±1 frame (±0.01 s) unstable near the quiet threshold**:
  numpy float32 pairwise vs sequential sums flip borderline quiet-frames. The
  reference python pipeline itself varies 83264↔83504 samples across runs for
  the same input, so this is accepted variance, not a bug; the oracle
  comparison (correlation 0.995–0.997, exact sample counts on clean runs)
  validates the port.
- **First pinned descriptors (model-files-v1.1, flat fp32, #26):**
  `kokoro-model` = kokoro-v1.0.onnx (325,505,369 B, sha beb0d184…df3a) and
  `kokoro-voices` = voices-v1.0.bin (28,214,398 B, 54 voices, sha bca610b8…fbf7d).
  Languages advertised = the pack's actual coverage (en, fr, es, it, pt, ja, zh,
  hi); German/Korean have no v1.0 voices.
- **Host RTF baseline (Ryzen 9 8945HS, fp32, ORT JVM): 0.15–0.23** vs the
  realtime bar; device pass stays V3. Android adapters (OkHttp transport,
  bundled espeak-ng) arrive with the player/settings slices.
## 27. License: GPL-3.0, building stays in-house (2026-08-25)
Owner decision after the landscape review: the repo publishes under GPL-3.0 from now
on (supersedes #10) and keeps building the app itself — no copying from candela or
VoxSherpa even though it is now license-permitted.
Context/evidence: core-ebook's `HuffCdic.kt` and `MobiNcx.kt` are direct Kotlin ports
of KindleUnpack code (GPL-3.0) — an Apache-2.0 repo already contained GPL-3.0-derived
code, a latent compliance gap. Single author, so relicensing is the owner's call.
Alternatives: keep Apache-2.0 + clean-room redo of the two parser files (extra days,
and GPL reuse stays forbidden); GPL-3.0 + copy candela wholesale (fastest to a shipped
player, forfeits the build-it-yourself learning and adopts a mismatched architecture).
Consequences: `LICENSE` = GPL-3.0 full text; the KindleUnpack-derived parser code is
now legally consistent with the repo license; Apache-2.0 (sherpa-onnx, CosyVoice3)
and MIT (kokoro-onnx) dependencies stay compatible (one-way into GPL-3.0); candela/
VoxSherpa code may be reused with attribution if ever wanted, but the clean-room
posture is kept by choice (learning + architecture fit — landscape.md). Attribution
obligations apply to any GPL code adopted later.
## 26. Voice-pack defaults: flat single-file artifacts; fp32 weights (2026-08-25)
Evidence from candela (docs/landscape.md): voice packs are re-hosted pre-extracted
because on-device `.tar.bz2` extraction delayed first chapters tens of seconds on
low-end hardware; and INT8 packs were regressed to fp32 because INT8 dynamic
quantization added audible vocoder noise. Decision: pack descriptors (#23) point at
single flat files — upstream tarballs get a server-side extraction re-host (the
pack-servicing step candela's `voices-v2` plays); fp32 is the default until a
measured RTF gate forces quantization, and even then only quantization-safe ops are
candidates. Applied at T2 pack pinning.

## 25. T2 engine layer: raw JVM port of kokoro-onnx; sherpa-onnx documented pivot (2026-08-25)
The landscape review (docs/landscape.md) surfaced sherpa-onnx (Apache-2.0; Java/Kotlin
Android API; prebuilt TTS demo APK; Kokoro v1_0/v1_1 bundles incl. phonemizer assets)
as the packaged engine candela ships in-process, vs the raw ONNX-Runtime port of
thewh1teagle/kokoro-onnx (MIT). The review's key finding — Kokoro is not a bare ONNX
call; phonemization is part of the pipeline — informed the implementation rather than
reversing the choice: T2 (in progress in the working tree) implements the engine as a
JVM port of kokoro-onnx (`SpeechPipeline` semantics) with espeak-ng phonemization via
JNA (system shared library), ONNX Runtime behind a `compileOnly` Java-API seam (JVM
jar for host tests/benchmark; the Android runtime ships inside the app, minSdk 26),
and the first real descriptors pinned as flat fp32 packs (kokoro-onnx
`model-files-v1.1`: `kokoro-v1.0.onnx` 325 MB + `voices-v1.0.bin` 28 MB, 54 voices —
decisions #23/#26). The advertised languages follow the pinned pack (en, fr, es, it,
pt, ja, zh, hi — v1.0 ships no German/Korean voices). sherpa-onnx remains the
documented pivot if the V3 device pass (RTF on the S22 Ultra, APK-size/ABI cost)
misses the bar — a `TTSEngine` impl swap; core-tts contracts and the pack seam are
unchanged.

## 24. GPL boundary: no reuse from candela or VoxSherpa-TTS (2026-08-25)
The landscape review (docs/landscape.md) found candela — a shipped Android
audiobook/reader that is the closest existing implementation of this app — and its
engine AAR VoxSherpa-TTS are GPL-3.0. GPL code and GPL AARs cannot link into this
Apache-2.0 project (#10) without contaminating it. Decision: candela/VoxSherpa are
design references only — their docs, issues, and documented behavior, never their
code. sherpa-onnx (Apache-2.0, the engine beneath VoxSherpa) stays a legal dependency
option.

**Superseded in part by #27 (2026-08-25):** this project is now GPL-3.0, so reuse
from candela/VoxSherpa is license-permitted; the reference-only posture stands by
choice (learning + architecture fit), not by law.

## 23. T1: TTSEngine contract + pack registry + download manager in core-tts (2026-08-25)
T1 ships `core-tts` (pure JVM): the `TTSEngine` interface, the pack registry
(engine → pack → status), and the download manager. Design decisions:

- **Pack descriptors are data; hashes are never fabricated.** A descriptor
  (HTTPS URL + pinned SHA-256 + size) exists only once a real artifact has been
  downloaded once and hashed — that happens during the engine slice (T2).
  `DefaultEngines` ships engine metadata only until then, so no placeholder
  URLs/hashes can ship by accident.
- **The cache is the persistent pack state; no Room pack-state column.** A pack
  is Ready iff a `.ready` marker sits next to its full-size artifact under
  `<root>/packs/<engineId>/`. A full-size unverified file is hashed exactly once
  on first use, then marked; the marker survives restarts, and deleting the pack
  files deletes the marker with them. (P1's "language-pack state" settings key
  is served by cache probing, not a separate table.)
- **Transport seam = the single sanctioned socket use** (hard-facts
  offline-first). `DownloadTransport` is the only network path; `JdkHttpTransport`
  (java.net.http) covers JVM/testing, and an Android adapter lands behind the
  same interface later — `java.net.http` isn't available on minSdk 26 devices.
- **Four statuses** — NotDownloaded / Downloading / Ready / Failed. Failed is
  per-session last-attempt memory (survives `refresh()`, cleared by the next
  attempt) so the UI can surface it instead of silently resetting.
- **Downloads:** resume via `Range` (206 append, 200 clean restart), per-chunk
  cancellation that keeps the `.part`, streamed SHA-256 verify before promote,
  corrupt artifacts deleted not cached, and concurrent requests for one pack
  coalesced into a single transfer (a join bug here — awaiting the caller's own
  fresh deferred instead of the in-flight one — was caught by the coalescing
  test and fixed).
- Consequence: **136 tests green** (+38 core-tts); T2 = Kokoro-82M impl behind
  `TTSEngine` + the first real descriptor.
## 22. Persistence stack: Room 2.8.4 on kapt + forced kotlin-metadata-jvm; LibraryStore in core-model (2026-08-25)
P1/P2 landed Room on the existing kapt path (Hilt already there; no KSP plugin
exists for Kotlin 2.4 as of this date — KSP tops out at 2.3.11). Room 2.8.4's
kapt processor bundles a kotlin-metadata-jvm reader capped at metadata **2.3.0**,
while Kotlin 2.4.10's own stdlib/coroutines classes carry 2.4.0 metadata — so
processing any suspend DAO method crashes the processor, no matter what the
module emits (the decision #19 class of problem, now inside a dependency).
Fix: `resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")`
on the module's kapt configurations — the reader API is backward-compatible.
Scoped to `core-persistence`; lift the force when Room/KSP support Kotlin 2.4
metadata.
**Follow-up (2026-08-25):** the "no KSP for Kotlin 2.4" fact above refers to
the KSP1 line; KSP2 — the analysis-API reimplementation — decoupled from
per-Kotlin versioning: release **2.3.11** (its 2.3.10 fix targets Kotlin 2.4.0
default module names) works with Kotlin 2.4.10. **Migration done, verified
same day:** all three kapt consumers moved to KSP2 (`com.google.devtools.ksp`
2.3.11) — Room in core-persistence, Hilt in feature-library + app; the forced
kotlin-metadata-jvm and the `kotlin-kapt` catalog alias are gone; the crash
class was kapt's metadata-jar parsing, and KSP2 reads symbols via the analysis
API instead. Bar met in the Docker toolchain: **179 tests green, 0 failures
(ebook 50, locate 32, persistence 9, tts 81, feature-library 7)** and
`assembleDebug` builds. One infra find: Gradle's default 384 MiB metaspace
(no `org.gradle.jvmargs`) is insufficient under KSP2 + R8 dexing —
`org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g` added to gradle.properties.
The AGP 10 built-in-Kotlin conversion stays deferred to post-v1 (owner,
2026-08-25): KSP2 removes the kapt wall from that migration but not the other
costs (new DSL, KGP band ceiling, compileSdk/BOM unfreeze, toolchain).
Schema: version 1, `exportSchema = false` (schema-drift check arrives with CI/V2),
migrations forward-only, **no destructive fallback** — a schema bump without a
migration fails loudly rather than wiping the library.
`LibraryStore` (books flow + add) is the domain contract in core-model;
`InMemoryLibraryStore` (unit tests) and `RoomLibraryStore` (production) implement
it. Authors are stored U+001F-joined in one column; if a real author name ever
contains U+001F, switch to a child table first.
The cached-parses table is the launch-time rebuild input; the rebuild is
mirror-set (purge ids absent from the cache), so it is idempotent and merges
safely with concurrent imports.
## 21. T3 CosyVoice3 gate result: CPU-only fails on the S22 Ultra; Kokoro stays v1 primary (2026-08-25)
Measured via the `spike-tts` harness (final, audio-verified run): jiangzhuo9357
int4 ONNX export (sokuji-audio-verified semantics), ORT 1.23.2 CPU-only, 6
threads, S22 Ultra (SM-S908U1), 3 runs on a cool device. Final RTF 14.7–17.5
per 10.1–13.1 s of audio (LLM 32–50 s, flow DiT 107–133 s ≈ 72% of cost, HiFT
8–10 s); VmHWM ≈ 2.4 GB, totalPss ≈ 333 MB, no thermal throttle. The flow DiT
has no credible mobile acceleration path (ORT Vulkan EP / NNAPI / LiteRT-LM
cover LLM-style ops only; bounded best case ≈ RTF 10–19). The spike's open
fidelity defect is closed: a stale diffusion-input snapshot (flow `x2` never
rebuilt per step) produced a hot/compressed mel (mean −0.9 vs prompt −5.6) and
clipped buzzing audio; fixed in `Pipeline.flowGenerate`, device mels now match
host (−5.3 ± 0.3, prompt scale) and audio is clean (RMS 0.05–0.08, peak < 0.7,
no clip). Gate verdict unchanged and now final: **CPU fails the ~0.5–1×-realtime
bar by ~15–30×; v1 primary = Kokoro-82M** (decisions #6).
Consequences: v1 = Kokoro-82M primary, CosyVoice3 remains behind the gate in the
fallback tier; `spike-tts` stays in the repo to re-run the gate when a DiT
acceleration path exists; AR codec-token engines (hard-facts watch item) are the
documented bypass if narration quality checks out.

## 19. Toolchain revision: Gradle 9.1.0 + AGP 9.0.1 for Hilt (built-in-Kotlin opt-out) (2026-08-24)
Hilt's gradle plugin requires AGP ≥ 9.0 since 2.59, and Hilt 2.58's processor
cannot read Kotlin 2.4 metadata — with Kotlin 2.4.10 pinned, only AGP 9 works.
AGP 9.0 requires Gradle ≥ 9.1.0, so the wrapper moved 8.14.3 → 9.1.0 (KGP
2.4.10 band: 7.6.3–9.5.0). AGP 9's new DSL + built-in Kotlin break the classic
kotlin-android/kapt path, so `android.newDsl=false` and
`android.builtInKotlin=false` opt out (both supported until AGP 10, which
forces the built-in-Kotlin migration — deferred to post-v1, decided 2026-08-25; the KSP2 finding under #22 removes the kapt wall but not the rest). Compose BOM stays
2026.06.01 (newer BOMs need compileSdk 37/AGP 9.1). Docker image unchanged
(build-tools 36.0.0 = AGP 9 default).


## 18. Gradle wrapper pulled forward from F1 (2026-08-24)
The wrapper is pure-JVM and README/build.md/docker-build.sh already document
`./gradlew` commands; landing it now makes those true and shrinks F1 by one item.
The sandbox Kotlin-compiler rig (decision #17) remains the proof source until the
Android toolchain lands.

## 17. Sandbox verification rig: standalone Kotlin 2.4.10 + JUnit Platform 6.1.3 (2026-08-24)
This environment lacks Gradle/Android SDK; the pure-JVM modules are compiled and
tested with the downloaded Kotlin compiler + JUnit console. Gradle (`gradlew`) configs
exist for normal machines; the rig is the proof source until then (build.md).

## 16. Offline-first: no network in the happy path (pre-planning)
Any download is a single explicit, consented, resumable operation; every socket use
must be justified in a PR (hard-facts.md).

## 15. DRM stays out-of-app, always (pre-planning, re-affirmed 2026-08-24)
The app never touches DRM: encrypted files are rejected up front with a clear message;
removal is the user's own out-of-app act. KU books excluded. This is a legal stance,
not a technical gap (hard-facts.md).

## 14. Slice order: match core + index + share receiver first (2026-08-24)
Owner's scope call: identification core before the player, so the riskiest logic was
proven first. Player resume wiring is the next slice.

## 13. Segmentation contract: passage = unit of matching + resume (C4, 2026-08-24)
Paragraph grain; long passages (>100 words) split at sentence boundaries
(abbreviation-safe); front/back-matter chapters stripped position-guarded; never strip
a whole book; kept chapters keep spine indexes. Import MUST segment before indexing.

## 12. Pure-JVM core modules + thin Android edges; import orchestration in core-ebook (2026-08-24)
All logic testable without the Android SDK (this environment proved it: 70 tests on a
standalone Kotlin compiler). BookImporter (parse→segment→index) lives in core-ebook
with a dependency on core-locate rather than a new module — a component lives in the
module of its primary responsibility; split only when a cycle forces it.

## 11. Book identity = SHA-256 of container bytes (2026-08-24)
Content-addressed: no cloud, deterministic across machines, idempotent re-import
(same file twice → "Unchanged", no re-parse); same name + changed content = distinct
book. Alternatives: UUID/file-path ids (unstable across re-imports).

## 10. License Apache-2.0, repo public (2026-08-24)
Owner chose public + Apache-2.0 (fits the open-weight/offline ethos). DRM specifics
sanitized before publishing (no tool names, no key-derivation mechanics in public docs).

Superseded by #27 (2026-08-25): the license is now GPL-3.0.

## 9. Docs split: agents.md = entry point; planning lives in docs/ (2026-08-24)
agents.md holds basic app description + pointers only (owner request). Topic docs:
hard-facts, conventions, modules, architecture, build, roadmap, decisions, features/.

## 8. Android toolchain: plain Ubuntu + cmdline-tools Docker image (2026-08-24)
vs. prebuilt SDK images (thyrlian/android-sdk etc.): exact version pins in-repo,
smaller image (one platform/build-tools/NDK), smaller trust chain (Ubuntu + Google
only). Tradeoff: a one-time ~5 min `docker build`. No emulator in Docker (KVM
flakiness) — physical device + host adb instead.

## 7. TTS model & language packs: downloadable, never bundled (2026-08-24)
All engine assets are runtime downloads — explicit, consented, resumable, cached.
The APK ships no TTS data. This was the owner's requirement to keep the APK lean and
per-language coverage flexible.

## 6. TTS primary target = Fun-CosyVoice3-0.5B-2512; Kokoro-82M demoted to fallback (2026-08-24)
Owner reviewed Kokoro as flat. Survey of open-weight engines (HF-verified): nothing
under 0.5B is genuinely expressive; Orpheus is 3B-only (desktop); KittenTTS/MeloTTS
are tiny but flat/English-only. CosyVoice3-0.5B is Apache-2.0, 9 languages + dialects,
emotion/speed/volume instruct, ONNX/GGUF ports exist. **Gated on an on-device
RTF/RAM/thermal measurement on an S22 Ultra** before it becomes default — audiobook-style
pre-generation means non-realtime synthesis is acceptable.
pt-BR is a nice-to-have: covered via Kokoro (3 pt-BR voices) and Piper packs.

## 5. OCR = tess-two (Tesseract), languages eng+spa+fra+deu+por+ita (2026-08-24)
Fully open-source (owner rejected Google ML Kit). Languages are a curated roman-alphabet
starter set; more languages ⇒ slower OCR + larger app + slightly lower accuracy.
No auto-detection — the bundled set runs.

## 4. Matcher: 4-gram recall with 3-gram fallback credit (2026-08-24)
Measured against plain n=4 (collapses under a few OCR typos) and n=3 (degraded
distinctiveness). n=4 + sub-3-gram credit keeps matches ≥0.6 under realistic noise
while cross-book text stays ≤0.05. Short snippets (<4 words) fall back to unigrams.

## 3. Match confidence threshold: default 0.6, configurable in settings (2026-08-24)
Recall semantics: "fraction of the snippet's word-groups found in a passage".
1.0 = verbatim; realistic OCR stays ≥0.6; cross-book noise measures ≤0.05; reordered
text ≈0.06 — a clean separation. 0.3 was proposed first; owner raised to 0.6.

## 2. DI = Hilt (2026-08-24)
Compile-time dependency graph with free ViewModel/Service/Compose integration.
Alternatives: minimal hand-rolled container (explicit but no cycle checks, manual
ViewModel factories; churns past ~30 objects; this app has ~35-45). Chosen before F1:
switching is cheap early, expensive later.

## 1. Package name `com.moronigranja.localttsreader` (2026-08-24)
Namespace for all modules. Confirmed by the owner; applied to core-locate before the
Android app existed so nothing needs a later rename. Alternatives: `com.localttsreader`.
