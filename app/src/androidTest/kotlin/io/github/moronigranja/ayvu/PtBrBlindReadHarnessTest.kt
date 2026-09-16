package io.github.moronigranja.ayvu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureplayer.playback.TranslateRuntime
import io.github.moronigranja.ayvu.featuresettings.AndroidHttpTransport
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.EspeakStager
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.EspeakPhonemizer
import io.github.moronigranja.ayvu.tts.kokoro.KokoroEngine
import io.github.moronigranja.ayvu.tts.kokoro.KokoroPacks
import io.github.moronigranja.ayvu.tts.kokoro.NormalizingPhonemizer
import io.github.moronigranja.ayvu.tts.translate.TranslatePackStager
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The pt-BR blind read (roadmap G0 gate, decisions #101/#162): renders the
 * read-in-language translations through the PRODUCTION path — the staged LFM2.5
 * GGUF opened by [TranslateRuntime], then the Kokoro target voice [pf_dora] (the
 * auto-picked pt-BR voice the translate slice ships) — and writes the
 * original-English + translated-Portuguese WAV pair per passage for the owner's
 * ear.
 *
 * The passages ARE the Phase-J blind-read set's English sources
 * (`docs/prints/phase-j/blind-read/SOURCES.txt`), chosen to carry the G0 stress
 * shapes into the translated render: times ("9:30", "11:00", "0230 UTC"),
 * percentages ("88%"), the em-dash range ("7–2" → "sete a dois"), cardinals and
 * years (1200, 2016), dialogue quotes ([15]), and foreign proper nouns (Mossack
 * Fonseca, Deutsche Bank, Downing Street, Hong Kong). The shipped G1 pt rules
 * (decimal-comma, rates, hyphen ranges) apply to whatever LFM2.5 produces, so
 * the read doubles as a cross-check of the rule set on translator output, not
 * just corpus text.
 *
 * Output: `<externalFilesDir>/ptbr-blind/ptbr_<slug>_{en,pt}.wav` (PCM16 mono
 * WAV at the engine rate) plus `ptbr_<slug>.txt` with the produced translation
 * for read-along. Nothing is played here — the owner judges the pt render (or
 * A/Bs it against the en reference) on the device or pulled to the host.
 *
 * Requires network on the first run (Kokoro model + voices + espeak bundle +
 * the 730 MB LFM pack); later runs reuse the staged artifacts. Budget: the
 * translate leg is the slow part (~5–10 s per passage on the S22, decisions
 * #168). Device smoke = a fresh render of every passage in both languages.
 */
@RunWith(AndroidJUnit4::class)
class PtBrBlindReadHarnessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** English source passages (blind-read set), in listen order. */
    private data class Passage(
        val slug: String,
        val en: String,
    )

    private val passages =
        listOf(
            Passage(
                "01-stanford-chip",
                "On Monday, scientists from the Stanford University School of Medicine announced the invention of a new diagnostic tool that can sort cells by type: a tiny printable chip that can be manufactured using standard inkjet printers for possibly about one U.S. cent each.",
            ),
            Passage(
                "03-gripen-crash",
                "The JAS 39C Gripen crashed onto a runway at around 9:30 am local time (0230 UTC) and exploded, closing the airport to commercial flights.",
            ),
            Passage(
                "05-fire-vehicle",
                "Local media reports an airport fire vehicle rolled over while responding.",
            ),
            Passage(
                "08-protest-whitehall",
                "The protest started around 11:00 local time (UTC+1) on Whitehall opposite the police-guarded entrance to Downing Street, the Prime Minister's official residence.",
            ),
            Passage(
                "12-nadal-record",
                "Nadal's head to head record against the Canadian is 7–2.",
            ),
            Passage(
                "14-nadal-points",
                "Nadal bagged 88% net points in the match winning 76 points in the first serve.",
            ),
            Passage(
                "15-nadal-quote",
                "After the match, King of Clay said, \"I am just excited about being back in the final rounds of the most important events. I am here to try to win this.\"",
            ),
            Passage(
                "16-panama-papers",
                "\"Panama Papers\" is an umbrella term for roughly ten million documents from Panamanian law firm Mossack Fonseca, leaked to the press in spring 2016.",
            ),
            Passage(
                "18-deutsche-bank",
                "British newspaper The Guardian suggested Deutsche Bank controlled roughly a third of the 1200 shell companies used to accomplish this.",
            ),
            Passage(
                "20-ma-hong-kong",
                "Born in Hong Kong, Ma studied at New York University and Harvard Law School and once held an American permanent resident \"green card\".",
            ),
            Passage(
                "p2-ring-shark-tank",
                "Danius said, \"Right now we are doing nothing. I have called and sent emails to his closest collaborator and received very friendly replies. For now, that is certainly enough.\" Previously, Ring's CEO, Jamie Siminoff, remarked the company started when his doorbell wasn't audible from his shop in his garage. He built a WiFi door bell, he said. Siminoff said sales boosted after his 2013 appearance in a Shark Tank episode where the show panel declined funding the startup. In late 2017, Siminoff appeared on shopping television channel QVC.",
            ),
        )

    private class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = rows[key]

        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }

        override suspend fun all(): List<SettingEntity> = rows.map { (key, value) -> SettingEntity(key, value) }

        override suspend fun putAll(settings: List<SettingEntity>) {
            settings.forEach { rows[it.key] = it.value }
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }

        override suspend fun deleteAll(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }
    }

    @Test
    fun renderEnglishAndPortuguesePairsThroughTheProductionPath() {
        val cache = PackCache(context.filesDir)
        val downloader = PackDownloader(cache, AndroidHttpTransport())
        runBlocking {
            for (pack in listOf(KokoroPacks.model, KokoroPacks.voices)) downloader.download(pack)
            downloader.download(KokoroPacks.espeak)
            EspeakStager.stage(context.filesDir, cache, KokoroPacks.espeak)
            assertTrue("espeak bundle must be staged", EspeakStager.isStaged(context.filesDir))

            downloader.download(TranslatePacks.pack)
            TranslatePackStager.stage(context.filesDir, cache, TranslatePacks.pack)
            assertTrue("translate bundle must be staged", TranslatePackStager.isStaged(context.filesDir))
        }

        val espeak =
            EspeakPhonemizer(
                libraryPath = File(context.filesDir, "espeak/libespeak-ng.so").absolutePath,
                dataPath = File(context.filesDir, "espeak/espeak-ng-data").absolutePath,
            )
        val engine =
            KokoroEngine.open(
                spec = DefaultEngines.kokoro,
                packs = KokoroPacks.all,
                modelFile = cache.targetFile(KokoroPacks.model),
                voicesFile = cache.targetFile(KokoroPacks.voices),
                phonemizer = NormalizingPhonemizer(espeak),
                sessionFactory = { it.setIntraOpNumThreads(4) },
            )

        val settings = AppSettings(SettingsStore(FakeSettingsDao()))
        val runtime = TranslateRuntime(context, settings)
        val translator = runtime.translator()
        assertNotNull("translator must open (failure=${runtime.failureReason})", translator)
        assertNull(runtime.failureReason)
        translator!!

        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "ptbr-blind")
        outDir.mkdirs()

        try {
            // Pass 1: ALL translations, back to back. TranslateRuntime's 60 s
            // idle close (#162 co-residency guard) is re-armed on every call, so
            // the render loop must never leave a >60 s translate gap — synthesis
            // between calls did exactly that on the first run (idle close + a
            // re-open race; see device log). Translated text is written per
            // passage so partial runs still leave read-along material.
            val done = mutableListOf<Pair<Passage, String>>()
            for (passage in passages) {
                val translated = runBlocking { translator.translate(passage.en, "Brazilian Portuguese") }
                assertTrue("${passage.slug}: no translation", translated.isNotBlank())
                assertFalse("${passage.slug}: untranslated passthrough", translated == passage.en)
                File(outDir, "ptbr_${passage.slug}.txt").writeText("EN:\n${passage.en}\n\nPT:\n$translated")
                done += passage to translated
            }

            // Pass 2: synthesize + write both WAVs per passage (no translator).
            var rendered = 0
            for ((passage, translated) in done) {
                val en = runBlocking { engine.synthesize(SynthesisRequest(passage.en, voice = "af_heart")) } as? SynthesisOutcome.Audio
                val pt = runBlocking { engine.synthesize(SynthesisRequest(translated, voice = "pf_dora")) } as? SynthesisOutcome.Audio
                assertTrue("${passage.slug}: en render failed", en != null && en.pcm.isNotEmpty())
                assertTrue("${passage.slug}: pt render failed", pt != null && pt.pcm.isNotEmpty())
                writeWav(File(outDir, "ptbr_${passage.slug}_en.wav"), en!!.pcm, en.sampleRateHz)
                writeWav(File(outDir, "ptbr_${passage.slug}_pt.wav"), pt!!.pcm, pt.sampleRateHz)
                rendered++
            }
            assertEquals("every passage must render", passages.size, rendered)
        } finally {
            runCatching { translator.close() }
            runCatching { engine.close() }
        }
    }

    /** Minimal PCM16 mono WAV writer (the G1 harness writer, same contract). */
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
