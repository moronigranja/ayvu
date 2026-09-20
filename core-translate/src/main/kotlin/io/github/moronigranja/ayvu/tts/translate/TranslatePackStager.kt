package io.github.moronigranja.ayvu.tts.translate

import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stages a verified translate zip under `files/<engine.bundleDirName>` — the
 * layout [io.github.moronigranja.ayvu.llm.LlamaTranslator]/[TranslateRuntime]
 * open (the espeak precedent: a separate bundle root from the pack cache,
 * extracted from the verified pack artifact, idempotent).
 *
 * One root per engine option ([TranslatePacks]): the shipped LFM2.5-1.2B keeps
 * `files/translate-lfm`, so an existing install never re-stages the 730 MB
 * bundle it already has; the second option uses its own root, so both can be
 * installed at once and the active engine's file is the one the runtime opens.
 *
 * The zips are ~730 MB–1.7 GB of GGUF, so extraction is a long file copy;
 * [stage] swaps bundle→backup→tmp like [io.github.moronigranja.ayvu.player
 * .EspeakStager], and [isStaged] is the readiness gate the runtime polls after
 * a download.
 */
object TranslatePackStager {
    /** The unpack root for [engine]: `files/<engine.bundleDirName>` (a sibling
     * of the other bundles; the pack-cache artifact itself stays at
     * `files/packs/<engineId>/<packId>`). */
    fun bundleDir(
        filesDir: File,
        engine: TranslateEngine,
    ): File = File(filesDir, engine.bundleDirName)

    /** The GGUF the runtime opens for [engine] — the bundle's only required
     * file. The file name is the artifact's upstream name, kept verbatim so a
     * staged file is traceable back to the release it came from. */
    fun modelFile(
        filesDir: File,
        engine: TranslateEngine,
    ): File = File(bundleDir(filesDir, engine), engine.modelFileName)

    /** Ready = [engine]'s GGUF is extracted (file presence; integrity was the
     * zip descriptor's sha256 at download — [PackCache.isVerified]). */
    fun isStaged(
        filesDir: File,
        engine: TranslateEngine,
    ): Boolean = modelFile(filesDir, engine).isFile

    /** [isStaged] for a stored engine id (unknown ids resolve to the default
     * engine, [TranslatePacks.byId]). */
    fun isStaged(
        filesDir: File,
        engineId: String?,
    ): Boolean = isStaged(filesDir, TranslatePacks.byId(engineId))

    /** Extracts the verified zip of [pack] into its engine's bundle dir
     * (idempotent, replaces an old bundle). The pack resolves to an engine via
     * [TranslatePacks.byPackId]; an unknown pack never stages.
     * Suspends cooperatively: Stop cancels the extract at an entry boundary, so
     * a cancelled unpack leaves the previous bundle intact. */
    suspend fun stage(
        filesDir: File,
        cache: PackCache,
        pack: TtsPack,
    ): Boolean {
        val engine = TranslatePacks.byPackId(pack.id) ?: return false
        if (isStaged(filesDir, engine)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false

        val tmp = File(filesDir, "${engine.bundleDirName}-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
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

        val target = bundleDir(filesDir, engine)
        val backup = File(filesDir, "${engine.bundleDirName}-bak")
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
