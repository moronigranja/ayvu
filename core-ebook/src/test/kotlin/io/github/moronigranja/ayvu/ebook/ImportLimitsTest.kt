package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.EpubFixture.zip
import io.github.moronigranja.ayvu.ebook.EpubFixture.zipBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The import ceilings: an untrusted container must be refused while it is read/inflated,
 * never after it has been materialised. Every case injects small limits — a real
 * quarter-gigabyte file is exactly what these guards exist to avoid building.
 */
class ImportLimitsTest {
    private fun source(
        name: String,
        bytes: ByteArray,
    ): EBookSource = EBookSource(name) { ByteArrayInputStream(bytes) }

    /** Highly compressible payload: the archive stays tiny while it inflates to [size]. */
    private fun zeros(size: Int): ByteArray = ByteArray(size)

    /**
     * Two entries where the second one is unreadable (its stored bytes are corrupted after
     * the archive was written, so its CRC no longer matches) — the "section that breaks
     * after good ones" the lenient path exists for.
     */
    private fun archiveWithBrokenTail(): ByteArray {
        val payload = "beta".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("good.txt"))
            zip.write("alpha".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("bad.txt").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = payload.size.toLong()
                    crc = CRC32().apply { update(payload) }.value
                },
            )
            zip.write(payload)
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        // Stored entries appear verbatim, and ISO-8859-1 maps bytes to chars 1:1.
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("beta")
        assertTrue(at > 0, "the stored payload must be findable in the archive")
        for (i in at until at + payload.size) bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
        return bytes
    }

    // ------------------------------------------------------------------
    // Container ceiling
    // ------------------------------------------------------------------

    @Test
    fun `container past the ceiling is refused while it is read`() {
        val limits = ImportLimits(maxContainerBytes = 3 * 1024 * 1024)

        // Exactly at the ceiling is a legitimate book, not an error.
        val atCeiling = zeros(limits.maxContainerBytes)
        assertEquals(atCeiling.size, source("AtCeiling.epub", atCeiling).readCapped(limits).size)

        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                source("OverCeiling.epub", zeros(limits.maxContainerBytes + 1)).readCapped(limits)
            }
        assertEquals("file is too large (over 3 MB)", thrown.message)
    }

    // ------------------------------------------------------------------
    // Archive ceilings (checked during inflation)
    // ------------------------------------------------------------------

    @Test
    fun `archive past the entry-count ceiling is refused`() {
        val limits = ImportLimits(maxEntryCount = 4)
        assertEquals(4, ZipEntries.readAll(zip(*Array(4) { "e$it.txt" to "x" }), limits).size)

        val overCeiling = zip(*Array(5) { "e$it.txt" to "x" })
        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                ZipEntries.readAll(overCeiling, limits)
            }
        assertEquals("archive has too many entries (over 4)", thrown.message)
    }

    @Test
    fun `entry past the per-entry ceiling is refused`() {
        val limits = ImportLimits(maxEntryBytes = 2 * 1024 * 1024)

        val atCeiling = zipBytes("big.bin" to zeros(limits.maxEntryBytes))
        assertEquals(limits.maxEntryBytes, ZipEntries.readAll(atCeiling, limits).getValue("big.bin").size)

        val overCeiling = zipBytes("big.bin" to zeros(limits.maxEntryBytes + 1))
        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                ZipEntries.readAll(overCeiling, limits)
            }
        assertEquals("archive entry is too large (over 2 MB): big.bin", thrown.message)
    }

    @Test
    fun `entries together inflating past the cumulative ceiling are refused`() {
        val limits = ImportLimits(maxTotalExpandedBytes = 3L * 1024 * 1024)

        // Two separate entries, each under the per-entry ceiling: 2 MB in total is fine.
        val within =
            zipBytes(
                "a.bin" to zeros(1024 * 1024),
                "b.bin" to zeros(1024 * 1024),
            )
        assertEquals(2, ZipEntries.readAll(within, limits).size)

        // 1 MB + 3 MB crosses the cumulative ceiling although no single entry does.
        val overCeiling =
            zipBytes(
                "a.bin" to zeros(1024 * 1024),
                "b.bin" to zeros(3 * 1024 * 1024),
            )
        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                ZipEntries.readAll(overCeiling, limits)
            }
        assertEquals("archive expands too far (over 3 MB)", thrown.message)
    }

    // ------------------------------------------------------------------
    // The lenient path: trailing junk is tolerated, a broken section is not
    // ------------------------------------------------------------------

    @Test
    fun `a valid archive that trails non-zip junk still yields its entries`() {
        val withJunk = zip("a.txt" to "alpha", "b.txt" to "beta") + "not part of the archive".toByteArray()

        assertEquals(setOf("a.txt", "b.txt"), ZipEntries.readUntilBroken(withJunk).keys)
    }

    @Test
    fun `lenient read keeps the sections that parsed before a broken one`() {
        val bytes = archiveWithBrokenTail()

        val thrown = assertThrows(EBookParseException::class.java) { ZipEntries.readAll(bytes) }
        assertEquals("not a valid zip/ebook container", thrown.message)

        assertEquals(setOf("good.txt"), ZipEntries.readUntilBroken(bytes).keys)
    }

    @Test
    fun `lenient read still refuses an archive past a ceiling`() {
        val limits = ImportLimits(maxEntryBytes = 1024 * 1024)
        val bomb = zipBytes("bomb.bin" to zeros(4 * 1024 * 1024)) + "trailing junk".toByteArray()

        val thrown =
            assertThrows(EBookLimitExceededException::class.java) {
                ZipEntries.readUntilBroken(bomb, limits)
            }
        assertTrue(
            thrown.message.orEmpty().startsWith("archive entry is too large"),
            "a bomb is not trailing junk (was ${thrown.message})",
        )
    }
}
