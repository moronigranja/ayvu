package io.github.moronigranja.ayvu.tts

import io.github.moronigranja.ayvu.ops.OperationChannel
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/**
 * B3: the shared install path — one progress stream per batch, staging after a
 * ready pack, and a recorded (flow-visible) failure when staging throws.
 */
class PackInstallerTest {
    @TempDir
    lateinit var root: File

    private lateinit var cache: PackCache
    private lateinit var transport: FakeTransport
    private lateinit var registry: PackRegistry

    private val source = Random(3).nextBytes(200_000)

    /** Two packs over the same artifact bytes: the fake transport serves one
     *  source, and the batch flow (base + companion) is what matters here. */
    private val modelPack =
        TtsPack(
            id = "test-model",
            engineId = "test",
            kind = PackKind.MODEL,
            displayName = "Test model",
            url = "https://example.test/packs/test-model.onnx",
            sha256Hex = sha256Hex(source),
            sizeBytes = source.size.toLong(),
        )
    private val voicePack =
        TtsPack(
            id = "test-voice",
            engineId = "test",
            kind = PackKind.VOICE,
            displayName = "Test voice",
            url = "https://example.test/packs/test-voice.bin",
            sha256Hex = sha256Hex(source),
            sizeBytes = source.size.toLong(),
            companionOf = "test-model",
        )

    @BeforeEach
    fun setUp() {
        cache = PackCache(root)
        transport = FakeTransport().apply { this.source = this@PackInstallerTest.source }
        registry =
            PackRegistry(
                cache,
                PackDownloader(cache, transport),
                listOf(
                    EngineDescriptor(
                        spec = EngineSpec("test", "Test Engine", EngineTier.FALLBACK, setOf("en")),
                        packs = listOf(modelPack, voicePack),
                    ),
                ),
            )
    }

    @Test
    fun `install reports rising percents ending at 100 and stages the ready pack once`() {
        val reports = mutableListOf<Pair<String, Int?>>()
        val staged = mutableListOf<String>()
        val installer = PackInstaller(registry) { staged += it }

        runBlocking {
            installer.install(listOf("test-model")) { text, percent -> reports += text to percent }
        }

        assertEquals(listOf("test-model"), staged)
        assertEquals("Test model" to 0, reports.first())
        val percents = reports.mapNotNull { it.second }
        assertTrue(percents.isNotEmpty(), "the transfer must report progress")
        assertEquals(percents.sorted(), percents, "progress must not go backwards")
        assertEquals(100, percents.last())
        assertEquals("Test model — unpacking…" to null, reports.last())
        assertEquals(
            PackStatus.Ready,
            registry.packs.value
                .single { it.pack.id == "test-model" }
                .status,
        )
        assertEquals(1, registry.stagedTick.value, "staging must publish the re-evaluation tick")
    }

    @Test
    fun `install stages every ready pack of the batch in order`() {
        val staged = mutableListOf<String>()
        val installer = PackInstaller(registry) { staged += it }

        runBlocking {
            installer.install(listOf("test-model", "test-voice"), OperationReporter { _, _ -> })
        }

        assertEquals(listOf("test-model", "test-voice"), staged)
    }

    @Test
    fun `a throwing stager records the failure and fails the batch`() {
        val installer = PackInstaller(registry) { error("disk full") }

        val failure =
            assertThrows(OperationFailed::class.java) {
                runBlocking { installer.install(listOf("test-model"), OperationReporter { _, _ -> }) }
            }

        assertEquals("1 pack(s) failed — see Settings", failure.message)
        assertEquals("unpacking failed: disk full", registry.installFailed.value["test-model"])
        assertEquals(0, registry.stagedTick.value, "a failed staging must not publish the tick")
        assertEquals(
            PackStatus.Ready,
            registry.packs.value
                .single { it.pack.id == "test-model" }
                .status,
            "the pack itself is done — the failure is the staging step",
        )
    }

    @Test
    fun `a cancelled staging propagates instead of reading as a failure`() {
        val installer = PackInstaller(registry) { throw CancellationException("Stop") }

        assertThrows(CancellationException::class.java) {
            runBlocking { installer.install(listOf("test-model"), OperationReporter { _, _ -> }) }
        }

        assertEquals(
            emptyMap<String, String>(),
            registry.installFailed.value,
            "Stop must not be recorded as an install failure",
        )
        assertEquals(0, registry.stagedTick.value)
    }

    @Test
    fun `a failed transfer is recorded and fails the batch`() {
        transport.mode = FakeTransport.Mode.HTTP_404
        val installer = PackInstaller(registry) { error("must not stage a failed download") }

        assertThrows(OperationFailed::class.java) {
            runBlocking { installer.install(listOf("test-model"), OperationReporter { _, _ -> }) }
        }

        assertEquals("HTTP 404", registry.installFailed.value["test-model"])
    }

    @Test
    fun `a fresh attempt clears the previous install failure`() {
        registry.recordInstallFailure("test-model", "unpacking failed: old")
        val installer = PackInstaller(registry) { }

        runBlocking { installer.install(listOf("test-model"), OperationReporter { _, _ -> }) }

        assertEquals(emptyMap<String, String>(), registry.installFailed.value)
    }

    @Test
    fun `installPacks runs the cancellable pack-download spec for the first id`() {
        val runner = RecordingRunner()
        val installer = PackInstaller(registry) { }

        runner.installPacks(installer, listOf("test-model", "test-voice"))

        val spec = runner.spec!!
        assertEquals("pack-download:test-model", spec.id)
        assertEquals(OperationChannel.DOWNLOADS, spec.channel)
        assertEquals("Ayvu — Test model", spec.title)
        assertEquals("Stop", spec.cancelLabel)
        assertTrue(runner.ran, "the body must run on the app-scope operation")
    }

    private class RecordingRunner : OperationRunner {
        var spec: OperationSpec? = null
        var ran = false

        override fun run(
            spec: OperationSpec,
            block: suspend (OperationReporter) -> Unit,
        ) {
            this.spec = spec
            ran = true
        }

        override fun cancel(id: String): Boolean = false
    }
}
