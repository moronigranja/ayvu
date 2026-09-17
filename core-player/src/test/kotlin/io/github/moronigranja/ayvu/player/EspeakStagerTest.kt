package io.github.moronigranja.ayvu.player

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
 * B1: [EspeakStager.stage] is a cooperative suspend function — a Stop (job
 * cancellation) aborts the extract at an entry boundary, and because the
 * tmp→bundle swap only runs after a complete pass, the previous bundle
 * survives.
 */
class EspeakStagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `stage extracts a verified zip into the bundle`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val cache = verifyArtifact(tempDir, TTS_ZIP_ENTRIES)

        assertTrue(runBlocking { EspeakStager.stage(filesDir, cache.cache, cache.pack) })

        assertTrue(EspeakStager.isStaged(filesDir))
        assertTrue(EspeakStager.libFile(filesDir).isFile)
        assertTrue(EspeakStager.dataDir(filesDir).listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `a cancelled stage extracts nothing and leaves the previous bundle intact`() {
        val filesDir = File(tempDir, "files").also { it.mkdirs() }
        val previous = File(EspeakStager.bundleDir(filesDir), "old.txt")
        previous.parentFile!!.mkdirs()
        previous.writeText("previous bundle")
        val cache = verifyArtifact(tempDir, TTS_ZIP_ENTRIES)

        val cancelled = Job().apply { cancel() }
        val scope = CoroutineScope(Dispatchers.Unconfined + cancelled)
        val deferred =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                EspeakStager.stage(filesDir, cache.cache, cache.pack)
            }

        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertTrue(
            File(filesDir, "espeak-tmp").isDirectory,
            "the body must reach the extraction loop before the check fires",
        )
        assertFalse(EspeakStager.isStaged(filesDir), "no bundle was swapped in")
        assertTrue(previous.readText() == "previous bundle", "the previous bundle survives")
    }

    private class Verified(
        val pack: TtsPack,
        val cache: PackCache,
    )

    /** Writes [entries] as a zip into a [PackCache] that reports it verified. */
    private fun verifyArtifact(
        root: File,
        entries: Map<String, ByteArray>,
    ): Verified {
        val zip = File(root, "artifact.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        val pack =
            TtsPack(
                id = "espeak-ng",
                engineId = "kokoro",
                kind = PackKind.LANGUAGE,
                displayName = "espeak-ng",
                url = "https://example.com/espeak.zip",
                sha256Hex = "0".repeat(64),
                sizeBytes = zip.length(),
            )
        val cache = PackCache(root)
        cache.targetFile(pack).parentFile!!.mkdirs()
        zip.copyTo(cache.targetFile(pack), overwrite = true)
        cache.markerFile(pack).writeText("verified")
        check(cache.isVerified(pack)) { "fixture artifact must read as verified" }
        return Verified(pack, cache)
    }

    private companion object {
        val TTS_ZIP_ENTRIES =
            mapOf(
                "libespeak-ng.so" to ByteArray(64) { it.toByte() },
                "espeak-ng-data/phontab" to ByteArray(32),
            )
    }
}
