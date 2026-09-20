package io.github.moronigranja.ayvu.featurelibrary

import io.github.moronigranja.ayvu.ebook.export.ExportFormat
import io.github.moronigranja.ayvu.ebook.export.ExportShape
import io.github.moronigranja.ayvu.ebook.export.ExportWriters
import io.github.moronigranja.ayvu.ebook.export.TranslationExportError
import io.github.moronigranja.ayvu.ebook.export.TranslationExportJob
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.ops.OperationChannel
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.player.pregen.TranslationService
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import io.github.moronigranja.ayvu.ui.languageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where the finished artifact goes. The caller owns the destination (SAF). */
fun interface ExportSink {
    suspend fun write(bytes: ByteArray)
}

/** The one export's user-visible state (the library screen's snackbar). */
sealed interface ExportUiState {
    data object Idle : ExportUiState

    data class Running(
        val done: Int,
        val total: Int,
    ) : ExportUiState

    /** [fileName] is the user-visible name the picker used, for the confirmation. */
    data class Finished(
        val fileName: String,
        val bytes: Long,
    ) : ExportUiState

    data class Failed(
        val message: String,
    ) : ExportUiState
}

/**
 * The ONE export state every surface renders: a singleton, not per-View-Model
 * state, so an export started from a row menu survives the dialog that started
 * it (the operation runs on the process-lifetime scope, not the screen's).
 */
@Singleton
class ExportStateHolder
    @Inject
    constructor() {
        private val _state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)

        val state: StateFlow<ExportUiState> = _state.asStateFlow()

        fun set(value: ExportUiState) {
            _state.value = value
        }

        /** Reads and clears a terminal state — the screen consumes it once. */
        fun consumeTerminal(): ExportUiState? {
            val current = _state.value
            if (current is ExportUiState.Finished || current is ExportUiState.Failed) {
                _state.value = ExportUiState.Idle
                return current
            }
            return null
        }
    }

/**
 * The per-book "Export translation…" operation: build one [ExportUiState]-driven
 * artifact over the shared [TranslationExportJob] (stored translations reused,
 * stale passages decoded through [TranslationService]) and hand it to the
 * caller's [ExportSink] in ONE write, only after the artifact is complete — a
 * failure before the write leaves the destination untouched.
 *
 * Mirrors [ImportOperations]: one export at a time (a new start supersedes the
 * running one), a host-or-hostless body (the pure-JVM harness runs it on
 * [appScope] with no notification host), and the terminal state lives on the
 * singleton [ExportStateHolder].
 */
@Singleton
class BookExportOperations
    @Inject
    constructor(
        private val books: LibraryStore,
        private val translations: TranslationService,
        private val settings: AppSettings,
        private val holder: ExportStateHolder,
        private val appScope: CoroutineScope,
    ) {
        /** The running export's operation id (null = none) — the supersede key. */
        private var runningId: String? = null

        /** The no-host run's job (pure-JVM harness only). */
        private var hostlessJob: Job? = null

        /**
         * Builds and writes one book artifact. [language] is the dialog's chosen
         * app language code; [fileName] is only echoed back for the confirmation.
         */
        fun start(
            bookId: String,
            fileName: String,
            language: String,
            format: ExportFormat,
            shape: ExportShape,
            sink: ExportSink,
            operations: OperationRunner?,
        ) {
            cancel(operations) // supersede: one export at a time
            val operationId = EXPORT_OPERATION_PREFIX + bookId
            runningId = operationId
            holder.set(ExportUiState.Running(0, 0))
            val body: suspend (OperationReporter) -> Unit = { reporter ->
                runExport(bookId, fileName, language, format, shape, sink, reporter)
            }
            if (operations != null) {
                val title =
                    books.books.value
                        .firstOrNull { it.book.id == bookId }
                        ?.book
                        ?.title
                        ?: fileName
                operations.run(
                    OperationSpec(
                        id = operationId,
                        channel = OperationChannel.EXPORT,
                        title = "Ayvu — exporting $title",
                        cancelLabel = "Stop",
                    ),
                    body,
                )
            } else {
                hostlessJob = appScope.launch { body(NO_REPORT) }
            }
        }

        /** Stops the running export (the notification's Stop action) and returns
         * the surface to [ExportUiState.Idle] — nothing is written. */
        fun cancel(operations: OperationRunner?) {
            val id = runningId
            runningId = null
            holder.set(ExportUiState.Idle)
            if (operations != null) {
                if (id != null) operations.cancel(id)
            } else {
                hostlessJob?.cancel()
                hostlessJob = null
            }
        }

        private suspend fun runExport(
            bookId: String,
            fileName: String,
            language: String,
            format: ExportFormat,
            shape: ExportShape,
            sink: ExportSink,
            reporter: OperationReporter,
        ) {
            try {
                val book =
                    books.cachedBooks().firstOrNull { it.id == bookId }
                        ?: throw IllegalStateException("This book is no longer in the library.")
                // The engine id keys every translation of the book (speech render
                // path segment + the stored-text translator column), so the export
                // reuses exactly the rows the active engine wrote.
                val translator = TranslatePacks.byId(settings.state.value.translateEngine).id
                val document =
                    TranslationExportJob(
                        translated = { chapter, passage, _ ->
                            translations.translate(bookId, chapter, passage, TranslationTarget(language, translator))
                        },
                        nowEpochMillis = System::currentTimeMillis,
                        onProgress = { done, total ->
                            holder.set(ExportUiState.Running(done, total))
                            reporter.report("$done/$total passages", if (total > 0) done * 100 / total else null)
                        },
                    ).build(book, language, languageLabel(language), translator, shape)
                val bytes = ExportWriters.of(format).write(document)
                // The ONLY write, after the artifact is complete: a failure before
                // this point leaves the destination untouched (no partial file).
                sink.write(bytes)
                holder.set(ExportUiState.Finished(fileName, bytes.size.toLong()))
            } catch (e: CancellationException) {
                holder.set(ExportUiState.Idle)
                throw e
            } catch (e: TranslationExportError) {
                holder.set(ExportUiState.Failed(e.message.orEmpty()))
            } catch (e: IllegalStateException) {
                holder.set(ExportUiState.Failed(e.message.orEmpty()))
            } catch (e: IOException) {
                holder.set(ExportUiState.Failed("Could not write the file: ${e.message}"))
            }
        }

        private companion object {
            /** The pure-JVM harness's no-op progress sink. */
            val NO_REPORT = OperationReporter { _, _ -> }
        }
    }

/** The export operation id's shared prefix (the ViewModel's `cancel` addresses
 *  the same id the operation runs under). */
internal const val EXPORT_OPERATION_PREFIX = "export:"
