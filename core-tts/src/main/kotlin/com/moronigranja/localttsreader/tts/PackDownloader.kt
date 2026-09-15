package com.moronigranja.localttsreader.tts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The download manager (T1): one pack at a time, explicit, resumable, verified,
 * cached. All of the semantics from hard-facts/decision #7:
 *
 * - **Explicit** — a download only ever starts because the caller (settings
 *   UI / player plumbing) invokes [download]; nothing downloads in the
 *   background unconsented.
 * - **Resumable** — an interrupted or cancelled download leaves a `.part`
 *   file; the next attempt re-requests `Range: bytes=<partialSize>-` and
 *   appends. Servers that ignore Range get a clean full restart. A `.part`
 *   already at full size (crash after the copy, before promotion) is verified
 *   and promoted without any network.
 * - **Verified** — a pack is usable only after its SHA-256 matches the
 *   descriptor (streamed hash). A mismatch deletes the artifact and fails
 *   typed, never leaving corrupt bytes cached.
 * - **Cached** — a marker-verified pack is [DownloadOutcome.AlreadyCached]
 *   with zero network; a full-size-but-unverified file is hashed once and
 *   marked (also zero network).
 *
 * Cancellation propagates ([ensureActive] per chunk, transport abort) and the
 * partial is retained for the next attempt. Failures are typed
 * ([DownloadFailureReason]); nothing throws except cancellation.
 */
class PackDownloader(
    private val cache: PackCache,
    private val transport: DownloadTransport,
    private val bufferSize: Int = 64 * 1024,
) {
    /**
     * Ensures [pack] is downloaded, verified and cached. Reports progress
     * through [onProgress] `(downloadedBytes, totalBytes)`.
     */
    suspend fun download(
        pack: TtsPack,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        // Path 1: verified on disk — cached, no network.
        if (cache.isVerified(pack)) return DownloadOutcome.AlreadyCached

        // Path 2: full-size artifact that never got verified — hash once, mark.
        if (cache.isComplete(pack)) {
            return if (cache.verifyAndMark(pack)) {
                DownloadOutcome.AlreadyCached
            } else {
                cache.deleteArtifacts(pack)
                DownloadOutcome.Failed(DownloadFailureReason.CorruptContent)
            }
        }

        // Path 3: the copy finished but promotion never ran (crash window).
        if (cache.partialFile(pack).isFile && cache.partialFile(pack).length() == pack.sizeBytes) {
            return if (cache.verifyPending(pack)) {
                cache.promote(pack)
                DownloadOutcome.AlreadyCached
            } else {
                cache.deleteArtifacts(pack)
                DownloadOutcome.Failed(DownloadFailureReason.CorruptContent)
            }
        }

        // Path 4: resume or start from scratch.
        val part = cache.partialFile(pack)
        var from = if (part.isFile) part.length() else 0L
        if (from > pack.sizeBytes) { // oversized garbage partial — restart clean
            part.delete()
            from = 0L
        }
        val body =
            when (
                val opened =
                    try {
                        transport.open(pack.url, from.takeIf { from > 0L })
                    } catch (e: IOException) {
                        // The transport may abort a blocked open on cancellation; surface
                        // cancellation instead of a misleading I/O failure.
                        currentCoroutineContext().ensureActive()
                        return DownloadOutcome.Failed(DownloadFailureReason.IoError(e.message ?: "network error"))
                    }
            ) {
                is OpenResult.HttpError -> return DownloadOutcome.Failed(DownloadFailureReason.HttpStatus(opened.status))
                is OpenResult.Body -> opened.body
            }

        body.use {
            when (body.statusCode) {
                200 -> from = 0L
                206 -> Unit // append below
                else -> return DownloadOutcome.Failed(DownloadFailureReason.HttpStatus(body.statusCode))
            }
            part.parentFile?.mkdirs()

            val resume = body.statusCode == 206 && from > 0L
            val options =
                if (resume) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                } else {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
            val channel = FileChannel.open(part.toPath(), *options)
            var downloaded = from
            val buffer = ByteArray(bufferSize)
            try {
                if (resume) channel.position(from)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n =
                        try {
                            body.bytes.read(buffer)
                        } catch (e: IOException) {
                            // A transport abort lands here on cancellation; surface the
                            // cancellation instead of a misleading I/O failure.
                            currentCoroutineContext().ensureActive()
                            return DownloadOutcome.Failed(DownloadFailureReason.IoError(e.message ?: "read failed"))
                        }
                    if (n < 0) break
                    channel.write(ByteBuffer.wrap(buffer, 0, n))
                    downloaded += n
                    onProgress(downloaded, pack.sizeBytes)
                }
            } finally {
                channel.close()
            }

            if (downloaded != pack.sizeBytes) {
                return DownloadOutcome.Failed(DownloadFailureReason.Incomplete(downloaded, pack.sizeBytes))
            }
            if (!cache.verifyPending(pack)) {
                cache.deleteArtifacts(pack)
                return DownloadOutcome.Failed(DownloadFailureReason.CorruptContent)
            }
            cache.promote(pack)
        }
        return DownloadOutcome.Ready
    }
}

/** One download's verdict. */
sealed interface DownloadOutcome {
    /** Downloaded and verified by this operation. */
    data object Ready : DownloadOutcome

    /** Was already verified on disk; no network was used. */
    data object AlreadyCached : DownloadOutcome

    data class Failed(
        val reason: DownloadFailureReason,
    ) : DownloadOutcome
}

sealed interface DownloadFailureReason {
    data class HttpStatus(
        val status: Int,
    ) : DownloadFailureReason

    data class IoError(
        val message: String,
    ) : DownloadFailureReason

    data object CorruptContent : DownloadFailureReason

    data class Incomplete(
        val downloadedBytes: Long,
        val expectedBytes: Long,
    ) : DownloadFailureReason
}
