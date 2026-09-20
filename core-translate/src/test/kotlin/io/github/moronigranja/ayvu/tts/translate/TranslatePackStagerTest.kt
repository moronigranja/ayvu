package io.github.moronigranja.ayvu.tts.translate

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * B1: [TranslatePackStager.stage] is a cooperative suspend function — Stop
 * aborts the (730 MB–1.7 GB in production) extract at an entry boundary and the
 * previous bundle survives, because the swap runs only after a complete pass.
 *
 * Decisions #182: staging is per ENGINE — each option owns a bundle root and a
 * GGUF file name, and readiness is asked for a specific engine (the runtime
 * opens the selected one).
 */
class TranslatePackStagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `stage extracts the gguf into the engine's bundle`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val verified = verifyArtifact(tempDir, TranslatePacks.shipped)

        assertTrue(runBlocking { TranslatePackStager.stage(filesDir, verified.cache, verified.pack) })
        assertTrue(TranslatePackStager.isStaged(filesDir, TranslatePacks.shipped))
        assertTrue(TranslatePackStager.modelFile(filesDir, TranslatePacks.shipped).isFile)
    }

    @Test
    fun `each engine stages into its own root and is ready on its own`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val shipped = verifyArtifact(tempDir, TranslatePacks.shipped)
        val better = verifyArtifact(tempDir, TranslatePacks.better)

        assertNotEquals(
            TranslatePackStager.bundleDir(filesDir, TranslatePacks.shipped),
            TranslatePackStager.bundleDir(filesDir, TranslatePacks.better),
        )
        assertTrue(runBlocking { TranslatePackStager.stage(filesDir, shipped.cache, shipped.pack) })
        assertTrue(TranslatePackStager.isStaged(filesDir, TranslatePacks.shipped))
        assertFalse(TranslatePackStager.isStaged(filesDir, TranslatePacks.better))

        assertTrue(runBlocking { TranslatePackStager.stage(filesDir, better.cache, better.pack) })
        assertTrue(TranslatePackStager.isStaged(filesDir, TranslatePacks.better))
        assertTrue(
            TranslatePackStager.isStaged(filesDir, TranslatePacks.shipped),
            "staging the second engine must not disturb the first bundle",
        )
    }

    @Test
    fun `a stored engine id selects the engine, unknown ids the default`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        assertFalse(TranslatePackStager.isStaged(filesDir, TranslatePacks.better.id))
        assertEquals(
            TranslatePackStager.isStaged(filesDir, TranslatePacks.shipped),
            TranslatePackStager.isStaged(filesDir, "no-such-engine"),
        )
    }

    @Test
    fun `a cancelled stage extracts nothing and leaves the previous bundle intact`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val previous = File(TranslatePackStager.bundleDir(filesDir, TranslatePacks.shipped), "old.gguf")
        previous.parentFile!!.mkdirs()
        previous.writeText("previous bundle")
        val verified = verifyArtifact(tempDir, TranslatePacks.shipped)

        val cancelled = Job().apply { cancel() }
        val scope = CoroutineScope(Dispatchers.Unconfined + cancelled)
        val deferred =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                TranslatePackStager.stage(filesDir, verified.cache, verified.pack)
            }

        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertTrue(
            File(filesDir, "${TranslatePacks.shipped.bundleDirName}-tmp").isDirectory,
            "the body must reach the extraction loop before the check fires",
        )
        assertFalse(TranslatePackStager.isStaged(filesDir, TranslatePacks.shipped))
        assertTrue(previous.readText() == "previous bundle", "the previous bundle survives")
    }

    private class Verified(
        val pack: TtsPack,
        val cache: PackCache,
    )

    /**
     * Writes a three-entry zip into a [PackCache] that reports it verified. The
     * pack keeps the real engine's identity (id + engineId, so the stager can
     * resolve the engine) and takes the fixture's length, which is what
     * [PackCache.isVerified] checks against the `.ready` marker.
     */
    private fun verifyArtifact(
        root: File,
        engine: TranslateEngine,
    ): Verified {
        val zip = File(root, engine.pack.id)
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            listOf(engine.modelFileName, "LICENSE", "SOURCE-OFFER.txt").forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(ByteArray(256) { it.toByte() })
                out.closeEntry()
            }
        }
        val pack = engine.pack.copy(url = "https://example.com/${engine.pack.id}.zip", sizeBytes = zip.length())
        val cache = PackCache(root)
        cache.targetFile(pack).parentFile!!.mkdirs()
        zip.copyTo(cache.targetFile(pack), overwrite = true)
        cache.markerFile(pack).writeText("verified")
        check(cache.isVerified(pack)) { "fixture artifact must read as verified" }
        return Verified(pack, cache)
    }
}
