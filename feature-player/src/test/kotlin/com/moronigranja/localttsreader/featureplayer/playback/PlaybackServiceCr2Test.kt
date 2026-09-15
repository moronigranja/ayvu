package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.player.BookLayout
import com.moronigranja.localttsreader.player.InMemoryPlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerProgress
import com.moronigranja.localttsreader.player.PlayerStateMachine
import com.moronigranja.localttsreader.player.PlayerStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

/**
 * CR-2 service-edge regression (roadmap A2): STOP and service teardown must
 * persist the LIVE intra-passage playhead, captured before the passage output
 * is released (a released output reads 0 samples), and exactly ONE
 * authoritative final write may occur per session.
 *
 * Drives the real [PlaybackService] (constructed directly — Hilt fields are
 * assigned by hand) with the real [PlayerStateMachine] over an
 * [InMemoryPlayerStore] and a fake [PassageOutput] that zeroes its head on
 * stop, mirroring [AudioTrackPassageOutput]'s contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceCr2Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sampleRate = 24_000

    private val book =
        Book(
            id = "cr2-book",
            title = "CR2",
            chapters = listOf(Chapter(0, "One", listOf(TextPassage("p0")))),
        )

    /** Fake output: reports live samples until [stop] zeroes the head — the
     * real AudioTrackPassageOutput contract (CR-2: persistence must capture
     * BEFORE teardown, or it reads baseline + 0). */
    private class FakeOutput(
        var liveSamples: Int = 0,
    ) : PassageOutput {
        var stopped = false

        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) {
            liveSamples = 0
            stopped = false
        }

        override fun stop() {
            stopped = true
        }

        override val positionSamples: Int
            get() = if (stopped) 0 else liveSamples

        override fun setVolume(multiplier: Float) = Unit
    }

    /** Counts [PlayerStore.commitProgress] calls — the single-write assertions —
     * and records the thread each write ran on (pass 3: the edge's writes must be
     * serialized on the dedicated player thread). */
    private class CountingStore(
        private val inner: PlayerStore,
    ) : PlayerStore by inner {
        var commits = 0
        val writeThreads = mutableListOf<String>()

        override suspend fun commitProgress(
            progress: PlayerProgress,
            ringPush: PlayerPosition?,
        ) {
            commits++
            writeThreads += Thread.currentThread().name
            inner.commitProgress(progress, ringPush)
        }
    }

    private fun playingMachine(store: PlayerStore): PlayerStateMachine {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking { machine.playFrom(PlayerPosition(book.id, 0, 0)) }
        return machine
    }

    private fun service(
        store: PlayerStore,
        machine: PlayerStateMachine,
        output: FakeOutput,
        baseline: Double = 10.0,
    ): PlaybackService =
        PlaybackService().apply {
            this.store = store
            this.machine = machine
            this.output = output
            this.baselineOffset = baseline
        }

    private fun awaitRow(
        store: PlayerStore,
        bookId: String,
        expect: Double,
    ): PlayerProgress {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val row = runBlocking { store.readProgress(bookId) }
            if (row != null && abs(row.offsetSeconds - expect) < 1e-9) return row
            Thread.sleep(20)
        }
        throw AssertionError("resume row never reached $expect; last=${runBlocking { store.readProgress(bookId) }}")
    }

    /** Baseline 10 s + 5 s live — STOP must persist 15 s, not the 10 s buffer
     * start that a post-teardown capture would read. */
    @Test
    fun `STOP captures the live playhead before the output is released and persists it`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)

        val captured = service.captureAndStop()

        assertEquals("capture happens before output release", 15.0, captured, 1e-9)
        assertTrue("output released after the capture", fake.stopped)
        val row = awaitRow(store, book.id, 15.0)
        assertEquals(15.0, row.offsetSeconds, 1e-9)
        assertEquals(PlayerPhase.IDLE, machine.state.value.phase)
    }

    /** Abrupt teardown (no graceful STOP): the onDestroy path captures first
     * and writes exactly once at the live playhead. */
    @Test
    fun `teardown without a graceful stop writes the captured playhead once`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)
        val commitsBefore = store.commits // playFrom's initial commit

        runBlocking { service.teardownWrite() }

        val row = awaitRow(store, book.id, 15.0)
        assertEquals("exactly one authoritative final write", commitsBefore + 1, store.commits)
        assertEquals(15.0, row.offsetSeconds, 1e-9)
        assertEquals(PlayerPhase.IDLE, machine.state.value.phase)
    }

    /** Graceful STOP then teardown: onDestroy joins the stop's write — a
     * second stale write (baseline-only, after output release) must never
     * land. */
    @Test
    fun `teardown joins a graceful stop and never double-writes`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)

        service.captureAndStop()
        awaitRow(store, book.id, 15.0)
        val commitsAfterGraceful = store.commits

        runBlocking { service.teardownWrite() } // onDestroy path

        assertEquals("the join adds no second write", commitsAfterGraceful, store.commits)
        val row = runBlocking { store.readProgress(book.id) }!!
        assertEquals("row still the captured playhead", 15.0, row.offsetSeconds, 1e-9)
    }

    /** The persistence cadence gate: at most one checkpoint per interval —
     * the ticker/UI must never write every tick by accident. */
    @Test
    fun `checkpoint gate throttles persistence to one commit per interval`() {
        val service = PlaybackService()
        // Epoch-like base (real clock() is currentTimeMillis — far from 0).
        val t0 = 100_000_000L
        assertTrue("first checkpoint is due", service.dueCheckpoint(t0))
        assertFalse("within the interval is not due", service.dueCheckpoint(t0 + 1_000L))
        assertFalse(service.dueCheckpoint(t0 + 4_999L))
        assertTrue("interval elapsed", service.dueCheckpoint(t0 + 5_000L))
        assertFalse(service.dueCheckpoint(t0 + 6_000L))
    }

    // ------------------------------------------------------------------
    // Pass 3 (2026-09-15): the final write is uncancellable but SERIALIZED
    // ------------------------------------------------------------------

    /** Occupies the service's dedicated player thread so a write enqueued after
     * this point cannot run until the returned lambda is called — the ordering
     * below becomes deterministic instead of a scheduling race. */
    private fun occupyPlayerThread(service: PlaybackService): () -> Unit {
        val field =
            PlaybackService::class.java.getDeclaredField("playerDispatcher").apply { isAccessible = true }
        val dispatcher = field.get(service) as CoroutineDispatcher
        val gate = CountDownLatch(1)
        CoroutineScope(dispatcher).launch { gate.await() }
        return { gate.countDown() }
    }

    private fun finalStopJobOf(service: PlaybackService): Job? {
        val field =
            PlaybackService::class.java.getDeclaredField("finalStopJob").apply { isAccessible = true }
        return field.get(service) as Job?
    }

    /** The CR-2 write runs on the loop's thread, not a bare `Dispatchers.Default`
     * worker: the machine's single-writer contract assumes the edge serializes. */
    @Test
    fun `the final stop write runs on the player thread`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)

        service.captureAndStop()
        awaitRow(store, book.id, 15.0)

        // Prefix, not equality: kotlinx-coroutines debug mode appends
        // " @coroutine#N" to the thread name.
        assertTrue(
            "the final write must run on the player thread, was ${store.writeThreads.last()}",
            store.writeThreads.last().startsWith("AyvuPlayer"),
        )
    }

    /** CR-2 + CR-5: the deferred teardown clear belongs to the session that
     * stopped. A newer command owns the published state — clearing here would
     * blank the UI of a session that is already loading. */
    @Test
    fun `a superseded stop never clears a newer session's published state`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)
        val release = occupyPlayerThread(service)
        try {
            PlaybackStateHolder.reset()
            PlaybackStateHolder.update { it.copy(bookId = "newer-session") }

            service.captureAndStop() // its write queues behind the occupier
            service.stopEverything() // a NEWER command's stop bumps the generation

            release()
            runBlocking { finalStopJobOf(service)?.join() }

            assertEquals(
                "the clear belongs to the superseded session only",
                "newer-session",
                PlaybackStateHolder.state.value.bookId,
            )
        } finally {
            release()
            PlaybackStateHolder.reset()
        }
    }

    /** The write targets the machine the session ENDED with: a later command
     * rebuilds `machine`, and writing through the field committed this session's
     * offset to the next book's row. */
    @Test
    fun `the final stop write never lands on a machine rebuilt by a later command`() {
        val store = CountingStore(InMemoryPlayerStore())
        val machine = playingMachine(store)
        val fake = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val service = service(store, machine, fake)
        val release = occupyPlayerThread(service)
        try {
            service.captureAndStop() // enqueued behind the occupier
            val replacementStore = CountingStore(InMemoryPlayerStore())
            service.machine = PlayerStateMachine(replacementStore, BookLayout(book)) // a newer command's rebuild
            val replacementCommits = replacementStore.commits

            release()
            runBlocking { finalStopJobOf(service)?.join() }

            assertEquals(
                "the superseded session's write stays on its own machine",
                replacementCommits,
                replacementStore.commits,
            )
            awaitRow(store, book.id, 15.0) // and it did land — on the original machine
        } finally {
            release()
        }
    }
}
