package io.github.moronigranja.ayvu.featurelibrary

import io.github.moronigranja.ayvu.ebook.export.ExportFormat
import io.github.moronigranja.ayvu.ebook.export.ExportShape
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.InMemoryLibraryStore
import io.github.moronigranja.ayvu.model.LibraryEntry
import io.github.moronigranja.ayvu.model.TextPassage
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.pregen.TranslationReady
import io.github.moronigranja.ayvu.player.pregen.TranslationService
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The export operation end to end (pure JVM): the chosen language reaches the
 * provider, a complete artifact is written through the sink exactly once, a
 * typed failure leaves the destination untouched, and a new start supersedes
 * the running export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookExportOperationsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val holder = ExportStateHolder()

    private fun book(): Book =
        Book(
            id = "book-1",
            title = "Light Years",
            authors = listOf("Ana Dias"),
            chapters =
                listOf(
                    Chapter(0, "Chapter 1", listOf(TextPassage("First original."), TextPassage("Second original."))),
                    Chapter(1, "Chapter 2", listOf(TextPassage("Third original."))),
                ),
        )

    private suspend fun storeWith(book: Book): InMemoryLibraryStore =
        InMemoryLibraryStore().also { it.add(LibraryEntry(book, importedAtEpochMillis = 0L)) }

    private fun operations(
        store: InMemoryLibraryStore,
        translations: TranslationService,
        settings: AppSettings = AppSettings(SettingsStore(FakeSettingsDao())),
        scope: CoroutineScope = CoroutineScope(dispatcher),
    ): BookExportOperations = BookExportOperations(store, translations, settings, holder, scope)

    /** The read-in-language seam fake: a per-(lang, chapter, passage) text map,
     *  recording every call so the test can assert the language that reached it. */
    private class FakeTranslations(
        private val texts: Map<String, String>,
    ) : TranslationService {
        val calls = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun cached(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? = null

        override suspend fun translate(
            bookId: String,
            chapter: Int,
            passage: Int,
            target: TranslationTarget,
        ): String? {
            calls += Triple(target.lang, chapter, passage)
            return texts["${target.lang}/$chapter/$passage"]
        }

        override fun prefetch(
            bookId: String,
            chapter: Int,
            passages: List<Int>,
            target: TranslationTarget,
        ) = Unit

        override val ready: Flow<TranslationReady> = flowOf()

        override val translatePossible: Boolean = true
    }

    /** A host that runs the body on the test scheduler and records how the
     *  operation was addressed and reported (the [ImportOperationsTest] shape). */
    private class RecordingHost(
        private val scope: CoroutineScope,
    ) : OperationRunner {
        val specs = mutableListOf<OperationSpec>()
        val reports = mutableListOf<Pair<String, Int?>>()
        val cancelled = mutableListOf<String>()
        private var job: Job? = null

        override fun run(
            spec: OperationSpec,
            block: suspend (OperationReporter) -> Unit,
        ) {
            specs += spec
            job = scope.launch { block(OperationReporter { text, percent -> reports += text to percent }) }
        }

        override fun cancel(id: String): Boolean {
            cancelled += id
            job?.cancel()
            return true
        }
    }

    private class FakeSettingsDao : SettingsDao {
        private val rows = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = rows[key]

        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }

        override suspend fun all(): List<SettingEntity> = rows.map { SettingEntity(it.key, it.value) }.sortedBy { it.key }

        override suspend fun putAll(settings: List<SettingEntity>) {
            settings.forEach { rows[it.key] = it.value }
        }

        override suspend fun deleteAll(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }
    }

    @Test
    fun `a fully stored book exports markdown through the sink once`() =
        runTest(dispatcher) {
            val book = book()
            val store = storeWith(book)
            val translations =
                FakeTranslations(
                    mapOf(
                        "pt-BR/0/0" to "Primeiro.",
                        "pt-BR/0/1" to "Segundo.",
                        "pt-BR/1/0" to "Terceiro.",
                    ),
                )
            var written: ByteArray? = null
            val exports = operations(store, translations)
            val host = RecordingHost(CoroutineScope(dispatcher))

            exports.start(
                bookId = book.id,
                fileName = "Light Years (pt-BR).md",
                language = "pt-BR",
                format = ExportFormat.MARKDOWN,
                shape = ExportShape.TRANSLATED_ONLY,
                sink = { written = it },
                operations = host,
            )
            testScheduler.advanceUntilIdle()

            val markdown = String(written!!, Charsets.UTF_8)
            assertTrue(markdown.startsWith("<!-- Ayvu translation export v1 · Light Years · Portuguese (Brazil) · lfm12b · "), markdown)
            assertTrue(markdown.contains("## Chapter 1"), markdown)
            assertTrue(markdown.contains("Primeiro."), markdown)
            assertTrue(markdown.contains("Terceiro."), markdown)
            assertEquals("export:book-1", host.specs.single().id)
            assertEquals(listOf("1/3 passages", "2/3 passages", "3/3 passages"), host.reports.map { it.first })
            val finished = holder.state.value as ExportUiState.Finished
            assertEquals("Light Years (pt-BR).md", finished.fileName)
            assertEquals(written!!.size.toLong(), finished.bytes)
        }

    @Test
    fun `the chosen language reaches the provider and the header`() =
        runTest(dispatcher) {
            val book = book()
            val store = storeWith(book)
            val translations =
                FakeTranslations(
                    mapOf(
                        "es/0/0" to "Primero.",
                        "es/0/1" to "Segundo.",
                        "es/1/0" to "Tercero.",
                    ),
                )
            var written: ByteArray? = null
            val exports = operations(store, translations)

            exports.start(
                book.id,
                "libro (es).md",
                "es",
                ExportFormat.MARKDOWN,
                ExportShape.TRANSLATED_ONLY,
                { written = it },
                operations = null,
            )
            testScheduler.advanceUntilIdle()

            assertEquals(setOf("es"), translations.calls.map { it.first }.toSet())
            val markdown = String(written!!, Charsets.UTF_8)
            assertTrue(markdown.contains("· Spanish · lfm12b · "), markdown)
            assertTrue(markdown.contains("Primero."), markdown)
        }

    @Test
    fun `an unavailable passage fails by coordinates and never writes`() =
        runTest(dispatcher) {
            val book = book()
            val store = storeWith(book)
            // The second chapter's only passage has no translation and the engine
            // cannot produce one.
            val translations = FakeTranslations(mapOf("pt-BR/0/0" to "Primeiro.", "pt-BR/0/1" to "Segundo."))
            var written = false
            val exports = operations(store, translations)

            exports.start(
                book.id,
                "x.md",
                "pt-BR",
                ExportFormat.MARKDOWN,
                ExportShape.TRANSLATED_ONLY,
                { written = true },
                operations = null,
            )
            testScheduler.advanceUntilIdle()

            val failed = holder.state.value as ExportUiState.Failed
            assertTrue(failed.message.contains("chapter 2, passage 1"), failed.message)
            assertTrue(!written, "the sink must not run when the artifact is incomplete")
        }

    @Test
    fun `an unknown book fails without touching the destination`() =
        runTest(dispatcher) {
            val store = storeWith(book())
            var written = false
            val exports = operations(store, FakeTranslations(emptyMap()))

            exports.start(
                "gone",
                "x.md",
                "pt-BR",
                ExportFormat.MARKDOWN,
                ExportShape.TRANSLATED_ONLY,
                { written = true },
                operations = null,
            )
            testScheduler.advanceUntilIdle()

            assertEquals(ExportUiState.Failed("This book is no longer in the library."), holder.state.value)
            assertTrue(!written)
        }

    @Test
    fun `a new start supersedes the running export`() =
        runTest(dispatcher) {
            val book = book()
            val store = storeWith(book)
            val translations =
                FakeTranslations(
                    mapOf(
                        "pt-BR/0/0" to "Primeiro.",
                        "pt-BR/0/1" to "Segundo.",
                        "pt-BR/1/0" to "Terceiro.",
                    ),
                )
            val exports = operations(store, translations)
            val host = RecordingHost(CoroutineScope(dispatcher))

            exports.start(book.id, "first.md", "pt-BR", ExportFormat.MARKDOWN, ExportShape.TRANSLATED_ONLY, {}, host)
            exports.start(book.id, "second.md", "pt-BR", ExportFormat.MARKDOWN, ExportShape.TRANSLATED_ONLY, {}, host)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("export:book-1"), host.cancelled)
            val finished = holder.state.value as ExportUiState.Finished
            assertEquals("second.md", finished.fileName)
            assertTrue(finished.bytes > 0)
        }

    @Test
    fun `an empty book fails with the typed empty message`() =
        runTest(dispatcher) {
            val empty = Book(id = "empty", title = "Empty")
            val store = storeWith(empty)
            var written = false
            val exports = operations(store, FakeTranslations(emptyMap()))

            exports.start(
                "empty",
                "x.md",
                "pt-BR",
                ExportFormat.PLAIN_TEXT,
                ExportShape.TRANSLATED_ONLY,
                { written = true },
                operations = null,
            )
            testScheduler.advanceUntilIdle()

            assertEquals(ExportUiState.Failed("This book has no passages to export."), holder.state.value)
            assertTrue(!written)
        }
}
