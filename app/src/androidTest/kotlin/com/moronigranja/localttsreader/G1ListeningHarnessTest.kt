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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The EAR gate for G1's pronunciation rules: renders every case TWICE — once
 * through the pre-G1 phonemizer (raw espeak-ng, what shipped before) and once
 * through the production pipeline ([NormalizingPhonemizer]) — and writes the
 * pairs as WAVs, so the owner can A/B them on the device.
 *
 * The IPA column cannot judge prosody, and G1's purpose is audible: an expanded
 * honorific must lose the orphan period-pause, and the separator rules must
 * remove a pause *inside* a number. Those are exactly the things only listening
 * settles, so this runs on the S22 rather than in the unit suite.
 *
 * Output: `<externalFilesDir>/g1/g1_<nn>_<lang>_<slug>-{before,after}.wav`
 * (alphabetical order interleaves each pair). Nothing is played here — the
 * device volume is irrelevant; the WAVs are the artifact.
 *
 * Usage (packs + espeak bundle staged, per build.md):
 *   adb shell am instrument -w \
 *     -e class com.moronigranja.localttsreader.G1ListeningHarnessTest \
 *     io.github.moronigranja.ayvu.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class G1ListeningHarnessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** One listening case: the corpus row (text + language) and its voice. */
    private data class Case(
        val slug: String,
        val language: String,
        val voice: String,
        val text: String,
    )

    private val cases =
        listOf(
            // G0 0004 — Ms. (already fixed pre-G1) / Dr. / Prof.
            Case("01-en-honorifics", "en-us", "af_heart", "Ms. Dalloway, Dr. Watson, and Prof. Higgins arrived at noon."),
            // G0 0005 — letter-spelled Sgt. plus the expanded-but-paused Mr./Mrs.
            Case("02-en-letters", "en-us", "af_heart", "Mrs. Danvers, Mr. Bean, and Sgt. Pepper asked for directions."),
            // G0 0006 — Rev./Capt./Gen.
            Case("03-en-titles", "en-us", "af_heart", "Rev. King, Capt. Ahab, and Gen. Lee signed the letter."),
            // G0 0009 — Cf./fig./vol./no.
            Case("04-en-abbrev", "en-us", "af_heart", "Cf. appendix B: the fig. shows vol. 2, no. 3."),
            // G0 0019 — units: ft, m, kg, L
            Case("05-en-units", "en-us", "af_heart", "The room was 12 ft by 8 m; it weighed 2.5 kg and held 3 L."),
            // G0 0057 — the decimal-period pause
            Case("06-en-decimal", "en-us", "af_heart", "He scored 99.5 per cent and lost 8 of 9 frames in the snooker."),
            // G0 0011 — minus sign still pre-G1 (batch 3): included as a control
            Case("07-en-minus", "en-us", "af_heart", "Temperatures hit -40 degrees, then rose 15.5 degrees by 9:45."),
            // G0 0094 — Sra./Dr./Srta.
            Case("08-es-honorifics", "es", "ef_dora", "Sra. Delgado, Dr. Vidal y Srta. Rivas llegaron al mediodía."),
            // G0 0095 — the feminine article case (la Prof.)
            Case("09-es-feminine", "es", "ef_dora", "El Sr. Ortega preguntó a la Prof. Salas por el Dr. García."),
            // G0 0107 — units + comma decimal
            Case("10-es-units", "es", "ef_dora", "La sala medía 12 pies por 8 m; pesaba 2,5 kg y cabían 3 l."),
            // G0 0106 — thousands separator inside a currency amount
            Case("11-es-thousands", "es", "ef_dora", "El precio subió de 1.234,56 $ a 1.300 € en un mes."),
            // G0 0135/0136 — M. → monsieur, Prof.
            Case("12-fr-titles", "fr-fr", "ff_siwis", "Mme Dalloway, Dr Watson et M. Higgins arrivèrent à midi."),
            // G0 0149 — the rate form km/h plus °C
            Case("13-fr-rate", "fr-fr", "ff_siwis", "Roulez 5 km au nord à 90 km/h par 36 °C à l'ombre."),
            // G0 0176/0177 — Sig.ra / Dott. / Prof. / Dott.ssa / Avv.
            Case("14-it-titles", "it", "if_sara", "Il Sig. Verdi chiese alla Dott.ssa Bianchi notizie dell'Avv. Rossi."),
            // G0 0189 — units spelled as letters in it
            Case("15-it-units", "it", "if_sara", "La stanza era 12 piedi per 8 m; pesava 2,5 kg e conteneva 3 l."),
            // G0 0218 — Sr./Profa./Dr.
            Case("16-pt-honorifics", "pt-br", "pf_dora", "O Sr. Oliveira perguntou à Profa. Costa pelo Dr. Barbosa."),
            // G0 0230 — units + comma decimal in pt-BR
            Case("17-pt-units", "pt-br", "pf_dora", "A sala tinha 12 pés por 8 m; pesava 2,5 kg e cabiam 3 l."),
        )

    @Test
    fun renderEveryCaseWithAndWithoutTheRules() {
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "g1")
        outDir.mkdirs()
        assertTrue("packs not staged (see build.md)", prerequisites().model.isFile)

        // One engine at a time: each open holds the 325 MB graph, so the two
        // passes are sequential and the first is closed before the second opens.
        val afterRenders = renderAll(openEngine(normalizing = true), outDir, suffix = "after")
        val beforeRenders = renderAll(openEngine(normalizing = false), outDir, suffix = "before")

        assertTrue("every case must synthesize in both passes", afterRenders == cases.size && beforeRenders == cases.size)
        // The pairs must actually differ: a rule that changes nothing audible is
        // not a fix, and a case that renders identically in both passes tells the
        // listener nothing (07-en-minus is the known pre-batch-3 control).
        val identical = cases.map { it.slug }.filter { slug -> sameAudio(outDir, slug) }
        assertTrue(
            "cases that render identically with and without the rules (expected only the pre-batch-3 control): $identical",
            identical.all { it.startsWith("07-") },
        )
    }

    private fun renderAll(
        engine: KokoroEngine,
        outDir: File,
        suffix: String,
    ): Int {
        var rendered = 0
        try {
            for (case in cases) {
                val outcome =
                    runBlocking {
                        engine.synthesize(SynthesisRequest(case.text, voice = case.voice))
                    }
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

    /** True when the pair's PCM is byte-identical (i.e. the rules changed nothing). */
    private fun sameAudio(
        outDir: File,
        slug: String,
    ): Boolean {
        val before = File(outDir, "g1_$slug-before.wav")
        val after = File(outDir, "g1_$slug-after.wav")
        if (!before.isFile || !after.isFile) return false
        val b = before.readBytes()
        val a = after.readBytes()
        if (b.size != a.size) return false
        // Skip the 44-byte header: sample rates are equal, so a byte compare is exact.
        return b.copyOfRange(44, b.size).contentEquals(a.copyOfRange(44, a.size))
    }

    private fun openEngine(normalizing: Boolean): KokoroEngine {
        val (model, voices, espeakLib, espeakData) = prerequisites()
        val espeak = EspeakPhonemizer(libraryPath = espeakLib.absolutePath, dataPath = espeakData.absolutePath)
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
        file.outputStream().use { it.write(header); it.write(pcm) }
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
