package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.ChapterCount
import io.github.moronigranja.ayvu.persistence.PassageDao
import io.github.moronigranja.ayvu.persistence.PassageEntity
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.StoredTranslation
import io.github.moronigranja.ayvu.player.TranslationStore
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The translation service's single-writer guarantees (C): cache-first with
 * keyed in-flight dedupe (one decode per passage per process — "a passage is
 * translated at most once"), null on decoder failure with nothing stored, and
 * the display path's engineInUse gate (display work yields to playback; the
 * audio path never self-gates). The decoder is a counting fake over the
 * TranslateRuntime seam — the LLM is never opened here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationServiceImplTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class FakePassageDao : PassageDao {
        val passages = mutableListOf<PassageEntity>()

        override suspend fun upsertAll(passages: List<PassageEntity>) {
            this.passages.clear()
            this.passages.addAll(passages)
        }

        override suspend fun forBook(bookId: String): List<PassageEntity> = passages.filter { it.bookId == bookId }

        override suspend fun all(): List<PassageEntity> = passages

        override fun chapterCounts(): Flow<List<ChapterCount>> = MutableSharedFlow()

        override suspend fun deleteByBook(bookId: String) {
            passages.removeAll { it.bookId == bookId }
        }
    }

    private class FakeStore : TranslationStore {
        private val rows = mutableMapOf<String, StoredTranslation>()

        override suspend fun get(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? = rows[key(bookId, chapter, passage, target)]?.text

        override suspend fun put(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
            text: String,
        ) {
            rows[key(bookId, chapter, passage, target)] =
                StoredTranslation(bookId, chapter, passage, target.lang, target.translator, text, 0L)
        }

        override suspend fun chapter(
            bookId: String,
            chapter: Int,
        ): List<StoredTranslation> = rows.values.filter { it.bookId == bookId && it.chapterIndex == chapter }

        override suspend fun deleteByBook(bookId: String) {
            rows.entries.removeAll { it.value.bookId == bookId }
        }

        fun isEmpty() = rows.isEmpty()

        private fun key(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ) = "$bookId/$chapter/$passage/${target.lang}/${target.translator}"
    }

    /** The counting decoder: the LLM opens are impossible in JVM tests, so
     * the seam translates without a session. [gate] parks a decode in flight;
     * [result] null = decoder failure (pack removed / translate null). */
    private class FakeTranslateRuntime(
        context: Context,
        settings: AppSettings,
        private val result: (String) -> String? = { "traduzido: $it" },
        private val gate: CompletableDeferred<Unit>? = null,
    ) : TranslateRuntime(context, settings) {
        val calls = AtomicInteger()

        override val canOpen: Boolean = true

        override suspend fun translate(
            text: String,
            promptLanguage: String,
        ): String? {
            calls.incrementAndGet()
            gate?.await()
            return result(text)
        }
    }

    private lateinit var passageDao: FakePassageDao
    private lateinit var store: FakeStore

    @Before
    fun setUp() {
        passageDao = FakePassageDao()
        store = FakeStore()
        passageDao.passages.add(PassageEntity("b1", 0, "Ch1", 0, "Hello world."))
        passageDao.passages.add(PassageEntity("b1", 0, "Ch1", 1, "Second passage."))
    }

    private class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()

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

    @After
    fun tearDown() {
        PlaybackActive.markEngineStopped()
    }

    private fun fakeRuntime(
        result: (String) -> String? = { "traduzido: $it" },
        gate: CompletableDeferred<Unit>? = null,
    ) = FakeTranslateRuntime(context, AppSettings(SettingsStore(FakeSettingsDao())), result, gate)

    private fun service(runtime: FakeTranslateRuntime) = TranslationServiceImpl(store, passageDao, runtime)

    @Test
    fun `a second translate for the same key returns the stored text without decoding again`() =
        runBlocking {
            val runtime = fakeRuntime()
            val service = service(runtime)
            val target = TranslationTarget("pt-BR")

            val first = service.translate("b1", 0, 0, target)
            val second = service.translate("b1", 0, 0, target)

            assertEquals("traduzido: Hello world.", first)
            assertEquals("the second call returns the STORED text", first, second)
            assertEquals("one decode per passage", 1, runtime.calls.get())
        }

    @Test
    fun `concurrent translates for the same key share one in-flight decode`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val runtime = fakeRuntime(gate = gate)
            val service = service(runtime)
            val target = TranslationTarget("pt-BR")

            val a = async { service.translate("b1", 0, 0, target) }
            // Wait until the decode is actually parked in flight (gate held).
            while (runtime.calls.get() < 1) delay(10)
            val b = async { service.translate("b1", 0, 0, target) }
            delay(50)
            assertEquals("the joiner shares the in-flight decode", 1, runtime.calls.get())
            gate.complete(Unit)
            val results = listOf(a.await(), b.await())
            assertEquals(2, results.filter { it == "traduzido: Hello world." }.size)
            assertEquals("still exactly one decode after completion", 1, runtime.calls.get())
        }

    @Test
    fun `translator null returns null and stores nothing`() =
        runBlocking {
            val runtime = fakeRuntime(result = { null })
            val service = service(runtime)
            val target = TranslationTarget("pt-BR")

            val first = service.translate("b1", 0, 0, target)
            val second = service.translate("b1", 0, 1, target)

            assertNull(first)
            assertNull(second)
            assertTrue("a failed decode never writes a row", store.isEmpty())
        }

    @Test
    fun `prefetch does not start a decode while the engine is in use`() =
        runBlocking {
            val runtime = fakeRuntime()
            val service = service(runtime)
            val target = TranslationTarget("pt-BR")

            PlaybackActive.markEngineUsed()
            service.prefetch("b1", 0, listOf(0, 1), target)
            delay(150) // well past the point the gate would have started a decode
            assertEquals("display work yields to playback", 0, runtime.calls.get())

            PlaybackActive.markEngineStopped()
            val deadline = System.currentTimeMillis() + 5_000
            while (runtime.calls.get() < 2 && System.currentTimeMillis() < deadline) delay(50)
            assertEquals("the fill decodes once the engine clears", 2, runtime.calls.get())
            assertEquals("traduzido: Hello world.", store.get("b1", 0, 0, target))
        }
}
