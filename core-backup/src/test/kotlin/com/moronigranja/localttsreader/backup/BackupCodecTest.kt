package com.moronigranja.localttsreader.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [BackupCodec] round-trip + failure typing (post-v1-plan Slice B phase 1).
 * Pure JVM: write → read must reproduce the snapshot exactly (DTO equality,
 * byte equality of book files), malformed archives must fail typed with no
 * partial state, and unknown archive versions must be refused BEFORE any
 * section parse.
 */
class BackupCodecTest {
    private val sample =
        BackupSnapshot(
            version = BackupCodec.BACKUP_VERSION,
            appVersion = "0.1.0",
            exportedAtEpochMillis = 1_752_000_000_000,
            settings =
                mapOf(
                    "voice" to "af_heart",
                    "theme_mode" to "dark",
                    // Per-book keys ride the archive as raw rows (same
                    // ride-along as book.voice., decisions #156) — the
                    // read-in-language target survives a restore (#114).
                    "book.voice.b1" to "pf_dora",
                    "book.translate.b1" to "pt-BR",
                    "book.translate.b2" to "zh",
                ),
            library =
                listOf(
                    BackupBook("b1", "Anna Karenina", listOf("Leo Tolstoy", "C. Garnett, trans."), 1_700_000_000_000),
                    BackupBook("b2", "Dom Casmurro", listOf("Machado de Assis"), 1_700_000_000_100),
                ),
            passages =
                listOf(
                    BackupPassage("b1", 0, "Happy Families", 0, "All happy families are alike."),
                    BackupPassage("b1", 0, "Happy Families", 1, "Each unhappy one in its own way."),
                    BackupPassage("b2", 1, null, 0, "Chapter-less passage."),
                ),
            progress =
                listOf(
                    BackupProgress("b1", 0, 1, 3.5, 1.0, 1_751_000_000_000),
                ),
            bookmarks =
                listOf(
                    BackupBookmark("b1", 0, 0, 2.25, "favorite line", 1_750_000_000_000),
                ),
            positionHistory =
                listOf(
                    BackupHistory("b1", 0, 1, 3.5, 1_751_000_000_000),
                    BackupHistory("b2", 1, 0, 0.0, 1_752_000_000_000),
                ),
            bookFiles =
                mapOf(
                    "b1.epub" to byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte(), 1, 2, 3),
                    "b2.azw3" to ByteArray(512) { it.toByte() },
                ),
        )

    @Test
    fun `write then read reproduces the snapshot exactly`() {
        val bytes = BackupCodec.write(sample)

        val result = BackupCodec.read(bytes)
        assertTrue(result is BackupReadResult.Ok, "expected Ok, got $result")
        assertEquals(sample, (result as BackupReadResult.Ok).snapshot)
    }

    @Test
    fun `empty library round-trips as a valid no-op backup`() {
        val empty =
            BackupSnapshot(
                version = BackupCodec.BACKUP_VERSION,
                appVersion = "0.1.0",
                exportedAtEpochMillis = 1,
                settings = emptyMap(),
                library = emptyList(),
                passages = emptyList(),
                progress = emptyList(),
                bookmarks = emptyList(),
                positionHistory = emptyList(),
                bookFiles = emptyMap(),
            )

        val result = BackupCodec.read(BackupCodec.write(empty))

        assertTrue(result is BackupReadResult.Ok)
        assertEquals(empty, (result as BackupReadResult.Ok).snapshot)
    }

    @Test
    fun `write output is byte-stable for a given snapshot`() {
        assertEquals(BackupCodec.write(sample).toList(), BackupCodec.write(sample).toList())
    }

    // ------------------------------------------------------------------
    // Failure typing — never a partial merge
    // ------------------------------------------------------------------

    @Test
    fun `garbage bytes fail as NotAZip`() {
        val result = BackupCodec.read(ByteArray(64) { 0x42 })

        assertTrue(result is BackupReadResult.Error)
        assertTrue((result as BackupReadResult.Error).reason is BackupReadError.NotAZip)
    }

    @Test
    fun `missing section fails as MissingSection`() {
        val zip = zipOf("manifest.json" to """{"version":1,"appVersion":"0.1.0","exportedAtEpochMillis":1}""")

        val result = BackupCodec.read(zip)

        assertTrue(result is BackupReadResult.Error)
        assertTrue((result as BackupReadResult.Error).reason is BackupReadError.MissingSection)
    }

    @Test
    fun `unknown future version is refused before any section parse`() {
        // A future version with garbage in every section: version check must
        // reject first, so the malformed sections are never touched.
        val zip =
            zipOf(
                "manifest.json" to """{"version":99,"appVersion":"9.9.9","exportedAtEpochMillis":1}""",
                "settings.json" to "not-json",
                "library.json" to "not-json",
                "passages.json" to "not-json",
                "progress.json" to "not-json",
                "bookmarks.json" to "not-json",
                "position_history.json" to "not-json",
            )

        val result = BackupCodec.read(zip)

        assertTrue(result is BackupReadResult.Error)
        val reason = (result as BackupReadResult.Error).reason
        assertTrue(reason is BackupReadError.UnsupportedVersion, "expected UnsupportedVersion, got $reason")
        assertEquals(99, (reason as BackupReadError.UnsupportedVersion).version)
    }

    @Test
    fun `malformed section JSON fails as MalformedSection`() {
        val zip =
            zipOf(
                "manifest.json" to """{"version":1,"appVersion":"0.1.0","exportedAtEpochMillis":1}""",
                "settings.json" to """{"voice":"af_heart"}""",
                "library.json" to """[{"id":"b1","title":"T","authors":[],"importedAtEpochMillis":1}]""",
                "passages.json" to "!!not-json!!",
                "progress.json" to "[]",
                "bookmarks.json" to "[]",
                "position_history.json" to "[]",
            )

        val result = BackupCodec.read(zip)

        assertTrue(result is BackupReadResult.Error)
        assertTrue((result as BackupReadResult.Error).reason is BackupReadError.MalformedSection)
    }

    @Test
    fun `book files are read as opaque bytes, not json`() {
        // A book file whose body is NOT JSON — the exact binary case a naive
        // section parse would break on.
        val zip =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write("""{"version":1,"appVersion":"0.1.0","exportedAtEpochMillis":1}""".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("settings.json"))
                        zip.write("{}".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("library.json"))
                        zip.write("[]".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("passages.json"))
                        zip.write("[]".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("progress.json"))
                        zip.write("[]".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("bookmarks.json"))
                        zip.write("[]".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("position_history.json"))
                        zip.write("[]".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("books/b1.epub"))
                        zip.write(
                            byteArrayOf(
                                0x50.toByte(),
                                0x4B.toByte(),
                                0x03.toByte(),
                                0x04.toByte(),
                                0x00.toByte(),
                                0xFF.toByte(),
                                0x00.toByte(),
                                0x7F.toByte(),
                            ),
                        )
                        zip.closeEntry()
                    }
                }.toByteArray()

        val result = BackupCodec.read(zip)

        assertTrue(result is BackupReadResult.Ok, "expected Ok, got $result")
        val snapshot = (result as BackupReadResult.Ok).snapshot
        assertEquals(1, snapshot.bookFiles.size)
        assertTrue(
            snapshot.bookFiles["b1.epub"]!!.contentEquals(
                byteArrayOf(
                    0x50.toByte(),
                    0x4B.toByte(),
                    0x03.toByte(),
                    0x04.toByte(),
                    0x00.toByte(),
                    0xFF.toByte(),
                    0x00.toByte(),
                    0x7F.toByte(),
                ),
            ),
            "book bytes must round-trip verbatim",
        )
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
