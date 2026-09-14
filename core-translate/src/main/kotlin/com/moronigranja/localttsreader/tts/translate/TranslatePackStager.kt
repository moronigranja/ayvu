package com.moronigranja.localttsreader.tts.translate

import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.TtsPack
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stages the verified translate zip under `files/translate-small100/` — the
 * layout the [Sm100Translator]/[TranslateRuntime] open (the espeak precedent:
 * a separate bundle root from the pack cache, extracted from the verified
 * pack artifact, idempotent).
 *
 * The zip is 916 MB of dense int8 graphs, so extraction is a long file copy;
 * [stage] swaps bundle→backup→tmp like [com.moronigranja.localttsreader.player.EspeakStager],
 * and [isStaged] is the readiness gate the runtime polls after a download.
 */
object TranslatePackStager {
    /** The unpack root: `files/translate-small100/` (sibling of the espeak
     * bundle; the pack-cache artifact itself stays at
     * `files/packs/translate-small100/translate-small100-int8-v1`). */
    fun bundleDir(filesDir: File): File = File(filesDir, "translate-small100")

    private val REQUIRED = listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx")

    /** Ready = the three graphs + tokenizer files extracted. */
    fun isStaged(filesDir: File): Boolean {
        val dir = bundleDir(filesDir)
        if (!dir.isDirectory) return false
        return REQUIRED.all { File(dir, it).isFile } &&
            File(dir, "sentencepiece.bpe.model").isFile &&
            File(dir, "vocab.json").isFile
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

        val tmp = File(filesDir, "translate-small100-tmp")
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
        val backup = File(filesDir, "translate-small100-bak")
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
