package io.github.moronigranja.ayvu.featureplayer.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector
import io.github.moronigranja.ayvu.featureplayer.playback.FakeTranslationService
import io.github.moronigranja.ayvu.featureplayer.playback.KokoroRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.PiperRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.PlaybackService
import io.github.moronigranja.ayvu.featureplayer.playback.TranslateRuntime
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.LibraryDatabase
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.AuditionUiState
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.player.VoicePackDownloader
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Owner report (2026-09-17): *"if I press play while the empty state or loading
 * message is showing, the playback starts at the beginning."*
 *
 * The reader's play control used to dispatch `ACTION_RESUME` in that window
 * (both states are entered exactly when `chapterPassages.isEmpty()`), which
 * resolves its own start position service-side — re-reading the persisted row
 * and racing the in-flight open, with a silent passage-0/0 fallback whenever no
 * valid row exists at that instant (never played, a row invalidated by a
 * re-parse, or a transient store failure swallowed by `storeOp`). The press is
 * now remembered and completed against the position the open PRESENTS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPlayWhileLoadingTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private lateinit var database: LibraryDatabase
    private lateinit var settings: AppSettings

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
        ): OpenResult = error("the reader tests never download packs")
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
        Dispatchers.setMain(dispatcher)
        shadowOf(application).clearStartedServices()
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
                    FakeTranslationService(),
                ),
            registry = registry,
            download =
                object : VoicePackDownloader {
                    override fun requestDownload(voice: String) = Unit
                },
            translationService = FakeTranslationService(),
            installer = PackInstaller(registry, PackStager { }),
        )
    }

    /** The chapter the open would present: content published at (chapter, passage). */
    private fun publishContent(
        chapter: Int,
        passage: Int,
    ) {
        PlaybackStateHolder.update {
            it.copy(
                bookId = BOOK,
                chapterIndex = chapter,
                passageIndex = passage,
                chapterPassages = listOf("first passage", "second passage"),
            )
        }
    }

    private fun serviceIntents(): List<Intent> = shadowOf(application).allStartedServices

    private fun playIntents(): List<Intent> = serviceIntents().filter { it.action != PlaybackService.ACTION_OPEN }

    @Test
    fun `a press while the chapter is loading dispatches no play command`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.open(BOOK) // the reader's entry: the open is in flight, nothing published yet
            advanceUntilIdle()
            assertEquals(listOf(PlaybackService.ACTION_OPEN), serviceIntents().map { it.action })

            vm.playFromView(chapter = 0, passage = 0)
            advanceUntilIdle()

            assertEquals(
                "a row-dependent resume must not race the in-flight open: ${serviceIntents().map { it.action }}",
                emptyList<Intent>(),
                playIntents(),
            )
        }

    @Test
    fun `the deferred press plays from the position the open presented`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.open(BOOK)
            advanceUntilIdle()
            vm.playFromView(chapter = 0, passage = 0)
            advanceUntilIdle()

            publishContent(chapter = 2, passage = 5)
            advanceUntilIdle()

            val played = playIntents().last()
            assertEquals(PlaybackService.ACTION_PLAY_POSITION, played.action)
            assertEquals(BOOK, played.getStringExtra(PlaybackService.EXTRA_BOOK_ID))
            assertEquals("the presented chapter, not the book's start", 2, played.getIntExtra(PlaybackService.EXTRA_CHAPTER, -1))
            assertEquals("the presented passage, not the book's start", 5, played.getIntExtra(PlaybackService.EXTRA_PASSAGE, -1))
        }

    @Test
    fun `a press with the chapter loaded plays from the view position immediately`() =
        runTest(dispatcher) {
            val vm = viewModel()
            publishContent(chapter = 3, passage = 1)
            vm.open(BOOK)
            advanceUntilIdle()
            shadowOf(application).clearStartedServices()

            vm.playFromView(chapter = 3, passage = 4)
            advanceUntilIdle()

            val played = playIntents().single()
            assertEquals(PlaybackService.ACTION_PLAY_POSITION, played.action)
            assertEquals(3, played.getIntExtra(PlaybackService.EXTRA_CHAPTER, -1))
            assertEquals(4, played.getIntExtra(PlaybackService.EXTRA_PASSAGE, -1))
        }

    @Test
    fun `an explicit stop before the chapter lands cancels the deferred press`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.open(BOOK)
            advanceUntilIdle()
            vm.playFromView(chapter = 0, passage = 0)
            vm.stop() // the user changed their mind while the book was opening
            advanceUntilIdle()
            shadowOf(application).clearStartedServices()

            publishContent(chapter = 1, passage = 2)
            advanceUntilIdle()

            assertEquals(
                "a stopped press must not fire once the chapter lands: ${playIntents().map { it.action }}",
                emptyList<Intent>(),
                playIntents(),
            )
        }

    private companion object {
        const val BOOK = "play-while-loading-book"
    }
}
