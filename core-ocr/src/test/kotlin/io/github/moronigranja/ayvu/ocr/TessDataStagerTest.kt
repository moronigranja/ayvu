package io.github.moronigranja.ayvu.ocr

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * B1: [TessDataStager.stage]'s copy is chunked with an `ensureActive` per MiB,
 * so a cancelled copy stops at a chunk boundary and never promotes a partial
 * `.traineddata` over the staged one.
 */
class TessDataStagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `stage copies the verified artifact into the tessdata path`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val verified = verifyArtifact(tempDir, MODEL_BYTES)

        assertTrue(runBlocking { TessDataStager.stage(filesDir, verified.cache, verified.pack) })

        val staged = stagedFile(filesDir, verified.pack)
        assertEquals(verified.pack.sizeBytes, staged.length())
        assertTrue(TessDataStager.isStaged(filesDir, verified.pack))
        assertFalse(File(staged.parentFile!!, "${verified.pack.id}.tmp").exists())
    }

    @Test
    fun `a cancelled copy leaves no staged file and keeps the previous model`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val verified = verifyArtifact(tempDir, MODEL_BYTES)
        val previous = stagedFile(filesDir, verified.pack)
        previous.parentFile!!.mkdirs()
        previous.writeText("previous model")

        val cancelled = Job().apply { cancel() }
        val scope = CoroutineScope(Dispatchers.Unconfined + cancelled)
        val deferred =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                TessDataStager.stage(filesDir, verified.cache, verified.pack)
            }

        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertTrue(
            File(previous.parentFile, "${verified.pack.id}.tmp").isFile,
            "the body must reach the copy loop before the check fires",
        )
        assertEquals("previous model", previous.readText())
    }

    private fun stagedFile(
        filesDir: File,
        pack: TtsPack,
    ): File = File(File(TessDataStager.tesseractDataPath(filesDir), "tessdata"), "${pack.id}.traineddata")

    private class Verified(
        val pack: TtsPack,
        val cache: PackCache,
    )

    private fun verifyArtifact(
        root: File,
        bytes: ByteArray,
    ): Verified {
        val artifact = File(root, "eng.traineddata")
        artifact.writeBytes(bytes)
        val pack =
            TtsPack(
                id = "eng",
                engineId = "tess-two",
                kind = PackKind.LANGUAGE,
                displayName = "English",
                url = "https://example.com/eng.traineddata",
                sha256Hex = "2".repeat(64),
                sizeBytes = artifact.length(),
            )
        val cache = PackCache(root)
        cache.targetFile(pack).parentFile!!.mkdirs()
        artifact.copyTo(cache.targetFile(pack), overwrite = true)
        cache.markerFile(pack).writeText("verified")
        check(cache.isVerified(pack)) { "fixture artifact must read as verified" }
        return Verified(pack, cache)
    }

    private companion object {
        /** Two chunks, so the copy loop iterates more than once. */
        val MODEL_BYTES = ByteArray((2 shl 20) + 4096) { it.toByte() }
    }
}
