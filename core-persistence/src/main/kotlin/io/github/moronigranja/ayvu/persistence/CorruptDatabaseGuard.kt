package io.github.moronigranja.ayvu.persistence

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quarantines a corrupt/truncated database file before Room opens it.
 *
 * Room has no corruption-seen hook: a garbage file at the database path throws at
 * first DAO access. This app opens the DB at launch (the `AyvuApp`
 * index rebuild reads `cachedBooks()`), so a single corrupt file crashes every
 * launch with no recovery path. The guard moves the artifact aside — preserved
 * under `files/corrupt-db/<timestamp>/` for analysis — and lets Room create a
 * fresh database: the library starts empty but recoverable (re-import or the
 * backup slice) instead of being unreachable forever.
 *
 * Valid SQLite files are never touched; this is NOT a destructive fallback
 * (decisions #22 forbids one): corrupted bytes are moved, never deleted.
 *
 * Device observation driving this guard: S22 2026-08-29 — after `adb install -r`
 * the library rendered from Room for hours while `files/databases/` held no db
 * file, then a 68 B fragment ("file is not a database") appeared at the db path
 * (open-bugs.md). The filesystem-side cause was never reproduced on the host;
 * this guard closes the app-side failure class the fragment exposed.
 */
object CorruptDatabaseGuard {
    private const val TAG = "CorruptDatabaseGuard"

    /** SQLite 1.x header is 100 bytes; a shorter file cannot be a valid database. */
    private const val MIN_VALID_LENGTH = 100L

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * If [databaseName]'s file exists but is not a valid SQLite database, moves it
     * (with its `-wal` / `-shm` siblings) into `files/corrupt-db/<timestamp>/` and
     * returns the moved file. Returns null when the path is absent, empty, or a
     * valid SQLite file — nothing to quarantine.
     */
    fun quarantineIfCorrupt(
        context: Context,
        databaseName: String,
    ): File? {
        val dbFile = context.getDatabasePath(databaseName)
        if (!dbFile.isFile) return null
        if (dbFile.length() == 0L) return null // SQLite initializes an empty file itself
        if (dbFile.length() >= MIN_VALID_LENGTH && hasSqliteHeader(dbFile)) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(File(context.filesDir, "corrupt-db"), stamp).apply { mkdirs() }
        val moved = mutableListOf<String>()
        for (candidate in listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))) {
            if (candidate.isFile) {
                val target = File(dir, candidate.name)
                if (candidate.renameTo(target)) moved += target.absolutePath
            }
        }
        Log.w(
            TAG,
            "quarantined corrupt database '${dbFile.name}' (${dbFile.length()} B at " +
                "'${dbFile.parentFile?.path}'): moved ${moved.size} file(s) to $dir; " +
                "a fresh database will be created",
        )
        return dbFile
    }

    private fun hasSqliteHeader(file: File): Boolean {
        val header = ByteArray(SQLITE_MAGIC.size)
        return file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) return@use false
                read += n
            }
            header.contentEquals(SQLITE_MAGIC)
        }
    }
}
