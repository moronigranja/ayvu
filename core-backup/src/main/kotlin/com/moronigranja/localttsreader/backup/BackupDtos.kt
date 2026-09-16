package com.moronigranja.localttsreader.backup

/**
 * Self-contained DTOs for the versioned backup archive (post-v1-plan Slice B).
 *
 * Pure JVM — no Android imports, no Room entities — so the codec and merge
 * policy are fully sandbox-testable. Serialization is manual via
 * kotlinx-serialization `JsonElement` (core-tts no-codegen convention); this
 * module never applies the serialization compiler plugin.
 *
 * Archive layout (v1): `manifest.json` + six JSON section files + optional
 * `books/` entries + optional `translations.json` (the read-in-language
 * display rows, a pure add — a v1 archive without it restores cleanly),
 * zipped. See [BackupCodec].
 */
data class BackupSnapshot(
    val version: Int,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val settings: Map<String, String>,
    val library: List<BackupBook>,
    val passages: List<BackupPassage>,
    val progress: List<BackupProgress>,
    val bookmarks: List<BackupBookmark>,
    val positionHistory: List<BackupHistory>,
    /** Stored passage translations (read-in-language display) — an optional
     * archive section; empty for a v1 archive without it. */
    val translations: List<BackupTranslation> = emptyList(),
    /** Original book files: `<bookId>.<ext>` → bytes. Empty when not included. */
    val bookFiles: Map<String, ByteArray>,
) {
    override fun equals(other: Any?): Boolean =
        other is BackupSnapshot &&
            version == other.version &&
            appVersion == other.appVersion &&
            exportedAtEpochMillis == other.exportedAtEpochMillis &&
            settings == other.settings &&
            library == other.library &&
            passages == other.passages &&
            progress == other.progress &&
            bookmarks == other.bookmarks &&
            positionHistory == other.positionHistory &&
            translations == other.translations &&
            bookFiles.size == other.bookFiles.size &&
            bookFiles.all { (k, v) -> other.bookFiles[k]?.contentEquals(v) == true }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + appVersion.hashCode()
        result = 31 * result + exportedAtEpochMillis.hashCode()
        result = 31 * result + settings.hashCode()
        result = 31 * result + library.hashCode()
        result = 31 * result + passages.hashCode()
        result = 31 * result + progress.hashCode()
        result = 31 * result + bookmarks.hashCode()
        result = 31 * result + positionHistory.hashCode()
        result = 31 * result + translations.hashCode()
        result = 31 * result + bookFiles.keys.hashCode()
        return result
    }
}

data class BackupBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val importedAtEpochMillis: Long,
)

data class BackupPassage(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val passageIndex: Int,
    val text: String,
)

data class BackupProgress(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val speed: Double,
    val updatedAtEpochMillis: Long,
)

data class BackupBookmark(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val label: String,
    val createdAtEpochMillis: Long,
)

data class BackupHistory(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val createdAtEpochMillis: Long,
)

/** One stored passage translation (read-in-language display): the natural
 * (bookId, chapterIndex, passageIndex, lang, translator) key — restored rows
 * overwrite local on that key (same precedence as settings). */
data class BackupTranslation(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val lang: String,
    val translator: String,
    val text: String,
    val createdAtEpochMillis: Long,
)
