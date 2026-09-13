package com.moronigranja.localttsreader

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.MIGRATION_1_2
import com.moronigranja.localttsreader.persistence.MIGRATION_2_3
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device verification for the reader's chapter-boundary page turn (open-bugs:
 * "last-third tap does not advance past the chapter's last page"). The reader
 * gesture dispatches ACTION_OPEN_CHAPTER; the service must land the machine on
 * the neighbor chapter's first passage forward, its LAST passage backward —
 * the reader's left-zone turn shows the previous chapter's ending — WITHOUT
 * starting playback (decisions #52: open ≠ auto-play), skip empty spine slots,
 * and no-op at both book edges while leaving the present machine intact for
 * the next turn. No engine/packs — a pure position move.
 */
@RunWith(AndroidJUnit4::class)
class OpenChapterE2eTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "open-chapter-e2e-book",
        title = "Open Chapter E2E",
        chapters = listOf(
            Chapter(
                0,
                "One",
                listOf(
                    TextPassage("First chapter first passage."),
                    TextPassage("First chapter last passage."),
                ),
            ),
            Chapter(1, "Empty", emptyList()), // BookLayout skips this spine slot
            Chapter(
                2,
                "Two",
                listOf(
                    TextPassage("Second chapter first passage."),
                    TextPassage("Second chapter last passage."),
                ),
            ),
        ),
    )

    @Before
    fun setUp() = runBlocking {
        database = Room.databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        store = RoomLibraryStore(database, scope)
        store.add(LibraryEntry(book, importedAtEpochMillis = 1L))
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase (A8): this is the app's live DB — the app's Hilt
        // Room singleton (PlaybackService/PregenWorker) holds a connection;
        // unlinking it under the running app wiped production data (same
        // class as the #42 finding fixed in PlaybackE2eTest/PregenE2eTest).
    }

    @Test
    fun openChapterTurnsAcrossBoundariesWithoutAutoPlay() {
        PlaybackStateHolder.reset()
        open()
        awaitState("book opens at chapter 0") { it.bookId == book.id }
        assertEquals(0, PlaybackStateHolder.state.value.chapterIndex)
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)

        // Forward from chapter 0 skips the empty chapter 1 → chapter 2, first passage.
        openChapter(+1)
        awaitState("forward lands on chapter 2") { it.chapterIndex == 2 && it.passageIndex == 0 }
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)

        // Book end: a forward turn past the last chapter is a no-op.
        openChapter(+1)
        Thread.sleep(2_000)
        assertEquals("book-end forward stays put", 2, PlaybackStateHolder.state.value.chapterIndex)

        // Backward from chapter 2 skips the empty chapter 1 → chapter 0's END.
        openChapter(-1)
        awaitState("backward lands on chapter 0's last passage") { it.chapterIndex == 0 && it.passageIndex == 1 }
        assertEquals(PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)

        // Book start: a backward turn before the first chapter is a no-op.
        openChapter(-1)
        Thread.sleep(2_000)
        assertEquals("book-start backward stays put", 0, PlaybackStateHolder.state.value.chapterIndex)
        assertEquals("open ≠ auto-play throughout", PlayerPhase.IDLE, PlaybackStateHolder.state.value.phase)
    }

    private fun open() = context.startForegroundService(
        Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_OPEN)
            .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
    )

    private fun openChapter(direction: Int) = context.startForegroundService(
        Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_OPEN_CHAPTER)
            .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
            .putExtra(PlaybackService.EXTRA_DIRECTION, direction),
    )

    private fun awaitState(what: String, cond: (PlaybackUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond(PlaybackStateHolder.state.value)) return
            Thread.sleep(100)
        }
        throw AssertionError("$what not reached; last state: ${PlaybackStateHolder.state.value}")
    }
}
