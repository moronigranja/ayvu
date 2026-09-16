package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.session.MediaSessionCompat
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.player.BookLayout
import io.github.moronigranja.ayvu.player.Bookmark
import io.github.moronigranja.ayvu.player.InMemoryPlayerStore
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerPhase
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.player.PlayerProgress
import io.github.moronigranja.ayvu.player.PlayerStateMachine
import io.github.moronigranja.ayvu.player.PlayerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bookmark action's service edge (cleanup pass 3, 2026-09-15). It had NO test
 * before: the path was a bare `scope.launch` on `Dispatchers.Default` writing the
 * machine outside the serializations the machine's single-writer contract assumes
 * (loop/ticker share the player thread; transport commands hold `commandLock`).
 *
 * Two contracts are pinned here:
 *  - the machine writes run on the dedicated player thread (`AyvuPlayer`), inside
 *    the command lock — a deliberate, observable consequence of the fix;
 *  - adding a bookmark is NOT a command: it must never claim command ownership,
 *    which would let the next command's `stopEverything` cancel the write and
 *    tear playback down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceBookmarkTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sampleRate = 24_000
    private val passageText = "The quick brown fox jumps over the lazy dog and keeps on running."

    private val book =
        Book(
            id = "bm-book",
            title = "Bookmarks",
            chapters = listOf(Chapter(0, "One", listOf(TextPassage(passageText)))),
        )

    /** Records the thread every machine write ran on, and the bookmarks it saw. */
    private class RecordingStore(
        private val inner: PlayerStore,
    ) : PlayerStore by inner {
        val writeThreads = mutableListOf<String>()
        val written = mutableListOf<Bookmark>()

        override suspend fun commitProgress(
            progress: PlayerProgress,
            ringPush: PlayerPosition?,
        ) {
            writeThreads += Thread.currentThread().name
            inner.commitProgress(progress, ringPush)
        }

        override suspend fun addBookmark(bookmark: Bookmark): Bookmark {
            writeThreads += Thread.currentThread().name
            written += bookmark
            return inner.addBookmark(bookmark)
        }
    }

    private class FakeOutput(
        var liveSamples: Int = 0,
    ) : PassageOutput {
        var stopped = false

        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) {
            stopped = false
        }

        override fun stop() {
            stopped = true
        }

        override val positionSamples: Int
            get() = liveSamples

        override fun setVolume(multiplier: Float) = Unit
    }

    /** Base context + session, as the other service harnesses do: Hilt's
     * transformed `onCreate` cannot run under plain Robolectric, and `publish()`
     * (the end of the bookmark path) touches the session. */
    private fun primedService(
        store: PlayerStore,
        machine: PlayerStateMachine,
        output: FakeOutput,
    ): PlaybackService =
        PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val session = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
            session.set(this, MediaSessionCompat(this, "bookmark-test"))

            this.store = store
            this.machine = machine
            this.book = this@PlaybackServiceBookmarkTest.book
            this.output = output
            this.baselineOffset = 10.0
        }

    private fun playing(
        store: PlayerStore,
        output: FakeOutput,
    ): Pair<PlayerStateMachine, PlaybackService> {
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking { machine.playFrom(PlayerPosition(book.id, 0, 0)) }
        machine.onAudioStarted() // PLAYING, live head 10 s + 5 s
        return machine to primedService(store, machine, output)
    }

    private fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    companion object {
        /** The dedicated player thread's name (decisions #85). */
        private const val AYVU_PLAYER_THREAD = "AyvuPlayer"
    }

    @Test
    fun `the bookmark write is serialized on the player thread and never tears playback down`() {
        val store = RecordingStore(InMemoryPlayerStore())
        val output = FakeOutput(liveSamples = (5.0 * sampleRate).toInt())
        val (machine, service) = playing(store, output)
        PlaybackStateHolder.reset()

        service.addBookmarkAtPlayhead()

        await("the bookmark write") { store.written.isNotEmpty() }
        store.writeThreads.takeLast(2).forEach { thread ->
            // Prefix, not equality: kotlinx-coroutines debug mode appends
            // " @coroutine#N" to the thread name.
            assertTrue(
                "notePlaybackOffset + addBookmark must run on the player thread, was $thread",
                thread.startsWith(AYVU_PLAYER_THREAD),
            )
        }
        val bookmark = store.written.single()
        assertEquals("baseline 10 s + 5 s live, captured at dispatch", 15.0, bookmark.offsetSeconds, 1e-9)
        assertEquals(0, bookmark.chapterIndex)
        assertEquals(0, bookmark.passageIndex)
        assertEquals(passageText.take(48), bookmark.label)
        assertFalse("a bookmark is not a command: playback must survive it", output.stopped)
        assertEquals("playback untouched", PlayerPhase.PLAYING, machine.state.value.phase)
    }

    @Test
    fun `the new bookmark reaches the published reader state`() {
        val store = RecordingStore(InMemoryPlayerStore())
        val output = FakeOutput(liveSamples = (sampleRate / 2).toInt())
        val (_, service) = playing(store, output)
        PlaybackStateHolder.reset()

        service.addBookmarkAtPlayhead()

        await("the published bookmark") {
            PlaybackStateHolder.state.value.bookmarks
                .isNotEmpty()
        }
        val published =
            PlaybackStateHolder.state.value.bookmarks
                .single()
        assertEquals(passageText.take(48), published.label)
        assertEquals(10.5, published.offsetSeconds, 1e-9)
        PlaybackStateHolder.reset()
    }
}
