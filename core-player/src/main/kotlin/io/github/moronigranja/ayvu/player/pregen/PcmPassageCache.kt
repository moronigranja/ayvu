package io.github.moronigranja.ayvu.player.pregen

import java.io.File

/**
 * Post-v1 disk tier for pre-generated passages (decisions #35): raw PCM with
 * a `.meta` sidecar (sample rate + sentence anchors) under
 * `<root>/<bookId>/<engine>/<voice>/<speed>/c<ch>p<passage>.pcm` — the
 * layout is the [PregenKey] path, so book removal deletes a `bookId` subtree
 * (content-hash ids, decisions #11) and engine+voice+speed are part of the
 * key (#31/#34). Pre-engine entries written under the v1 layout
 * `<root>/<bookId>/<voice>/<speed>/…` still bootstrap: [PregenKey.parse]
 * reads them as engine `kokoro` (decisions #54) and [pcmFile] resolves
 * them, so an upgrade never wipes or orphans existing PCM and an
 * over-cap v1 tier still converges (CR-4 deletes only what cannot parse).
 *
 * LRU eviction by a byte cap tracked in-process. Within one process, access
 * order is exact ([get]/[put] refresh it); across a process restart, the
 * on-disk entries are bootstrapped into the eviction order by pcm mtime
 * (oldest first) — a deterministic approximation of true LRU (CR-4: a
 * reopened cache must still be able to replace old audio near the cap).
 * Writes are atomic (tmp + rename); `get` re-validates the sidecar before
 * returning; the cap invariant holds after any successful [put] (and at
 * construction for a pre-populated-over-cap cache), except for an entry
 * that alone exceeds the cap — it cannot be retained and is evicted.
 */
class PcmPassageCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Object()

    // accessOrder=true: re-put/read moves the key to the end; first = LRU.
    private val recency = object : LinkedHashMap<PregenKey, Long>(16, 0.75f, true) {}
    private var now = 0L

    init {
        bootstrap()
    }

    /**
     * CR-4: a reopened cache must not freeze replacement. Loads every valid
     * on-disk entry into [recency] in on-disk age order (pcm mtime, oldest
     * first — the head is the eviction candidate), then converges an
     * over-cap cache below [maxBytes] so the pregen planner's
     * [bytesRemaining] check and the next [put] start from a healthy cap.
     *
     * Artifacts that could never be valid entries are removed here — the
     * natural place to delete them so [contains] can never report a
     * permanent false hit: stale `.tmp` writes, PCM whose [PregenKey] path
     * or `.meta` sidecar does not parse, and metadata without its PCM.
     *
     * Policy: an entry larger than [maxBytes] alone cannot be retained and
     * is evicted like any other overflow (regenerable audio — safe to drop).
     */
    private fun bootstrap() {
        if (!root.isDirectory) return
        val aged = mutableListOf<Pair<PregenKey, Long>>() // key to pcm lastModified
        root.walkTopDown().forEach { file ->
            when {
                file.name.endsWith(".tmp") -> file.delete()
                file.name.endsWith(".pcm") -> {
                    val key = PregenKey.parse(file.relativeTo(root).path.removeSuffix(".pcm"))
                    val metaFile = File(file.parentFile, file.nameWithoutExtension + ".meta")
                    if (key == null || !metaFile.isFile || parseMeta(metaFile.readText().trim()) == null) {
                        file.delete()
                        metaFile.delete()
                    } else {
                        aged += key to file.lastModified()
                    }
                }
                file.name.endsWith(".meta") -> {
                    // Metadata without its PCM cannot be served either; drop.
                    val pcm = File(file.parentFile, file.nameWithoutExtension + ".pcm")
                    if (!pcm.isFile) file.delete()
                }
            }
        }
        // Oldest first: insertion order of an access-ordered map is the
        // eviction order, so the head is the least-recently-used entry.
        aged
            .sortedWith(compareBy({ it.second }, { it.first.toString() }))
            .forEach { (key, _) -> recency[key] = ++now }
        if (totalBytesLocked() > maxBytes) evictLocked()
    }

    fun put(
        key: PregenKey,
        audio: PregenAudio,
    ) = synchronized(lock) {
        val pcmFile = pcmFile(key)
        pcmFile.parentFile?.mkdirs()
        val tmp = File(pcmFile.parentFile, pcmFile.name + ".tmp")
        tmp.writeBytes(audio.pcm)
        File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta").writeText(meta(audio))
        if (!tmp.renameTo(pcmFile)) {
            tmp.delete()
            return // another writer won the race; the cache stays consistent
        }
        now++
        recency[key] = now
        evictLocked()
    }

    fun get(key: PregenKey): PregenAudio? =
        synchronized(lock) {
            val pcmFile = pcmFile(key)
            if (!pcmFile.isFile) return null
            val metaFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta")
            val meta = metaFile.takeIf { it.isFile }?.readText()?.trim() ?: return null
            val parsed = parseMeta(meta) ?: return null
            now++
            recency[key] = now
            PregenAudio(pcmFile.readBytes(), parsed.first, parsed.second)
        }

    /** Existence check without reading the PCM — the pregen planner's skip-check. */
    fun contains(key: PregenKey): Boolean = synchronized(lock) { pcmFile(key).isFile }

    /** Free bytes under the cap; the planner stops at 0 (a put would only evict). */
    fun bytesRemaining(): Long = synchronized(lock) { (maxBytes - totalBytesLocked()).coerceAtLeast(0) }

    /** Exact on-disk bytes for one passage (the .pcm file), or null when not cached. */
    fun sizeOf(key: PregenKey): Long? =
        synchronized(lock) {
            val file = pcmFile(key)
            if (file.isFile) file.length() else null
        }

    /** Bytes on disk per book — pcm + sidecar files under each `bookId` subtree (decisions #44). */
    fun usageByBook(): Map<String, Long> =
        synchronized(lock) {
            root
                .listFiles()
                ?.filter { it.isDirectory }
                ?.associate { dir -> dir.name to dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
                ?.filterValues { it > 0L }
                ?: emptyMap()
        }

    /**
     * Spine keys currently on disk for one book under one voice+speed. The
     * [recency] map is the exact valid-entry set (bootstrapped at open, CR-4,
     * maintained by put/get/evict) — no disk walk. Legacy v1 keys parse as the
     * default engine, so they are included naturally.
     */
    fun generatedKeys(
        bookId: String,
        voice: String,
        speed: Double,
    ): Set<PregenKey> =
        synchronized(lock) {
            recency.keys.filterTo(mutableSetOf()) {
                it.bookId == bookId && it.engine == PregenKey.DEFAULT_ENGINE &&
                    it.voice == voice && it.speed == speed
            }
        }

    fun delete(key: PregenKey) =
        synchronized(lock) {
            val pcmFile = pcmFile(key)
            pcmFile.delete()
            File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta").delete()
            recency.remove(key)
            pruneEmptyDirs(root, pcmFile.parentFile)
        }

    fun deleteBook(bookId: String) =
        synchronized(lock) {
            File(root, bookId).deleteRecursively()
            recency.keys.removeAll { it.bookId == bookId }
        }

    fun totalBytes(): Long = synchronized(lock) { root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    private fun evictLocked() {
        var total = totalBytesLocked()
        while (total > maxBytes) {
            val lru = recency.keys.firstOrNull() ?: break
            val file = pcmFile(lru)
            file.delete()
            File(file.parentFile, file.nameWithoutExtension + ".meta").delete()
            recency.remove(lru)
            pruneEmptyDirs(root, file.parentFile)
            total = totalBytesLocked()
        }
    }

    private fun totalBytesLocked(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun pcmFile(key: PregenKey): File {
        val slug = key.toString()
        val bookPart = slug.substringBefore('/')
        val rest = slug.substringAfter('/')
        val folderPart = rest.substringBeforeLast('/')
        val namePart = rest.substringAfterLast('/')
        val v2 = File(File(File(root, bookPart), folderPart), "$namePart.pcm")
        // Engine-dimension migration (decisions #54): the pre-engine tier
        // wrote <bookId>/<voice>/<speed>/… without the engine segment. Only
        // the default engine predates that dimension, so ONLY kokoro keys
        // may resolve to a v1 file — a non-default engine must never see a
        // voice-only path (that is the collision #54 exists to prevent). The
        // v2 file is authoritative once written; the v1 fallback keeps legacy
        // entries genuinely addressable (get/contains/sizeOf/delete/evict all
        // resolve through here), so an over-cap or cap-full v1 tier still
        // converges and evicts REAL bytes instead of phantom keys (CR-4).
        if (key.engine == PregenKey.DEFAULT_ENGINE && !v2.isFile) {
            val legacy = File(File(File(root, bookPart), "${key.voice}/${PregenKey.formatSpeed(key.speed)}"), "$namePart.pcm")
            if (legacy.isFile) return legacy
        }
        return v2
    }

    private fun meta(audio: PregenAudio): String =
        buildString {
            append(audio.sampleRateHz)
            audio.segments?.forEach { append('\n').append(it.startSeconds).append(';').append(it.endSeconds) }
        }

    private fun parseMeta(meta: String): Pair<Int, List<io.github.moronigranja.ayvu.tts.SegmentAnchor>>? {
        val lines = meta.lines().filter { it.isNotBlank() }
        val sampleRate = lines.firstOrNull()?.toIntOrNull() ?: return null
        val segments =
            lines.drop(1).mapNotNull { line ->
                val (start, end) = line.split(';').takeIf { it.size == 2 } ?: return@mapNotNull null
                val s = start.toDoubleOrNull() ?: return@mapNotNull null
                val e = end.toDoubleOrNull() ?: return@mapNotNull null
                io.github.moronigranja.ayvu.tts
                    .SegmentAnchor(s, e)
            }
        return sampleRate to segments
    }

    private fun pruneEmptyDirs(
        root: File,
        start: File,
    ) {
        var dir = start
        while (dir != root && dir.isDirectory && dir.listFiles().orEmpty().isEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
    }

    companion object {
        /**
         * 4 GiB of PCM — whole books fit (an hour ≈ 170 MB at 24 kHz 16-bit);
         * LRU still bounds growth past the cap, and the device has the space
         * (decision #42 follow-up).
         */
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024 * 1024
    }
}
