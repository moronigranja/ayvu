package io.github.moronigranja.ayvu.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioned backup archive codec (post-v1-plan Slice B, phase 1). Pure JVM:
 * `write(BackupSnapshot) → zip bytes` and `read(bytes) → snapshot`, with all
 * failures typed — never a partial merge.
 *
 * v1 layout (section names are the format contract):
 *
 * ```
 * manifest.json         { version, appVersion, exportedAtEpochMillis }
 * settings.json         { "<key>": "<value>" }          (raw rows, not typed)
 * library.json          [ { id, title, authors, importedAtEpochMillis } ]
 * passages.json         [ { bookId, chapterIndex, chapterTitle, passageIndex, text } ]
 * progress.json         [ { bookId, chapterIndex, passageIndex, offsetSeconds, speed, updatedAtEpochMillis } ]
 * bookmarks.json        [ { bookId, chapterIndex, passageIndex, offsetSeconds, label, createdAtEpochMillis } ]
 * position_history.json [ { bookId, chapterIndex, passageIndex, offsetSeconds, createdAtEpochMillis } ]
 * translations.json     optional: [ { bookId, chapterIndex, passageIndex, lang, translator, text, createdAtEpochMillis } ]
 * books/<name>          optional: <bookId>.<ext> only when book files were included
 * ```
 *
 * Reading validates [BACKUP_VERSION] FIRST — an unknown/future version fails
 * with [BackupReadError.UnsupportedVersion] before any section is parsed, so
 * a newer archive can never partially apply. Unknown extra keys inside a
 * section object are ignored (forward-tolerant within a version).
 *
 * Reading is hardened for untrusted input (an archive is a file the user picked): the
 * archive's own size, its entry count, each entry's size, the cumulative inflation and the
 * manifest each have a ceiling enforced WHILE inflating — [BackupLimits], local to this pure
 * JVM module — duplicate entry names are refused instead of silently keeping the last, a
 * `books/` name must be ONE safe path segment (a `books/../../databases/ayvu.db` entry is a
 * zip-slip attempt, refused as [BackupReadError.UnsafeBookFile]), and an `OutOfMemoryError`
 * from a bomb is reported as [BackupReadError.OutOfMemory], never left to crash the process.
 */
object BackupCodec {
    const val BACKUP_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private const val MANIFEST = "manifest.json"
    private const val SETTINGS = "settings.json"
    private const val LIBRARY = "library.json"
    private const val PASSAGES = "passages.json"
    private const val PROGRESS = "progress.json"
    private const val BOOKMARKS = "bookmarks.json"
    private const val HISTORY = "position_history.json"
    private const val TRANSLATIONS = "translations.json"
    private const val BOOKS_DIR = "books/"

    /** A book-file name is one path segment — no separator, no traversal, no leading dot. */
    private val BOOK_FILE_NAME = Regex("[A-Za-z0-9._-]+")

    private val REQUIRED_SECTIONS = setOf(MANIFEST, SETTINGS, LIBRARY, PASSAGES, PROGRESS, BOOKMARKS, HISTORY)

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    fun write(snapshot: BackupSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries =
                linkedMapOf(
                    MANIFEST to
                        JsonObject(
                            mapOf(
                                "version" to JsonPrimitive(snapshot.version),
                                "appVersion" to JsonPrimitive(snapshot.appVersion),
                                "exportedAtEpochMillis" to JsonPrimitive(snapshot.exportedAtEpochMillis),
                            ),
                        ),
                    SETTINGS to JsonObject(snapshot.settings.mapValues { (_, v) -> JsonPrimitive(v) }),
                    LIBRARY to
                        buildJsonArray {
                            snapshot.library.forEach { book ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "id" to JsonPrimitive(book.id),
                                            "title" to JsonPrimitive(book.title),
                                            "authors" to buildJsonArray { book.authors.forEach { add(JsonPrimitive(it)) } },
                                            "importedAtEpochMillis" to JsonPrimitive(book.importedAtEpochMillis),
                                        ),
                                    ),
                                )
                            }
                        },
                    PASSAGES to
                        buildJsonArray {
                            snapshot.passages.forEach { p ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "bookId" to JsonPrimitive(p.bookId),
                                            "chapterIndex" to JsonPrimitive(p.chapterIndex),
                                            "chapterTitle" to (p.chapterTitle?.let { JsonPrimitive(it) } ?: JsonNull),
                                            "passageIndex" to JsonPrimitive(p.passageIndex),
                                            "text" to JsonPrimitive(p.text),
                                        ),
                                    ),
                                )
                            }
                        },
                    PROGRESS to
                        buildJsonArray {
                            snapshot.progress.forEach { p ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "bookId" to JsonPrimitive(p.bookId),
                                            "chapterIndex" to JsonPrimitive(p.chapterIndex),
                                            "passageIndex" to JsonPrimitive(p.passageIndex),
                                            "offsetSeconds" to JsonPrimitive(p.offsetSeconds),
                                            "speed" to JsonPrimitive(p.speed),
                                            "updatedAtEpochMillis" to JsonPrimitive(p.updatedAtEpochMillis),
                                        ),
                                    ),
                                )
                            }
                        },
                    BOOKMARKS to
                        buildJsonArray {
                            snapshot.bookmarks.forEach { b ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "bookId" to JsonPrimitive(b.bookId),
                                            "chapterIndex" to JsonPrimitive(b.chapterIndex),
                                            "passageIndex" to JsonPrimitive(b.passageIndex),
                                            "offsetSeconds" to JsonPrimitive(b.offsetSeconds),
                                            "label" to JsonPrimitive(b.label),
                                            "createdAtEpochMillis" to JsonPrimitive(b.createdAtEpochMillis),
                                        ),
                                    ),
                                )
                            }
                        },
                    HISTORY to
                        buildJsonArray {
                            snapshot.positionHistory.forEach { h ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "bookId" to JsonPrimitive(h.bookId),
                                            "chapterIndex" to JsonPrimitive(h.chapterIndex),
                                            "passageIndex" to JsonPrimitive(h.passageIndex),
                                            "offsetSeconds" to JsonPrimitive(h.offsetSeconds),
                                            "createdAtEpochMillis" to JsonPrimitive(h.createdAtEpochMillis),
                                        ),
                                    ),
                                )
                            }
                        },
                    TRANSLATIONS to
                        buildJsonArray {
                            snapshot.translations.forEach { t ->
                                add(
                                    JsonObject(
                                        mapOf(
                                            "bookId" to JsonPrimitive(t.bookId),
                                            "chapterIndex" to JsonPrimitive(t.chapterIndex),
                                            "passageIndex" to JsonPrimitive(t.passageIndex),
                                            "lang" to JsonPrimitive(t.lang),
                                            "translator" to JsonPrimitive(t.translator),
                                            "text" to JsonPrimitive(t.text),
                                            "createdAtEpochMillis" to JsonPrimitive(t.createdAtEpochMillis),
                                        ),
                                    ),
                                )
                            }
                        },
                )
            // Deterministic order: sections in the contract order, then book
            // files sorted by key — byte-stable output for a given snapshot.
            entries.forEach { (name, element) -> zip.putEntry(name) { zip.write(element.toString().encodeToByteArray()) } }
            snapshot.bookFiles.entries.sortedBy { it.key }.forEach { (name, content) ->
                zip.putEntry("$BOOKS_DIR$name") { zip.write(content) }
            }
        }
        return bytes.toByteArray()
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun read(bytes: ByteArray): BackupReadResult = attempt { readArchive(bytes, BackupLimits.DEFAULT) }

    /**
     * Reads the archive straight off [input] (the SAF source), capped as it fills — the
     * stream is NOT closed here; the caller owns it. This is the restore path's way in:
     * a `ContentResolver` length is never trusted, only the bytes actually delivered.
     */
    fun read(input: InputStream): BackupReadResult = attempt { readArchive(input.readCapped(), BackupLimits.DEFAULT) }

    internal fun read(
        bytes: ByteArray,
        limits: BackupLimits,
    ): BackupReadResult = attempt { readArchive(bytes, limits) }

    internal fun read(
        input: InputStream,
        limits: BackupLimits,
    ): BackupReadResult = attempt { readArchive(input.readCapped(limits), limits) }

    /**
     * Every read failure, typed: a [BackupReadError] as-is, an [OutOfMemoryError] (a zip
     * bomb) as [BackupReadError.OutOfMemory] — an `Error` must never reach the UI or the
     * process — and anything else as [BackupReadError.NotAZip].
     */
    private fun attempt(block: () -> BackupReadResult): BackupReadResult =
        try {
            block()
        } catch (e: BackupReadError) {
            BackupReadResult.Error(e)
        } catch (e: OutOfMemoryError) {
            BackupReadResult.Error(BackupReadError.OutOfMemory)
        } catch (e: Exception) {
            BackupReadResult.Error(BackupReadError.NotAZip(e))
        }

    private fun readArchive(
        bytes: ByteArray,
        limits: BackupLimits,
    ): BackupReadResult {
        if (bytes.size > limits.maxArchiveBytes) {
            throw BackupReadError.ArchiveTooLarge(limits.maxArchiveBytes)
        }
        val entries = readEntries(bytes, limits)
        val present = entries.keys

        val missing = REQUIRED_SECTIONS - present
        if (missing.isNotEmpty()) {
            return BackupReadResult.Error(BackupReadError.MissingSection(missing))
        }
        // The manifest is parsed first and is three fields: refuse a padded one before
        // its string is ever materialised.
        if (entries.getValue(MANIFEST).size > limits.maxManifestBytes) {
            throw BackupReadError.ManifestTooLarge(limits.maxManifestBytes)
        }

        val manifest = parseObject(parseSection(entries, MANIFEST), MANIFEST)
        val version =
            manifest["version"]?.jsonPrimitive?.int
                ?: return BackupReadResult.Error(BackupReadError.MalformedSection(MANIFEST, "missing 'version'"))
        if (version != BACKUP_VERSION) {
            return BackupReadResult.Error(BackupReadError.UnsupportedVersion(version))
        }
        val appVersion =
            manifest["appVersion"]?.jsonPrimitive?.contentOrNull()
                ?: return BackupReadResult.Error(BackupReadError.MalformedSection(MANIFEST, "missing 'appVersion'"))
        val exportedAt =
            manifest["exportedAtEpochMillis"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: return BackupReadResult.Error(BackupReadError.MalformedSection(MANIFEST, "missing 'exportedAtEpochMillis'"))

        val settings =
            parseObject(parseSection(entries, SETTINGS), SETTINGS)
                .mapNotNull { (k, v) ->
                    if (v is JsonNull) {
                        null
                    } else {
                        (v as? JsonPrimitive)?.content?.let { k to it }
                            ?: throw BackupReadError.MalformedSection(SETTINGS, "value not a JSON primitive")
                    }
                }.toMap()
        val library = parseArray(parseSection(entries, LIBRARY), LIBRARY).map { parseBook(it, LIBRARY) }
        val passages = parseArray(parseSection(entries, PASSAGES), PASSAGES).map { parsePassage(it, PASSAGES) }
        val progress = parseArray(parseSection(entries, PROGRESS), PROGRESS).map { parseProgress(it, PROGRESS) }
        val bookmarks = parseArray(parseSection(entries, BOOKMARKS), BOOKMARKS).map { parseBookmark(it, BOOKMARKS) }
        val history = parseArray(parseSection(entries, HISTORY), HISTORY).map { parseHistory(it, HISTORY) }
        // translations is an OPTIONAL section: a v1 archive (7 required
        // sections, no translations.json) restores cleanly with none.
        val translations =
            entries[TRANSLATIONS]
                ?.let { parseArray(parseSection(entries, TRANSLATIONS), TRANSLATIONS).map { parseTranslation(it, TRANSLATIONS) } }
                ?: emptyList()
        // book files are OPAQUE bytes — never JSON-parsed; only the books/ prefix
        // marks them, so a binary body cannot be misread as a section.
        val bookFiles = entries.bookFiles()

        return BackupReadResult.Ok(
            BackupSnapshot(
                version = version,
                appVersion = appVersion,
                exportedAtEpochMillis = exportedAt,
                settings = settings,
                library = library,
                passages = passages,
                progress = progress,
                bookmarks = bookmarks,
                positionHistory = history,
                translations = translations,
                bookFiles = bookFiles,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun parseObject(
        element: JsonElement,
        section: String,
    ): JsonObject =
        element.jsonObjectOrNull()
            ?: throw BackupReadError.MalformedSection(section, "not a JSON object")

    private fun parseArray(
        element: JsonElement,
        section: String,
    ): JsonArray =
        (element as? JsonArray)
            ?: throw BackupReadError.MalformedSection(section, "not a JSON array")

    private fun parseBook(
        element: JsonElement,
        section: String,
    ): BackupBook {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "book entry not an object")
        return BackupBook(
            id = o.stringField("id", section),
            title = o.stringField("title", section),
            authors =
                (o["authors"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.content
                        ?: throw BackupReadError.MalformedSection(section, "book 'authors' not a string array")
                }
                    ?: throw BackupReadError.MalformedSection(section, "book 'authors' not a string array"),
            importedAtEpochMillis = o.longField("importedAtEpochMillis", section),
        )
    }

    private fun parsePassage(
        element: JsonElement,
        section: String,
    ): BackupPassage {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "passage entry not an object")
        return BackupPassage(
            bookId = o.stringField("bookId", section),
            chapterIndex = o.intField("chapterIndex", section),
            chapterTitle = o["chapterTitle"]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull() },
            passageIndex = o.intField("passageIndex", section),
            text = o.stringField("text", section),
        )
    }

    private fun parseProgress(
        element: JsonElement,
        section: String,
    ): BackupProgress {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "progress entry not an object")
        return BackupProgress(
            bookId = o.stringField("bookId", section),
            chapterIndex = o.intField("chapterIndex", section),
            passageIndex = o.intField("passageIndex", section),
            offsetSeconds = o.doubleField("offsetSeconds", section),
            speed = o.doubleField("speed", section),
            updatedAtEpochMillis = o.longField("updatedAtEpochMillis", section),
        )
    }

    private fun parseBookmark(
        element: JsonElement,
        section: String,
    ): BackupBookmark {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "bookmark entry not an object")
        return BackupBookmark(
            bookId = o.stringField("bookId", section),
            chapterIndex = o.intField("chapterIndex", section),
            passageIndex = o.intField("passageIndex", section),
            offsetSeconds = o.doubleField("offsetSeconds", section),
            label = o.stringField("label", section),
            createdAtEpochMillis = o.longField("createdAtEpochMillis", section),
        )
    }

    private fun parseTranslation(
        element: JsonElement,
        section: String,
    ): BackupTranslation {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "translation entry not an object")
        return BackupTranslation(
            bookId = o.stringField("bookId", section),
            chapterIndex = o.intField("chapterIndex", section),
            passageIndex = o.intField("passageIndex", section),
            lang = o.stringField("lang", section),
            translator = o.stringField("translator", section),
            text = o.stringField("text", section),
            createdAtEpochMillis = o.longField("createdAtEpochMillis", section),
        )
    }

    private fun parseHistory(
        element: JsonElement,
        section: String,
    ): BackupHistory {
        val o = element.jsonObjectOrNull() ?: throw BackupReadError.MalformedSection(section, "history entry not an object")
        return BackupHistory(
            bookId = o.stringField("bookId", section),
            chapterIndex = o.intField("chapterIndex", section),
            passageIndex = o.intField("passageIndex", section),
            offsetSeconds = o.doubleField("offsetSeconds", section),
            createdAtEpochMillis = o.longField("createdAtEpochMillis", section),
        )
    }

    private fun JsonObject.stringField(
        key: String,
        section: String,
    ): String =
        this[key]?.jsonPrimitive?.contentOrNull()
            ?: throw BackupReadError.MalformedSection(section, "entry missing '$key'")

    private fun JsonObject.intField(
        key: String,
        section: String,
    ): Int =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw BackupReadError.MalformedSection(section, "entry missing int '$key'")

    private fun JsonObject.longField(
        key: String,
        section: String,
    ): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw BackupReadError.MalformedSection(section, "entry missing long '$key'")

    private fun JsonObject.doubleField(
        key: String,
        section: String,
    ): Double =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: throw BackupReadError.MalformedSection(section, "entry missing number '$key'")

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = (this as? JsonArray)

    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

    // ------------------------------------------------------------------
    // Zip helpers
    // ------------------------------------------------------------------

    private fun readEntries(
        bytes: ByteArray,
        limits: BackupLimits,
    ): Map<String, ByteArray> {
        // Hostile-input guard: a non-zip blob must fail as NotAZip, not be read
        // as an empty archive (ZipInputStream silently yields no entries).
        val magic = bytes.take(4)
        val pkLocal = byteArrayOf(0x50, 0x4B, 0x03, 0x04).toList()
        val pkEmpty = byteArrayOf(0x50, 0x4B, 0x05, 0x06).toList()
        if (bytes.size < 4 || (magic.toList() != pkLocal && magic.toList() != pkEmpty)) {
            throw BackupReadError.NotAZip(null)
        }
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    count++
                    if (count > limits.maxEntryCount) {
                        throw BackupReadError.TooManyEntries(limits.maxEntryCount)
                    }
                    // A repeated name is ambiguous — the last would silently win — so it is
                    // refused, like every other malformed-container shape.
                    if (entries.containsKey(entry.name)) {
                        throw BackupReadError.DuplicateEntry(entry.name)
                    }
                    val data = inflate(zip, entry.name, expanded, limits)
                    expanded += data.size
                    entries[entry.name] = data
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    /**
     * One entry's bytes. [expandedSoFar] is what the earlier entries already cost; both the
     * per-entry and the cumulative ceiling are checked AS the entry streams in, so an archive
     * that inflates past a ceiling throws before its bomb is ever held in memory — the same
     * discipline `core-ebook`'s ZipEntries applies to ebook containers.
     */
    private fun inflate(
        zip: ZipInputStream,
        name: String,
        expandedSoFar: Long,
        limits: BackupLimits,
    ): ByteArray {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        val out = ByteArrayOutputStream(IO_BUFFER_BYTES)
        var size = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            size += read
            if (size > limits.maxEntryBytes) {
                throw BackupReadError.EntryTooLarge(name, limits.maxEntryBytes)
            }
            if (expandedSoFar + size > limits.maxTotalExpandedBytes) {
                throw BackupReadError.ExpandedTooLarge(limits.maxTotalExpandedBytes)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * The `books/` entries as `<name>` → bytes, refusing any name that is not ONE safe path
     * segment: `books/../../databases/ayvu.db` reaches the sidecar store as
     * `../../databases/ayvu.db` and would be written outside `files/books` (zip slip), so a
     * separator, a `..`, a leading dot or any character outside `[A-Za-z0-9._-]` fails the
     * whole read.
     */
    private fun Map<String, ByteArray>.bookFiles(): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        for ((path, content) in this) {
            if (!path.startsWith(BOOKS_DIR)) continue
            val name = path.removePrefix(BOOKS_DIR)
            if (!isSafeBookFileName(name)) throw BackupReadError.UnsafeBookFile(path)
            files[name] = content
        }
        return files
    }

    private fun isSafeBookFileName(name: String): Boolean = name.isNotEmpty() && !name.startsWith(".") && BOOK_FILE_NAME.matches(name)

    private fun parseSection(
        entries: Map<String, ByteArray>,
        section: String,
    ): JsonElement =
        try {
            json.parseToJsonElement(entries.getValue(section).decodeToString())
        } catch (e: Exception) {
            throw BackupReadError.MalformedSection(section, "not valid JSON: ${e.message}")
        }

    private fun ZipOutputStream.putEntry(
        name: String,
        block: () -> Unit,
    ) {
        putNextEntry(ZipEntry(name))
        block()
        closeEntry()
    }
}

/** Typed failure from [BackupCodec.read] — never a partial merge on any of these. */
sealed class BackupReadError(
    message: String,
) : Exception(message) {
    /** Bytes are not a readable zip. */
    data class NotAZip(
        val failure: Throwable?,
    ) : BackupReadError("not a valid zip: ${failure?.message}")

    /** One or more mandatory section files are absent. */
    data class MissingSection(
        val sections: Set<String>,
    ) : BackupReadError("missing sections: $sections")

    /** A section exists but does not parse to the expected shape. */
    data class MalformedSection(
        val section: String,
        val detail: String,
    ) : BackupReadError("malformed $section: $detail")

    /** Archive version is not [BackupCodec.BACKUP_VERSION] — refuse before any section parse. */
    data class UnsupportedVersion(
        val version: Int,
    ) : BackupReadError("unsupported archive version $version (supported: ${BackupCodec.BACKUP_VERSION})")

    /** The archive exceeds the whole-archive ceiling — refused while it is read, never buffered. */
    data class ArchiveTooLarge(
        val maxBytes: Int,
    ) : BackupReadError("archive is too large (over ${megabytes(maxBytes.toLong())} MB)")

    /** More entries than the ceiling — a container that would not fit a real library. */
    data class TooManyEntries(
        val max: Int,
    ) : BackupReadError("archive has too many entries (over $max)")

    /** One entry inflates past the per-entry ceiling. */
    data class EntryTooLarge(
        val name: String,
        val maxBytes: Int,
    ) : BackupReadError("archive entry is too large (over ${megabytes(maxBytes.toLong())} MB): $name")

    /** The entries together inflate past the cumulative ceiling, though no single one does. */
    data class ExpandedTooLarge(
        val maxBytes: Long,
    ) : BackupReadError("archive expands too far (over ${megabytes(maxBytes)} MB)")

    /** The manifest is padded past its ceiling — three fields never need that much. */
    data class ManifestTooLarge(
        val maxBytes: Int,
    ) : BackupReadError("manifest is too large (over $maxBytes bytes)")

    /** The same entry name appears twice: ambiguous, so the archive is refused outright. */
    data class DuplicateEntry(
        val name: String,
    ) : BackupReadError("duplicate archive entry: $name")

    /**
     * A `books/` entry name is not a single safe path segment — a zip-slip attempt
     * (`books/../../databases/ayvu.db`) that must never reach the sidecar store.
     */
    data class UnsafeBookFile(
        val name: String,
    ) : BackupReadError("unsafe book file name: $name")

    /**
     * The archive exhausted the heap (a zip bomb). A singleton, not a data class: an
     * [OutOfMemoryError] is caught here, and building a message string at that moment is
     * exactly the allocation that must not be attempted.
     */
    data object OutOfMemory : BackupReadError("archive exhausted memory")
}

sealed class BackupReadResult {
    data class Ok(
        val snapshot: BackupSnapshot,
    ) : BackupReadResult()

    data class Error(
        val reason: BackupReadError,
    ) : BackupReadResult()
}
