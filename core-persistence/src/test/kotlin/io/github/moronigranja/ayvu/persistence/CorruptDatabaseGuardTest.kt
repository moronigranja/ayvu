package io.github.moronigranja.ayvu.persistence

import androidx.room.Room
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.LibraryEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [CorruptDatabaseGuard] — a garbage file at the Room path must be quarantined
 * (preserved, not deleted) before the builder opens it; valid SQLite files must
 * be untouched. Mirrors the S22 2026-08-29 observation: a 68 B fragment at the
 * db path would otherwise crash the launch-time rebuild on every start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptDatabaseGuardTest {
    private val context = RuntimeEnvironment.getApplication()

    private val dbPath: File
        get() = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        dbPath.delete()
        File(dbPath.path + "-wal").delete()
        File(dbPath.path + "-shm").delete()
        File(context.filesDir, "corrupt-db").deleteRecursively()
    }

    @Test
    fun `garbage file is quarantined under corrupt-db and original path is cleared`() {
        // The observed S22 artifact size (68 B), prefix of a real header but garbage.
        dbPath.parentFile?.mkdirs()
        dbPath.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(52) { 0xAB.toByte() })

        val moved = CorruptDatabaseGuard.quarantineIfCorrupt(context, DB_NAME)

        assertNotNull("corrupt file must be reported", moved)
        assertFalse("original path must be cleared", dbPath.exists())
        val dir = File(context.filesDir, "corrupt-db")
        assertTrue("quarantine dir exists", dir.isDirectory)
        val artifact =
            dir
                .listFiles()!!
                .single()
                .listFiles()!!
                .single { it.name == "$DB_NAME" }
        assertEquals("artifact preserved byte-for-byte", 68L, artifact.length())
    }

    @Test
    fun `wal and shm siblings move with the corrupt main file`() {
        dbPath.parentFile?.mkdirs()
        dbPath.writeBytes("garbage-not-a-database".toByteArray())
        File(dbPath.path + "-wal").writeBytes(ByteArray(16) { 1 })
        File(dbPath.path + "-shm").writeBytes(ByteArray(16) { 2 })

        CorruptDatabaseGuard.quarantineIfCorrupt(context, DB_NAME)

        val dir = File(context.filesDir, "corrupt-db").listFiles()!!.single()
        assertTrue("wal moved", dir.listFiles()!!.any { it.name == "$DB_NAME-wal" })
        assertTrue("shm moved", dir.listFiles()!!.any { it.name == "$DB_NAME-shm" })
    }

    @Test
    fun `valid sqlite database is left untouched`() =
        runBlocking {
            val database =
                Room
                    .databaseBuilder(context, LibraryDatabase::class.java, DB_NAME)
                    .allowMainThreadQueries()
                    .build()
            database.bookDao().upsert(LibraryEntry(Book("b1", "Keep Me", emptyList()), 1_000).bookEntity())
            database.close()
            val bytesBefore = dbPath.readBytes()

            val moved = CorruptDatabaseGuard.quarantineIfCorrupt(context, DB_NAME)

            assertNull("valid db must not be reported corrupt", moved)
            assertTrue("db file still present", dbPath.isFile)
            assertTrue("db bytes unchanged", bytesBefore.contentEquals(dbPath.readBytes()))
            assertFalse("no quarantine dir created", File(context.filesDir, "corrupt-db").exists())
        }

    @Test
    fun `empty and missing files are not quarantined`() {
        assertNull(CorruptDatabaseGuard.quarantineIfCorrupt(context, DB_NAME))

        dbPath.parentFile?.mkdirs()
        dbPath.writeBytes(ByteArray(0))
        assertNull("0-byte file is initializable by SQLite, not corrupt", CorruptDatabaseGuard.quarantineIfCorrupt(context, DB_NAME))
        assertTrue("empty file left in place", dbPath.isFile)
    }

    private companion object {
        const val DB_NAME = "guard-test.db"
    }
}
