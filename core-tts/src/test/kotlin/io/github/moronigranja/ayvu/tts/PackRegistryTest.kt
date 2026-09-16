package io.github.moronigranja.ayvu.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PackRegistryTest {
    @TempDir
    lateinit var root: File

    private lateinit var cache: PackCache
    private lateinit var transport: FakeTransport
    private lateinit var registry: PackRegistry

    private val modelSource = Random(1).nextBytes(100_000)
    private val langSource = Random(2).nextBytes(50_000)
    private val modelPack =
        TtsPack(
            id = "test-model",
            engineId = "test",
            kind = PackKind.MODEL,
            displayName = "Test model",
            url = "https://example.test/packs/test-model.onnx",
            sha256Hex = sha256Hex(modelSource),
            sizeBytes = modelSource.size.toLong(),
        )
    private val langPack =
        TtsPack(
            id = "test-lang-en",
            engineId = "test",
            kind = PackKind.LANGUAGE,
            displayName = "English",
            url = "https://example.test/packs/test-lang-en.bin",
            sha256Hex = sha256Hex(langSource),
            sizeBytes = langSource.size.toLong(),
        )
    private val descriptor =
        EngineDescriptor(
            spec = EngineSpec("test", "Test Engine", EngineTier.FALLBACK, setOf("en")),
            packs = listOf(modelPack, langPack),
        )

    @BeforeEach
    fun setUp() {
        cache = PackCache(root)
        transport = FakeTransport()
        transport.source = modelSource
        registry = PackRegistry(cache, PackDownloader(cache, transport), listOf(descriptor))
    }

    private fun state(packId: String): PackStatus =
        registry.packs.value
            .first { it.pack.id == packId }
            .status

    // ------------------------------------------------------------------
    // Initial state
    // ------------------------------------------------------------------

    @Test
    fun `initial statuses are not downloaded and keep declaration order`() {
        assertEquals(
            listOf("test-model", "test-lang-en"),
            registry.packs.value.map { it.pack.id },
        )
        assertEquals(PackStatus.NotDownloaded, state("test-model"))
        assertEquals(PackStatus.NotDownloaded, state("test-lang-en"))
        assertEquals(listOf("test-model", "test-lang-en"), registry.packsFor("test").map { it.pack.id })
        assertEquals(emptyList<PackState>(), registry.packsFor("other"))
        assertFalse(registry.isReady("test-model"))
    }

    // ------------------------------------------------------------------
    // Download lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `download drives status through downloading to ready`() =
        runBlocking {
            var downloadingSeen: PackStatus? = null
            val outcome =
                registry.download("test-model") { _, _ ->
                    downloadingSeen = state("test-model")
                }

            assertEquals(DownloadOutcome.Ready, outcome)
            assertEquals(PackStatus.Ready, state("test-model"))
            assertTrue(downloadingSeen is PackStatus.Downloading, "progress must publish a Downloading status")
            assertTrue(registry.isReady("test-model"))
            assertEquals(PackStatus.NotDownloaded, state("test-lang-en"))
        }

    @Test
    fun `failed download surfaces a typed failure and a failed status`() =
        runBlocking {
            transport.mode = FakeTransport.Mode.HTTP_404

            val outcome = registry.download("test-model")

            assertEquals(DownloadOutcome.Failed(DownloadFailureReason.HttpStatus(404)), outcome)
            assertEquals(PackStatus.Failed(DownloadFailureReason.HttpStatus(404)), state("test-model"))
        }

    @Test
    fun `second download of a ready pack is cached with no extra transfer`() =
        runBlocking {
            assertEquals(DownloadOutcome.Ready, registry.download("test-model"))
            val callsAfterFirst = transport.calls.size

            assertEquals(DownloadOutcome.AlreadyCached, registry.download("test-model"))

            assertEquals(callsAfterFirst, transport.calls.size, "verified pack must not hit the network again")
            assertEquals(PackStatus.Ready, state("test-model"))
        }

    @Test
    fun `concurrent downloads of one pack coalesce into a single transfer`() =
        runBlocking {
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val first =
                async(Dispatchers.IO) {
                    registry.download("test-model") { _, _ ->
                        started.countDown()
                        gate.await() // hold the first transfer mid-stream
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // A dispatcher-less async inherits runBlocking's single thread and runs
            // its body eagerly: use a real dispatcher so the second call cannot trap
            // the main thread inside the constructor.
            val second = async(Dispatchers.Default) { registry.download("test-model") } // joins the running transfer
            gate.countDown()

            assertEquals(DownloadOutcome.Ready, first.await())
            assertEquals(DownloadOutcome.Ready, second.await())
            assertEquals(1, transport.calls.size, "two requests must share one transfer")
            assertEquals(PackStatus.Ready, state("test-model"))
        }

    @Test
    fun `cancellation returns to not-downloaded and keeps the partial for resume`() =
        runBlocking {
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val job =
                launch(Dispatchers.IO) {
                    registry.download("test-model") { _, _ ->
                        started.countDown()
                        gate.await()
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            gate.countDown()
            withTimeout(5_000) { job.join() }

            assertEquals(PackStatus.NotDownloaded, state("test-model"), "cancelled download must not stay Ready/Failed")
            assertTrue(cache.partialFile(modelPack).isFile)

            gate.countDown()
            assertEquals(DownloadOutcome.Ready, registry.download("test-model"))
        }

    @Test
    fun `refresh recomputes status from disk truth`() =
        runBlocking {
            // Place a full-size artifact by hand (e.g. off-device restore) — no marker yet.
            cache.targetFile(modelPack).parentFile?.mkdirs()
            cache.targetFile(modelPack).writeBytes(modelSource)

            registry.refresh()
            assertEquals(PackStatus.NotDownloaded, state("test-model"), "unverified bytes are not Ready")

            val outcome = registry.download("test-model")
            assertEquals(DownloadOutcome.AlreadyCached, outcome, "bytes already on disk: verify only, no network")
            assertTrue(transport.calls.isEmpty())
            assertEquals(PackStatus.Ready, state("test-model"))
        }

    @Test
    fun `unknown pack id fails fast`() =
        runBlocking {
            val e = runCatching { registry.download("nope") }.exceptionOrNull()
            assertTrue(e is IllegalStateException, "unknown pack must fail fast, got $e")
        }

    // ------------------------------------------------------------------
    // Composition validation
    // ------------------------------------------------------------------

    @Test
    fun `duplicate pack ids across engines are rejected`() {
        val other =
            EngineDescriptor(
                spec = EngineSpec("other", "Other", EngineTier.PRIMARY, setOf("fr")),
                packs = listOf(modelPack.copy(engineId = "other", id = "test-model")), // same id
            )
        assertThrows(IllegalArgumentException::class.java) {
            PackRegistry(cache, PackDownloader(cache, transport), listOf(descriptor, other))
        }
    }

    @Test
    fun `descriptor rejects packs that do not belong to its engine`() {
        val stray = modelPack.copy(engineId = "someone-else")
        assertThrows(IllegalArgumentException::class.java) {
            EngineDescriptor(
                spec = EngineSpec("test", "Test Engine", EngineTier.FALLBACK, setOf("en")),
                packs = listOf(stray),
            )
        }
    }

    @Test
    fun `engine spec rejects blank ids and empty language sets`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineSpec("", "x", EngineTier.PRIMARY, setOf("en"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineSpec("x", "x", EngineTier.PRIMARY, emptySet())
        }
    }
}
