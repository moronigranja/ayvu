package io.github.moronigranja.ayvu.tts.translate

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackKind
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * B1: [TranslatePackStager.stage] is a cooperative suspend function — Stop
 * aborts the (730 MB in production) extract at an entry boundary and the
 * previous bundle survives, because the swap runs only after a complete pass.
 */
class TranslatePackStagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `stage extracts the gguf into the bundle`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val verified = verifyArtifact(tempDir)

        assertTrue(runBlocking { TranslatePackStager.stage(filesDir, verified.cache, verified.pack) })
        assertTrue(TranslatePackStager.isStaged(filesDir))
        assertTrue(File(TranslatePackStager.bundleDir(filesDir), TranslatePackStager.MODEL_FILE).isFile)
    }

    @Test
    fun `a cancelled stage extracts nothing and leaves the previous bundle intact`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val previous = File(TranslatePackStager.bundleDir(filesDir), "old.gguf")
        previous.parentFile!!.mkdirs()
        previous.writeText("previous bundle")
        val verified = verifyArtifact(tempDir)

        val cancelled = Job().apply { cancel() }
        val scope = CoroutineScope(Dispatchers.Unconfined + cancelled)
        val deferred =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                TranslatePackStager.stage(filesDir, verified.cache, verified.pack)
            }

        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertTrue(
            File(filesDir, "translate-lfm-tmp").isDirectory,
            "the body must reach the extraction loop before the check fires",
        )
        assertFalse(TranslatePackStager.isStaged(filesDir))
        assertTrue(previous.readText() == "previous bundle", "the previous bundle survives")
    }

    private class Verified(
        val pack: TtsPack,
        val cache: PackCache,
    )

    /** Writes a three-entry zip into a [PackCache] that reports it verified. */
    private fun verifyArtifact(root: File): Verified {
        val zip = File(root, "translate-lfm12b-q4-v1")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            listOf("LFM2.5-1.2B-Instruct-Q4_K_M.gguf", "README.md", "config.json").forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(ByteArray(256) { it.toByte() })
                out.closeEntry()
            }
        }
        val pack =
            TtsPack(
                id = "translate-lfm12b",
                engineId = TranslatePacks.PACK_ENGINE_ID,
                kind = PackKind.MODEL,
                displayName = "Translate pack",
                url = "https://example.com/translate.zip",
                sha256Hex = "1".repeat(64),
                sizeBytes = zip.length(),
            )
        val cache = PackCache(root)
        cache.targetFile(pack).parentFile!!.mkdirs()
        zip.copyTo(cache.targetFile(pack), overwrite = true)
        cache.markerFile(pack).writeText("verified")
        check(cache.isVerified(pack)) { "fixture artifact must read as verified" }
        return Verified(pack, cache)
    }
}
