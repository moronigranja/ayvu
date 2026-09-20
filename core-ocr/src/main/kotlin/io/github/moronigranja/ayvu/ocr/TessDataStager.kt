package io.github.moronigranja.ayvu.ocr

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * The OCR engine's data path (S1): `TessBaseAPI.init(dataPath, lang)` resolves
 * `<dataPath>/tessdata/<lang>.traineddata`, so [tesseractDataPath] is the
 * directory that CONTAINS the `tessdata/` subdirectory.
 *
 * The path is **versioned by pack tier and release**
 * (`files/tesseract/fast-4.1.0/tessdata/`): a re-pin changes the directory, so
 * the engine can never read a model from a previous generation. That is not
 * theoretical — 0.1.x staged legacy 3.04.00 models at
 * `files/tesseract/tessdata/`, and the OCR path is invoked with the user's
 * configured languages without a stagedness check (feature-share), so a
 * same-name legacy file would otherwise be served to a Tesseract 5 engine.
 * The retired directory and the retired engine's pack-cache artifacts
 * ([TrainedDataPacks.RETIRED_ENGINE_ID]) are reclaimed on the next staging run
 * — the only hook the app has that touches OCR storage.
 *
 * Packs download into the repository pack cache (`<root>/packs/tesseract/…`,
 * [PackCache] layout, decision #7); [TessDataStager] copies a verified
 * artifact into place so the engine's data path stays a plain, stable
 * directory — an accelerator-style copy, idempotent and cheap.
 *
 * A6: moved to core-ocr — pure File logic shared by the settings surface and
 * the Tesseract implementation without a feature-to-feature edge.
 */
object TessDataStager {
    /** Cancellation granularity of the staged copy (see [stage]). */
    private const val COPY_CHUNK_BYTES = 1 shl 20

    /** The base passed to the engine: contains `tessdata/<lang>.traineddata`. */
    fun tesseractDataPath(filesDir: File): File = File(filesDir, "tesseract/${TrainedDataPacks.TIER}-${TrainedDataPacks.RELEASE}")

    private fun stagedFile(
        filesDir: File,
        pack: TtsPack,
    ): File = File(File(tesseractDataPath(filesDir), "tessdata"), "${pack.id}.traineddata")

    /** The pre-LSTM generation's staging directory (`files/tesseract/tessdata/`). */
    private fun retiredDataPath(filesDir: File): File = File(filesDir, "tesseract/tessdata")

    fun isStaged(
        filesDir: File,
        pack: TtsPack,
    ): Boolean {
        val target = stagedFile(filesDir, pack)
        return target.isFile && target.length() == pack.sizeBytes
    }

    /** Copies the verified pack artifact into the engine's data path (idempotent).
     *  Suspends cooperatively: the copy is chunked with an [ensureActive] per MiB, so
     *  Stop cancels a model copy at a chunk boundary (the staged file is only
     *  promoted by the final rename). */
    suspend fun stage(
        filesDir: File,
        cache: PackCache,
        pack: TtsPack,
    ): Boolean {
        reclaimRetiredGeneration(filesDir, cache)
        if (isStaged(filesDir, pack)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false
        val dir = stagedFile(filesDir, pack).parentFile!!
        if (dir.isFile) dir.delete()
        dir.mkdirs()
        val target = stagedFile(filesDir, pack)
        val tmp = File(dir, "${pack.id}.tmp")
        source.inputStream().use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(COPY_CHUNK_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        return true
    }

    /**
     * Frees what the pre-LSTM generation (0.1.x, tess-two + legacy 3.04.00
     * models) left behind: its staging directory and its pack-cache
     * artifacts. Both are unreachable from the app once the engine id moved to
     * [TrainedDataPacks.ENGINE_ID] — no pack row, no uninstall action, and the
     * engine's data path no longer points at them. Idempotent and cheap (a
     * directory probe per staging run); no-op on a fresh install.
     */
    fun reclaimRetiredGeneration(
        filesDir: File,
        cache: PackCache,
    ) {
        retiredDataPath(filesDir).deleteRecursively()
        cache.directory(TrainedDataPacks.RETIRED_ENGINE_ID).deleteRecursively()
    }
}
