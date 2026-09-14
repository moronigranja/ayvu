# G0 — Narration-quality listening findings (decisions: roadmap G0)

Status: **in progress — listening pass active (2026-09-13); first confirmed
findings recorded below.** The corpus (377 entries, 9 languages × 15 categories),
the device WAVs, the IPA column, and the device measurement are complete;
mispronunciation classification for the five Roman languages + en-GB is being done
by the owner's native ear. ja/zh(cmn)/hi are limited
to "synthesizes finite, no crash, no obviously wrong length" plus the IPA
spot-checks below — mispronunciation classification for those three is
recorded as a limitation, not attempted by a non-native ear.

Artifacts:
- Corpus source: `tools/g0-corpus.tsv` (lang ␥ category ␥ text)
- Device corpus: `core-tts/g0_corpus.tsv` (id ␥ lang ␥ category ␥ text ␥ phonemes),
  produced by `./gradlew :core-tts:g0Corpus` through the production
  `NormalizingPhonemizer → EspeakPhonemizer` stack
- Device results: `build/g0-device/` — `g0_results.json` + one
  `g0_<id>_<lang>_<category>.wav` per entry (S22, `G0CorpusBenchmarkTest`,
  Kokoro fp32, first female voice per family)

## Method (how to finish this doc)

Listen to each WAV and cross-check the `phonemes` column. For every heard/read
mispronunciation, add a typed class in the template below. Every class that is
a normalization fix gets an explicit G1 rule (ordered literal regex +
replacement), so the G1 `PronunciationNormalizer` extension consumes this doc
directly. Engine-level defects (prosody, voice quality) get "none — engine
  level, not a normalization fix".

Template:

```
### <class-kebab-name>
- Category: <category token>
- Example: "<corpus text>"
- Produced (IPA/approx): "<phonemes or phonetic rendering>"
- Expected: "<correct spoken form>"
- Frequency: <count>/<corpus size>
- G1 rule: literal-ordered replacement (regex + replacement string) OR "none —
  engine level, not a normalization fix"
```

## Confirmed findings

### ms-honorific-letter-by-letter
- Category: `honorific`
- Example: "Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon."
- Produced (raw espeak, pre-normalizer): `ˌɛmˈɛs. dˈæləwˌeɪ …` ("M S" letter
  names) — the known espeak-ng 1.52.0 regression family.
- Expected: `/mɪz/`
- Status: **already fixed.** The shipped `PronunciationNormalizer` rule
  (`Ms. → Miz`, bounded by non-alphanumerics) renders `mˈɪz` in the corpus
  row `0004` — verified in `g0_corpus.tsv`. The corpus exercises the
  regression; the fix holds.
- Frequency: 2/377 (`Ms.` appears in the en-US and en-GB `honorific` lines)
- G1 rule: none (shipped rule confirmed by this corpus).

### abbrev-not-expanded (truncated or letter-by-letter instead of the full word)
- Category: `honorific`, `abbreviation`
- Example (owner-confirmed, en-us 0005/0006): "…and Sgt. Pepper…" → `Sgt.` rendered
  `ˌɛsdʒˌiːtˈiː` ("S G T", letter-by-letter); "Rev. King, Capt. Ahab, and Gen. Lee"
  → `Rev.` `ɹˈɛv` ("rev"), `Capt.` `kˈæpt` ("capt"), `Gen.` `dʒˈɛn` ("gen").
  Expected: sergeant / reverend / captain / general.
- Scope (phoneme-verified across the Latin-script corpus, same defect): en-us
  `Prof.`→"prof" (0004); en-gb `Rev.`→"rev" (0050); es `Prof.`→"prof" (0095);
  fr-fr `M.`→"em" (spelled, 0135/0136), `Prof.`→"prof" (0136); it `Sig.`→"sig",
  `Sig.ra`→"sig.ra" (spelled), `Dott.`→"dott", `Dott.ssa`→"dot.ssa" (spelled),
  `Avv.`→"avv", `Prof.`→"prof" (0176/0177); pt-br `Sra.`→"sra" (spelled, 0217),
  `Sr.`→"s r e x e" (spelled, 0218), `Profa.`→"profa", `Prof.`→"prof" (0217/0218).
  General abbreviations too: en `approx.`→"approx", `fig.`→"fig", `vol.`→"vol"
  (0008/0009); es `pág.`→"pag" (0096); it `pag.`→"pag", `ecc.`→"ecc" (0178); pt-br
  `pág.`→"pag", `aprox.`→"aprox" (0219/0220). Expected: approximately, figure,
  volume, página, eccetera, etc.
- Frequency: the dominant G0 class — every honorific/abbreviation row in en-us,
  en-gb, it and pt-br shows it; es and fr partially.
- G1 rule: an ordered literal replacement dictionary (abbreviation + period → full
  word), word-boundary bounded — the `Ms.→Miz` rule is the shape. The dictionary
  MUST consume the trailing period (see the next class). This class bounds G1's
  built-in set substantially.
- Status: **owner-confirmed (2026-09-13)** — "abbreviations should be read fully."

### abbrev-period-pause (trailing period of an expanded abbreviation kept as a pause)
- Category: `honorific`, `name`
- Example (owner-confirmed, en-us 0001): "Mr. João da Silva" — espeak expands
  `Mr.`→"mister" (`mˈɪstɚ.`) but retains the abbreviation's period as a pause
  boundary, so the voice pauses too long after "mister". Same after `Mrs.`/`Mr.`
  (0005) — each expanded honorific is followed by a full-stop pause.
- Expected: no pause after the spoken honorific beyond the normal word gap.
- Frequency: every Latin-script honorific/name row where espeak expands the
  abbreviation (Mr, Mrs, Dr, Sra, Sr, Dokt…) — the period survives espeak's own
  expansion (`mˈɪstɚ.`, `mˈɪsɪz.`, `dˈɑːktɚ.`, …).
- G1 rule: the abbreviation dictionary consumes the trailing period (as `Ms.→Miz`
  already does), so no orphan period reaches phonemization. Same mechanism as the
  class above — one dictionary lands both fixes.
- Status: **owner-confirmed (2026-09-13).**

### date-slash-read-aloud (slash-dates spoken as numbers, slash verbalized)
- Category: `date`
- Example (owner-confirmed): "On 3/4/2024 …" (en-us 0013) renders
  "three **slash** four **slash** two thousand twenty-four"
  (`θɹˈiː slˈæʃ fˈɔːɹ slˈæʃ tˈuː θˈaʊzənd twˈɛnti fˈɔːɹ`); `06/07/2000` → "zero six
  **slash** zero seven **slash** two thousand" (0014). en-gb identical (0058);
  es reads the slash as "barra" (`βˈara` 0101, `βˈara ðˈoθe` for 25/12 0103); it
  as "barra" (`bˈarɾa` 0183–0185).
- Scope note: the verbalized slash is only half the defect — the numeric slash-date
  is never normalized to a spoken date at all. fr-fr drops the slash but reads the
  digits as bare cardinals ("trois quatre deux mille vingt-quatre" 0142,
  "vingt-et-un cinq" for 21/5 0143); pt-br reads them digit-by-digit zero-padded
  ("zero three zero four …" 0224, "twenty-five twelve" for 25/12 0226). Every
  variant loses the month/day meaning.
- Expected: a spoken date per language — en-us "March fourth, twenty twenty-four";
  es "el tres de abril de 2024"; it "il tre aprile 2024"; fr "le trois avril 2024";
  pt "três de abril de 2024".
- Ambiguity to decide (owner call): a bare `3/4/2024` in a mixed corpus is March 4
  (US) vs April 3 (EU day-first); the corpus pairs each slash-date with its spoken
  form so the intended order is recoverable per language, but the normalizer needs a
  per-locale convention, not a guess.
- Frequency: every slash-date row (en-us/en-gb 0013/0014/0058, es 0101/0103, it
  0183–0185, pt-br 0224/0226; fr drops the slash but still mis-reads).
- G1 rule: a language-aware numeric-date expansion (`D/D/YYYY`, `MM/DD`, `DD/MM` →
  spoken month/day/year) before phonemization — removing the slash alone still reads
  "three four" as cardinals.
- Status: **owner-confirmed (2026-09-13)** — "dates are reading slashes."

## Candidate classes (host IPA reading only — confirm by ear before G1)

These are IPA-column observations, not listening verdicts; each needs owner
confirmation before a G1 rule lands.

### year-decade-read-digit-by-digit
- Category: `date`
- Example: "The 1800s, the '90s, and 2024 C.E. all appear in the timeline."
- Produced (en-US row 0015 IPA): `ðə wˈʌn θˈaʊzənd ˈeɪthˈʌndɹɪd z` — "the one
  thousand eight hundred s"
- Expected (idiomatic): "the eighteen hundreds"
- Frequency: TBD
- G1 rule: TBD (candidate: `18xx`-range year + trailing `s` → "eighteen <rest>
  hundreds"; needs the full class enumerated by ear first).

## Non-findings worth recording

- `https://example.com/path?q=1&x=2` → espeak reads it as
  `ˌeɪtʃtˌiːtˈiːpˌiːˈɛs:slˈæʃslæʃ …` ("H T T P S colon slash slash …") —
  verbose but letter-accurate; whether to compact is an owner call, not a
  mispronunciation.
- `&&` → `ˈændænd` ("and and"); `e.g.` → `ˈiː.dʒˈiː.`; `i.e.` → `ˈaɪ.ˈiː.` —
  plausible renderings, confirm by ear.

## ja / cmn / hi spot-checks (IPA only, non-native limitation)

- ja `number` row 0241 ("人口は1,204,567人…"): espeak-ja emits
  `(en)tʃˈaɪniːz(ja)lˈe̞tə` — the literal words "Chinese letter" — for every kanji
  it cannot map. This is the espeak-ng ja voice's own dictionary gap, upstream of
  the engine; not a normalization fix.
- cmn `currency` row 0340 ("它花了1,234.56元…"): digits and symbols render as
  plausible Mandarin syllables with correct tone digits (`lˈə1 jˈi5 ˌər5pai2
  sˈa5ns.i.ɜsi̪5 tˈiɛɜn` ≈ 一千二百三十四点…); currency names carried through.
- hi `honorific` row 0313 ("श्रीमती दल्लो…"): `ʃɾˈiːmtˌi dˈʌlloː` — plausible
  Devanagari readings with retroflexes; needs a native ear for a verdict.

All three languages synthesized finite audio on device (no `error` rows in
`g0_results.json`); lengths are proportionate to text length. Per plan, their
mispronunciation classification is a recorded limitation, not attempted here.
