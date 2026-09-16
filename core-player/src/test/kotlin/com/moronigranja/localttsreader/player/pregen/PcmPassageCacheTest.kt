package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.tts.SegmentAnchor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** T5-core disk tier: round-trip, LRU eviction, book deletion, atomicity. */
class PcmPassageCacheTest {
    @TempDir
    lateinit var tempDir: File

    private fun audio(seed: Int) =
        PregenAudio(
            pcm = ByteArray(2_000 + seed) { ((it + seed) % 256).toByte() },
            sampleRateHz = 24_000,
            segments = listOf(SegmentAnchor(0.5, 1.5)),
        )

    private fun key(
        chapter: Int,
        passage: Int,
    ) = PregenKey("book-$chapter-$passage", chapter, passage, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)

    @Test
    fun `put and get round-trip pcm and anchors`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(key(0, 1), audio(7))
        val loaded = cache.get(key(0, 1))
        assertArrayEquals(audio(7).pcm, loaded?.pcm)
        assertEquals(24_000, loaded?.sampleRateHz)
        assertEquals(listOf(SegmentAnchor(0.5, 1.5)), loaded?.segments)
        assertNull(cache.get(key(0, 2)), "missing key")
    }

    @Test
    fun `LRU eviction drops the least recently used under the byte cap`() {
        // each entry is ~2 KB; a 6 KB cap holds two entries (+ meta).
        val cache = PcmPassageCache(tempDir, maxBytes = 6_000)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.put(key(0, 2), audio(2)) // evicts the first
        assertNull(cache.get(key(0, 0)), "oldest evicted")
        assertTrue(cache.get(key(0, 1)) != null, "kept")
        assertTrue(cache.get(key(0, 2)) != null, "kept")
        assertTrue(cache.totalBytes() <= 6_000, "cap enforced")
    }

    @Test
    fun `get refreshes LRU recency`() {
        val cache = PcmPassageCache(tempDir, maxBytes = 6_000)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.get(key(0, 0)) // becomes the newest
        cache.put(key(0, 2), audio(2)) // evicts key(0,1) instead
        assertTrue(cache.get(key(0, 0)) != null)
        assertNull(cache.get(key(0, 1)), "least recently used evicted")
    }

    @Test
    fun `delete removes a single key and book removal clears the subtree`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.delete(key(0, 0))
        assertNull(cache.get(key(0, 0)))
        assertTrue(cache.get(key(0, 1)) != null)

        cache.put(PregenKey("b2", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(3))
        cache.deleteBook("book-0-1")
        assertNull(cache.get(key(0, 1)))
        assertTrue(cache.get(PregenKey("b2", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)) != null, "other book intact")
    }

    @Test
    fun `a tombstone-less missing pcm is null`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertNull(cache.get(key(9, 9)))
    }

    @Test
    fun `sizeOf reports the exact on-disk bytes per passage`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertNull(cache.sizeOf(key(0, 0)), "absent passage")
        cache.put(key(0, 0), audio(7))
        assertEquals(2_007L, cache.sizeOf(key(0, 0)), "the pcm file's length (meta excluded)")
        assertEquals(2_007L, cache.sizeOf(key(0, 0)), "stat reads don't mutate anything")
    }

    @Test
    fun `usageByBook sums every file per book subtree`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(0)) // 2000 + meta
        cache.put(PregenKey("b1", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(5)) // 2005 + meta
        cache.put(PregenKey("b2", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(9)) // 2009 + meta

        val usage = cache.usageByBook()
        assertEquals(setOf("b1", "b2"), usage.keys)
        // pcm bytes + the meta sidecars (small, counted but not bit-pinned).
        val b1Pcm =
            cache.sizeOf(PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE))!! +
                cache.sizeOf(PregenKey("b1", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE))!!
        val b2Pcm = cache.sizeOf(PregenKey("b2", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE))!!
        assertTrue(usage["b1"]!! > b1Pcm && usage["b1"]!! - b1Pcm in 2..200, "b1 includes sidecars")
        assertTrue(usage["b2"]!! > b2Pcm && usage["b2"]!! - b2Pcm in 2..200, "b2 includes sidecars")
        assertEquals(b1Pcm + (usage["b1"]!! - b1Pcm) + b2Pcm + (usage["b2"]!! - b2Pcm), usage.values.sum())
    }

    @Test
    fun `usageByBook is empty for an empty cache`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertTrue(cache.usageByBook().isEmpty())
    }

    // ------------------------------------------------------------------
    // CR-4: a reopened cache must keep LRU replacement working
    // ------------------------------------------------------------------

    private fun pcmPath(key: PregenKey): File {
        val slug = key.toString()
        return File(
            File(File(tempDir, slug.substringBefore('/')), slug.substringAfter('/').substringBeforeLast('/')),
            slug.substringAfterLast('/') + ".pcm",
        )
    }

    private fun age(
        key: PregenKey,
        epochMillis: Long,
    ) {
        assertTrue(pcmPath(key).setLastModified(epochMillis), "test controls on-disk age")
    }

    /** CR-4 acceptance: instance A writes two entries, B opens the same root
     * and writes a third over a two-entry cap — an OLD entry, not the new
     * one, is evicted (fixes startup self-eviction freezing replacement). */
    @Test
    fun `reopen bootstraps eviction order so an old entry is evicted not the new one`() {
        // Each entry ~2 KB (+ meta): a 6 KB cap holds two entries.
        val a = PcmPassageCache(tempDir, maxBytes = 6_000)
        a.put(key(0, 0), audio(0))
        a.put(key(0, 1), audio(1))
        age(key(0, 0), 1_000_000)
        age(key(0, 1), 2_000_000)

        val b = PcmPassageCache(tempDir, maxBytes = 6_000)
        b.put(key(0, 2), audio(2)) // needs room: must evict the older entry

        assertNull(b.get(key(0, 0)), "oldest on disk evicted")
        assertTrue(b.get(key(0, 1)) != null, "kept")
        assertTrue(b.get(key(0, 2)) != null, "the new entry must survive")
        assertTrue(b.totalBytes() <= 6_000, "cap enforced after reopen")
    }

    /** CR-4: a cache opened over its cap converges below it at construction,
     * so the pregen planner's bytesRemaining gate is never stuck at 0. */
    @Test
    fun `opening an over-cap cache converges below the cap`() {
        val a = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        a.put(key(0, 0), audio(0))
        a.put(key(0, 1), audio(1))
        a.put(key(0, 2), audio(2))
        a.put(key(0, 3), audio(3))
        (0..3).forEach { i -> age(key(0, i), 1_000L * (i + 1)) }

        val b = PcmPassageCache(tempDir, maxBytes = 6_000) // ~8 KB on disk

        assertTrue(b.totalBytes() <= 6_000, "bootstrap converged below the cap")
        assertNull(b.get(key(0, 0)), "oldest evicted")
        assertNull(b.get(key(0, 1)), "second-oldest evicted")
        assertTrue(b.get(key(0, 2)) != null)
        assertTrue(b.get(key(0, 3)) != null)
    }

    /** CR-4: stale .tmp, PCM-without-sidecar, sidecar-without-PCM and
     * unparseable paths are removed on reopen — `contains` can never report
     * a permanent false hit, and the passage is regenerable. */
    @Test
    fun `reopen removes invalid artifacts so passages can be regenerated`() {
        val cacheA = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        val valid = PregenKey("book-invalid", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        cacheA.put(valid, audio(1))
        val speedDir = File(File(tempDir, "book-invalid"), "kokoro/af_heart/1")
        File(speedDir, "c0p1.pcm.tmp").writeText("stale")
        File(speedDir, "c0p1.pcm").writeBytes(ByteArray(100)) // no sidecar
        File(speedDir, "c0p2.meta").writeText("24000") // no pcm
        File(tempDir, "junk.pcm").writeBytes(ByteArray(50)) // unparseable path

        val reopened = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)

        assertFalse(File(speedDir, "c0p1.pcm.tmp").exists(), "stale tmp removed")
        assertFalse(File(speedDir, "c0p1.pcm").exists(), "meta-less pcm removed")
        assertFalse(File(speedDir, "c0p2.meta").exists(), "pcm-less meta removed")
        assertFalse(File(tempDir, "junk.pcm").exists(), "unparseable path removed")
        assertFalse(reopened.contains(PregenKey("book-invalid", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)))
        reopened.put(PregenKey("book-invalid", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(2))
        assertTrue(reopened.contains(PregenKey("book-invalid", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)), "regenerable")
        assertTrue(reopened.get(valid) != null, "valid entries survive reopen")
    }

    /** Explicit oversized policy: an entry alone larger than the cap cannot
     * be retained; it is evicted like any other overflow. */
    @Test
    fun `an entry larger than the cap alone cannot be retained`() {
        val cache = PcmPassageCache(tempDir, maxBytes = 1_000)
        cache.put(key(0, 0), audio(9)) // pcm alone is ~2 KB > cap
        assertNull(cache.get(key(0, 0)), "oversized entry evicted by the cap policy")
        assertTrue(cache.totalBytes() <= 1_000)
    }

    /** Engine-dimension migration (decisions #54): pre-engine v1 paths
     * `<root>/<bookId>/<voice>/<speed>/…` bootstrap as kokoro entries — an
     * upgrade keeps every existing PCM addressable and never treats it as a
     * disk artifact (CR-4 deletes only what cannot parse). */
    @Test
    fun `legacy pre-engine paths bootstrap as kokoro entries`() {
        val legacyDir = File(File(File(tempDir, "b1"), "af_heart"), "1")
        assertTrue(legacyDir.mkdirs(), "test writes the v1 layout by hand")
        File(legacyDir, "c0p0.pcm").writeBytes(ByteArray(2_000) { 7 })
        File(legacyDir, "c0p0.meta").writeText("24000\n0.5;1.5")

        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)

        val expected = PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        assertTrue(cache.contains(expected), "legacy entry stays addressable under the default engine")
        assertEquals(2_000L, cache.sizeOf(expected), "legacy PCM is served and counted")
        assertEquals(listOf(SegmentAnchor(0.5, 1.5)), cache.get(expected)?.segments)
        assertEquals(setOf("b1"), cache.usageByBook().keys, "bookId subtree remains the usage unit")
    }

    /** CR-4 across the migration: an over-cap v1 tier converges below the cap
     * at reopen — legacy entries evict as REAL bytes, not phantom keys that
     * leave [PcmPassageCache.bytesRemaining] stuck at 0 (the pregen gate). */
    @Test
    fun `an over-cap legacy tier converges below the cap at reopen`() {
        repeat(4) { i ->
            val dir = File(File(File(tempDir, "b1"), "af_heart"), "1")
            dir.mkdirs()
            File(dir, "c0p$i.pcm").writeBytes(ByteArray(2_000 + i) { 1 })
            File(dir, "c0p$i.meta").writeText("24000")
        }

        val cache = PcmPassageCache(tempDir, maxBytes = 6_000) // ~8 KB of v1 on disk

        assertTrue(cache.totalBytes() <= 6_000, "v1 entries evict as real bytes under the cap")
        assertTrue(cache.bytesRemaining() > 0, "the pregen gate is not stuck at 0")
        assertTrue(cache.contains(PregenKey("b1", 0, 3, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)))
    }

    /** A put on a legacy keyed entry overwrites its v1 slot — one file per
     * logical key, so the byte cap never double-counts a migrated entry. */
    @Test
    fun `a put on a legacy key replaces its v1 slot without doubling`() {
        val legacyDir = File(File(File(tempDir, "b1"), "af_heart"), "1")
        assertTrue(legacyDir.mkdirs(), "test writes the v1 layout by hand")
        File(legacyDir, "c0p0.pcm").writeBytes(ByteArray(2_000) { 7 })
        File(legacyDir, "c0p0.meta").writeText("24000")

        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        val key = PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        cache.put(key, audio(1)) // 2001 bytes

        assertEquals(2_001L, cache.sizeOf(key), "the put replaced the v1 file, not doubled it")
        assertFalse(
            File(File(File(tempDir, "b1"), "kokoro"), "af_heart/1/c0p0.pcm").exists(),
            "no v2 twin was created",
        )
    }

    // ------------------------------------------------------------------
    // Coverage source (coverage-bar redo, step 1): generatedKeys
    // ------------------------------------------------------------------

    /** The exact valid-entry set the coverage bar reads: only the queried
     * bookId+voice+speed, default engine, on-disk keys only. */
    @Test
    fun `generatedKeys returns only the queried book voice and speed`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        val wanted = PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        cache.put(wanted, audio(1))
        cache.put(PregenKey("b1", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(2))
        cache.put(PregenKey("b1", 0, 2, "af_sara", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(3)) // other voice
        cache.put(PregenKey("b1", 0, 3, "af_heart", 1.5, engine = PregenKey.DEFAULT_ENGINE), audio(4)) // other speed
        cache.put(PregenKey("b2", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE), audio(5)) // other book

        assertEquals(
            setOf(wanted, PregenKey("b1", 0, 1, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)),
            cache.generatedKeys("b1", "af_heart", 1.0),
            "books/voices/speeds are all part of the key",
        )
        assertEquals(setOf<PregenKey>(), cache.generatedKeys("b1", "af_heart", 2.0), "absent speed → empty")
        assertEquals(setOf<PregenKey>(), cache.generatedKeys("nope", "af_heart", 1.0), "absent book → empty")
    }

    /** Legacy v1 entries (no engine path segment) parse as the default
     * engine, so the coverage source includes them naturally. */
    @Test
    fun `generatedKeys includes a legacy v1 layout entry`() {
        val legacyDir = File(File(File(tempDir, "b1"), "af_heart"), "1")
        assertTrue(legacyDir.mkdirs(), "test writes the v1 layout by hand")
        File(legacyDir, "c0p0.pcm").writeBytes(ByteArray(2_000) { 7 })
        File(legacyDir, "c0p0.meta").writeText("24000\n0.5;1.5")

        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)

        assertEquals(
            setOf(PregenKey("b1", 0, 0, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)),
            cache.generatedKeys("b1", "af_heart", 1.0),
        )
    }

    /** [generatedKeys] reflects the live recency map: an entry evicted by a
     * cap-breaking put is no longer reported. */
    @Test
    fun `generatedKeys excludes a key evicted by a cap-breaking put`() {
        val cache = PcmPassageCache(tempDir, maxBytes = 6_000) // ~2 KB per entry + meta → holds 2
        val sameBook = { chapter: Int, passage: Int ->
            PregenKey("b1", chapter, passage, "af_heart", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        }
        cache.put(sameBook(0, 0), audio(0))
        cache.put(sameBook(0, 1), audio(1))
        cache.put(sameBook(0, 2), audio(2)) // evicts b1/0/0

        assertEquals(
            setOf(sameBook(0, 1), sameBook(0, 2)),
            cache.generatedKeys("b1", "af_heart", 1.0),
            "the evicted entry is not on disk anymore",
        )
    }

    // ------------------------------------------------------------------
    // Translator dimension (decisions #161/#162)
    // ------------------------------------------------------------------

    /** An LFM render must never be served for the same passage translated by
     * another engine — different engines produce different audio for identical
     * text, so a shared path would play the wrong language variant. */
    @Test
    fun `a translated key is isolated by its translator`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        val lfm =
            PregenKey(
                "b1",
                0,
                0,
                "pf_dora",
                1.0,
                engine = PregenKey.DEFAULT_ENGINE,
                target = TranslationTarget("pt-BR"),
            )
        val small100 = lfm.copy(target = TranslationTarget("pt-BR", PregenKey.SMALL100_TRANSLATOR))
        cache.put(small100, audio(3))

        assertNull(cache.get(lfm), "the small-100-era entry is not an LFM hit")
        assertArrayEquals(audio(3).pcm, cache.get(small100)?.pcm, "the entry itself is intact")
        cache.put(lfm, audio(9))
        assertArrayEquals(audio(9).pcm, cache.get(lfm)?.pcm)
        assertArrayEquals(audio(3).pcm, cache.get(small100)?.pcm, "both variants coexist")
    }
}
