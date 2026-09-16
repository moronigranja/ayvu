package io.github.moronigranja.ayvu.player

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T4-1 state machine contract (decisions #33): transport transitions, the
 * single transactional write point, ring push/undo semantics, sleep timer,
 * speed preservation, bookmarks, completion.
 */
class PlayerStateMachineTest {
    private lateinit var store: InMemoryPlayerStore
    private lateinit var machine: PlayerStateMachine
    private var now = 1_000L

    private val book =
        Book(
            id = "b1",
            title = "Anna Karenina",
            chapters =
                listOf(
                    Chapter(0, "Happy Families", listOf(TextPassage("p0"), TextPassage("p1"), TextPassage("p2"))),
                    Chapter(1, "Troubles", listOf(TextPassage("p3"), TextPassage("p4"))),
                ),
        )

    private fun passage(
        chapter: Int,
        passage: Int,
    ) = PlayerPosition("b1", chapter, passage)

    @BeforeEach
    fun setUp() {
        store = InMemoryPlayerStore(ringCapacity = 10)
        machine = PlayerStateMachine(store, BookLayout(book)) { now }
    }

    // ------------------------------------------------------------------

    @Test
    fun `resume without progress stays idle`() =
        runTest {
            assertNull(machine.resume())
            assertEquals(PlayerPhase.IDLE, machine.state.value.phase)
        }

    @Test
    fun `resume loads the stored position and pins speed to one point zero`() =
        runTest {
            store.commitProgress(
                PlayerProgress("b1", 0, 2, 1.5, 1.25, now),
                null,
            )
            val position = machine.resume()
            assertEquals(passage(0, 2).copy(offsetSeconds = 1.5), position)
            assertEquals(1.0, machine.state.value.speed, "stored per-book speed ignored (decisions #71)")
            assertEquals(PlayerPhase.LOADING, machine.state.value.phase)
        }

    @Test
    fun `resume with a stale out-of-layout progress falls back to a fresh start`() =
        runTest {
            // A stored point past the current layout (parse drift from an older
            // import/device cycle) must not crash — the caller restarts the book.
            store.commitProgress(
                PlayerProgress("b1", 5, 0, 0.0, 1.0, now), // no chapter index 5 in the book
                null,
            )
            assertNull(machine.resume(), "stale progress yields no resume point")
            assertEquals(PlayerPhase.IDLE, machine.state.value.phase)
        }

    @Test
    fun `playFrom a different position pushes the stored resume point`() =
        runTest {
            store.commitProgress(PlayerProgress("b1", 0, 0, 0.0, 1.0, now), null)
            machine.playFrom(passage(0, 2))
            assertEquals(listOf(passage(0, 0)), store.readRing("b1"), "resume point pushed for undo")
            assertEquals(passage(0, 2), machine.state.value.position)
        }

    @Test
    fun `playFrom the same pointer does not pollute the ring`() =
        runTest {
            store.commitProgress(PlayerProgress("b1", 0, 1, 0.0, 1.0, now), null)
            machine.playFrom(passage(0, 1))
            assertTrue(store.readRing("b1").isEmpty(), "identical pointer: nothing to undo")
        }

    /** The undo flag is machine state (the ring's owner), so it is exact on
     * every path — including the two the edge's old mirror missed: an existing
     * ring on open, and an emptied ring after the pop. */
    @Test
    fun `canUndo tracks the ring across open, user move and undo`() =
        runTest {
            store.commitProgress(PlayerProgress("b1", 0, 0, 0.0, 1.0, now), ringPush = passage(0, 1))
            machine.present(passage(0, 0))
            assertTrue(machine.state.value.canUndo, "a ring entry left by an earlier session is undoable on open")

            machine.skipForward()
            assertTrue(machine.state.value.canUndo, "a user move pushes what it leaves")

            machine.undoSkip() // pops (0,0) — the entry seeded above is still there
            assertTrue(machine.state.value.canUndo, "one entry remains, so undo is still available")

            machine.undoSkip() // pops (0,1) — the ring is empty now
            assertFalse(machine.state.value.canUndo, "the emptied ring offers no undo")
        }

    @Test
    fun `book completion leaves the ending undoable`() =
        runTest {
            machine.playFrom(passage(1, 1)) // the book's last passage
            machine.onAudioStarted()
            machine.onPassageFinished()
            assertEquals(PlayerPhase.COMPLETED, machine.state.value.phase)
            assertTrue(machine.state.value.canUndo, "completion pushes the ending for one undo")
        }

    @Test
    fun `natural forward advance never pushes the ring`() =
        runTest {
            machine.resume()
            machine.playFrom(passage(0, 0))
            machine.onAudioStarted()

            // Traverse the whole two-chapter book; each advance leaves the passage
            // LOADING until the edge reports its audio started.
            val advanced = mutableListOf<PlayerEvent>()
            for (expected in listOf(0 to 1, 0 to 2, 1 to 0, 1 to 1)) {
                advanced.addAll(machine.onPassageFinished())
                assertEquals(PlayerPhase.LOADING, machine.state.value.phase, "advanced passage waits for its audio")
                machine.onAudioStarted()
                assertTrue(store.readRing("b1").isEmpty(), "natural advance must never push")
            }
            assertEquals(
                listOf(
                    PlayerEvent.PassageAdvanced(0, 1),
                    PlayerEvent.PassageAdvanced(0, 2),
                    PlayerEvent.PassageAdvanced(1, 0),
                    PlayerEvent.PassageAdvanced(1, 1),
                ),
                advanced,
            )

            assertEquals(listOf(PlayerEvent.PlaybackCompleted), machine.onPassageFinished())
            val ring = store.readRing("b1")
            assertEquals(listOf(passage(1, 1)), ring, "only the completion pushes (the ending)")
            assertEquals(PlayerPhase.COMPLETED, machine.state.value.phase)
        }

    @Test
    fun `skipForward pushes what is being left`() =
        runTest {
            machine.playFrom(passage(0, 1))
            val events = machine.skipForward()
            assertEquals(listOf(PlayerEvent.PassageAdvanced(0, 2)), events)
            assertEquals(listOf(passage(0, 1)), store.readRing("b1"))
            assertEquals(passage(0, 2), machine.state.value.position)
        }

    @Test
    fun `skipChapter forward lands on the next chapter first passage`() =
        runTest {
            machine.playFrom(passage(0, 1))
            val events = machine.skipChapter(1)
            assertEquals(listOf(PlayerEvent.PassageAdvanced(1, 0)), events)
            assertEquals(passage(1, 0), machine.state.value.position)
            assertEquals(PlayerPhase.LOADING, machine.state.value.phase)
            // One ring entry — the intermediate passages collapse into it.
            assertEquals(listOf(passage(0, 1)), store.readRing("b1"))
        }

    @Test
    fun `skipChapter backward lands on the previous chapter first passage`() =
        runTest {
            machine.playFrom(passage(1, 1))
            machine.skipChapter(-1)
            assertEquals(passage(0, 0), machine.state.value.position)
            assertEquals(listOf(passage(1, 1)), store.readRing("b1"))
        }

    @Test
    fun `skipChapter at book bounds is a no-op`() =
        runTest {
            machine.playFrom(passage(0, 0))
            assertTrue(machine.skipChapter(-1).isEmpty(), "no chapter before the first")
            assertEquals(passage(0, 0), machine.state.value.position)
            assertTrue(store.readRing("b1").isEmpty())

            machine.playFrom(passage(1, 1))
            assertTrue(machine.skipChapter(1).isEmpty(), "no chapter after the last")
            assertEquals(passage(1, 1), machine.state.value.position)
        }

    @Test
    fun `skipChapter crosses empty chapters`() =
        runTest {
            val gapped =
                Book(
                    id = "gap",
                    title = "G",
                    chapters =
                        listOf(
                            Chapter(0, "A", listOf(TextPassage("a"))),
                            Chapter(1, "Empty", emptyList()),
                            Chapter(2, "B", listOf(TextPassage("b"))),
                        ),
                )
            val gappedMachine = PlayerStateMachine(store, BookLayout(gapped)) { now }
            gappedMachine.playFrom(PlayerPosition("gap", 0, 0))
            gappedMachine.skipChapter(1)
            assertEquals(PlayerPosition("gap", 2, 0), gappedMachine.state.value.position)

            gappedMachine.playFrom(PlayerPosition("gap", 2, 0))
            gappedMachine.skipChapter(-1)
            assertEquals(PlayerPosition("gap", 0, 0), gappedMachine.state.value.position)
        }

    @Test
    fun `undo after a chapter skip restores the exact playhead`() =
        runTest {
            machine.playFrom(passage(0, 1))
            machine.notePlaybackOffset(3.5) // playhead advances into the passage
            machine.skipChapter(1)
            assertEquals(passage(1, 0), machine.state.value.position)

            val undone = machine.undoSkip()
            assertEquals(passage(0, 1).copy(offsetSeconds = 3.5), undone)
            assertEquals(passage(0, 1).copy(offsetSeconds = 3.5), machine.state.value.position)
            assertTrue(store.readRing("b1").isEmpty(), "undo consumes the ring entry")
        }

    @Test
    fun `undoSkip returns to the pushed position`() =
        runTest {
            machine.playFrom(passage(0, 1))
            machine.skipForward()
            val popped = machine.undoSkip()
            assertEquals(passage(0, 1), popped)
            assertEquals(passage(0, 1), machine.state.value.position)
            assertTrue(store.readRing("b1").isEmpty(), "undo consumes the entry")
            // positional undo restores the progress row too (same written pointer)
            val progress = store.readProgress("b1")
            assertEquals(0, progress?.chapterIndex)
            assertEquals(1, progress?.passageIndex)
        }

    @Test
    fun `seekTo a different pointer pushes and the same pointer is a no-op`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.seekTo(passage(1, 1))
            assertEquals(listOf(passage(0, 0)), store.readRing("b1"))
            assertEquals(passage(1, 1), machine.state.value.position)
            machine.seekTo(passage(1, 1))
            assertEquals(1, store.readRing("b1").size, "same-pointer seek must not push again")
        }

    @Test
    fun `ring is capped per book`() =
        runTest {
            for (i in 1..12) {
                machine.playFrom(PlayerPosition("b1", 0, (i % 3), 0.0))
            }
            val ring = store.readRing("b1")
            assertEquals(10, ring.size, "cap enforced")
            assertEquals(passage(0, 2), ring.first(), "newest first")
        }

    @Test
    fun `completion pushes the ending for undo and writes the resume row`() =
        runTest {
            machine.playFrom(passage(1, 1))
            machine.onAudioStarted()
            val events = machine.onPassageFinished()
            assertEquals(listOf(PlayerEvent.PlaybackCompleted), events)
            assertEquals(PlayerPhase.COMPLETED, machine.state.value.phase)
            assertEquals(listOf(passage(1, 1)), store.readRing("b1"), "undo replays the ending")
            assertEquals(1, store.readProgress("b1")?.passageIndex, "resume row stays at the ending")
        }

    @Test
    fun `completion from a non-playing state is ignored`() =
        runTest {
            machine.playFrom(passage(1, 1)) // LOADING, not PLAYING
            assertTrue(machine.onPassageFinished().isEmpty())
            assertEquals(PlayerPhase.LOADING, machine.state.value.phase)
        }

    @Test
    fun `end-of-chapter sleep pauses at the chapter's last passage`() =
        runTest {
            machine.playFrom(passage(0, 2))
            machine.onAudioStarted()
            machine.setSleepTimer(SleepTimer.EndOfChapter)
            val events = machine.onPassageFinished()
            assertEquals(listOf(PlayerEvent.PauseRequested), events)
            assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
            assertEquals(passage(0, 2), machine.state.value.position, "stops before the new chapter")
            assertEquals(SleepTimer.Off, machine.state.value.sleepTimer, "timer consumed")
            // resume row at the chapter end, no ring pollution
            assertEquals(2, store.readProgress("b1")?.passageIndex)
            assertTrue(store.readRing("b1").isEmpty())
        }

    @Test
    fun `end-of-chapter sleep lets the last chapter run to completion`() =
        runTest {
            machine.playFrom(passage(1, 1)) // the book's final passage
            machine.onAudioStarted()
            machine.setSleepTimer(SleepTimer.EndOfChapter)
            val events = machine.onPassageFinished()
            assertEquals(listOf(PlayerEvent.PlaybackCompleted), events, "no chapter boundary ahead")
        }

    @Test
    fun `countdown sleep fires once on the tick`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.onAudioStarted()
            machine.setSleepTimer(SleepTimer.Duration(now + 5_000))
            assertTrue(machine.advance(now + 4_900).isEmpty(), "not yet expired")
            val events = machine.advance(now + 5_000)
            assertEquals(listOf(PlayerEvent.PauseRequested), events)
            assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
            assertTrue(machine.advance(now + 60_000).isEmpty(), "fires once")
        }

    @Test
    fun `countdown expiry while paused clears the timer without events`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.pause()
            machine.setSleepTimer(SleepTimer.Duration(now + 1))
            assertTrue(machine.advance(now + 10).isEmpty())
            assertEquals(SleepTimer.Off, machine.state.value.sleepTimer)
            assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
        }

    /** The cycle policy (and its 30-minute constant) is machine state, not
     * Android glue: it used to live in PlaybackService, which left the machine
     * exposing only a dumb setter. */
    @Test
    fun `the sleep button cycles off, end-of-chapter, thirty minutes, off`() =
        runTest {
            assertEquals(SleepTimer.Off, machine.state.value.sleepTimer, "starts off")

            machine.cycleSleepTimer(now)
            assertEquals(SleepTimer.EndOfChapter, machine.state.value.sleepTimer)

            machine.cycleSleepTimer(now)
            assertEquals(
                SleepTimer.Duration(now + PlayerStateMachine.SLEEP_STEP_MILLIS),
                machine.state.value.sleepTimer,
                "armed for 30 minutes from the tap",
            )

            machine.cycleSleepTimer(now)
            assertEquals(SleepTimer.Off, machine.state.value.sleepTimer)

            machine.cycleSleepTimer(now) // wraps around again
            assertEquals(SleepTimer.EndOfChapter, machine.state.value.sleepTimer)
        }

    @Test
    fun `speed change preserves the play point and persists per book`() =
        runTest {
            machine.playFrom(passage(0, 1).copy(offsetSeconds = 2.25))
            machine.setSpeed(1.5)
            assertEquals(
                passage(0, 1).copy(offsetSeconds = 2.25),
                machine.state.value.position,
                "offset is book-time — must not move with speed",
            )
            assertEquals(1.5, store.readProgress("b1")?.speed, "speed persisted with the position")
        }

    @Test
    fun `speed is clamped`() =
        runTest {
            machine.setSpeed(9.0)
            assertEquals(PlayerStateMachine.MAX_SPEED, machine.state.value.speed)
            machine.setSpeed(-1.0)
            assertEquals(PlayerStateMachine.MIN_SPEED, machine.state.value.speed)
        }

    @Test
    fun `bookmark captures the machine position`() =
        runTest {
            machine.playFrom(passage(1, 0).copy(offsetSeconds = 3.0))
            val bookmark = machine.addBookmark("favorite")
            assertEquals(bookmark?.bookId, "b1")
            assertEquals(1, bookmark?.chapterIndex)
            assertEquals(0, bookmark?.passageIndex)
            assertEquals(3.0, bookmark?.offsetSeconds)
            assertEquals(listOf(bookmark), machine.bookmarks())

            machine.removeBookmark(bookmark!!.id)
            assertTrue(machine.bookmarks().isEmpty())
        }

    @Test
    fun `bookmark without a loaded position is a no-op`() =
        runTest {
            assertNull(machine.addBookmark())
        }

    @Test
    fun `notePlaybackOffset commits the live playhead without touching the ring`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.notePlaybackOffset(4.25)
            assertEquals(
                4.25,
                machine.state.value.position
                    ?.offsetSeconds,
            )
            assertEquals(4.25, store.readProgress("b1")?.offsetSeconds, "resume row follows the playhead")
            assertTrue(store.readRing("b1").isEmpty(), "playhead writes never push")
        }

    @Test
    fun `pause at the playhead writes the final offset once`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.pause(offsetSeconds = 5.5)
            assertEquals(5.5, store.readProgress("b1")?.offsetSeconds)
            assertEquals(PlayerPhase.PAUSED, machine.state.value.phase)
            assertTrue(store.readRing("b1").isEmpty())
        }

    @Test
    fun `stop at the playhead writes before going idle`() =
        runTest {
            machine.playFrom(passage(0, 0))
            machine.stop(offsetSeconds = 2.0)
            assertEquals(2.0, store.readProgress("b1")?.offsetSeconds)
            assertEquals(PlayerPhase.IDLE, machine.state.value.phase)
        }

    @Test
    fun `store failures surface as typed failure without state corruption`() =
        runTest {
            val failing = FailingStore(delegate = store)
            val broken = PlayerStateMachine(failing, BookLayout(book)) { now }
            broken.playFrom(passage(0, 0))
            assertNotNull(broken.state.value.failure)
            assertEquals(PlayerPhase.LOADING, broken.state.value.phase)
        }
}

/** Wraps a store and fails every operation after construction. */
private class FailingStore(
    private val delegate: PlayerStore,
) : PlayerStore {
    override suspend fun readProgress(bookId: String): PlayerProgress? = throw IllegalStateException("io down")

    override suspend fun commitProgress(
        progress: PlayerProgress,
        ringPush: PlayerPosition?,
    ) = throw IllegalStateException("io down")

    override suspend fun readRing(bookId: String): List<PlayerPosition> = throw IllegalStateException("io down")

    override suspend fun popRing(bookId: String): PlayerPosition? = throw IllegalStateException("io down")

    override suspend fun addBookmark(bookmark: Bookmark) = throw IllegalStateException("io down")

    override suspend fun removeBookmark(bookmarkId: Long) = throw IllegalStateException("io down")

    override suspend fun bookmarks(bookId: String): List<Bookmark> = throw IllegalStateException("io down")
}
