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
  en-gb, it and pt-br shows it; es and fr partially. Plus (resolved 2026-09-13)
  `e.g.`→"for example" and `i.e.`→"that is" — the natural spoken expansions, for a
  more natural feel.
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

### measurement-unit-not-expanded (unit abbreviations spelled or unit omitted)
- Category: `measurement`
- Example (owner-confirmed): "…weighed 2.5 kg and held 3 L" (en-us 0019) → "two
  point five **K G**" (`kˌeɪdʒˈiː`) and "three **L**"; "The room was 12 ft" → "twelve
  **F T**" (`ˌɛftˈiː`). Expected: kilograms / liters / feet.
- Scope (phoneme-verified): en-us/en-gb `kg`→"K G", `ft`→"F T", `lb`→"L B",
  `oz`→"O Z", `m`→"M", `L`→"L", `mi`→"mi", `st`→"st", `mph`→"M P H", `W`→"W",
  `°F`→"degrees F", `°C`→"degrees C" (0019–0021, 0064–0066); it `kg`→"K P G",
  `km`→"ka p emme", `°C`→"C" (0189–0191); pt-br `kg`→"ka je", `km`→"ka eme",
  `°C`→"C" (0230–0232). **Scope extended (owner-confirmed 2026-09-15, es 0107):** the
  earlier "espeak-es and espeak-fr expand most units correctly — the defect is en/it/pt"
  note was too generous. espeak-es expands `kg` properly ("dos coma cinco
  **kilogramo**") but spells single-letter metric symbols and imperial abbreviations as
  letters: `8 m` → "ocho **eme**", `3 l` → "tres **ele**" (0107, owner-confirmed);
  `14 st 2 lb` → "catorce **ese-te** dos **ele-be**", `6 mph` →
  "seis **eme-pe-hache**" (0109, owner-confirmed 2026-09-15 — "units of measurement
  should be spelled out"). fr the
  same: `8 m` → "huit **emme**", `3 l` → "trois **elle**" (0148/0150). So es and fr fail
  on single-letter metric symbols and on imperial abbreviations, and succeed only on
  multi-letter metric ones (`kg`, `km`). The conclusion stands — the G1 dictionary must
  be **language-scoped, not global** — but it now needs per-language entries across all
  six Latin-script languages, not just en/it/pt.
- Also (extends the slash finding): `km/h` → spelled units plus the slash verbalized
  ("ka eme barra hache" es 0108, "ka eme barra acca" it 0190, "ka eme aga" pt 0231)
  — the slash-normalization fix from date-slash-read-aloud must be shared with
  unit-rate notation.
- Expected: feet, meters, kilograms, liters, miles, pounds, ounces, miles per hour,
  stone, watts, degrees Celsius/Fahrenheit (per language).
- Frequency: every measurement row in en-us/en-gb/it/pt-br (0019–0021, 0064–0066,
  0189–0191, 0230–0232); es/fr partial.
- G1 rule: an ordered unit dictionary (symbol → full unit word), language-scoped,
  sharing the abbrev-not-expanded mechanism; rate forms (`km/h`, `mph`) need
  slash-aware expansion to "per hour"/per-language equivalent.
- Status: **owner-confirmed (2026-09-13)** — "measurements are reading k g instead
  of kilograms, f t instead of feet." Reconfirmed for es on 2026-09-15 with the rule
  stated plainly: **"units of measurement should be spelled out."**

### dialogue-quote-attribution-pause (no pause between closing quote and attribution)
- Category: `dialogue`
- Example (owner-confirmed): `"Mind the gap," he said.` (en-us 0026) — the closing
  quote + comma runs straight into "he said" with no distinct pause; expected
  `"Mind the gap"` [pause] `he said.` Same across the guillemet forms
  (`«Cuidado», dijo él.` 0114, `«Attento», disse lui.` 0196, `"Cuidado", disse ele.`
  0237).
- Expected: a natural pause at the closing-quote / narration-tag boundary.
- Frequency: every quote-first dialogue row with a following attribution
  (en-us/en-gb 0026/0071, es 0114, fr-fr 0155, it 0196, pt-br 0237).
- G1 rule: a pause rule at the closing-quote + attribution boundary — the comma is
  present but reads too short; the pause-family (parenthetical-pause rules already
  in G1's candidate set) needs a quote-attribution boundary that inserts or
  strengthens the pause. Must handle both curly-quote and guillemet forms.
- Status: **owner-confirmed (2026-09-13)** — "there should be a pause on dialogue
  after closing quotation marks."

### decade-trailing-s-read-literally (decades read with "hundred" + dangling s)
- Category: `date`
- Example (owner-confirmed): "The 1800s, the '90s, and 2024 C.E. …" (en-us 0015)
  → "the one **thousand eight hundred s**" (`ðə wˈʌn θˈaʊzənd ˈeɪthˈʌndɹɪd z`)
  — should be "the eighteen hundreds"; "the '90s" → "the **ninety s**"
  (`nˈaɪnti z`) — should be "the nineties"; "the 1910s" (en-gb 0060) → "the
  **nineteen hundred and ten s**" — should be "the nineteen-tens".
- Expected (idiomatic): the named decade — "the eighteen hundreds", "the
  nineteen-tens", "the nineties".
- Frequency: 3/377 (0015 ×2, 0060) — low count in the corpus, but the class is
  generic: every `YYYYs` / `'YYs` decade with a trailing s.
- G1 rule: `YYYYs` / `'YYs` decade → named-decade form ("eighteen hundreds" /
  "nineteen-tens" / "nineties"); the trailing-`s` century form is the
  `filter_page_numbers`-adjacent shape.
- Status: **owner-confirmed (2026-09-13)** — "1800s is reading as one thousand
  eight hundreds, not eighteen hundreds."

### roman-numeral-read-with-label (roman numerals read with a literal "roman" prefix)
- Category: `roman-numeral`
- Example (owner-confirmed): "King Henry VIII ruled" (en-us 0022) → "King Henry
  **roman eight**" (`hˈɛnɹi ɹˌoʊmən ˈeɪt`) — should be "Henry the Eighth" / "Henry
  eight"; "Chapter IV" → "chapter **roman four**" (`ɹˌoʊmən fˈɔːɹ`). Same across
  en-us/en-gb (0022–0024, 0067–0069): every numeral `IV`, `VIII`, `II`, `XII`,
  `XIV`, `xlii`, `iii`, `XIX` gets the "roman" label.
- Scope: espeak-en prepends "roman" to every roman numeral; espeak-fr does the
  same with "romain" (`quatre **romain**` 0151, `deux **romain**` 0153). espeak-es
  and espeak-it/pt read them as plain cardinals/ordinals without a label (`cuatro`,
  `quarto`, `dodicesimo`, `doze`) — so the G1 fix is en/fr-scoped.
- Expected: the plain number (cardinal for chapters/sections, ordinal for monarchs:
  "the Eighth", "the Fourteenth").
- Frequency: every en-us/en-gb/fr-fr roman-numeral row (0022–0024, 0067–0069,
  0151–0153).
- G1 rule: a roman-numeral → plain-number literal mapping (`IV`→"four", `VIII`→
  "eight", `XIX`→"nineteen", …) applied before phonemization so espeak never sees
  the glyph it labels "roman"; ordinal form for monarch ordinals is a refinement
  (context rule), the cardinal form is the safe default.
- Status: **owner-confirmed (2026-09-13)** — "roman numerals reading as 'roman
  eight' instead of just eight."

### footnote-reference-marker-read-aloud (superscript footnote markers read as numbers)
- Category: `footnote`
- Example (owner-confirmed): "The claim is disputed.² Other scholars disagree.³"
  (en-us 0032) → "The claim is disputed **two** Other scholars disagree **three**"
  (`…dɪspjˈuːɾᵻd.tˈuː …dˌɪsɐɡɹˈiː.θɹˈiː`); "See note 1 for details.¹" (0031) →
  "…for details **one**". The superscript reference markers are read as cardinal
  numbers at sentence end — they should be silent page furniture.
- Scope (phoneme-verified, all six Latin-script languages): es `detˈaʎes.ˈuno`,
  `.dˈos`/`.tɾˈes` (0119/0120); fr `.detˈaj.ˈœ̃` (0160); it `.detːˈaʎɪ.ˈuno`,
  `.dˈue`/`.trˈe` (0201/0202); pt `.dˌetˈaljys.ˈũŋ`, `.dˈoɪz`/`.trˈes`
  (0242/0243). The dagger forms are already silent (`Table 2†` reads "Table two",
  no marker, 0033) — only the numeric superscripts leak.
- Expected: markers dropped entirely (like page numbers).
- Frequency: every footnote row with a numeric superscript marker (0031/0032,
  0076/0077, 0119/0120, 0160/0161, 0201/0202, 0242/0243).
- G1 rule: strip footnote reference markers (¹ ² ³ … superscript digits, and the
  bracketed `[3]` form) before phonemization — the `filter_page_numbers`-family
  drop; dagger/cross forms are already silent and need no rule.
- Status: **owner-confirmed (2026-09-13)** — "the three at the end …
  likely shouldn't be read aloud."

### question-boundary-pause-short (insufficient pause after "?" before the next utterance)
- Category: `speed-transition`
- Example (owner-confirmed): "One. Two... three? No — four." (en-us 0044) — the
  pause between "three?" and "No" is weirdly short / nearly absent; expected a
  clear pause at the question boundary. The same rapid-interjection pattern
  repeats across the Latin-script speed-transition rows (0089 en-gb, 0131 es
  "¿tres? No", 0172 fr, 0213 it, 0254 pt).
- Expected: a natural pause after the question mark before the next clause.
- Frequency: every Latin-script speed-transition row (0044/0089/0131/0172/0213/0254).
- G1 rule: pause-family — likely the SAME boundary-pause mechanism as
  dialogue-quote-attribution-pause, extended to the `?` → next-clause boundary;
  one shared clause-boundary pause rule rather than two.
- Status: **owner-confirmed (2026-09-13)** — "weird lack of pause between three
  and no."

### negative-sign-english-injection (minus read with an English "minus" or dropped)
- Category: `number`
- Example (owner-confirmed): "La temperatura bajó a −5 grados…" (es 0099) → "a
  **minus** cinco grados" — the minus sign renders as an English-accented "minus"
  (`(en)mˈaɪnəs(es)`) injected into the Spanish text; expected "menos cinco". fr 0140
  has the same English "minus" in French (expected "moins cinq"); pt 0222 *drops*
  the minus entirely ("caiu a cinco graus", expected "menos cinco"). it 0181 reads
  correctly ("meno cinque").
- Scope: the minus sign (U+2212 − and ASCII `-`) is read as an English loanword in
  es/fr and dropped in pt; only it handles it natively. en-us 0011 is correct
  ("minus forty").
- Frequency: every es/fr/pt negative-number row (0099, 0140, 0222).
- G1 rule: normalize the minus sign to the per-language negative marker
  (menos / moins / pt must keep "menos") before phonemization.
- Status: **owner-confirmed (2026-09-13)** — "weird minus."

### currency-amount-misread (English currency name before the number + decimal not cents)
- Category: `currency`
- Example (owner-confirmed): "Prices rose from ¥10,000…" (en-us 0017) → "**yen ten
  thousand**" instead of "ten thousand yen"; "₹750" → "**rupee seven hundred
  fifty**" instead of "seven hundred fifty rupees". And "$1,234.56" (0016) →
  "**dollar** one thousand… **point five six**" — the currency name precedes the
  number and the decimal reads as "point five six" instead of "one thousand two
  hundred thirty-four dollars and fifty-six cents".
- Scope: every English currency row reads the symbol/name first (dollar/pound/
  euro/yen/rupee before the number) and the cents as "point N N". The name-first
  order mirrors the symbol's text position (`¥10,000`, `$1,234.56`) but is not the
  natural English spoken form.
- Expected: "<amount> <currency> and <cents> cents" — "ten thousand yen", "one
  thousand two hundred thirty-four dollars **and fifty-six cents**" (owner settled
  the cents wording to the "and" form, 2026-09-13).
- Frequency: all en-us/en-gb currency rows (0016–0018, 0061–0063).
- G1 rule: a currency-amount normalizer that reorders name-after-number and renders
  the decimal as "<cents> cents" (with the "and" connector) for English.
- Status: **owner-confirmed (2026-09-13)** — "reading yen ten thousand instead of
  ten thousand yen."

> Withdrawn the same day: `decimal-comma-read-aloud` (es 0098 "3,14159" →
> "tres coma catorce mil…") — the owner decided the spoken decimal comma and the
> fractional reading are **fine** ("decimal comma is fine, nevermind"), so no rule.
> The Spanish "coma" convention stands as-is.

### decimal-comma-pause (pause inserted at the decimal comma inside a number)
- Category: `number`
- Example (owner-confirmed): "…subiu 2,5 graus…" (pt-br 0222) → "dois virgula
  [pause] cinco graus" — the decimal comma is treated as a clause-boundary comma,
  inserting a pause inside the number; a decimal separator must be spoken without a
  gap ("dois virgula cinco").
- Scope: likely every language using the comma as the decimal separator (pt/es/fr/it);
  the owner confirmed pt-br. Distinct from the withdrawn decimal-comma reading
  finding — the spoken "virgula"/"coma" word is fine; only the pause is the defect.
- Frequency: every comma-decimal row (0222; currency 0104/0106/0227/0229; fr/it/es
  number rows 0139/0180/0098).
- G1 rule: when a comma is a decimal separator (`digit , digit`), rewrite it to a
  non-punctuation separator so espeak adds no clause pause — keep the spoken
  "virgula"/"coma" word, remove the pause. Opposite direction from the
  boundary-pause family (those strengthen clause pauses; this removes a
  number-internal one).
- Status: **owner-confirmed (2026-09-13)** — "pause after the comma in 'dois
  virgula cinco graus'."

### heading-title-colon-pause (pause at the heading colon too short before the title)
- Category: `heading`
- Example (owner-confirmed): "CHAPTER ONE: An Unexpected Party" (en-us 0028) → the
  colon break between "chapter one" and "an unexpected party" reads too short;
  expected a clear pause ("chapter one [pause] an unexpected party"). Same at the
  em-dash forms ("PART TWO — The Road Goes Ever On" 0029) and the appendix colon
  ("Appendix A: Pronunciation Guide" 0030), across all six Latin-script heading
  rows (0073–0075, 0116–0118, 0157–0159, 0198–0200, 0239–0241).
- Expected: a clear pause at the heading's number → title boundary (colon or
  em-dash).
- Frequency: every heading row (Latin-script 0028–0030, 0073–0075, 0116–0118,
  0157–0159, 0198–0200, 0239–0241).
- G1 rule: boundary-pause family — the SAME clause-boundary pause rule as
  dialogue-quote-attribution-pause / question-boundary-pause-short, applied to the
  heading colon/em-dash. One shared mechanism.
- Status: **owner-confirmed (2026-09-13)** — "a pause after the heading, before the
  title would be better."

### roman-numeral-regnal-not-ordinal (regnal numerals spoken as cardinals)
- Category: `roman-numeral`
- Example (owner-confirmed, pt-br 0235): "Luís XIV encontrou Isabel II; o papa Pio XII
  observava." → "Isabel **dois**" (`ˌizabˈɛʊ dˈoɪs`), "Pio **doze**" (`pˈiʊ dˈozy`).
  Expected: the ordinal — "Isabel **segunda**", i.e. the numeral on a person's name is a
  regnal number, not a count.
- Scope: **es and pt, one rule** (owner, 2026-09-15): identical behaviour in both.
  `it` needs **no** rule — espeak-it ordinalizes throughout and that is grammatical
  Italian ("Pio **dodicesimo**", "Elisabetta **seconda**", "Luigi XIV" →
  *quattordicesimo*), which is its own convention, not a defect. `fr` is subsumed by the
  confirmed `roman-numeral-read-with-label`.
- **Settled (owner, 2026-09-15): ordinals from I to X, cardinals from XI onward — the
  same for Portuguese and Spanish.** So the corpus defect is narrower than the class name
  suggests: today's renderings are wrong only where the numeral is I–X (0233 "D. Pedro
  **dois**" → *segundo*, 0235 "Isabel **dois**" → *segunda*, es 0112 "Isabel **dos**" →
  *segunda*, es 0110 "Felipe VI"), while XI+ already reads as the cardinal the rule wants
  ("Pio **doze**", "Luís **catorze**").
- G1 rule: **context-sensitive — the first finding that is not a literal dictionary.**
  `(<Capitalized name>)\s+(I|II|…|X)\b` → the ORDINAL from a per-language table; XI and
  above are left alone (they already read as cardinals). With a negative guard for
  structural contexts (`chapter`/`part`/`section`/`appendix`/`table`/`figure`/`book`/
  `volume`/`act`/`scene`) so "Chapter IV" stays "capítulo quatro". Two implementation
  details the rule must carry, neither expressible as a bare literal replacement:
  (a) a **roman→value** table, since the threshold needs the numeral's value; (b)
  **grammatical gender** — the owner's example is feminine ("Isabel segunda") while a
  king takes the masculine ("Pedro segundo"), so the table needs both forms and the rule
  needs the name's gender (a small regnal-name lexicon, or masculine as the default when
  unknown). Bounded pattern, still testable — but it is the case the doc's "an unbounded
  rule can silently rewrite a whole book" warning is about, so it lands with its negative
  tests.
- Frequency: 4/377 rows carry an I–X regnal numeral and read it wrongly (pt 0233/0235,
  es 0110/0112). es 0111 and it 0192–0194 read correctly and need no rule.
- Status: **owner-confirmed (2026-09-15)** — "some roman numerals should read as ordinal:
  'Isabel segunda', not 'Isabel dois'", with the boundary settled the same day:
  **"ordinals from I to X, cardinals from XI onward" — identical for pt and es.**

### hyphen-read-as-dash (numeric range/score hyphen spoken as "dash")
- Category: `number`, `page-furniture`
- Example (owner-confirmed, en-gb 0056): "The match ended 2-1 before 76,212 fans" → "two
  **dash** one" (`tˈuː dˈaʃ wˈɒn`). Expected: "two **to** one" (owner wording).
- Scope (phoneme-verified): 0035 en-us and 0080 en-gb — the `page-furniture` rows'
  "lines 8-19" → "eight **dash** nineteen" (`ˈeɪt dˈæʃ nˈaɪntiːn` / `ˈeɪt dˈaʃ nˈaɪntiːn`).
  **This lands inside a category the doc had recorded as verified fine** — the
  page-furniture verdict covered pagination and boundaries, not numeric ranges, so that
  verdict is now qualified rather than general (see the non-findings section).
- Expected: a numeric hyphen is a range/score, not punctuation — "to" (en). Other
  languages need their own call; only the English rows show it here.
- Frequency: 3/377 (0056, 0035, 0080) — every `digit-digit` hyphen in the corpus.
- G1 rule: `(?<=\d)\s?-\s?(?=\d)` → `" to "` (language-scoped word), before
  phonemization. Note this is the *opposite* direction from the separator family: it
  ADDS a word where the family REMOVES a punctuation role. Bounded and literal.
- Status: **owner-confirmed (2026-09-15)** — "I would expect 'two to one'."

### decimal-period-pause (a decimal point spoken as a clause break)
- Category: `number`, `currency`
- Example (owner-confirmed): "…and 0.5% is half." (0010 en-us; 0055 en-gb identical) →
  "zero **[break]** five per cent" (`zˈiəɹoʊ.  fˈaɪv pɚsˈɛnt` — a clause break where a
  decimal separator belongs), and "He scored 99.5 per cent…" (0057 en-gb) →
  "ninety-nine **[break]** five per cent" (`nˈaɪnti nˈaɪn.  fˈaɪv`).
  Owner report cited id `0012`; the corpus rows carrying a period-decimal are 0010/0055
  (0.5%) and 0057 (99.5) — 0012's IPA has no break and its text has no decimal (it is the
  `007` row). Recorded against the rows that carry the evidence.
- Counter-examples in the SAME corpus (why this is not "espeak can't read decimals"):
  `3.14159` (0010/0055) → "three **point** one four one five nine" ✓ and `£12.50` (0062)
  → "twelve **point** five zero" ✓. So espeak does emit "point" for some fraction shapes;
  the defect is the dot's punctuation role surviving for others (single-digit and
  two-digit fractions, `.00`). That shape-dependence is exactly why the fix must not be
  "add the word point" but "make `digit.digit` non-punctuating".
- Frequency: 4/377 rows observed so far (0010, 0055, 0057; 0018's `$45.00` →
  "forty-five **[break]** zero zero" is the same defect); 37 corpus rows carry a
  period-decimal, so the class is *not* universal — the remaining rows should be heard
  when the pass reaches them.
- G1 rule: one shared **separator** mechanism with the confirmed pt-br
  `decimal-comma-pause`: rewrite the separator so it carries no punctuation role, keeping
  the spoken word ("point" / "vírgula" / "coma"). Language-aware, and it must cover the
  thousands separator too (es 0106 / it 0188 read `1.234,56` as "uno. **[break]**
  doscientos treinta y cuatro coma…" — a full stop inside the number).
- Status: **owner-confirmed (2026-09-15)** — "0.5 percent has an unnecessary pause after
  the dot."

## Candidate classes (host IPA reading only — confirm by ear before G1)

These are IPA-column observations, not listening verdicts; each needs owner
confirmation before a G1 rule lands.

## Non-findings worth recording

- `https://example.com/path?q=1&x=2` → espeak reads it as
  `ˌeɪtʃtˌiːtˈiːpˌiːˈɛs:slˈæʃslæʃ …` ("H T T P S colon slash slash …") —
  **owner-accepted as-is (2026-09-13)**: no compaction rule. The URL reading
  stands as-is.
- `page-furniture` — **owner-verified for pagination and boundaries (2026-09-13),
  qualified 2026-09-15**: "Page 42 of 320 — continued from previous — end of chapter"
  and the `Page 89 — blank…` rows read correctly, and the em-dash boundaries carry
  proper pauses — but the same rows' **numeric ranges are not fine**: `lines 8-19`
  renders "eight **dash** nineteen" (0035 en-us, 0080 en-gb), now recorded as the
  confirmed `hyphen-read-as-dash` class. The verdict covers pagination, not numbers.
- `long-paragraph` — **owner-verified fine (2026-09-13)**: no mispronunciations;
  pacing/prosody is engine-level, not a normalization fix.
- `&&` → `ˈændænd` ("and and") — **owner-accepted as-is (2026-09-13)**: no rule.
- `e.g.` → `ˈiː.dʒˈiː.` and `i.e.` → `ˈaɪ.ˈiː.` — **resolved (2026-09-13)**: expand
  to the natural spoken forms, "for example" / "that is" (owner: "for a more
  natural feel"). Folds into the `abbrev-not-expanded` G1 dictionary. No remaining
  open owner-calls.

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
