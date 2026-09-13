package com.moronigranja.localttsreader.featuresettings

import com.moronigranja.localttsreader.ocr.TrainedDataPacks
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.DownloadTransport
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.OpenResult
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * K2 (decisions #156): the Settings Speech section's pack rows derive from
 * the registered engines' [EngineDescriptor]s — no hardcoded id lists — so
 * piper-v1's rows appear with no settings-surface edit, and the degraded
 * system voice shows the open-weight upgrade path. The OCR subpane derives
 * its language rows from the tess-two descriptor the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPackRowsTest {
    @TempDir
    lateinit var tempDir: File

    private val dispatcher = UnconfinedTestDispatcher()

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
    }

    /** No downloads are triggered by constructing the registry. */
    private object UnusedTransport : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = error("this harness never downloads")
    }

    /** The real engine descriptors (kokoro + piper + gated CosyVoice3) plus
     * the tess-two OCR language packs — the exact registry shape the app
     * wires in PackModule. */
    private fun viewModel(dao: FakeSettingsDao): SettingsViewModel =
        SettingsViewModel(
            registry = PackRegistry(
                PackCache(tempDir),
                PackDownloader(PackCache(tempDir), UnusedTransport),
                DefaultEngines.descriptors +
                    listOf(EngineDescriptor(TrainedDataPacks.spec, TrainedDataPacks.all)),
            ),
            cache = PackCache(tempDir),
            settings = AppSettings(SettingsStore(dao)),
            voiceCatalog = VoiceCatalog(PackCache(tempDir)),
            filesDir = tempDir,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pack rows follow the selected engine's descriptor with no settings edit`() =
        runTest(dispatcher) {
            val vm = viewModel(FakeSettingsDao())
            // Subscribe like the screen does — WhileSubscribed only runs on demand.
            backgroundScope.launch { vm.state.collect {} }

            vm.setEngine(SettingsStore.DEFAULT_TTS_ENGINE)
            assertEquals(
                setOf("kokoro-model", "kokoro-voices", "espeak-ng"),
                vm.state.first { it.ttsEngine == SettingsStore.DEFAULT_TTS_ENGINE }.speechPackIds,
            )

            // Selecting piper-v1 surfaces its voice rows — derived from its
            // registered descriptor, nothing edited on this surface. The
            // per-voice `.onnx.json` companions are NOT rows: they download
            // with their model, so one voice is one row, not two.
            vm.setEngine(SettingsStore.PIPER_ENGINE)
            assertEquals(
                setOf("piper-lessac-medium", "piper-thorsten-high", "espeak-ng"),
                vm.state.first { it.ttsEngine == SettingsStore.PIPER_ENGINE }.speechPackIds,
            )

            // The degraded system voice registers no packs: its rows are the
            // open-weight upgrade path (the same set the plan card offers).
            vm.setEngine(SettingsStore.SYSTEM_TTS_ENGINE)
            assertEquals(
                setOf("kokoro-model", "kokoro-voices", "espeak-ng"),
                vm.state.first { it.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE }.speechPackIds,
            )
        }

    @Test
    fun `ocr rows derive from the tess-two descriptor`() =
        runTest(dispatcher) {
            val vm = viewModel(FakeSettingsDao())
            backgroundScope.launch { vm.state.collect {} }

            assertEquals(
                setOf("eng", "spa", "fra", "deu", "por", "ita"),
                vm.state.first { it.packs.isNotEmpty() }.packs
                    .filter { it.engineId == TESS_ENGINE_ID }
                    .map { it.packId }
                    .toSet(),
            )
        }
}
