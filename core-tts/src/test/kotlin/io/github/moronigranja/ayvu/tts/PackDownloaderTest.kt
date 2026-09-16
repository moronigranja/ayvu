package io.github.moronigranja.ayvu.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PackDownloaderTest {
    @TempDir
    lateinit var root: File

    private lateinit var cache: PackCache
    private lateinit var transport: FakeTransport
    private lateinit var downloader: PackDownloader

    @BeforeEach
    fun setUp() {
        cache = PackCache(root)
        transport = FakeTransport()
        downloader = PackDownloader(cache, transport)
    }

    private fun bytes(
        size: Int,
        seed: Long = 42L,
    ): ByteArray = Random(seed).nextBytes(size)

    private fun packFor(
        source: ByteArray,
        id: String = "test-model",
    ): TtsPack =
        TtsPack(
            id = id,
            engineId = "test",
            kind = PackKind.MODEL,
            displayName = "Test model",
            url = "https://example.test/packs/$id.onnx",
            sha256Hex = sha256Hex(source),
            sizeBytes = source.size.toLong(),
        )

    private fun writePartial(
        pack: TtsPack,
        content: ByteArray,
    ) {
        cache.partialFile(pack).parentFile?.mkdirs()
        cache.partialFile(pack).writeBytes(content)
    }

    private fun writeTarget(
        pack: TtsPack,
        content: ByteArray,
    ) {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(content)
    }

    // ------------------------------------------------------------------
    // Fresh download
    // ------------------------------------------------------------------

    @Test
    fun `fresh download verifies promotes and reports progress`() =
        runBlocking {
            val source = bytes(200_000, seed = 1)
            val pack = packFor(source)
            transport.source = source

            val progress = mutableListOf<Long>()
            val outcome = downloader.download(pack) { downloaded, _ -> progress += downloaded }

            assertEquals(DownloadOutcome.Ready, outcome)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
            assertTrue(cache.markerFile(pack).exists(), "verified marker must be written")
            assertFalse(cache.partialFile(pack).exists(), "partial must be promoted away")
            assertEquals(listOf(pack.url to null), transport.calls.toList())
            assertEquals(source.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b > a }, "progress must be monotonic")
        }

    // ------------------------------------------------------------------
    // Cached paths (no network)
    // ------------------------------------------------------------------

    @Test
    fun `verified pack short circuits without network`() =
        runBlocking {
            val source = bytes(1000)
            val pack = packFor(source)
            writeTarget(pack, source)
            cache.verifyAndMark(pack)

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.AlreadyCached, outcome)
            assertTrue(transport.calls.isEmpty(), "no network for a verified pack")
        }

    @Test
    fun `complete unverified artifact is hashed once and marked without network`() =
        runBlocking {
            val source = bytes(1000)
            val pack = packFor(source)
            writeTarget(pack, source)

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.AlreadyCached, outcome)
            assertTrue(transport.calls.isEmpty(), "bytes were already on disk")
            assertTrue(cache.markerFile(pack).exists())
            assertEquals(DownloadOutcome.AlreadyCached, downloader.download(pack))
            assertTrue(transport.calls.isEmpty(), "second call must still be cached")
        }

    @Test
    fun `corrupt full-size artifact is deleted and fails typed`() =
        runBlocking {
            val source = bytes(1000)
            val pack = packFor(source)
            writeTarget(pack, bytes(1000, seed = 99)) // same size, wrong content

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.Failed(DownloadFailureReason.CorruptContent), outcome)
            assertFalse(cache.targetFile(pack).exists(), "corrupt artifact must not stay cached")
        }

    @Test
    fun `fully downloaded partial promotes without network`() =
        runBlocking {
            val source = bytes(1000)
            val pack = packFor(source)
            writePartial(pack, source)

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.AlreadyCached, outcome)
            assertTrue(transport.calls.isEmpty())
            assertTrue(cache.targetFile(pack).exists())
            assertFalse(cache.partialFile(pack).exists())
            assertTrue(cache.markerFile(pack).exists())
        }

    // ------------------------------------------------------------------
    // Resume
    // ------------------------------------------------------------------

    @Test
    fun `resume requests the range and appends`() =
        runBlocking {
            val source = bytes(200_000, seed = 1)
            val pack = packFor(source)
            transport.source = source
            writePartial(pack, source.copyOfRange(0, 80_000))

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.Ready, outcome)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
            assertEquals(listOf(pack.url to 80_000L), transport.calls.toList(), "resume must re-request from the partial size")
        }

    @Test
    fun `server ignoring range restarts cleanly and overwrites the partial`() =
        runBlocking {
            val source = bytes(200_000, seed = 1)
            val pack = packFor(source)
            transport.source = source
            transport.mode = FakeTransport.Mode.IGNORES_RANGE
            writePartial(pack, bytes(80_000, seed = 7)) // stale garbage partial

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.Ready, outcome)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source), "200 response must overwrite, not append")
            assertFalse(cache.partialFile(pack).exists())
        }

    @Test
    fun `early stream termination keeps the partial for a later resume`() =
        runBlocking {
            val source = bytes(200_000, seed = 1)
            val pack = packFor(source)
            transport.source = source
            transport.truncateAt = 50_000L

            val first = downloader.download(pack)
            assertEquals(DownloadOutcome.Failed(DownloadFailureReason.Incomplete(50_000, 200_000)), first)
            assertTrue(cache.partialFile(pack).isFile && cache.partialFile(pack).length() == 50_000L)

            transport.truncateAt = null
            val second = downloader.download(pack)
            assertEquals(DownloadOutcome.Ready, second)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
            assertEquals(listOf(pack.url to null, pack.url to 50_000L), transport.calls.toList())
        }

    @Test
    fun `oversized garbage partial is discarded before downloading`() =
        runBlocking {
            val source = bytes(1000, seed = 1)
            val pack = packFor(source)
            transport.source = source
            writePartial(pack, bytes(5000, seed = 3)) // bigger than sizeBytes

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.Ready, outcome)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
        }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    @Test
    fun `http error maps to typed failure`() =
        runBlocking {
            val source = bytes(1000)
            transport.source = source
            transport.mode = FakeTransport.Mode.HTTP_404

            assertEquals(
                DownloadOutcome.Failed(DownloadFailureReason.HttpStatus(404)),
                downloader.download(packFor(source)),
            )
            transport.mode = FakeTransport.Mode.HTTP_500
            assertEquals(
                DownloadOutcome.Failed(DownloadFailureReason.HttpStatus(500)),
                downloader.download(packFor(source)),
            )
        }

    @Test
    fun `connection failure maps to typed io error`() =
        runBlocking {
            val pack = packFor(bytes(1000))
            transport.failOpenWith = IOException("no route to host")

            assertEquals(
                DownloadOutcome.Failed(DownloadFailureReason.IoError("no route to host")),
                downloader.download(pack),
            )
        }

    @Test
    fun `stream content mismatching the descriptor fails and deletes the partial`() =
        runBlocking {
            val served = bytes(1000, seed = 5)
            val pack = packFor(bytes(1000, seed = 6)) // descriptor pins different content
            transport.source = served

            val outcome = downloader.download(pack)

            assertEquals(DownloadOutcome.Failed(DownloadFailureReason.CorruptContent), outcome)
            assertFalse(cache.partialFile(pack).exists(), "corrupt partial must not stay cached")
            assertFalse(cache.targetFile(pack).exists())
            assertFalse(cache.markerFile(pack).exists())
        }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    fun `cancellation aborts retains the partial and resume completes`() =
        runBlocking {
            val source = bytes(1_000_000, seed = 1)
            val pack = packFor(source)
            transport.source = source

            val started = CountDownLatch(1)
            val progressGate = CountDownLatch(1)
            val job =
                launch(Dispatchers.IO) {
                    downloader.download(pack) { _, _ ->
                        started.countDown()
                        progressGate.await() // hold the loop open until the test decides
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            progressGate.countDown()
            withTimeout(5_000) { job.join() }

            val partial = cache.partialFile(pack)
            assertTrue(partial.isFile, "cancelled download must keep its partial")
            assertTrue(partial.length() in 1 until pack.sizeBytes, "partial size ${partial.length()} must be mid-stream")
            assertFalse(cache.targetFile(pack).exists())
            assertFalse(cache.markerFile(pack).exists())
            val partialSize = partial.length() // promotion renames it away; keep the number

            progressGate.countDown() // unblock any late progress callback
            val resumed = downloader.download(pack)
            assertEquals(DownloadOutcome.Ready, resumed)
            assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
            assertEquals(2, transport.calls.size, "resume must be one additional transfer")
            assertEquals(partialSize, transport.calls.last().second, "resume must start at the retained partial")
        }

    // ------------------------------------------------------------------
    // Descriptor validation
    // ------------------------------------------------------------------

    @Test
    fun `invalid http url is rejected by the descriptor`() {
        val source = bytes(10)
        val e =
            runCatching {
                TtsPack(
                    id = "bad",
                    engineId = "test",
                    kind = PackKind.MODEL,
                    displayName = "Bad",
                    url = "http://plain-http.example/x",
                    sha256Hex = sha256Hex(source),
                    sizeBytes = source.size.toLong(),
                )
            }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "http (non-https) pack URL must be rejected, got $e")
    }

    @Test
    fun `descriptor rejects a non-hex sha256`() {
        val source = bytes(10)
        val e =
            runCatching {
                TtsPack(
                    id = "bad",
                    engineId = "test",
                    kind = PackKind.MODEL,
                    displayName = "Bad",
                    url = "https://example.test/x",
                    sha256Hex = "zz".repeat(32),
                    sizeBytes = source.size.toLong(),
                )
            }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "non-hex sha must be rejected, got $e")
    }
}
