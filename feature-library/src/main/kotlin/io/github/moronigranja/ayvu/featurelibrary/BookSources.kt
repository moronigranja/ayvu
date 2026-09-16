package io.github.moronigranja.ayvu.featurelibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.moronigranja.ayvu.ebook.EBookSource

// SAF adapters: turn picked [Uri]s into the domain's [EBookSource]s and make
// read access persistable where the provider allows it. Unsupported extensions
// or unreadable streams are surfaced by the importer as typed failures.

/** Resolves [uris] to [EBookSource]s with display names and lazy content streams. */
fun Context.toEBookSources(uris: List<Uri>): List<EBookSource> =
    uris.map { uri ->
        EBookSource(
            fileName = displayName(uri) ?: uri.lastPathSegment ?: "book.epub",
        ) {
            contentResolver.openInputStream(uri) ?: error("cannot open $uri")
        }
    }

/** Resolves a folder scan (F3) to [EBookSource]s, reusing the scanned display
 * names instead of re-querying each URI's [OpenableColumns.DISPLAY_NAME]. */
fun Context.toEBookSources(result: FolderScanResult<Uri>): List<EBookSource> =
    result.files.map { file ->
        EBookSource(
            fileName = file.name,
        ) {
            contentResolver.openInputStream(file.payload) ?: error("cannot open ${file.name}")
        }
    }

private fun Context.displayName(uri: Uri): String? =
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

/**
 * Requests a persistable read grant for [uri] so re-reading it (e.g. after a
 * process restart) keeps working. Some providers refuse; that is fine — the
 * grant still holds for the current session, so failures are swallowed silently.
 */
fun Context.takeReadPermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        // Provider refuses persistable grants — session-scoped read still works.
    } catch (e: IllegalArgumentException) {
        // Unknown URI or missing flag — nothing to persist.
    }
}
