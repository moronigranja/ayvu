package io.github.moronigranja.ayvu.ocr

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TtsPack
import java.io.File

/**
 * The tess-two data path (S1): `TessBaseAPI.init(dataPath, lang)` resolves
 * `<dataPath>/tessdata/<lang>.traineddata`, so [tesseractDataPath] is the
 * directory that CONTAINS the `tessdata/` subdirectory (under files dir:
 * `files/tesseract/`, staged models at `files/tesseract/tessdata/`).
 *
 * Packs download into the repository pack cache (`<root>/packs/tess-two/…`,
 * [PackCache] layout, decision #7); [TessDataStager] copies a verified
 * artifact into place so the engine's data path stays a plain, stable
 * directory — an accelerator-style copy, idempotent and cheap.
 *
 * A6: moved to core-ocr — pure File logic shared by the settings surface and
 * the tess-two implementation without a feature-to-feature edge.
 */
object TessDataStager {
    /** The base passed to the engine: contains `tessdata/<lang>.traineddata`. */
    fun tesseractDataPath(filesDir: File): File = File(filesDir, "tesseract")

    private fun stagedFile(
        filesDir: File,
        pack: TtsPack,
    ): File = File(File(tesseractDataPath(filesDir), "tessdata"), "${pack.id}.traineddata")

    fun isStaged(
        filesDir: File,
        pack: TtsPack,
    ): Boolean {
        val target = stagedFile(filesDir, pack)
        return target.isFile && target.length() == pack.sizeBytes
    }

    /** Copies the verified pack artifact into the tess-two data path (idempotent). */
    fun stage(
        filesDir: File,
        cache: PackCache,
        pack: TtsPack,
    ): Boolean {
        if (isStaged(filesDir, pack)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false
        val dir = stagedFile(filesDir, pack).parentFile!!
        if (dir.isFile) dir.delete()
        dir.mkdirs()
        val target = stagedFile(filesDir, pack)
        val tmp = File(dir, "${pack.id}.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** Removes the staged copy for [pack] (language de-selection). */
    fun unstage(
        filesDir: File,
        pack: TtsPack,
    ) {
        stagedFile(filesDir, pack).delete()
    }
}
