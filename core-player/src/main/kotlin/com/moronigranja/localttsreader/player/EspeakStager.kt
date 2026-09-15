package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.TtsPack
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stages the downloaded espeak-ng bundle under `files/espeak/` — the layout
 * the Kokoro runtime and the settings status read (decision #32: lib + data
 * staged next to each other, loaded by explicit path). The pack artifact is
 * a zip of `libespeak-ng.so` + `espeak-ng-data/` at its root; the download
 * itself is a normal verified pack ([PackCache] layout, decision #7), so the
 * staging is an accelerator-style extract, idempotent and cheap.
 *
 * A6: moved to core-player — pure File logic, shared by the player runtime
 * (feature-player) and the settings surface without a feature edge.
 */
object EspeakStager {
    fun bundleDir(filesDir: File): File = File(filesDir, "espeak")

    fun libFile(filesDir: File): File = File(bundleDir(filesDir), "libespeak-ng.so")

    fun dataDir(filesDir: File): File = File(bundleDir(filesDir), "espeak-ng-data")

    /** Ready = staged lib + non-empty data dir (the readiness KokoroRuntime requires). */
    fun isStaged(filesDir: File): Boolean {
        val lib = libFile(filesDir)
        val data = dataDir(filesDir)
        return lib.isFile && data.isDirectory && data.listFiles()?.isNotEmpty() == true
    }

    /** Extracts the verified zip pack into [bundleDir] (idempotent; replaces an old bundle). */
    fun stage(
        filesDir: File,
        cache: PackCache,
        pack: TtsPack,
    ): Boolean {
        if (isStaged(filesDir)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false

        val tmp = File(filesDir, "espeak-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(tmp, entry.name)
                check(target.canonicalPath.startsWith(tmp.canonicalPath)) { "zip entry escapes the bundle dir: ${entry.name}" }
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
        val backup = File(filesDir, "espeak-bak")
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

/**
 * UI display for the storage-transparency surfaces (decisions #44; A6) — bytes,
 * KB, MB or GB, whichever is the largest unit the value fills at least once.
 *
 * The device-smoke pass on the signed 0.1.1 build caught the missing GB branch:
 * a 760 GB free-space figure rendered as "760321.4 MB free" on the setup
 * screen, which reads as nonsense to a user.
 */
fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
