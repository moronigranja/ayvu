package com.moronigranja.localttsreader.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class PackCacheTest {
    @TempDir
    lateinit var root: File

    private lateinit var cache: PackCache
    private val source = Random(1).nextBytes(10_000)
    private val pack =
        TtsPack(
            id = "test-model",
            engineId = "test",
            kind = PackKind.MODEL,
            displayName = "Test model",
            url = "https://example.test/packs/test-model.onnx",
            sha256Hex = sha256Hex(source),
            sizeBytes = source.size.toLong(),
        )

    @BeforeEach
    fun setUp() {
        cache = PackCache(root)
    }

    @Test
    fun `layout groups by engine under the pack root`() {
        assertEquals(File(root, "packs/test"), cache.directory("test"))
        assertEquals(File(root, "packs/test/test-model"), cache.targetFile(pack))
        assertEquals(File(root, "packs/test/test-model.part"), cache.partialFile(pack))
        assertEquals(File(root, "packs/test/test-model.ready"), cache.markerFile(pack))
    }

    @Test
    fun `downloadedBytes prefers the verified target over a partial`() {
        assertFalse(cache.downloadedBytes(pack) > 0)

        cache.partialFile(pack).parentFile?.mkdirs()
        cache.partialFile(pack).writeBytes(source.copyOfRange(0, 100))
        assertEquals(100L, cache.downloadedBytes(pack))

        cache.targetFile(pack).writeBytes(source)
        assertEquals(source.size.toLong(), cache.downloadedBytes(pack), "target wins over partial")
    }

    @Test
    fun `isComplete requires the exact size`() {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(source.copyOfRange(0, 100))
        assertFalse(cache.isComplete(pack))

        cache.targetFile(pack).writeBytes(source)
        assertTrue(cache.isComplete(pack))
    }

    @Test
    fun `isVerified requires both the marker and a complete artifact`() {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(source)
        assertFalse(cache.isVerified(pack), "no marker yet")

        cache.markerFile(pack).writeText("verified\n")
        assertTrue(cache.isVerified(pack))

        cache.targetFile(pack).delete()
        assertFalse(cache.isVerified(pack), "marker without artifact is not verified")
    }

    @Test
    fun `verifyAndMark writes the marker only on a hash match`() {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(Random(9).nextBytes(source.size))
        assertFalse(cache.verifyAndMark(pack), "wrong content must not verify")
        assertFalse(cache.markerFile(pack).exists())

        cache.targetFile(pack).writeBytes(source)
        assertTrue(cache.verifyAndMark(pack))
        assertTrue(cache.markerFile(pack).readText().startsWith("verified:"))
    }

    @Test
    fun `promote moves the verified partial and marks it`() {
        cache.partialFile(pack).parentFile?.mkdirs()
        cache.partialFile(pack).writeBytes(source)
        assertTrue(cache.verifyPending(pack))

        cache.promote(pack)

        assertFalse(cache.partialFile(pack).exists())
        assertTrue(cache.targetFile(pack).readBytes().contentEquals(source))
        assertTrue(cache.markerFile(pack).exists())
    }

    @Test
    fun `deleteArtifacts removes every file for the pack`() {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(source)
        cache.partialFile(pack).writeBytes(source)
        cache.markerFile(pack).writeText("verified\n")

        cache.deleteArtifacts(pack)

        assertFalse(cache.targetFile(pack).exists())
        assertFalse(cache.partialFile(pack).exists())
        assertFalse(cache.markerFile(pack).exists())
    }

    @Test
    fun `matchesDescriptor is case-insensitive on hex`() {
        cache.targetFile(pack).parentFile?.mkdirs()
        cache.targetFile(pack).writeBytes(source)
        assertTrue(cache.matchesDescriptor(cache.targetFile(pack), pack))
        val uppercase = pack.copy(sha256Hex = pack.sha256Hex.uppercase())
        assertTrue(cache.matchesDescriptor(cache.targetFile(pack), uppercase))
    }
}
