package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import androidx.room.Room
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import io.github.moronigranja.ayvu.tts.translate.TranslatingEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The speech-vs-display constraint (D): a passage is translated AT MOST once —
 * with a display language set, the spoken language is either the original
 * (Off) or that same translation. [EngineSelector.translateTarget] is the
 * single choke point; a stored mismatch degrades to original audio rather
 * than starting a second translation. The decoration's final gate (the LLM
 * session) is device-only — LfmTranslatedPlaybackE2eTest covers the full
 * decorated path — so the positive branch is asserted as the resolution
 * decision + the plain-engine fallthrough observable host-side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineSelectorTranslateConstraintTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private lateinit var settings: AppSettings
    private lateinit var translateRuntime: TranslateRuntime
    private lateinit var selector: EngineSelector

    private class RecordingEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en", "pt-BR"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, segments = null)
    }

    private class FakeKokoroRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = RecordingEngine()

        override val failureReason: String? = null
    }

    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts must not be used in these tests")
        }

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        settings = AppSettings(SettingsStore(database.settingsDao()))
        translateRuntime = TranslateRuntime(context, settings)
        selector =
            EngineSelector(
                FakeKokoroRuntime(context, settings),
                PiperRuntime(context, settings),
                translateRuntime,
                onUnusedSystemTts,
                settings,
                FakeTranslationService(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------
    // The single choke point (translateTarget)
    // ------------------------------------------------------------------

    @Test
    fun `speech matching the display language stays in force`() =
        runBlocking {
            settings.setBookDisplay("b", "pt-BR")
            settings.setBookTranslate("b", "pt-BR")

            assertEquals("pt-BR", selector.translateTarget("b"))
        }

    @Test
    fun `a stored speech-and-display mismatch degrades to original audio`() =
        runBlocking {
            settings.setBookDisplay("b", "pt-BR")
            settings.setBookTranslate("b", "es")

            assertNull(
                "the mismatched speech target resolves to Off",
                selector.translateTarget("b"),
            )
            val engine = selector.resolve("b").first
            assertFalse(
                "resolve never wraps a mismatched book — original audio, no second translation",
                engine is TranslatingEngine,
            )
            assertTrue(engine is RecordingEngine)
        }

    @Test
    fun `speech still works without a display language`() =
        runBlocking {
            settings.setBookTranslate("b", "es")

            // No display target: the old audio-only read-in behavior,
            // unchanged by the constraint (no regression).
            assertEquals("es", selector.translateTarget("b"))
        }

    @Test
    fun `writing a display language clears a mismatched speech target`() =
        runBlocking {
            settings.setBookTranslate("b", "es")
            settings.setBookDisplay("b", "pt-BR")
            settings.setBookDisplay("b", null)

            // The display write normalized the speech target away; clearing
            // the display leaves the (already cleared) speech alone.
            assertNull(settings.bookTranslate("b"))
            assertNull(settings.bookDisplay("b"))
        }

    @Test
    fun `writing a matching speech target after a display keeps both`() =
        runBlocking {
            settings.setBookDisplay("b", "pt-BR")
            settings.setBookTranslate("b", "pt-BR")

            assertEquals("pt-BR", settings.bookDisplay("b"))
            assertEquals("pt-BR", settings.bookTranslate("b"))
        }

    // ------------------------------------------------------------------
    // Display mode + mirror
    // ------------------------------------------------------------------

    @Test
    fun `display mode default is interleaved and round-trips`() =
        runBlocking {
            assertEquals("interleaved", io.github.moronigranja.ayvu.player.DisplayMode.INTERLEAVED.key)
            assertEquals(
                io.github.moronigranja.ayvu.player.DisplayMode.INTERLEAVED,
                io.github.moronigranja.ayvu.player.DisplayMode
                    .from("interleaved"),
            )
            assertEquals(io.github.moronigranja.ayvu.player.DisplayMode.INTERLEAVED, settings.displayMode())
            settings.setDisplayMode(io.github.moronigranja.ayvu.player.DisplayMode.TRANSLATED_ONLY)
            assertEquals(io.github.moronigranja.ayvu.player.DisplayMode.TRANSLATED_ONLY, settings.displayMode())
            assertEquals(io.github.moronigranja.ayvu.player.DisplayMode.TRANSLATED_ONLY, settings.state.value.displayMode)
        }
}
