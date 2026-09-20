package io.github.moronigranja.ayvu.ebook.export

/** Which text the artifact carries (the dialog's "Content" choice). */
enum class ExportShape { TRANSLATED_ONLY, BILINGUAL }

/** A file type the export can write; extension + SAF mime + dialog label. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
) {
    MARKDOWN("md", "text/markdown", "Markdown (.md)"),
    PLAIN_TEXT("txt", "text/plain", "Plain text (.txt)"),
    EPUB("epub", "application/epub+zip", "EPUB (.epub)"),
}

/** One passage: its translation, plus the original when [ExportShape.BILINGUAL]. */
data class ExportPassage(
    val original: String,
    val translated: String,
)

data class ExportChapter(
    val index: Int,
    val title: String?,
    val passages: List<ExportPassage>,
)

/** A book's translated text, complete, ready to be written in any [ExportFormat]. */
data class ExportDocument(
    val bookId: String,
    val title: String,
    val authors: List<String>,
    /** Canonical app code, e.g. `pt-BR` (the chosen language). */
    val language: String,
    /** Display name for the header/metadata, e.g. "Portuguese (Brazil)". */
    val languageLabel: String,
    /** The translator identity that wrote the text (engine id, e.g. `lfm12b`). */
    val translator: String,
    val shape: ExportShape,
    val generatedAtEpochMillis: Long,
    val chapters: List<ExportChapter>,
)
