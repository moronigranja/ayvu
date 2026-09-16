package io.github.moronigranja.ayvu.tts.translate

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TtsPack
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stages the verified translate zip under `files/translate-lfm/` — the layout
 * the [io.github.moronigranja.ayvu.llm.LlamaTranslator]/[TranslateRuntime]
 * open (the espeak precedent: a separate bundle root from the pack cache,
 * extracted from the verified pack artifact, idempotent).
 *
 * The zip is 730 MB of GGUF, so extraction is a long file copy; [stage] swaps
 * bundle→backup→tmp like [io.github.moronigranja.ayvu.player.EspeakStager],
 * and [isStaged] is the readiness gate the runtime polls after a download.
 */
object TranslatePackStager {
    /** The unpack root: `files/translate-lfm/` (sibling of the espeak bundle;
     * the pack-cache artifact itself stays at
     * `files/packs/translate-lfm12b/translate-lfm12b-q4-v1`). */
    fun bundleDir(filesDir: File): File = File(filesDir, "translate-lfm")

    /** The GGUF's file name inside the bundle — the artifact's upstream name
     * (`LiquidAI/LFM2.5-1.2B-Instruct-GGUF`), kept verbatim so a staged file is
     * traceable back to the release it came from. */
    const val MODEL_FILE = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf"

    private val REQUIRED = listOf(MODEL_FILE)

    /** Ready = the GGUF extracted. */
    fun isStaged(filesDir: File): Boolean {
        val dir = bundleDir(filesDir)
        if (!dir.isDirectory) return false
        return REQUIRED.all { File(dir, it).isFile }
    }

    /** Extracts the verified zip into [bundleDir] (idempotent, replaces an old bundle). */
    fun stage(
        filesDir: File,
        cache: PackCache,
        pack: TtsPack,
    ): Boolean {
        if (isStaged(filesDir)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false

        val tmp = File(filesDir, "translate-lfm-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(tmp, entry.name)
                check(target.canonicalPath.startsWith(tmp.canonicalPath)) {
                    "zip entry escapes the bundle dir: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }

        val target = bundleDir(filesDir)
        val backup = File(filesDir, "translate-lfm-bak")
        backup.deleteRecursively()
        if (target.isDirectory && !target.renameTo(backup)) return false
        if (!tmp.renameTo(target)) {
            backup.renameTo(target) // restore the previous bundle on failure
            return false
        }
        backup.deleteRecursively()
        return true
    }
}
