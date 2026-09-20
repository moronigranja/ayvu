package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.session.MediaSessionCompat
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.BookLayout
import io.github.moronigranja.ayvu.player.InMemoryPlayerStore
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerPhase
import io.github.moronigranja.ayvu.player.PlayerPosition
import io.github.moronigranja.ayvu.player.PlayerStateMachine
import io.github.moronigranja.ayvu.tts.TTSEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An explicit OPEN POSITION is a progress MOVE (the bookmark bug, decisions
 * #185): the service presents the named passage AND commits it — offset
 * included — so the reader's play button (`ACTION_RESUME`, which reads the
 * stored row) resumes exactly where the user aimed instead of at the pre-jump
 * playhead. The offset reaches the service through
 * [PlaybackService.EXTRA_OFFSET_SECONDS] and applies only to the exact target:
 * the empty-spine fallbacks are different passages, where it is meaningless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceOpenPositionTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val book =
        Book(
            id = "open-pos-book",
            title = "Open position",
            chapters =
                listOf(
                    Chapter(
                        index = 0,
                        title = "One",
                        passages = listOf(TextPassage("First passage text."), TextPassage("Second passage text.")),
                    ),
                    Chapter(index = 1, title = "Two", passages = listOf(TextPassage("Next chapter text."))),
                ),
        )

    private class FakeSettingsDao : SettingsDao {
        private val rows = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = rows[key]

        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }

        override suspend fun all(): List<SettingEntity> = rows.map { (key, value) -> SettingEntity(key, value) }

        override suspend fun putAll(settings: List<SettingEntity>) {
            settings.forEach { rows[it.key] = it.value }
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }

        override suspend fun deleteAll(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }
    }

    private class FakeOutput : PassageOutput {
        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) = Unit

        override fun stop() = Unit

        override val positionSamples: Int = 0

        override fun setVolume(multiplier: Float) = Unit
    }

    private class FakeRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = null

        override val failureReason: String? = null
    }

    /** The degraded path is unused here: the selector's system engine is never
     * realized while `tts_engine` stays kokoro. */
    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts is unused in this test")
        }

    private fun service(
        store: InMemoryPlayerStore,
        machine: PlayerStateMachine,
        library: InMemoryLibraryStore,
    ): PlaybackService =
        PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val session = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
            session.set(this, MediaSessionCompat(this, "open-position-test"))
            this.store = store
            this.machine = machine
            this.book = this@PlaybackServiceOpenPositionTest.book
            this.output = FakeOutput()
            this.libraryStore = library
            this.settings = AppSettings(SettingsStore(FakeSettingsDao()))
            this.runtime = FakeRuntime(context, this.settings)
            this.selector =
                EngineSelector(
                    this.runtime,
                    PiperRuntime(context, this.settings),
                    TranslateRuntime(context, this.settings),
                    onUnusedSystemTts,
                    this.settings,
                    FakeTranslationService(),
                )
        }

    /** The service's LIVE machine: [PlaybackService.bindBook] replaces the
     * instance on every open, so the one a test builds is not the one the
     * command writes to. */
    private fun liveMachine(service: PlaybackService): PlayerStateMachine {
        val field = PlaybackService::class.java.getDeclaredField("machine").apply { isAccessible = true }
        return field.get(service) as PlayerStateMachine
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

    @Test
    fun `a bookmark jump commits the passage and its offset so play resumes there`() {
        val store = InMemoryPlayerStore()
        val machine = PlayerStateMachine(store, BookLayout(book))
        runBlocking { machine.playFrom(PlayerPosition(book.id, 0, 0)) } // a pre-jump playhead
        val library = InMemoryLibraryStore()
        runBlocking { library.add(LibraryEntry(book, importedAtEpochMillis = 1)) }
        val service = service(store, machine, library)
        PlaybackStateHolder.reset()

        service.openPosition(book.id, chapter = 0, passage = 1, offsetSeconds = 42.0)

        await("the jump's commit") {
            runBlocking { store.readProgress(book.id) }?.offsetSeconds == 42.0
        }
        val committed = runBlocking { store.readProgress(book.id) }!!
        assertEquals("the jumped-to passage", 1, committed.passageIndex)
        assertEquals("the bookmark's own second", 42.0, committed.offsetSeconds, 1e-9)
        // Open ≠ auto-play: the LIVE machine (bindBook replaced the instance)
        // holds the jumped-to passage and its offset, and stays IDLE — the
        // pre-arm queues audio without claiming a phase.
        val live = liveMachine(service)
        assertEquals(PlayerPhase.IDLE, live.state.value.phase)
        val presented = live.state.value.position!!
        assertEquals(1, presented.passageIndex)
        assertEquals(42.0, presented.offsetSeconds, 1e-9)
        // And the promise the bug record makes: pressing play resumes HERE.
        val resumed = runBlocking { live.resume() }!!
        assertEquals(1, resumed.passageIndex)
        assertEquals(42.0, resumed.offsetSeconds, 1e-9)
        PlaybackStateHolder.reset()
    }

    @Test
    fun `an offset on an unreachable target falls back to the neighbour's start`() {
        val store = InMemoryPlayerStore()
        val machine = PlayerStateMachine(store, BookLayout(book))
        val library = InMemoryLibraryStore()
        runBlocking { library.add(LibraryEntry(book, importedAtEpochMillis = 1)) }
        val service = service(store, machine, library)
        PlaybackStateHolder.reset()

        // Chapter 5 does not exist; the fallback is a DIFFERENT passage, so the
        // offset must not travel with it.
        service.openPosition(book.id, chapter = 5, passage = 7, offsetSeconds = 42.0)

        await("the fallback commit") {
            runBlocking { store.readProgress(book.id) } != null
        }
        val committed = runBlocking { store.readProgress(book.id) }!!
        // Past the end of a shorter book: clamp to the LAST chapter, and do not
        // carry the offset onto a different passage.
        assertEquals(1, committed.chapterIndex)
        assertEquals(0.0, committed.offsetSeconds, 1e-9)
        PlaybackStateHolder.reset()
    }
}
