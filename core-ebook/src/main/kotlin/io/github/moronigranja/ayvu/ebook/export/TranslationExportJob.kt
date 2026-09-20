package io.github.moronigranja.ayvu.ebook.export

import io.github.moronigranja.ayvu.model.CachedBook
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why an export could not be produced. */
sealed class TranslationExportError(
    message: String,
) : Exception(message) {
    class EmptyBook : TranslationExportError("This book has no passages to export.")

    class Unavailable(
        val chapter: Int,
        val passage: Int,
    ) : TranslationExportError(
            "Could not translate chapter ${chapter + 1}, passage ${passage + 1} — the read-in-language engine is unavailable.",
        )
}

/**
 * Builds one book's translated text as an [ExportDocument]: every passage's
 * translation, walked in spine order through [translated] — the stored row when
 * one exists, a fresh decode otherwise. A null return is [TranslationExportError.Unavailable]
 * for that passage, never a silent gap (the artifact is all-or-nothing).
 *
 * Pure JVM: no Android, no I/O. Cancellation cooperates at every passage
 * (`currentCoroutineContext().ensureActive()`), so the operation host's Stop
 * abandons the walk with nothing written.
 */
class TranslationExportJob(
    /** One passage's translated text: the stored row, or a fresh decode (the caller
     *  wires `TranslationService.translate`). Null = unavailable, never a silent gap. */
    private val translated: suspend (chapterIndex: Int, passageIndex: Int, source: String) -> String?,
    private val nowEpochMillis: () -> Long,
    /** After every passage: passages done of the total, in spine order. */
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
) {
    suspend fun build(
        book: CachedBook,
        language: String,
        languageLabel: String,
        translator: String,
        shape: ExportShape,
    ): ExportDocument {
        if (book.passages.isEmpty()) throw TranslationExportError.EmptyBook()
        val total = book.passages.size
        val chapters = mutableListOf<ExportChapter>()
        var chapterIndex = -1
        var chapterTitle: String? = null
        var passages = mutableListOf<ExportPassage>()
        var done = 0
        for (row in book.passages) {
            currentCoroutineContext().ensureActive()
            // The rows are spine-ordered, so a chapter is one run of consecutive
            // rows sharing chapterIndex; its title rides every row.
            if (row.chapterIndex != chapterIndex) {
                if (chapterIndex >= 0) chapters += ExportChapter(chapterIndex, chapterTitle, passages.toList())
                chapterIndex = row.chapterIndex
                chapterTitle = row.chapterTitle
                passages = mutableListOf()
            }
            val text =
                translated(row.chapterIndex, row.passageIndex, row.text)?.takeIf { it.isNotBlank() }
                    ?: throw TranslationExportError.Unavailable(row.chapterIndex, row.passageIndex)
            passages += ExportPassage(original = row.text, translated = text)
            done++
            onProgress(done, total)
        }
        chapters += ExportChapter(chapterIndex, chapterTitle, passages.toList())
        return ExportDocument(
            bookId = book.id,
            title = book.title,
            authors = book.authors,
            language = language,
            languageLabel = languageLabel,
            translator = translator,
            shape = shape,
            generatedAtEpochMillis = nowEpochMillis(),
            chapters = chapters,
        )
    }
}
