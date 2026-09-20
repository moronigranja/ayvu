package io.github.moronigranja.ayvu.featureplayer.ui

import android.content.Context
import androidx.room.Room
import io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector
import io.github.moronigranja.ayvu.featureplayer.playback.FakeTranslationService
import io.github.moronigranja.ayvu.featureplayer.playback.KokoroRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.PiperRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.TranslateRuntime
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.DisplayKind
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.player.VoicePackDownloader
import io.github.moronigranja.ayvu.player.pregen.PregenKey
import io.github.moronigranja.ayvu.player.pregen.TranslationReady
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.DownloadTransport
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.OpenResult
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStager
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Device report (2026-09-17): *"sometimes I have the translation set to off, but I see an
 * english translation of english text."*
 *
 * Root cause: a display-priority decode captures its target when it STARTS, and it can land
 * after the user cleared the book's display language (clearing it starts no new prefetch,
 * so nothing supersedes the in-flight one — `TranslationServiceImpl.prefetch` only
 * supersedes on a *new* target). The reader's `ready` merge took any landed text, so the
 * stale translation became a projected block while display was Off, and the entry survived
 * until the next re-seed — the whole chapter read as a rewrite of the original.
 *
 * This drives the exact window with the fake service's `emit` (no ~1 s LLM decode).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderDisplayTargetOffTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private lateinit var settings: AppSettings
    private val translation = FakeTranslationService()

    private val dispatcher = StandardTestDispatcher()

    private class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(4), 24_000, 1, emptyList())
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine = FakeEngine()

        override val failureReason: String? = null
    }

    private object UnusedTransport : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = error("the reader display tests never download packs")
    }

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        settings = AppSettings(SettingsStore(database.settingsDao()))
        PlaybackStateHolder.reset()
        PlaybackStateHolder.update {
            it.copy(bookId = BOOK, chapterIndex = 0, chapterPassages = PASSAGES)
        }
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        database.close()
        PlaybackStateHolder.reset()
        Dispatchers.resetMain()
    }

    private fun viewModel(): ReaderViewModel {
        val cache = PackCache(context.cacheDir)
        val registry = PackRegistry(cache, PackDownloader(cache, UnusedTransport), emptyList())
        return ReaderViewModel(
            context = context,
            settings = settings,
            audition =
                object : VoiceAudition {
                    override val state: StateFlow<AuditionUiState> = MutableStateFlow(AuditionUiState())

                    override fun preview(voice: String) = Unit

                    override fun stop() = Unit
                },
            selector =
                EngineSelector(
                    FakeRuntime(context, settings),
                    PiperRuntime(context, settings),
                    TranslateRuntime(context, settings),
                    object : dagger.Lazy<TTSEngine> {
                        override fun get(): TTSEngine = error("the system voice is unused in these tests")
                    },
                    settings,
                    translation,
                ),
            registry = registry,
            download =
                object : VoicePackDownloader {
                    override fun requestDownload(voice: String) = Unit
                },
            translationService = translation,
            installer = PackInstaller(registry, PackStager { }),
        )
    }

    @Test
    fun `a translation landing after the display language is cleared never renders`() =
        runTest(dispatcher) {
            settings.setBookDisplay(BOOK, "en")
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.chapterDisplay.collect {} }
            advanceUntilIdle()

            // Display language in force: the projection carries the translation
            // column (Pending until the text lands).
            assertTrue(
                "expected a translation column while display is on: ${vm.chapterDisplay.value.blocks}",
                vm.chapterDisplay.value.blocks
                    .any { it.kind == DisplayKind.Pending },
            )

            // The user turns the display language OFF while a decode is in flight.
            settings.setBookDisplay(BOOK, null)
            advanceUntilIdle()
            assertEquals(
                "display Off renders the original-only layout",
                listOf(DisplayKind.Original, DisplayKind.Original),
                vm.chapterDisplay.value.blocks
                    .map { it.kind },
            )

            // The decode that started under `en` lands now.
            translation.emit(
                TranslationReady(
                    BOOK,
                    chapter = 0,
                    passage = 0,
                    target = TranslationTarget("en", PregenKey.LFM_TRANSLATOR),
                    text = "REWRITTEN ENGLISH",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "a stale decode must not put a translation block on screen with display Off",
                listOf(DisplayKind.Original, DisplayKind.Original),
                vm.chapterDisplay.value.blocks
                    .map { it.kind },
            )
            assertTrue(
                "the stale text must not enter the display map: ${vm.chapterDisplay.value.translations}",
                vm.chapterDisplay.value.translations
                    .isEmpty(),
            )
        }

    @Test
    fun `a translation for the in-force language still lands`() =
        runTest(dispatcher) {
            settings.setBookDisplay(BOOK, "en")
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.chapterDisplay.collect {} }
            advanceUntilIdle()

            translation.emit(
                TranslationReady(
                    BOOK,
                    chapter = 0,
                    passage = 0,
                    target = TranslationTarget("en", PregenKey.LFM_TRANSLATOR),
                    text = "THE TRANSLATION",
                ),
            )
            advanceUntilIdle()

            val blocks = vm.chapterDisplay.value.blocks
            val translated = blocks.firstOrNull { it.kind == DisplayKind.Translation }
            assertEquals("the landing translation renders", "THE TRANSLATION", translated?.text)
            assertEquals(
                "THE TRANSLATION",
                vm.chapterDisplay.value.translations[0]?.let {
                    (it as io.github.moronigranja.ayvu.player.TranslationState.Ready).text
                },
            )
        }

    private companion object {
        const val BOOK = "display-off-book"
        val PASSAGES = listOf("Original passage one.", "Original passage two.")
    }
}
