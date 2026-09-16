package io.github.moronigranja.ayvu.player.pregen

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T5-core: bounded look-ahead, dedup, stale pruning, failure fallback. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PregenQueueTest {
    private val book =
        Book(
            id = "b1",
            title = "Anna",
            chapters =
                listOf(
                    Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1"), TextPassage("p2"))),
                    Chapter(1, "Two", listOf(TextPassage("p3"), TextPassage("p4"))),
                ),
        )

    private var callCount = 0
    private var failIndex: Int? = null

    /** Deterministic fake: size = (spine index + 1) * 1000 bytes per passage. */
    private suspend fun fake(
        text: String,
        @Suppress("UNUSED_PARAMETER") chapterIndex: Int,
        @Suppress("UNUSED_PARAMETER") passageIndex: Int,
    ): SynthesisOutcome {
        callCount++
        val spineIndex = text.drop(1).toInt()
        if (spineIndex == failIndex) return SynthesisOutcome.Failed("boom")
        val size = (spineIndex + 1) * 1_000
        val pcm = ByteArray(size) { ((it + spineIndex) % 256).toByte() }
        return SynthesisOutcome.Audio(
            pcm,
            24_000,
            1,
            listOf(SegmentAnchor(0.0, 1.0)),
        )
    }

    private fun queue(lookahead: Int = 2) = PregenQueue(book, "af_heart", 1.0, ::fake, lookahead)

    /** A queue whose fake passages are 1 s of audio each, so a small time bound caps fills. */
    private fun timeQueue(lookaheadSeconds: Double) =
        PregenQueue(
            book,
            "af_heart",
            1.0,
            { _, _, _ ->
                callCount++
                SynthesisOutcome.Audio(ByteArray(24_000) { 0 }, 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
            },
            lookahead = 20,
            lookaheadSeconds = lookaheadSeconds,
        )

    @Test
    fun `pre-generates the next passages within the bound`() =
        runTest {
            val q = queue()
            q.ensure(PlayerPosition("b1", 0, 0))
            assertEquals(2, q.size, "lookahead=2 fills immediately")
            assertEquals(2, callCount)

            val one = q.take(0, 1)
            assertEquals(2_000, one?.pcm?.size, "p1 = (1+1)*1000")
            assertEquals(listOf(SegmentAnchor(0.0, 1.0)), one?.segments)

            val fast = q.take(0, 2)
            assertEquals(3_000, fast?.pcm?.size, "p2 = (2+1)*1000")
            assertNull(q.take(0, 2), "take consumes")
        }

    @Test
    fun `ensure is idempotent - no re-synthesis of queued passages`() =
        runTest {
            val q = queue()
            q.ensure(PlayerPosition("b1", 0, 0))
            q.ensure(PlayerPosition("b1", 0, 0))
            assertEquals(2, q.size)
            assertEquals(2, callCount, "second ensure must not re-synthesize")
        }

    @Test
    fun `refills after the playhead advances`() =
        runTest {
            val q = queue()
            q.ensure(PlayerPosition("b1", 0, 0)) // queues 0/1, 0/2
            q.take(0, 1)
            q.ensure(PlayerPosition("b1", 0, 1)) // prunes 0/1-ish, refills toward 1/0
            val refill = q.take(0, 2)
            assertEquals(3_000, refill?.pcm?.size)
            assertEquals(3, callCount, "0/1 + 0/2 first, then 1/0 refill")
        }

    @Test
    fun `a jump forward prunes the stale look-ahead`() =
        runTest {
            val q = queue()
            q.ensure(PlayerPosition("b1", 0, 0)) // 0/1, 0/2 queued
            q.ensure(PlayerPosition("b1", 1, 0)) // jump: past passages drop, 1/1 + end queued
            assertNull(q.take(0, 1), "stale look-ahead pruned")
            val fresh = q.take(1, 1)
            assertEquals(5_000, fresh?.pcm?.size, "p4 = (4+1)*1000")
        }

    @Test
    fun `failed synthesis stops the look-ahead`() =
        runTest {
            failIndex = 1
            val q = queue(lookahead = 3)
            q.ensure(PlayerPosition("b1", 0, 0)) // p1 (spine 1) fails
            assertEquals(0, q.size, "p1 failed -> nothing queued past the gap")
            assertNull(q.take(0, 1))
        }

    @Test
    fun `book end yields nothing`() =
        runTest {
            val q = queue()
            q.ensure(PlayerPosition("b1", 1, 1)) // last passage: no look-ahead
            assertEquals(0, q.size)
        }

    @Test
    fun `the time bound caps buffered audio ahead of the playhead`() =
        runTest {
            callCount = 0
            val q = timeQueue(3.0) // 1 s passages -> fills ~3 s then stops
            q.ensure(PlayerPosition("b1", 0, 0))
            // 0/1, 0/2, 0/3 each 1 s: fills 3 s, at the time bound (lookahead=20 not the cap).
            assertTrue(q.size in 2..4, "time-bound fill, got size=${q.size}")
            assertTrue(q.take(0, 1) != null)
        }

    @Test
    fun `in-flight plan yields when the playhead jumps forward mid-ensure (D1 survive-seek)`() =
        runTest {
            val q = queue(lookahead = 3)
            // The first synthesis flips the playhead far ahead (a seek landed);
            // ensure must stop synthesizing the stale plan right there.
            var playhead = PlayerPosition("b1", 0, 0)
            val jump =
                PregenQueue(
                    book,
                    "af_heart",
                    1.0,
                    { text, _, _ ->
                        callCount++
                        if (playhead == PlayerPosition("b1", 0, 0)) {
                            playhead = PlayerPosition("b1", 1, 0) // seek during the first in-flight passage
                        }
                        val spineIndex = text.drop(1).toInt()
                        SynthesisOutcome.Audio(
                            ByteArray((spineIndex + 1) * 1_000) { 0 },
                            24_000,
                            1,
                            listOf(SegmentAnchor(0.0, 1.0)),
                        )
                    },
                    3,
                )

            jump.ensure(PlayerPosition("b1", 0, 0)) { playhead }

            assertEquals(
                1,
                callCount,
                "the plan yields after the playhead overtakes the next planned passage",
            )
            // The one in-flight passage (0/1) is queued but stale once the playhead
            // moved: the NEXT ensure from the new playhead prunes and refills.
            jump.ensure(PlayerPosition("b1", 1, 0)) { playhead }
            assertNull(jump.take(0, 1), "stale in-flight result pruned by the re-arm")
            assertTrue(jump.take(1, 1) != null, "refill from the new playhead")
        }

    @Test
    fun `the D1 horizon fills about 30 s of audio ahead and stops`() =
        runTest {
            callCount = 0
            // 40 one-second passages: the DEFAULT horizon (30 s, decisions #155)
            // is what stops the fill — not the passage ceiling (60, the production
            // bulwark) and not the book end.
            val longBook =
                Book(
                    id = "b1",
                    title = "Long",
                    chapters = listOf(Chapter(0, "One", (0 until 40).map { i -> TextPassage("p$i") })),
                )
            val q =
                PregenQueue(
                    longBook,
                    "af_heart",
                    1.0,
                    { _, _, _ ->
                        callCount++
                        SynthesisOutcome.Audio(ByteArray(24_000 * 2), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
                    },
                    lookahead = 60,
                )
            val from = PlayerPosition("b1", 0, 0)
            q.ensure(from)
            assertEquals(30, callCount, "the fill stops AT the horizon (30 × 1 s), synthesizing nothing extra")
            assertEquals(30.0, q.aheadSeconds(from), 1e-9, "~30 s of audio queued ahead — the D1 horizon target")
            assertEquals(30, q.size, "queue memory is horizon-bounded, not book-bounded")
        }

    @Test
    fun `a concurrent ensure from a seeked playhead joins without waiting on the stale in-flight owner`() =
        runTest {
            callCount = 0
            val gate = CompletableDeferred<Unit>()
            var playhead = PlayerPosition("b1", 0, 0)
            val q =
                PregenQueue(
                    book,
                    "af_heart",
                    1.0,
                    { text, _, _ ->
                        callCount++
                        if (text == "p1") gate.await() // the seek lands while p1 is in flight
                        SynthesisOutcome.Audio(ByteArray(24_000) { 0 }, 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
                    },
                    lookahead = 3,
                )
            val a = launch { q.ensure(playhead) { playhead } }
            runCurrent() // A parks inside p1's synthesis; in-flight = {0/1, 0/2, 1/0}

            // A seek mid-flight: the playhead jumps forward. The joiner ensure from
            // the NEW playhead must return immediately — never wait on A's parked
            // passage (the dead-owner wait) — and never duplicate in-flight work.
            playhead = PlayerPosition("b1", 0, 2)
            q.ensure(PlayerPosition("b1", 0, 2)) { playhead }
            assertTrue(!gate.isCompleted, "the join never touched the owner's in-flight synthesis")
            assertEquals(1, callCount, "the join adds no synthesis (contiguous prefix stops at in-flight work)")

            gate.complete(Unit)
            a.join() // A finishes its in-flight passage, yields the stale remainder
            // The next ensure re-plans from the seeked playhead: prunes the stale
            // 0/1 and refills — the seek costs one in-flight passage, not a restart.
            q.ensure(PlayerPosition("b1", 0, 2)) { playhead }
            assertEquals(3, callCount, "refill synthesizes 1/0 + 1/1 only (no stale work repeated)")
            assertNull(q.take(0, 1), "the stale in-flight result is pruned by the re-plan")
            assertTrue(q.take(1, 0) != null, "the new playhead's successor is queued")
        }

    @Test
    fun `PregenKey round-trips through its path form - engine dimension`() {
        // V2 layout: <bookId>/<engine>/<voice>/<speed>/c<ch>p<passage>
        val key = PregenKey("abc123", 2, 5, "af_heart", 1.5, engine = "cosyvoice3")
        assertEquals("abc123/cosyvoice3/af_heart/1_5/c2p5", key.toString())
        assertEquals(key, PregenKey.parse(key.toString()))
        val intSpeed = PregenKey("abc123", 0, 0, "pf_dora", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        assertEquals("abc123/kokoro/pf_dora/1/c0p0", intSpeed.toString())
        assertEquals(intSpeed, PregenKey.parse(intSpeed.toString()))
        assertTrue(PregenKey.parse("") == null)
    }

    @Test
    fun `PregenKey parses legacy paths without an engine segment as kokoro`() {
        // V1 layout (pre-engine): <bookId>/<voice>/<speed>/c<ch>p<passage>
        val expected = PregenKey("abc123", 0, 0, "pf_dora", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        assertEquals(expected, PregenKey.parse("abc123/pf_dora/1/c0p0"))
        val fractionalSpeed = PregenKey("abc123", 2, 5, "af_heart", 1.5, engine = PregenKey.DEFAULT_ENGINE)
        assertEquals(fractionalSpeed, PregenKey.parse("abc123/af_heart/1_5/c2p5"))
        // ... and the v2 form re-serializes with the explicit engine segment.
        assertEquals(
            "abc123/kokoro/pf_dora/1/c0p0",
            PregenKey.parse("abc123/pf_dora/1/c0p0")?.toString(),
        )
    }

    @Test
    fun `PregenKey round-trips through its path form - target dimension`() {
        // V2b layout:
        // <bookId>/<engine>/<voice>/<speed>/x<lang>/t<translator>/c<ch>p<passage>
        val lfm =
            PregenKey(
                "abc123",
                0,
                0,
                "pf_dora",
                1.0,
                engine = PregenKey.DEFAULT_ENGINE,
                target = TranslationTarget("pt-BR"),
            )
        assertEquals("abc123/kokoro/pf_dora/1/xpt-BR/tlfm12b/c0p0", lfm.toString())
        assertEquals(lfm, PregenKey.parse(lfm.toString()))
        // A target whose translator is not the default renders its own
        // `t<translator>` segment and round-trips as itself.
        val small100 = lfm.copy(target = TranslationTarget("pt-BR", PregenKey.SMALL100_TRANSLATOR))
        assertEquals("abc123/kokoro/pf_dora/1/xpt-BR/tsmall100/c0p0", small100.toString())
        assertEquals(small100, PregenKey.parse(small100.toString()))
        // No target = no translation segments: the v2 form, unchanged.
        assertEquals("abc123/kokoro/pf_dora/1/c0p0", lfm.copy(target = null).toString())
    }

    @Test
    fun `PregenKey parses a pre-translator-dimension key as small100`() {
        // V2a (6 segments): written before decisions #162, so the only runtime
        // that could have produced it is SMaLL-100. Defaulting to it is what
        // keeps an LFM run from treating that audio as a cache hit.
        val parsed = PregenKey.parse("abc123/kokoro/pf_dora/1/xpt-BR/c0p0")
        assertEquals(TranslationTarget("pt-BR", PregenKey.SMALL100_TRANSLATOR), parsed?.target)
        assertEquals("pt-BR", parsed?.target?.lang)
        assertEquals(null, parsed?.copy(target = null)?.target, "the legacy marker is parse-only")
        // Malformed translator segments are rejected, never guessed.
        assertNull(PregenKey.parse("abc123/kokoro/pf_dora/1/xpt-BR/zzz/c0p0"))
        assertNull(PregenKey.parse("abc123/kokoro/pf_dora/1/xpt-BR/t/c0p0"))
        assertNull(PregenKey.parse("abc123/kokoro/pf_dora/1/xpt-BR/tlfm12b/c0p0/extra"))
    }

    @Test
    fun `PregenKey round-trips identity through every disk form`() {
        // No target + LFM target: the toString forms are byte-identical to
        // the pre-TranslationTarget forms (the on-disk layout never changed).
        val plain = PregenKey("abc123", 0, 0, "pf_dora", 1.0, engine = PregenKey.DEFAULT_ENGINE)
        assertEquals("abc123/kokoro/pf_dora/1/c0p0", plain.toString())
        assertEquals(plain, PregenKey.parse(plain.toString()))
        val lfm =
            PregenKey(
                "abc123",
                0,
                0,
                "pf_dora",
                1.0,
                engine = PregenKey.DEFAULT_ENGINE,
                target = TranslationTarget("pt-BR"),
            )
        assertEquals("abc123/kokoro/pf_dora/1/xpt-BR/tlfm12b/c0p0", lfm.toString())
        assertEquals(lfm, PregenKey.parse(lfm.toString()))
        // The 6-segment v2a path predates the translator dimension — its only
        // possible author is the retired SMaLL-100 runtime.
        assertEquals(
            TranslationTarget("pt-BR", PregenKey.SMALL100_TRANSLATOR),
            PregenKey.parse("abc123/kokoro/pf_dora/1/xpt-BR/c0p0")?.target,
        )
        // The 4-segment v1 path predates the engine dimension — the default
        // engine, and it re-serializes with the explicit engine segment.
        assertEquals(plain, PregenKey.parse("abc123/pf_dora/1/c0p0"))
        assertEquals("abc123/kokoro/pf_dora/1/c0p0", PregenKey.parse("abc123/pf_dora/1/c0p0")?.toString())
    }

    @Test
    fun `concurrent ensures share in-flight work`() =
        runTest {
            callCount = 0
            val synthesized = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val q =
                PregenQueue(
                    book,
                    "af_heart",
                    1.0,
                    { text, _, _ ->
                        callCount++
                        synthesized += text
                        if (!gate.isCompleted) gate.await()
                        SynthesisOutcome.Audio(ByteArray(24_000) { 0 }, 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
                    },
                    lookahead = 20,
                    lookaheadSeconds = 5.0,
                )
            val a = launch { q.ensure(PlayerPosition("b1", 0, 0)) }
            runCurrent() // A enters synthesis and parks on the gate
            val b = launch { q.ensure(PlayerPosition("b1", 0, 0)) }
            runCurrent() // B plans: everything is in-flight -> synthesizes nothing
            assertTrue(b.isCompleted, "second ensure returns without synthesizing")
            assertEquals(1, callCount, "0/1 synthesized once, not twice")
            gate.complete(Unit)
            a.join()
            assertEquals(4, callCount, "after release, A synthesizes each passage after 0/0 once (5 s target, 1 s passages, book end)")
            assertEquals(synthesized.size, synthesized.distinct().size, "no passage synthesized twice")
        }

    @Test
    fun `concurrent ensures plan a contiguous prefix - no far-ahead hole`() =
        runTest {
            callCount = 0
            val gate = CompletableDeferred<Unit>()
            val q =
                PregenQueue(
                    book,
                    "af_heart",
                    1.0,
                    { _, _, _ ->
                        callCount++
                        if (!gate.isCompleted) gate.await()
                        SynthesisOutcome.Audio(ByteArray(24_000) { 0 }, 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
                    },
                    lookahead = 2, // A's plan covers only (0,1) and (0,2)
                    lookaheadSeconds = 45.0,
                )
            val from = PlayerPosition("b1", 0, 0)
            val a = launch { q.ensure(from) }
            runCurrent() // A parks synthesizing (0,1); (0,2) registered in-flight too
            val b = launch { q.ensure(from) }
            runCurrent() // B must plan NOTHING: the walk breaks at the in-flight (0,1)
            assertTrue(b.isCompleted, "second ensure returns without synthesizing")
            assertEquals(1, callCount, "B synthesizes nothing while A owns the near gap")
            assertEquals(0.0, q.aheadSeconds(from), 0.0, "no far-ahead audio queued past the in-flight gap")
            gate.complete(Unit)
            a.join()
            assertEquals(2, callCount, "A alone fills its contiguous plan: 0/1 then 0/2")
            assertTrue(q.take(0, 1) != null, "nearest successor is queued (contiguity)")
            assertTrue(q.take(0, 2) != null)
            assertNull(q.take(1, 0), "far passages are never planned around the in-flight gap")
        }

    @Test
    fun `onSynthesized fires once per queued passage`() =
        runTest {
            val seen = mutableListOf<String>()
            val q =
                PregenQueue(
                    book,
                    "af_heart",
                    1.0,
                    ::fake,
                    2,
                    onSynthesized = { key, _ -> seen += "${key.chapterIndex}/${key.passageIndex}" },
                )
            q.ensure(PlayerPosition("b1", 0, 0))
            assertEquals(listOf("0/1", "0/2"), seen)
        }

    @Test
    fun `entries are keyed per engine - take matches the queue's engine`() =
        runTest {
            val q = PregenQueue(book, "af_heart", 1.0, ::fake, 2, engine = "cosyvoice3")
            q.ensure(PlayerPosition("b1", 0, 0))
            val one = q.take(0, 1)
            assertEquals(2_000, one?.pcm?.size, "take finds the engine-keyed entry")
            val defaultEngine = PregenQueue(book, "af_heart", 1.0, ::fake, 2)
            assertNull(defaultEngine.take(0, 1), "a kokoro queue cannot take cosyvoice3 audio")
        }
}
