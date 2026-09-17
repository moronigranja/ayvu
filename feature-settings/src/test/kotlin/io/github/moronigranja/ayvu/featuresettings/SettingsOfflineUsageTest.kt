package io.github.moronigranja.ayvu.featuresettings

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.OfflineStorage
import io.github.moronigranja.ayvu.player.pregen.PregenSpaceEstimate
import io.github.moronigranja.ayvu.tts.DownloadTransport
import io.github.moronigranja.ayvu.tts.OpenResult
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Open-bugs "Offline-audio usage row is stale on return to a live Settings
 * screen" (decisions #144): `SettingsViewModel` read `OfflineStorage
 * .usageByBook()` only in `init` and after a delete, so a settings instance
 * that stayed alive kept showing the tier as it was when the screen was first
 * created — a book pre-generated in between rendered as an empty row.
 *
 * The fix re-reads on `ON_RESUME` (SettingsScreen's `LifecycleEventEffect`);
 * this pins the ViewModel half: after [SettingsViewModel.refreshOfflineUsage]
 * the published rows are the disk tier as it is now. Nothing here tests the
 * lifecycle wiring — only the publish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOfflineUsageTest {
    @TempDir
    lateinit var tempDir: File

    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeSettingsDao : SettingsDao {
        private val rows = mutableMapOf<String, String>()

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

    /** Disk tier that changes between reads — what a pre-generation run does
     * behind a live settings screen. */
    private class FakeStorage(
        var usage: Map<String, Long> = emptyMap(),
    ) : OfflineStorage {
        override fun usageByBook(): Map<String, Long> = usage

        override suspend fun estimateAll(): Map<String, PregenSpaceEstimate> = emptyMap()

        override fun deleteBook(bookId: String) {
            usage = usage - bookId
        }
    }

    /** No packs are registered below, so the transport is never reached. */
    private object UnusedTransport : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = error("this harness never downloads")
    }

    /** The settings ViewModel with no packs and just the offline-audio seams. */
    private fun viewModel(
        library: InMemoryLibraryStore,
        storage: OfflineStorage,
    ): SettingsViewModel {
        val cache = PackCache(tempDir)
        val registry = PackRegistry(cache, PackDownloader(cache, UnusedTransport), emptyList())
        return SettingsViewModel(
            registry = registry,
            installer = PackInstaller(registry, PackStager { }),
            settings = AppSettings(SettingsStore(FakeSettingsDao())),
            filesDir = tempDir,
            repository = library,
            storage = storage,
        )
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a refresh publishes the disk tier as it is now`() =
        runTest(dispatcher) {
            val storage = FakeStorage(usage = mapOf("b1" to 512L))
            val library = InMemoryLibraryStore()
            library.add(LibraryEntry(Book(id = "b1", title = "Book one"), importedAtEpochMillis = 1L))
            val vm = viewModel(library, storage)

            // The open-time read publishes what the tier held then.
            assertEquals(
                listOf(SettingsViewModel.OfflineAudioRow("b1", "Book one", 512L)),
                vm.offlineRows.first { it.isNotEmpty() },
            )

            // A pre-generation run lands while this screen instance stays alive.
            storage.usage = mapOf("b1" to 3_456_789L)
            vm.refreshOfflineUsage() // the ON_RESUME trigger

            assertEquals(
                SettingsViewModel.OfflineAudioRow("b1", "Book one", 3_456_789L),
                vm.offlineRows.first { it.singleOrNull()?.bytes == 3_456_789L }.single(),
            )
        }
}
