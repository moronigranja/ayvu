package io.github.moronigranja.ayvu.featurelibrary

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * F3: the SAF tree adapter — turns an `ACTION_OPEN_DOCUMENT_TREE` grant into the
 * pure [ScanNode] shape the policy walks. `children` is lazy so only the
 * bounded levels are enumerated; a `fromTreeUri` grant the provider can no
 * longer resolve yields an empty result (nothing imported), never a crash.
 */
fun Context.scanTree(uri: Uri): FolderScanResult<Uri> {
    val root =
        DocumentFile.fromTreeUri(this, uri)
            ?: return FolderScanResult(emptyList(), 0, truncated = false)
    return FolderScanPolicy.collect(root.toScanNode())
}

private fun DocumentFile.toScanNode(): ScanNode<Uri> =
    ScanNode(
        name = name ?: "",
        isDirectory = isDirectory,
        // Files carry their own content URI; directories carry nothing.
        payload = if (isFile) uri else null,
        listChildren = { listFiles().map { it.toScanNode() }.toList() },
    )
