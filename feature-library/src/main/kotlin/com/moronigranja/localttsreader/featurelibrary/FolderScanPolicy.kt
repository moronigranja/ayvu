package com.moronigranja.localttsreader.featurelibrary

import com.moronigranja.localttsreader.ebook.EBookFormats

/**
 * F3: the recursion/cap policy for folder import, kept Android-free so the
 * hostile-input decisions (depth bound, per-batch cap, extension filter) are
 * host-testable. The `FolderScanner` DocumentFile adapter supplies the tree;
 * the pure [FolderScanPolicy.collect] decides what to keep.
 *
 * The two bounds are the first concrete resource-exhaustion controls of the
 * "hostile-input and resource limits" review (roadmap, decisions #96.8):
 *
 * - [MAX_DEPTH]: nested folder levels below the granted root that get scanned.
 *   A tree grant is typically library-shaped (a folder, or a folder of
 *   subfolders), so one level covers "books grouped by author/series" without
 *   an unbounded-recursion surface on an adversarial tree.
 * - [MAX_FILES]: defensive ceiling on files collected from one tree, so a
 *   drop-box folder cannot feed an unbounded batch.
 */
object FolderScanPolicy {
    /** Nested folder levels below the granted root that get scanned (root = depth 0). */
    const val MAX_DEPTH = 1

    /** Defensive cap on files collected from a single tree grant. */
    const val MAX_FILES = 200

    /**
     * Walks [root] and returns the importable files in walk order. Reuses the
     * shared [EBookFormats] extension gate (no second extension list); the
     * importer's `UnsupportedFormat` path remains the backstop for strays.
     */
    fun <T> collect(
        root: ScanNode<T>,
        supported: (String) -> Boolean = { EBookFormats.parserFor(it) != null },
    ): FolderScanResult<T> {
        val files = ArrayList<ScanFile<T>>()
        var skipped = 0
        var truncated = false

        fun walk(
            node: ScanNode<T>,
            depth: Int,
        ) {
            if (truncated || depth > MAX_DEPTH) return
            for (child in node.children()) {
                if (files.size >= MAX_FILES) {
                    truncated = true
                    return
                }
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (supported(child.name)) {
                    files += ScanFile(child.name, requireNotNull(child.payload))
                } else {
                    skipped += 1
                }
            }
        }

        walk(root, 0)
        return FolderScanResult(files, skipped, truncated)
    }
}

/**
 * A lazily-enumerated tree node; [payload] is the adapter's handle for a file
 * (null for directories). `children` is deferred so `DocumentFile.listFiles()`
 * is only called for the levels [FolderScanPolicy] actually descends — a
 * hostile tree is never materialized beyond the depth bound.
 */
class ScanNode<T>(
    val name: String,
    val isDirectory: Boolean,
    val payload: T?,
    private val listChildren: () -> List<ScanNode<T>>,
) {
    fun children(): List<ScanNode<T>> = listChildren()
}

/** One kept file: its display name plus the adapter's opaque handle. */
data class ScanFile<T>(
    val name: String,
    val payload: T,
)

/** The outcome of one folder scan. */
data class FolderScanResult<T>(
    val files: List<ScanFile<T>>,
    val skipped: Int,
    val truncated: Boolean,
)
