package io.github.moronigranja.ayvu.tts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk layout and integrity probes for downloaded packs. Layout under the
 * cache root passed by the app (internal storage files dir in production):
 *
 * ```
 * <root>/packs/<engineId>/<packId>          verified artifact
 * <root>/packs/<engineId>/<packId>.part    in-progress download (resume input)
 * <root>/packs/<engineId>/<packId>.ready   verification marker
 * ```
 *
 * **The cache is the persistent pack state** (T1 decision): "verified" is a
 * property of the artifact on disk, so restart survival needs no database.
 * [PackStatus.Ready] is marker-truth — a full-size file without a marker is
 * only verified (hashed once) on first use, then marked. A marker survives
 * restarts; deleting the pack files on the user's storage deletes the marker
 * with them.
 *
 * The atomic sequence for a finished download is: hash the `.part` → move it to
 * the target → write the marker. A crash between rename and marker is
 * recovered by the one-time hash on the next attempt.
 */
class PackCache(
    private val root: File,
) {
    fun directory(engineId: String): File = File(root, "packs/$engineId")

    fun targetFile(pack: TtsPack): File = File(directory(pack.engineId), pack.id)

    fun partialFile(pack: TtsPack): File = File(directory(pack.engineId), "${pack.id}.part")

    fun markerFile(pack: TtsPack): File = File(directory(pack.engineId), "${pack.id}.ready")

    /** Bytes already on disk for this pack: full artifact if present, else the partial. */
    fun downloadedBytes(pack: TtsPack): Long {
        val target = targetFile(pack)
        if (target.isFile) return target.length()
        val partial = partialFile(pack)
        return if (partial.isFile) partial.length() else 0L
    }

    /** A full-size artifact exists on disk (of any content — integrity is [isVerified]). */
    fun isComplete(pack: TtsPack): Boolean {
        val target = targetFile(pack)
        return target.isFile && target.length() == pack.sizeBytes
    }

    /** Marker present **and** the artifact is still complete — the Ready gate. */
    fun isVerified(pack: TtsPack): Boolean = markerFile(pack).isFile && isComplete(pack)

    /** Streaming SHA-256 of [file] equal to the descriptor's expected hex. */
    fun matchesDescriptor(
        file: File,
        pack: TtsPack,
    ): Boolean = sha256Hex(file) == pack.sha256Hex.lowercase()

    /** Hashes the existing full-size artifact and writes the marker when it matches. */
    fun verifyAndMark(pack: TtsPack): Boolean {
        val target = targetFile(pack)
        if (!target.isFile || !matchesDescriptor(target, pack)) return false
        writeMarker(pack)
        return true
    }

    /** True when the finished `.part` hashes correctly, before promotion. */
    fun verifyPending(pack: TtsPack): Boolean {
        val partial = partialFile(pack)
        return partial.isFile && matchesDescriptor(partial, pack)
    }

    /** Promotes a `.part` that has already verified to the target and marks it. */
    fun promote(pack: TtsPack) {
        val partial = partialFile(pack)
        check(partial.isFile) { "promote called without a partial for ${pack.id}" }
        targetFile(pack).parentFile?.mkdirs()
        Files.move(
            partial.toPath(),
            targetFile(pack).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        writeMarker(pack)
    }

    /** Removes every artifact for [pack] (corrupt data, user delete). */
    fun deleteArtifacts(pack: TtsPack) {
        targetFile(pack).delete()
        partialFile(pack).delete()
        markerFile(pack).delete()
    }

    private fun writeMarker(pack: TtsPack) {
        markerFile(pack).parentFile?.mkdirs()
        markerFile(pack).writeText("verified:${pack.sha256Hex.lowercase()}\n")
    }
}
