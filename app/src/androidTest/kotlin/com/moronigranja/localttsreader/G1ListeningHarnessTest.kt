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
            // The known gap, kept visible: batch 3 owns the minus sign, so this
            // case must still say the English "minus" while the decimal is fixed.
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
