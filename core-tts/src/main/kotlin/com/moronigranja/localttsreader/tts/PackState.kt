package com.moronigranja.localttsreader.tts

/**
 * One pack's observable state — the settings UI's surface ("a language that is
 * not downloaded is surfaced with a download action, never a silent failure",
 * hard-facts). Four statuses:
 *
 * - [NotDownloaded] — nothing (or only a partial `.part` file) on disk; the
 *   download action starts or resumes.
 * - [Downloading] — an in-flight download for this pack, with progress.
 * - [Ready] — verified on disk (`.ready` marker present); usable, no network.
 * - [Failed] — the last attempt's typed failure; superseded by the next attempt
 *   or [PackRegistry.refresh].
 *
 * Ready is marker-truth (see [PackCache]): a full-size but never-verified file
 * is still NotDownloaded until the first download attempt hashes it once.
 */
sealed interface PackStatus {
    data object NotDownloaded : PackStatus

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : PackStatus

    data object Ready : PackStatus

    data class Failed(
        val reason: DownloadFailureReason,
    ) : PackStatus
}

data class PackState(
    val pack: TtsPack,
    val status: PackStatus,
)
