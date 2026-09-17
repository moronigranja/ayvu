package io.github.moronigranja.ayvu.persistence

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * [BookFileStore] hardening (audit A1/A4), on a real filesystem: a restored archive is
 * untrusted input, so a sidecar name that resolves outside the store root is refused
 * (`../../databases/ayvu.db` would otherwise overwrite the database), and a save lands
 * through a temp file renamed into place — a failed write neither truncates what was
 * already there nor leaves a temp file behind.
 */
class BookFileStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(createTempDirectory("book-file-store").toFile(), "books")
    }

    @After
    fun tearDown() {
        root.parentFile?.deleteRecursively()
    }

    private fun store() = BookFileStore(root)

    @Test
    fun `a name that escapes the store root is refused and nothing is written outside it`() {
        listOf("../escape.txt", "../../databases/ayvu.db", "sub/../../escape.txt", "..").forEach { name ->
            try {
                store().save(name, "pwned".toByteArray())
                fail("$name must be refused")
            } catch (e: IllegalStateException) {
                assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains("escapes the store root"))
            }
        }

        // The store root itself is all a refused save may create.
        assertEquals(listOf("books"), root.parentFile!!.listFiles()!!.map { it.name })
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `a successful save leaves exactly the sidecar, with no temp file`() {
        store().save("b1.epub", byteArrayOf(1, 2, 3))

        assertEquals(listOf("b1.epub"), root.listFiles()!!.map { it.name })
        assertEquals(listOf<Byte>(1, 2, 3), File(root, "b1.epub").readBytes().toList())
        assertEquals(setOf("b1.epub"), store().all().keys)
    }

    @Test
    fun `a save that cannot replace its target leaves neither a partial file nor a temp file`() {
        // A non-empty directory where the sidecar belongs: the final rename cannot succeed —
        // the same shape as a disk-full write, without needing to fill a disk.
        val blocked = File(root, "b1.epub")
        blocked.mkdirs()
        File(blocked, "existing").writeText("x")

        try {
            store().save("b1.epub", "new".toByteArray())
            fail("a save that cannot replace its target must fail loudly")
        } catch (e: IllegalStateException) {
            assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains("cannot replace"))
        }

        assertEquals(listOf("existing"), blocked.listFiles()!!.map { it.name })
        assertEquals(listOf("b1.epub"), root.listFiles()!!.map { it.name })
    }
}
