package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.content.ContextWrapper
import android.support.v4.media.session.MediaSessionCompat
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.BookLayout
import io.github.moronigranja.ayvu.player.InMemoryPlayerStore
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerStateMachine
import io.github.moronigranja.ayvu.tts.TTSEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Owner report (2026-09-27): a reopened book could sit forever on
 * `LoadingState("Opening book…")`. When the book cannot be loaded (absent from
 * the library — e.g. deleted underneath a stale reader target), the open
 * command must publish a terminal FAILURE instead of returning silently, so the
 * reader lands on its error branch rather than an endless loading state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceOpenFailureTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val book =
        Book(
            id = "open-failure-book",
            title = "Open failure",
            chapters = listOf(Chapter(index = 0, title = "One", passages = listOf(TextPassage("Only passage.")))),
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

    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts is unused in this test")
        }

    private fun service(
        store: InMemoryPlayerStore,
        library: InMemoryLibraryStore,
    ): PlaybackService =
        PlaybackService().apply {
            val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            attach.invoke(this, context)
            val session = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
            session.set(this, MediaSessionCompat(this, "open-failure-test"))
            this.store = store
            this.machine = PlayerStateMachine(store, BookLayout(this@PlaybackServiceOpenFailureTest.book))
            this.book = this@PlaybackServiceOpenFailureTest.book
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
    fun `a book absent from the library publishes a terminal failure`() {
        val service = service(InMemoryPlayerStore(), InMemoryLibraryStore())
        PlaybackStateHolder.reset()

        service.openBook(book.id)

        await("the terminal failure") { PlaybackStateHolder.state.value.failure != null }
        val state = PlaybackStateHolder.state.value
        assertNotNull("the reader's error branch has a message", state.failure)
        assertNull("no book is published as open", state.bookId)
        assertEquals("the failure carries no stale chapter text", emptyList<String>(), state.chapterPassages)
        PlaybackStateHolder.reset()
    }
}
