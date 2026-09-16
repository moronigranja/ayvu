package com.moronigranja.localttsreader

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.kokoro.EspeakPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.KokoroEngine
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.kokoro.NormalizingPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.PronunciationNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The EAR gate for G1's pronunciation rules: renders every case TWICE — through
 * the raw espeak-ng phonemizer (what shipped before G1) and through the
 * production [NormalizingPhonemizer] — writing the pairs as WAVs so the owner can
 * A/B them on the device. The IPA column cannot judge prosody, and G1's
 * deliberate audible effects (an expanded honorific losing its orphan
 * period-pause, a separator pause disappearing inside a number) are exactly what
 * only listening settles.
 *
 * **What it asserts, and why not "the audio differs":** an earlier version of
 * this harness asserted that each pair's PCM differed, and it passed against a
 * STALE app APK (only the test APK had been reinstalled) because fp32 ORT renders
 * are not bit-reproducible — the assertion was satisfiable by noise. So the
 * checks are now the deterministic ones:
 *
 * 1. a **rules-present guard** — the classes under test must actually contain the
 *    G1 rules (the stale-APK failure this harness was written for);
 * 2. per case, the normalized TEXT must contain the expansion this rule set
 *    promises (the real contract: what reaches the phonemizer);
 * 3. per case, the PHONEMES must differ between the two passes — deterministic,
 *    unlike PCM.
 *
 * Output: `<externalFilesDir>/g1/g1_<nn>_<lang>_<slug>-{before,after}.wav`.
 * Nothing is played here, so device volume is irrelevant.
 *
 * Usage: rebuild BOTH APKs and install BOTH, then
 *   adb shell am instrument -w -e class com.moronigranja.localttsreader.G1ListeningHarnessTest \
 *     io.github.moronigranja.ayvu.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class G1ListeningHarnessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * One listening case. [expectWords] are the expansions this rule set must
     * produce, checked against the normalized text; [expectPhoneme] is an
     * optional IPA fragment for behaviour that is deliberately NOT fixed yet.
     */
    private data class Case(
        val slug: String,
        val language: String,
        val voice: String,
        val text: String,
        val expectWords: List<String>,
        val expectAbsent: List<String> = emptyList(),
        val expectPhoneme: String? = null,
        val expectUnchangedPhonemes: Boolean = false,
    )

    private val cases =
        listOf(
            // G0 0004 — Ms. (fixed before G1) / Dr. / Prof.
            Case(
                "01-en-honorifics",
                "en-us",
                "af_heart",
                "Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon.",
                listOf("Miz", "doctor", "professor"),
            ),
            // G0 0005 — letter-spelled Sgt. plus the expanded-but-paused Mr./Mrs.
            Case(
                "02-en-letters",
                "en-us",
                "af_heart",
                "Mrs. Danvers, Mr. Bean, and Sgt. Pepper asked for directions.",
                listOf("missus", "mister", "sergeant"),
            ),
            // G0 0006 — Rev./Capt./Gen.
            Case(
                "03-en-titles",
                "en-us",
                "af_heart",
                "Rev. King, Capt. Ahab, and Gen. Lee signed the letter.",
                listOf("reverend", "captain", "general"),
            ),
            // G0 0009 — Cf./fig./vol./no.
            Case(
                "04-en-abbrev",
                "en-us",
                "af_heart",
                "Cf. appendix B: the fig. shows vol. 2, no. 3.",
                listOf("compare", "figure", "volume", "number"),
            ),
            // G0 0019 — units: ft, m, kg, L
            Case(
                "05-en-units",
                "en-us",
                "af_heart",
                "The room was 12 ft by 8 m; it weighed 2.5 kg and held 3 L.",
                listOf("feet", "meters", "kilograms", "liters", "point"),
            ),
            // G0 0057 — the decimal-period pause
            Case(
                "06-en-decimal",
                "en-us",
                "af_heart",
                "He scored 99.5 per cent and lost 8 of 9 frames in the snooker.",
                listOf("99 point 5"),
            ),
            // English reads the minus natively ("minus forty"), so no rule fires
            // here and this case must stay as-is in both passes — the control
            // that tells a listener a pair apart from an inert one.
            Case(
                "07-en-minus",
                "en-us",
                "af_heart",
                "Temperatures hit -40 degrees, then rose 15.5 degrees by 9:45.",
                listOf("point"),
                expectPhoneme = "mˈaɪnəs",
            ),
            Case(
                "08-es-honorifics",
                "es",
                "ef_dora",
                "Sra. Delgado, Dr. Vidal y Srta. Rivas llegaron al mediodía.",
                listOf("señora", "doctor", "señorita"),
            ),
            Case(
                "09-es-feminine",
                "es",
                "ef_dora",
                "El Sr. Ortega preguntó a la Prof. Salas por el Dr. García.",
                listOf("señor", "profesora", "doctor"),
            ),
            Case(
                "10-es-units",
                "es",
                "ef_dora",
                "La sala medía 12 pies por 8 m; pesaba 2,5 kg y cabían 3 l.",
                listOf("metros", "kilogramos", "litros", "coma"),
            ),
            Case("11-es-thousands", "es", "ef_dora", "El precio subió de 1.234,56 $ a 1.300 € en un mes.", listOf("1234 coma 56")),
            Case("12-fr-titles", "fr-fr", "ff_siwis", "Mme Dalloway, Dr Watson et M. Higgins arrivèrent à midi.", listOf("monsieur")),
            Case(
                "13-fr-rate",
                "fr-fr",
                "ff_siwis",
                "Roulez 5 km au nord à 90 km/h par 36 °C à l'ombre.",
                listOf("kilomètres par heure", "degrés Celsius"),
            ),
            Case(
                "14-it-titles",
                "it",
                "if_sara",
                "Il Sig. Verdi chiese alla Dott.ssa Bianchi notizie dell'Avv. Rossi.",
                listOf("signor", "dottoressa", "avvocato"),
            ),
            Case(
                "15-it-units",
                "it",
                "if_sara",
                "La stanza era 12 piedi per 8 m; pesava 2,5 kg e conteneva 3 l.",
                listOf("metri", "chilogrammi", "litri", "virgola"),
            ),
            Case(
                "16-pt-honorifics",
                "pt-br",
                "pf_dora",
                "O Sr. Oliveira perguntou à Profa. Costa pelo Dr. Barbosa.",
                listOf("senhor", "professora", "doutor"),
            ),
            Case(
                "17-pt-units",
                "pt-br",
                "pf_dora",
                "A sala tinha 12 pés por 8 m; pesava 2,5 kg e cabiam 3 l.",
                listOf("metros", "quilogramas", "litros", "vírgula"),
            ),
            // Batch 3: signs, ranges, footnote markers, decades.
            Case("18-en-hyphen", "en-us", "af_heart", "The match ended 2-1 before 76,212 fans; a 4th straight win.", listOf("2 to 1")),
            Case(
                "19-en-decades",
                "en-us",
                "af_heart",
                "The 1800s, the '90s, and 2024 C.E. were all busy.",
                listOf("eighteen hundreds", "nineties"),
            ),
            Case(
                "20-en-footnotes",
                "en-us",
                "af_heart",
                "The claim is disputed.² Other scholars disagree.³",
                emptyList(),
                expectAbsent = listOf("²", "³"),
            ),
            Case(
                "21-es-minus",
                "es",
                "ef_dora",
                "La temperatura bajó a −5 grados y subió 2,5 grados hacia las 9:45.",
                listOf("menos 5", "coma"),
            ),
            Case(
                "22-fr-minus",
                "fr-fr",
                "ff_siwis",
                "La température est tombée à −5 degrés, puis montée de 2,5 degrés.",
                listOf("moins 5", "virgule"),
            ),
            Case(
                "23-pt-minus",
                "pt-br",
                "pf_dora",
                "A temperatura caiu a −5 graus e subiu 2,5 graus por volta das 9h45.",
                listOf("menos 5", "vírgula", "9 e 45"),
            ),
            // Batch 4: roman numerals and money.
            Case(
                "24-en-roman",
                "en-us",
                "af_heart",
                "Chapter IV begins at line xlii; King Henry VIII ruled.",
                listOf("Chapter 4", "line 42", "Henry 8"),
            ),
            Case(
                "25-fr-roman",
                "fr-fr",
                "ff_siwis",
                "Le chapitre IV commence à la ligne xlii ; Louis XIV a régné.",
                listOf("chapitre 4", "Louis 14"),
            ),
            Case(
                "26-es-regnal",
                "es",
                "ef_dora",
                "Luis XIV conoció a Isabel II; el papa Pío XII lo observó.",
                listOf("Isabel segunda"),
                expectAbsent = listOf("Isabel dos"),
            ),
            Case(
                "27-pt-regnal",
                "pt-br",
                "pf_dora",
                "Luís XIV encontrou Isabel II; o papa Pio XII observava.",
                listOf("Isabel segunda"),
                expectAbsent = listOf("Isabel dois"),
            ),
            Case(
                "28-en-money",
                "en-us",
                "af_heart",
                "It cost $1,234.56, or €99.95, or £50.",
                listOf("1,234 dollars and 56 cents", "99 euros and 95 cents", "50 pounds"),
            ),
            Case(
                "29-pt-money",
                "pt-br",
                "pf_dora",
                "Gorjeta de 15% sobre R$ 45,00, e 20 centavos economizados.",
                listOf("45 reais"),
                expectAbsent = listOf("dólar"),
            ),
            // Batch 5: dates. Each expectation is the corpus row's own spelled-out
            // half, so the ear compares against the corpus's own reference reading.
            Case(
                "30-en-us-date",
                "en-us",
                "af_heart",
                "On 3/4/2024, or March 4th 2024, they met; it was the 21st of May.",
                listOf("March 4th, 2024"),
                expectAbsent = listOf("3/4/2024"),
            ),
            Case(
                "31-en-gb-date",
                "en-gb",
                "af_heart",
                "On 3/4/2024, or 4 March 2024, they met; it was the 21st of May.",
                listOf("4 March 2024"),
                expectAbsent = listOf("3/4/2024"),
            ),
            Case(
                "32-es-date",
                "es",
                "ef_dora",
                "El 3/4/2024, o el 4 de marzo de 2024, se vieron; era el 21 de mayo.",
                listOf("4 de marzo de 2024"),
                expectAbsent = listOf("3/4/2024"),
            ),
            Case(
                "33-fr-date",
                "fr-fr",
                "ff_siwis",
                "Le 3/4/2024, ou le 4 mars 2024, ils se sont vus ; c'était le 21 mai.",
                listOf("4 mars 2024"),
                expectAbsent = listOf("3/4/2024"),
            ),
            Case(
                "34-it-date",
                "it",
                "if_sara",
                "Il 3/4/2024, ovvero il 4 marzo 2024, si incontrarono; era il 21 maggio.",
                listOf("4 marzo 2024"),
                expectAbsent = listOf("3/4/2024"),
            ),
            Case(
                "35-pt-date",
                "pt-br",
                "pf_dora",
                "Em 03/04/2024, ou 4 de março de 2024, se encontraram; era 21 de maio.",
                listOf("4 de março de 2024"),
                expectAbsent = listOf("03/04/2024"),
            ),
            // The day/month pair needs a date word: a bare "3/4" is a fraction.
            Case(
                "36-ratio-guard",
                "en-us",
                "af_heart",
                "They won 2/1 and the odds were 5/2 in the 3-2 final.",
                listOf("2/1", "5/2"),
            ),
            // Batch 6: fractions. Case 36 is the guard that proves a ratio stays a
            // ratio; 37 is the owner's own example.
            Case(
                "37-en-fraction",
                "en-us",
                "af_heart",
                "Add 3/4 cup of sugar and 1/2 cup of flour.",
                listOf("three fourths of a cup", "half a cup"),
            ),
            Case(
                "38-es-fraction",
                "es",
                "ef_dora",
                "A\u00f1ade 3/4 taza de az\u00facar y 1/2 taza de harina.",
                listOf("tres cuartos de taza", "la mitad de taza"),
            ),
            Case(
                "39-fr-fraction",
                "fr-fr",
                "ff_siwis",
                "Ajoutez 3/4 tasse de sucre et 1/2 tasse de farine.",
                listOf("trois quarts de tasse", "la moiti\u00e9 de tasse"),
            ),
            Case(
                "40-it-fraction",
                "it",
                "if_sara",
                "Aggiungi 3/4 tazza di zucchero e 1/2 tazza di farina.",
                listOf("tre quarti di tazza", "la met\u00e0 di tazza"),
            ),
            Case(
                "41-pt-fraction",
                "pt-br",
                "pf_dora",
                "Adicione 3/4 x\u00edcara de a\u00e7\u00facar e 1/2 x\u00edcara de farinha.",
                listOf("tr\u00eas quartos de x\u00edcara", "a metade de x\u00edcara"),
            ),
            // Batch 7: the clause-boundary pause family, punctuation-insertion
            // mechanism (owner-approved 2026-09-15). The A/B render proves the
            // phonemes changed; the LISTEN sheet carries the measured gap.
            Case(
                "42-en-dialogue",
                "en-us",
                "af_heart",
                "\"Mind the gap,\" he said. \"Mind it... every single time.\"",
                listOf("\"Mind the gap.\""),
            ),
            Case(
                "43-en-question",
                "en-us",
                "af_heart",
                "One. Two... three? No \u2014 four. Four, four, four.",
                listOf("three? \u2014 No"),
            ),
            Case(
                "44-en-heading",
                "en-us",
                "af_heart",
                "CHAPTER ONE: An Unexpected Party",
                listOf("CHAPTER ONE. An Unexpected Party"),
            ),
            Case(
                "45-en-gb-heading",
                "en-gb",
                "af_heart",
                "CHAPTER ONE: An Unexpected Party",
                listOf("CHAPTER ONE. An Unexpected Party"),
            ),
            Case(
                "46-es-dialogue",
                "es",
                "ef_dora",
                "\u00abCuidado\u00bb, dijo \u00e9l. \u00abCuidado... siempre, siempre.\u00bb",
                listOf("\u00abCuidado.\u00bb"),
            ),
            Case(
                "47-fr-dialogue",
                "fr-fr",
                "ff_siwis",
                "\u00ab Attention \u00bb, dit-il. \u00ab Attention... toujours, toujours. \u00bb",
                listOf("\u00ab Attention. \u00bb"),
            ),
            Case(
                "48-it-dialogue",
                "it",
                "if_sara",
                "\u00abAttento\u00bb, disse lui. \u00abAttento... sempre, sempre.\u00bb",
                listOf("\u00abAttento.\u00bb"),
            ),
            Case(
                "49-pt-dialogue",
                "pt-br",
                "pf_dora",
                "\"Cuidado\", disse ele. \"Cuidado... sempre, sempre.\"",
                listOf("\"Cuidado.\""),
            ),
            Case(
                "50-es-question",
                "es",
                "ef_dora",
                "Uno. Dos... \u00bftres? No \u2014 cuatro. Cuatro, cuatro, cuatro.",
                listOf("tres? \u2014 No"),
            ),
            Case(
                "51-es-heading",
                "es",
                "ef_dora",
                "CAP\u00cdTULO UNO: Una fiesta inesperada",
                listOf("CAP\u00cdTULO UNO. Una fiesta inesperada"),
            ),
            Case(
                "52-fr-heading",
                "fr-fr",
                "ff_siwis",
                "CHAPITRE PREMIER : Une f\u00eate inattendue",
                listOf("CHAPITRE PREMIER. Une f\u00eate inattendue"),
            ),
            Case(
                "53-pt-heading",
                "pt-br",
                "pf_dora",
                "PARTE SEGUNDA \u2014 A estrada continua",
                listOf("PARTE SEGUNDA. A estrada continua"),
            ),
        )

    @Test
    fun everyRuleReachesTheTextAndThePhonemes() {
        // (1) The guard the first version lacked: prove the classes under test are
        // the G1 ones before trusting a single render. A stale APP apk (the test
        // apk alone carries no production code) fails here, loudly.
        assertTrue(
            "the running APK does not contain the G1 rules — rebuild and install the APP apk, " +
                "not just the androidTest apk",
            PronunciationNormalizer.forSpeech("Prof. Higgins", "en").contains("professor"),
        )

        val (model, voices, espeakLib, espeakData) = prerequisites()
        assertTrue("packs not staged (see build.md)", model.isFile && voices.isFile)
        val espeak = EspeakPhonemizer(libraryPath = espeakLib.absolutePath, dataPath = espeakData.absolutePath)
        val normalizing = NormalizingPhonemizer(espeak)

        // (2) The real contract: what reaches the phonemizer.
        for (case in cases) {
            val normalized = PronunciationNormalizer.forSpeech(case.text, case.language)
            for (word in case.expectWords) {
                assertTrue(
                    "${case.slug}: normalized text must contain \"$word\" — was \"$normalized\"",
                    normalized.contains(word),
                )
            }
            for (gone in case.expectAbsent) {
                assertTrue(
                    "${case.slug}: normalized text must not contain \"$gone\" — was \"$normalized\"",
                    !normalized.contains(gone),
                )
            }
        }

        // (3) Deterministic A/B on the phonemes (not the PCM: fp32 ORT is not
        // bit-reproducible, which is how the first version passed against a stale APK).
        for (case in cases) {
            val before = espeak.phonemize(case.text, case.language)
            val after = normalizing.phonemize(case.text, case.language)
            if (case.expectUnchangedPhonemes) {
                assertEquals("${case.slug}: no rule should fire here", before, after)
            } else {
                assertTrue("${case.slug}: the rules must change the phonemes", before != after)
            }
            case.expectPhoneme?.let {
                assertTrue(
                    "${case.slug}: deliberately unfixed behaviour must still be present ($it) — was \"$after\"",
                    after.contains(it),
                )
            }
        }

        // (4) The listening artifact.
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "g1")
        outDir.mkdirs()
        val after = renderAll(openEngine(espeak, normalizing = true), outDir, "after")
        val before = renderAll(openEngine(espeak, normalizing = false), outDir, "before")
        assertEquals("every case must synthesize in both passes", cases.size, after)
        assertEquals("every case must synthesize in both passes", cases.size, before)
    }

    private fun renderAll(
        engine: KokoroEngine,
        outDir: File,
        suffix: String,
    ): Int {
        var rendered = 0
        try {
            for (case in cases) {
                val outcome = runBlocking { engine.synthesize(SynthesisRequest(case.text, voice = case.voice)) }
                val audio = outcome as? SynthesisOutcome.Audio ?: continue
                if (audio.pcm.isEmpty()) continue
                writeWav(File(outDir, "g1_${case.slug}-$suffix.wav"), audio.pcm, audio.sampleRateHz)
                rendered++
            }
        } finally {
            runCatching { engine.close() }
        }
        return rendered
    }

    private fun openEngine(
        espeak: EspeakPhonemizer,
        normalizing: Boolean,
    ): KokoroEngine {
        val (model, voices, _, _) = prerequisites()
        return KokoroEngine.open(
            spec = DefaultEngines.kokoro,
            packs = KokoroPacks.all,
            modelFile = model,
            voicesFile = voices,
            // The whole point of the harness: the same engine with and without
            // the G1 rule decorator.
            phonemizer = if (normalizing) NormalizingPhonemizer(espeak) else espeak,
            sessionFactory = { it.setIntraOpNumThreads(4) },
        )
    }

    private data class Prerequisites(
        val model: File,
        val voices: File,
        val espeakLib: File,
        val espeakData: File,
    )

    private fun prerequisites(): Prerequisites {
        val cache = PackCache(context.filesDir)
        return Prerequisites(
            model = cache.targetFile(KokoroPacks.model),
            voices = cache.targetFile(KokoroPacks.voices),
            espeakLib = File(context.filesDir, "espeak/libespeak-ng.so"),
            espeakData = File(context.filesDir, "espeak/espeak-ng-data"),
        )
    }

    /** Minimal PCM16 mono WAV writer: the artifact players read. */
    private fun writeWav(
        file: File,
        pcm: ByteArray,
        sampleRateHz: Int,
    ) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val dataSize = pcm.size
        val header =
            ByteArray(44).apply {
                putAscii(0, "RIFF")
                putIntLe(4, 36 + dataSize)
                putAscii(8, "WAVE")
                putAscii(12, "fmt ")
                putIntLe(16, 16)
                putShortLe(20, 1)
                putShortLe(22, channels)
                putIntLe(24, sampleRateHz)
                putIntLe(28, byteRate)
                putShortLe(32, channels * bitsPerSample / 8)
                putShortLe(34, bitsPerSample)
                putAscii(36, "data")
                putIntLe(40, dataSize)
            }
        file.outputStream().use {
            it.write(header)
            it.write(pcm)
        }
    }

    private fun ByteArray.putAscii(
        offset: Int,
        value: String,
    ) {
        for (i in value.indices) this[offset + i] = value[i].code.toByte()
    }

    private fun ByteArray.putIntLe(
        offset: Int,
        value: Int,
    ) {
        for (i in 0..3) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(
        offset: Int,
        value: Int,
    ) {
        for (i in 0..1) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
}
