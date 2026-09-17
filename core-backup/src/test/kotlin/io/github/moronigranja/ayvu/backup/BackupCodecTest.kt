package io.github.moronigranja.ayvu.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Deflater
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
            translations =
                listOf(
                    BackupTranslation("b1", 0, 0, "pt-BR", "lfm12b", "Todas as famílias felizes são iguais.", 1_750_500_000_000),
                    BackupTranslation("b1", 0, 1, "pt-BR", "lfm12b", "Cada infeliz o é à sua maneira.", 1_750_500_000_100),
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

    // ------------------------------------------------------------------
    // Hostile archives: zip slip, duplicate entries, ceilings
    // ------------------------------------------------------------------

    @Test
    fun `a book file name that is not a single safe path segment is refused`() {
        listOf(
            "books/../escape.txt",
            "books/../../databases/ayvu.db",
            "books/sub/b1.epub",
            "books/..\\escape.txt",
            "books/.hidden",
            "books/..",
            "books/a b.epub",
        ).forEach { entry ->
            val result = BackupCodec.read(zipBytes(validSections() + (entry to "pwned".toByteArray())))

            assertTrue(result is BackupReadResult.Error, "$entry must fail the read")
            assertEquals(BackupReadError.UnsafeBookFile(entry), (result as BackupReadResult.Error).reason)
        }
    }

    @Test
    fun `a book file name at the safe edge still reads`() {
        val result = BackupCodec.read(zipBytes(validSections() + ("books/b1.a-b_c.D.EPUB" to "bytes".toByteArray())))

        assertTrue(result is BackupReadResult.Ok, "expected Ok, got $result")
        assertEquals(
            setOf("b1.a-b_c.D.EPUB"),
            (result as BackupReadResult.Ok).snapshot.bookFiles.keys,
        )
    }

    @Test
    fun `a duplicate entry name is refused instead of silently keeping the last`() {
        val result = BackupCodec.read(zipWithDuplicate("settings.json"))

        assertTrue(result is BackupReadResult.Error, "expected Error, got $result")
        assertEquals(BackupReadError.DuplicateEntry("settings.json"), (result as BackupReadResult.Error).reason)
    }

    @Test
    fun `an archive over the entry-count ceiling is refused`() {
        val sections = validSections()
        val archive = zipBytes(sections)

        // Exactly at the ceiling is a legitimate archive…
        assertTrue(BackupCodec.read(archive, BackupLimits(maxEntryCount = sections.size)) is BackupReadResult.Ok)
        // …one entry more is not.
        val result = BackupCodec.read(archive, BackupLimits(maxEntryCount = sections.size - 1))

        assertEquals(BackupReadError.TooManyEntries(sections.size - 1), (result as BackupReadResult.Error).reason)
    }

    @Test
    fun `an archive over the byte ceiling is refused`() {
        val archive = zipBytes(validSections())

        assertTrue(BackupCodec.read(archive, BackupLimits(maxArchiveBytes = archive.size)) is BackupReadResult.Ok)
        val result = BackupCodec.read(archive, BackupLimits(maxArchiveBytes = archive.size - 1))

        assertEquals(BackupReadError.ArchiveTooLarge(archive.size - 1), (result as BackupReadResult.Error).reason)
    }

    @Test
    fun `an archive stream over the byte ceiling stops at the ceiling`() {
        val limits = BackupLimits(maxArchiveBytes = 1024 * 1024)
        // A stream with no trustworthy length (available() lies): only the bytes it
        // actually hands over count — and a restore must not drain a huge file to find out.
        val stream = CountingStream(ByteArray(8 * limits.maxArchiveBytes) { 'x'.code.toByte() })

        val result = BackupCodec.read(stream, limits)

        assertEquals(BackupReadError.ArchiveTooLarge(limits.maxArchiveBytes), (result as BackupReadResult.Error).reason)
        assertTrue(
            stream.consumed <= limits.maxArchiveBytes + IO_BUFFER_BYTES,
            "the read must stop at the ceiling, not drain the stream (read ${stream.consumed} bytes)",
        )
    }

    @Test
    fun `an entry that inflates past the per-entry ceiling is refused while it inflates`() {
        // A 1 GiB bomb in a few MB of archive: buffered whole it could not fit the test
        // heap at all, so reaching the typed failure proves the ceiling is applied AS the
        // entry streams in (never after it has been materialised).
        val archive = zipBomb(1024L * 1024 * 1024)

        val result = BackupCodec.read(archive, BackupLimits(maxEntryBytes = 1024 * 1024))

        assertEquals(
            BackupReadError.EntryTooLarge("books/big.bin", 1024 * 1024),
            (result as BackupReadResult.Error).reason,
        )
    }

    @Test
    fun `entries together inflating past the cumulative ceiling are refused`() {
        val limits = BackupLimits(maxTotalExpandedBytes = 3L * 1024 * 1024, maxEntryBytes = 2 * 1024 * 1024)

        // 1 MB + 1 MB: neither entry breaks its own ceiling, together they stay inside.
        val within = zipBytes(validSections() + listOf("books/a.bin" to zeros(1), "books/b.bin" to zeros(1)))
        assertTrue(BackupCodec.read(within, limits) is BackupReadResult.Ok, "inside the ceiling must read")

        // + 2 MB: still no single entry over its ceiling, the total is.
        val over =
            zipBytes(
                validSections() + listOf("books/a.bin" to zeros(1), "books/b.bin" to zeros(1), "books/c.bin" to zeros(2)),
            )
        val result = BackupCodec.read(over, limits)

        assertEquals(BackupReadError.ExpandedTooLarge(limits.maxTotalExpandedBytes), (result as BackupReadResult.Error).reason)
    }

    @Test
    fun `a manifest padded past its ceiling is refused`() {
        val padded = """{"version":1,"appVersion":"0.1.0","exportedAtEpochMillis":1,"pad":"${"x".repeat(4096)}"}"""
        val archive =
            zipBytes(
                validSections().map { (name, content) ->
                    name to (if (name == "manifest.json") padded.toByteArray() else content)
                },
            )

        // At the ceiling the padded manifest still parses (unknown keys are ignored)…
        assertTrue(
            BackupCodec.read(archive, BackupLimits(maxManifestBytes = padded.toByteArray().size)) is BackupReadResult.Ok,
        )
        // …past it the read refuses before the string is materialised.
        val result = BackupCodec.read(archive, BackupLimits(maxManifestBytes = 256))

        assertEquals(BackupReadError.ManifestTooLarge(256), (result as BackupReadResult.Error).reason)
    }

    // ------------------------------------------------------------------
    // Hostile-archive helpers
    // ------------------------------------------------------------------

    /** The seven mandatory sections, minimal but valid — the base every hostile case extends. */
    private fun validSections(): List<Pair<String, ByteArray>> =
        listOf(
            "manifest.json" to """{"version":1,"appVersion":"0.1.0","exportedAtEpochMillis":1}""".toByteArray(),
            "settings.json" to """{"voice":"af_heart"}""".toByteArray(),
            "library.json" to """[{"id":"b1","title":"T","authors":[],"importedAtEpochMillis":1}]""".toByteArray(),
            "passages.json" to "[]".toByteArray(),
            "progress.json" to "[]".toByteArray(),
            "bookmarks.json" to "[]".toByteArray(),
            "position_history.json" to "[]".toByteArray(),
        )

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zeros(megabytes: Int): ByteArray = ByteArray(megabytes * 1024 * 1024)

    /**
     * A valid archive plus one entry that inflates to [size] zeros, deflated in chunks so
     * the TEST never allocates the expansion either: a few MB of archive, [size] bytes of
     * contents.
     */
    private fun zipBomb(size: Long): ByteArray {
        val chunk = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            validSections().forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("books/big.bin"))
            var written = 0L
            while (written < size) {
                val n = minOf(chunk.size.toLong(), size - written).toInt()
                zip.write(chunk, 0, n)
                written += n
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * A zip carrying [name] twice. [ZipOutputStream] refuses to write a duplicate, so a decoy
     * name of the same length is patched in afterwards: entry names appear verbatim in both the
     * local header and the central directory, and ISO-8859-1 maps bytes to chars 1:1.
     */
    private fun zipWithDuplicate(name: String): ByteArray {
        val decoy = "duplicate.txt" // same length as "settings.json"
        check(decoy.length == name.length) { "the decoy must be the same length as $name" }
        val bytes = zipBytes(validSections() + (decoy to "{}".toByteArray()))
        val glued = String(bytes, Charsets.ISO_8859_1)
        var at = glued.indexOf(decoy)
        check(at > 0) { "the decoy entry name must be findable in the archive" }
        while (at >= 0) {
            name.toByteArray().copyInto(bytes, at)
            at = glued.indexOf(decoy, at + 1)
        }
        return bytes
    }

    /** An archive source with no trustworthy length: it reports what was actually read. */
    private class CountingStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var consumed = 0
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) consumed++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len).also { if (it > 0) consumed += it }

        override fun available(): Int = 0
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
