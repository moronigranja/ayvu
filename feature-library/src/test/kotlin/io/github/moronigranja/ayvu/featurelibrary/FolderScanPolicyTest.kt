package io.github.moronigranja.ayvu.featurelibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F3: the hostile-input policy — recursion depth, the per-batch file cap and
 * the shared extension gate — is pure and host-testable without Android. The
 * `FolderScanner` DocumentFile adapter only supplies [ScanNode]s; these tests
 * pin the decisions themselves.
 */
class FolderScanPolicyTest {
    private fun file(name: String): ScanNode<String> = ScanNode(name, isDirectory = false, payload = name) { emptyList() }

    private fun dir(
        name: String,
        vararg children: ScanNode<String>,
    ): ScanNode<String> = ScanNode(name, isDirectory = true, payload = null) { children.toList() }

    @Test
    fun `root files are collected in walk order with unsupported entries skipped`() {
        val root =
            dir(
                "root",
                file("a.epub"),
                file("notes.pdf"),
                file("b.mobi"),
                file("cover.jpg"),
                file("c.txt"),
            )

        val result = FolderScanPolicy.collect(root)

        assertEquals(listOf("a.epub", "b.mobi", "c.txt"), result.files.map { it.name })
        assertEquals(2, result.skipped, "the pdf and jpg are filtered, not failed")
        assertFalse(result.truncated)
    }

    @Test
    fun `one nested folder level is scanned`() {
        val root =
            dir(
                "root",
                dir("series", file("book-one.epub"), file("book-two.epub")),
            )

        val result = FolderScanPolicy.collect(root)

        assertEquals(listOf("book-one.epub", "book-two.epub"), result.files.map { it.name })
    }

    @Test
    fun `folders deeper than one nested level are not descended`() {
        val root =
            dir(
                "root",
                dir(
                    "author",
                    dir("trilogy", dir("extra", file("too-deep.epub"))),
                    file("on-shelf.epub"),
                ),
            )

        val result = FolderScanPolicy.collect(root)

        // "on-shelf.epub" sits at depth 1 (scanned); "too-deep.epub" is depth 3 (pruned).
        assertEquals(listOf("on-shelf.epub"), result.files.map { it.name })
    }

    @Test
    fun `the file cap stops the walk and reports truncation`() {
        val files = (1..(FolderScanPolicy.MAX_FILES + 1)).map { file("book-$it.epub") }
        val root = dir("root", *files.toTypedArray())

        val result = FolderScanPolicy.collect(root)

        assertEquals(FolderScanPolicy.MAX_FILES, result.files.size, "exactly the cap is kept")
        assertTrue(result.truncated, "the extra file beyond the cap must be reported")
    }

    @Test
    fun `the default gate is the shared EBookFormats extension set`() {
        val root =
            dir(
                "root",
                file("a.epub"),
                file("b.txt"),
                file("c.markdown"),
                file("d.md"),
                file("e.azw3"),
                file("f.kf8"),
                file("g.mobi"),
                file("h.azw"),
                file("i.pdf"),
                file("j.jpg"),
                file("k.kfx"),
            )

        // No explicit predicate: the shared EBookFormats.parserFor is the gate.
        val result = FolderScanPolicy.collect(root)

        assertEquals(
            listOf("a.epub", "b.txt", "c.markdown", "d.md", "e.azw3", "f.kf8", "g.mobi", "h.azw"),
            result.files.map { it.name },
        )
        assertEquals(3, result.skipped, "pdf/jpg/kfx are not importable formats")
    }

    @Test
    fun `a custom predicate replaces the extension gate for traversal tests`() {
        val root = dir("root", file("a.epub"), file("b.anything"))

        val result = FolderScanPolicy.collect(root) { true }

        assertEquals(listOf("a.epub", "b.anything"), result.files.map { it.name })
        assertEquals(0, result.skipped)
    }
}
