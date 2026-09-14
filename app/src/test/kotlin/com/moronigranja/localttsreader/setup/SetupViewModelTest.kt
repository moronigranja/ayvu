package com.moronigranja.localttsreader.setup

import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.ImportCoordinator
import com.moronigranja.localttsreader.locate.IndexLock
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.InMemoryLibraryStore
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.AuditionUiState
import com.moronigranja.localttsreader.player.VoiceAudition
import com.moronigranja.localttsreader.tts.DownloadTransport
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.OpenResult
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackKind
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.VoiceCatalog
import com.moronigranja.localttsreader.tts.sha256Hex
import com.moronigranja.localttsreader.tts.setup.StorageProbe
import com.moronigranja.localttsreader.tts.setup.StepKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Setup wizard pointer (item 6, JVM fakes — no Robolectric, the gate-test
 * pattern): the VM owns [SetupUiState.currentStep] with the clamp rules — a
 * step that disappears never throws the user back, a reappearing step
 * inserts without moving the pointer, Back is blocked on PRIVACY (the gate
 * owns dismissal), and system Back maps to [SetupViewModel.wizardBack].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    @TempDir
    lateinit var root: File

    private lateinit var filesDir: File
    private lateinit var cache: PackCache
    private lateinit var registry: PackRegistry
    private lateinit var library: InMemoryLibraryStore
    private lateinit var settings: AppSettings
    private val dao = FakeSettingsDao()

    private val model = fixturePack("kokoro-model", 64)
    private val voices = fixturePack("kokoro-voices", 32)
    private val espeak = fixturePack("espeak-ng", 16)
    private val piperModel = fixturePack("piper-lessac-medium", 64, engineId = "piper-v1")
    private val piperConfig = fixturePack("piper-lessac-medium-config", 4, engineId = "piper-v1")

    @BeforeEach
    fun setUp() {
        filesDir = File(root, "files").apply { mkdirs() }
        cache = PackCache(filesDir)
        val descriptors =
            listOf(
                EngineDescriptor(
                    spec = EngineSpec("kokoro-82m", "Kokoro", EngineTier.PRIMARY, setOf("en")),
                    packs = listOf(model, voices, espeak),
                ),
                EngineDescriptor(
                    spec = EngineSpec("piper-v1", "Piper", EngineTier.PRIMARY, setOf("en", "de")),
                    packs = listOf(piperModel, piperConfig),
                ),
            )
        registry = PackRegistry(cache, PackDownloader(cache, FailTransport()), descriptors)
        library = InMemoryLibraryStore()
        settings = AppSettings(SettingsStore(dao))
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(dispatcher: TestDispatcher = StandardTestDispatcher()): SetupViewModel =
        SetupViewModel(
            registry = registry,
            cache = cache,
            settings = settings,
            voiceCatalog = VoiceCatalog(cache),
            libraryStore = library,
            filesDir = filesDir,
            storageProbe =
                object : StorageProbe {
                    override fun availableBytes(): Long = 1L shl 30
                },
            coordinator =
                ImportCoordinator(
                    importer = BookImporter(),
                    store = InMemoryLibraryStore(),
                    index = TextIndex(),
                    indexLock = IndexLock(),
                ),
            ioDispatcher = dispatcher,
            voiceAudition =
                object : VoiceAudition {
                    override val state: StateFlow<AuditionUiState> = MutableStateFlow(AuditionUiState())
                    override fun preview(voice: String) = Unit
                    override fun stop() = Unit
                },
        )

    private fun markReady(pack: TtsPack) {
        val target = cache.targetFile(pack)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(pack.sizeBytes.toInt()))
        check(cache.verifyAndMark(pack)) { "fixture pack must verify" }
    }

    /** The degraded path needs no pack ready-gate beyond the opt-in. */
    private fun fixturePack(
        id: String,
        size: Long,
        engineId: String = "kokoro-82m",
    ): TtsPack =
        TtsPack(
            id = id,
            engineId = engineId,
            kind = PackKind.MODEL,
            displayName = id,
            url = "https://example.test/$id",
            sha256Hex = sha256Hex(ByteArray(size.toInt())),
            sizeBytes = size,
        )

    private fun entry(id: String) = LibraryEntry(Book(id = id, title = id), importedAtEpochMillis = 1)

    private class FailTransport : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = OpenResult.HttpError(404)
    }

    @Test
    fun `empty facts open on privacy and walk next and back through the full plan`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)

            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.DOWNLOAD_PACKS, vm.state.value.currentStep)

            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.CHOOSE_VOICE, vm.state.value.currentStep)

            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.IMPORT_BOOK, vm.state.value.currentStep)

            // Last step: Next is a no-op (the Finish button, not Next).
            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.IMPORT_BOOK, vm.state.value.currentStep)

            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.CHOOSE_VOICE, vm.state.value.currentStep)

            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.DOWNLOAD_PACKS, vm.state.value.currentStep)

            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)

            // Back on PRIVACY is blocked — the gate owns dismissal.
            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)
        }

    @Test
    fun `a removed step clamps back to the nearest surviving predecessor`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.DOWNLOAD_PACKS, vm.state.value.currentStep)

            // The user opts into the degraded path: DOWNLOAD_PACKS disappears
            // from the derived list — the pointer must NOT throw forward onto
            // CHOOSE_VOICE; it clamps back to the nearest surviving step.
            settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE)
            advanceUntilIdle()
            assertEquals(
                listOf(StepKind.PRIVACY, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK),
                vm.state.value.steps,
            )
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)
        }

    @Test
    fun `a reinserted step does not move the pointer`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()

            // Degraded path, advance to CHOOSE_VOICE.
            settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE)
            advanceUntilIdle()
            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.CHOOSE_VOICE, vm.state.value.currentStep)

            // Back to Kokoro: DOWNLOAD_PACKS re-inserts BEFORE the current
            // step — the pointer stays on CHOOSE_VOICE (never yanked back).
            settings.setTtsEngine(SettingsStore.DEFAULT_TTS_ENGINE)
            advanceUntilIdle()
            assertEquals(
                listOf(StepKind.PRIVACY, StepKind.DOWNLOAD_PACKS, StepKind.CHOOSE_VOICE, StepKind.IMPORT_BOOK),
                vm.state.value.steps,
            )
            assertEquals(StepKind.CHOOSE_VOICE, vm.state.value.currentStep)
        }

    @Test
    fun `system back maps to wizard back and is blocked on privacy`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()

            // The screen maps system Back to wizardBack() while a
            // non-terminal, non-first step is current; the VM contract here:
            // PRIVACY → no-op, other steps → previous surviving step.
            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)

            vm.wizardNext()
            advanceUntilIdle()
            vm.wizardBack()
            advanceUntilIdle()
            assertEquals(StepKind.PRIVACY, vm.state.value.currentStep)
        }

    @Test
    fun `selecting piper drives the download plan to piper packs`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()

            vm.setEngine(SettingsStore.PIPER_ENGINE)
            advanceUntilIdle()
            assertEquals(SettingsStore.PIPER_ENGINE, vm.state.value.ttsEngine)
            // The required pack rows become piper's (model + config + espeak).
            assertEquals(
                setOf("piper-lessac-medium", "piper-lessac-medium-config", "espeak-ng"),
                vm.state.value.packs.map { it.packId }.toSet(),
            )
        }

    @Test
    fun `packs becoming ready keeps the pointer on download packs and next then advances`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val vm = viewModel(dispatcher)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            advanceUntilIdle()
            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.DOWNLOAD_PACKS, vm.state.value.currentStep)

            // All three packs land (the devise path: downloads complete
            // mid-flow) — DOWNLOAD_PACKS survives, so the pointer holds.
            markReady(model)
            markReady(voices)
            markReady(espeak)
            registry.refresh()
            File(filesDir, "espeak").mkdirs()
            File(filesDir, "espeak/espeak-ng-data").mkdirs()
            File(filesDir, "espeak/libespeak-ng.so").writeBytes(ByteArray(4))
            advanceUntilIdle()
            assertEquals(StepKind.DOWNLOAD_PACKS, vm.state.value.currentStep)
            assertEquals(true, vm.state.value.packs.all { it.status == com.moronigranja.localttsreader.ui.PlanPackStatus.Ready })

            vm.wizardNext()
            advanceUntilIdle()
            assertEquals(StepKind.CHOOSE_VOICE, vm.state.value.currentStep)
        }
}
