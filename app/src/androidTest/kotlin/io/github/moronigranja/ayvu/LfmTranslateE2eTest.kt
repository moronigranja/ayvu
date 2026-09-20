package io.github.moronigranja.ayvu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.featureplayer.playback.TranslateRuntime
import io.github.moronigranja.ayvu.featuresettings.AndroidHttpTransport
import io.github.moronigranja.ayvu.llm.LlamaTranslator
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.pregen.PcmPassageCache
import io.github.moronigranja.ayvu.player.pregen.PregenAudio
import io.github.moronigranja.ayvu.player.pregen.PregenKey
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
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
 * On-device verification of the read-in-language runtime (decisions #161/#162)
 * through the PRODUCTION path: the pinned pack descriptor downloads from the
 * GitHub release and verifies by sha256, [TranslatePackStager] extracts it,
 * [TranslateRuntime] opens the vendored llama.cpp session over the staged GGUF
 * (picking the arm64 kernel variant from the packaged dl-loaded backends), and
 * [LlamaTranslator] translates the gate's own FLORES sentences to Brazilian
 * Portuguese.
 *
 * Evidence lives in logcat (`adb logcat -s LfmE2e`): per-passage milliseconds,
 * prompt/generation stats, and the retired-artifact cleanup line. The measured
 * reference is #161 (22.3 tok/s, 2.7 s/passage incl. prompt on this S22).
 *
 * The sentences are the first three of the 40-sentence gate corpus
 * (`m/flores/flores101_dataset/devtest/eng.devtest`), so a timing run here is
 * directly comparable with `docs/prints/beam-spike/lfm_ondevice.json`.
 *
 * Requires: network (the shipped 730 MB pack downloads once; the second engine's
 * 1.67 GB pack downloads only for the test that asks for it; later runs hit the
 * cache).
 */
@RunWith(AndroidJUnit4::class)
class LfmTranslateE2eTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val files: File get() = context.filesDir

    private val sentences =
        listOf(
            "\"We now have 4-month-old mice that are non-diabetic that used to be diabetic,\" he added.",
            "Dr. Ehud Ur, professor of medicine at Dalhousie University in Halifax, Nova Scotia and chair of " +
                "the clinical and scientific division of the Canadian Diabetes Association cautioned that the " +
                "research is still in its early days.",
            "Like some other experts, he is skeptical about whether diabetes can be cured, noting that these " +
                "findings have no relevance to people who already have Type 1 diabetes.",
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

    private fun settings() = AppSettings(SettingsStore(FakeSettingsDao()))

    /** The pack end-to-end: descriptor URL → sha256/size verification → staged GGUF. */
    @Test
    fun packDownloadsVerifiesStagesThenTranslatesOnDevice() {
        val pack = TranslatePacks.shipped.pack
        val cache = PackCache(files)
        val downloader = PackDownloader(cache, AndroidHttpTransport())

        val outcome =
            runBlocking {
                downloader.download(pack) { done, total -> Log.i(TAG, "download $done/$total") }
            }
        Log.i(TAG, "download outcome=$outcome")
        assertTrue("pack must verify ($outcome)", cache.isVerified(pack))

        assertTrue(
            "staging must succeed",
            runBlocking { TranslatePackStager.stage(files, cache, pack) },
        )
        assertTrue("staged bundle must be ready", TranslatePackStager.isStaged(files, TranslatePacks.shipped))
        val gguf = TranslatePackStager.modelFile(files, TranslatePacks.shipped)
        assertEquals("the shipped LFM2.5-1.2B GGUF", 730_895_168L, gguf.length())
        Log.i(TAG, "staged ${gguf.path} (${gguf.length()} B)")

        // The runtime opens it — this is where the packaged ggml CPU backends
        // must be found on the filesystem (nativeLibraryDir) and the best arm64
        // kernel variant loaded; a failure here is the dl-loading failing.
        val runtime = TranslateRuntime(context, settings())
        val translator = runtime.translator()
        assertNotNull("translator must open (failure=${runtime.failureReason})", translator)
        assertNull(runtime.failureReason)
        translator!!

        try {
            for ((index, sentence) in sentences.withIndex()) {
                val started = System.currentTimeMillis()
                val translated = runBlocking { translator.translate(sentence, "Brazilian Portuguese") }
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "sentence $index: ${translated.length} chars in $elapsed ms :: $translated")
                assertTrue("sentence $index produced no translation", translated.isNotBlank())
                assertFalse("sentence $index came back untranslated", translated == sentence)
            }
        } finally {
            translator.close()
        }
    }

    /**
     * Decisions #182 on the device: the SECOND engine's pack comes off its own
     * release (descriptor URL → sha256/size verification → its own staged
     * bundle), [TranslateRuntime] opens whichever engine the stored selection
     * names, and a selection change closes the resident session and opens the
     * other model — two models are never resident together.
     *
     * Readiness follows the selection, not the staged set: with the shipped
     * engine selected and NOT staged the runtime declines with a typed failure
     * instead of quietly translating through the other engine's bundle.
     */
    @Test
    fun secondEngineStagesAndTheRuntimeFollowsTheSelection() {
        val pack = TranslatePacks.better.pack
        val cache = PackCache(files)
        val downloader = PackDownloader(cache, AndroidHttpTransport())

        val outcome =
            runBlocking {
                downloader.download(pack) { done, total -> Log.i(TAG, "download $done/$total") }
            }
        Log.i(TAG, "second-pack download outcome=$outcome")
        assertTrue("second pack must verify ($outcome)", cache.isVerified(pack))
        assertTrue(
            "staging must succeed",
            runBlocking { TranslatePackStager.stage(files, cache, pack) },
        )
        val gguf = TranslatePackStager.modelFile(files, TranslatePacks.better)
        assertEquals("the LFM2.5-2.6B-Base GGUF", 1_674_454_080L, gguf.length())
        Log.i(TAG, "staged ${gguf.path} (${gguf.length()} B)")

        val settings = settings()
        val runtime = TranslateRuntime(context, settings)

        runBlocking {
            settings.setTranslateEngine(TranslatePacks.better.id)
            assertTrue("the selected, staged engine must be openable", runtime.canOpen)
            val translated = runtime.translate(sentences[0], "Brazilian Portuguese")
            assertNotNull("the 2.6B engine must translate (failure=${runtime.failureReason})", translated)
            Log.i(TAG, "lfm26b: $translated")

            settings.setTranslateEngine(TranslatePacks.shipped.id)
            assertFalse("readiness must follow the selection, not the staged set", runtime.canOpen)
            assertNull(
                "the unstaged engine must not be opened from the other engine's bundle",
                runtime.translate(sentences[0], "Brazilian Portuguese"),
            )
            Log.i(TAG, "shipped-selected failure=${runtime.failureReason}")

            settings.setTranslateEngine(TranslatePacks.better.id)
            val again = runtime.translate(sentences[0], "Brazilian Portuguese")
            assertNotNull("switching back must re-open the staged engine", again)
            Log.i(TAG, "lfm26b again: $again")
        }
    }

    /** The clean cutover reclaims the retired SMaLL-100 bundle + cached zip. */
    @Test
    fun firstTranslateCallRemovesTheRetiredSmall100Artifacts() {
        val staged = File(files, "translate-small100")
        val cached = File(files, "packs/translate-small100")
        staged.mkdirs()
        File(staged, "encoder_model.onnx").writeBytes(byteArrayOf(1))
        cached.mkdirs()
        File(cached, "translate-small100-int8-v1").writeBytes(byteArrayOf(1))

        // The pack is downloaded by the sibling test (or a previous run); the
        // cleanup runs before any prerequisite check, so this holds either way.
        val runtime = TranslateRuntime(context, settings())
        runtime.translator()

        Log.i(TAG, "cleanup: staged=${staged.exists()} cached=${cached.exists()}")
        assertFalse("staged small-100 bundle must be gone", staged.exists())
        assertFalse("cached small-100 zip must be gone", cached.exists())
    }

    /** Translated pregen audio lands under the translator's own cache segment. */
    @Test
    fun pregenKeysLandUnderTheTranslatorSegment() {
        val root = File(files, "pregen-lfm-test")
        root.deleteRecursively()
        val cache = PcmPassageCache(root, maxBytes = Long.MAX_VALUE)
        val key =
            PregenKey(
                "lfm-e2e-book",
                0,
                0,
                "pf_dora",
                1.0,
                engine = PregenKey.DEFAULT_ENGINE,
                target = TranslationTarget("pt-BR", PregenKey.LFM_TRANSLATOR),
            )
        cache.put(key, PregenAudio(ByteArray(2_000) { 3 }, 24_000, null))

        val path = File(root, "$key.pcm")
        Log.i(TAG, "pregen key path=${path.path}")
        assertTrue("expected the x<lang>/t<translator> segments on disk", path.isFile)
        assertTrue(key.toString().contains("/xpt-BR/tlfm12b/"))
        assertEquals(key, PcmPassageCache(root, Long.MAX_VALUE).generatedKeys("lfm-e2e-book", "pf_dora", 1.0).firstOrNull())
        root.deleteRecursively()
    }

    private companion object {
        const val TAG = "LfmE2e"
    }
}
