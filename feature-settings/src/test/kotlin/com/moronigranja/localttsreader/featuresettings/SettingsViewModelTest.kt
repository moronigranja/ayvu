package com.moronigranja.localttsreader.featuresettings

import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.tts.DownloadTransport
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.HttpBody
import com.moronigranja.localttsreader.tts.OpenResult
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackKind
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.tts.TtsPack
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * V2 regression pair for the settings screen (S-debug findings, 2026-08-26):
 * - theme radio: setTheme must be observed by state IMMEDIATELY (push-based;
 *   a 2 s poll was the pre-fix behavior the user saw as a stale radio).
 * - download voices: the progress-update path must not recurse infinitely
 *   (the deleted Map operator extensions stack-overflowed the app on the S22)
 *   and must clear progress + surface typed errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
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

    private class FakeTransport(
        private val body: ByteArray,
    ) : DownloadTransport {
        override suspend fun open(
            url: String,
            rangeFrom: Long?,
        ): OpenResult = OpenResult.Body(HttpBody(200, body.size.toLong(), ByteArrayInputStream(body)))
    }

    /** One pack, pinned to [pinnedBytes]; the transport serves [servedBytes] (same unless corrupt). */
    private fun harness(
        dao: FakeSettingsDao = FakeSettingsDao(),
        pinnedBytes: ByteArray,
        servedBytes: ByteArray? = null,
    ): PackRegistry {
        val pack =
            TtsPack(
                id = "test-pack",
                engineId = "test-engine",
                kind = PackKind.MODEL,
                displayName = "Test pack",
                url = "https://example.invalid/test.bin",
                sha256Hex =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(pinnedBytes)
                        .joinToString("") { "%02x".format(it) },
                sizeBytes = pinnedBytes.size.toLong(),
            )
        val cache = PackCache(tempDir)
        val downloader = PackDownloader(cache, FakeTransport(servedBytes ?: pinnedBytes))
        val spec = EngineSpec("test-engine", "Test", EngineTier.PRIMARY, setOf("en"))
        return PackRegistry(cache, downloader, listOf(EngineDescriptor(spec, listOf(pack))))
    }

    private fun viewModel(
        registry: PackRegistry,
        dao: FakeSettingsDao,
    ): SettingsViewModel =
        SettingsViewModel(
            registry = registry,
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
    fun `setTheme is observed by the state immediately`() =
        runTest(dispatcher) {
            val dao = FakeSettingsDao()
            val vm = viewModel(harness(dao, pinnedBytes = byteArrayOf(1, 2, 3)), dao)
            // Subscribe like the screen does — WhileSubscribed only runs on demand.
            backgroundScope.launch { vm.state.collect {} }
            assertEquals(ThemeMode.SYSTEM, vm.state.value.themeMode)

            vm.setTheme(ThemeMode.DARK)

            assertEquals(ThemeMode.DARK, vm.state.value.themeMode, "no poll: state reflects immediately")
        }

    @Test
    fun `setTtsThreads is observed by the state immediately`() {
        runTest(dispatcher) {
            val dao = FakeSettingsDao()
            val vm = viewModel(harness(dao, pinnedBytes = byteArrayOf(1, 2, 3)), dao)
            backgroundScope.launch { vm.state.collect {} }
            assertEquals(SettingsStore.DEFAULT_TTS_THREADS, vm.state.value.ttsThreads)

            vm.setTtsThreads(2)

            assertEquals(2, vm.state.value.ttsThreads)
            assertEquals("2", dao.rows[SettingsStore.KEY_TTS_THREADS])
        }
    }

    @Test
    fun `download completes, clears progress and never recurses`() =
        runTest(dispatcher) {
            val dao = FakeSettingsDao()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val vm = viewModel(harness(dao, pinnedBytes = bytes), dao)
            backgroundScope.launch { vm.state.collect {} }

            vm.download("test-pack")

            // The download hop to Dispatchers.IO real threads: wait for the flow
            // to settle instead of racing the assertion.
            vm.state.first { it.packs.any { p -> p.packId == "test-pack" && p.status !is PackStatus.Downloading } }
            val row =
                vm.state.value.packs
                    .first { it.packId == "test-pack" }
            assertEquals(PackStatus.Ready, row.status)
            assertNull(row.progress, "progress cleared on completion")
            assertNull(row.error)
        }

    @Test
    fun `a corrupt download surfaces a typed error without recursion`() =
        runTest(dispatcher) {
            val dao = FakeSettingsDao()
            val expected = ByteArray(4096) { (it % 251).toByte() }
            val served = ByteArray(4096) { 0x41 } // transport lies vs the pin
            val vm = viewModel(harness(dao, pinnedBytes = expected, servedBytes = served), dao)
            backgroundScope.launch { vm.state.collect {} }

            vm.download("test-pack")

            vm.state.first { it.packs.any { p -> p.packId == "test-pack" && p.status !is PackStatus.Downloading } }
            val row =
                vm.state.value.packs
                    .first { it.packId == "test-pack" }
            assertTrue(row.status is PackStatus.Failed)
            assertEquals("checksum mismatch", row.error)
        }

    @Test
    fun `downloading a base pack also fetches its companion`() =
        runTest(dispatcher) {
            val dao = FakeSettingsDao()
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val base =
                TtsPack(
                    id = "test-model",
                    engineId = "test-engine",
                    kind = PackKind.MODEL,
                    displayName = "Test model",
                    url = "https://example.invalid/test-model.onnx",
                    sha256Hex = digest,
                    sizeBytes = bytes.size.toLong(),
                )
            val config =
                TtsPack(
                    id = "test-model-config",
                    engineId = "test-engine",
                    kind = PackKind.VOICE,
                    displayName = "Test model config",
                    url = "https://example.invalid/test-model.onnx.json",
                    sha256Hex = digest,
                    sizeBytes = bytes.size.toLong(),
                    companionOf = base.id,
                )
            val cache = PackCache(tempDir)
            val registry =
                PackRegistry(
                    cache,
                    PackDownloader(cache, FakeTransport(bytes)),
                    listOf(
                        EngineDescriptor(
                            EngineSpec("test-engine", "Test", EngineTier.PRIMARY, setOf("en")),
                            listOf(base, config),
                        ),
                    ),
                )
            val vm = viewModel(registry, dao)
            backgroundScope.launch { vm.state.collect {} }

            vm.download("test-model")

            // A partial fetch (model without its config) leaves the voice
            // unusable — the base row must bring the companion with it. Wait
            // like the sibling download tests: the IO-hop resumption lands in
            // real time, so a withTimeout would race the virtual test clock.
            vm.state.first { s -> s.packs.all { it.status == PackStatus.Ready } }
            val rows =
                vm.state.value.packs
                    .associateBy { it.packId }
            assertEquals(PackStatus.Ready, rows.getValue("test-model").status)
            assertEquals(PackStatus.Ready, rows.getValue("test-model-config").status)
        }
}
