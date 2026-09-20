package io.github.moronigranja.ayvu.ebook.export

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Serializes one document to the artifact's bytes. */
interface ExportWriter {
    fun write(document: ExportDocument): ByteArray
}

/** The [ExportWriter] of every [ExportFormat]: one document, three encodings. */
object ExportWriters {
    fun of(format: ExportFormat): ExportWriter =
        when (format) {
            ExportFormat.MARKDOWN -> MarkdownExportWriter
            ExportFormat.PLAIN_TEXT -> PlainTextExportWriter
            ExportFormat.EPUB -> EpubExportWriter
        }
}

// ---------------------------------------------------------------------------
// Shared rules — all three writers emit the same identity line and headings
// ---------------------------------------------------------------------------

/** Identity header prefix; the rest is `<title> · <languageLabel> · <translator> · <ISO-8601 UTC>`. */
private const val HEADER_PREFIX = "Ayvu translation export v1 · "

private val ISO_UTC: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** The artifact's identity line, without any format's delimiters around it. */
private fun identityOf(document: ExportDocument): String =
    "$HEADER_PREFIX${document.title} · ${document.languageLabel} · ${document.translator} · " +
        isoTimestamp(document.generatedAtEpochMillis)

/** Whole UTC seconds — the only timestamp shape EPUB 3 `dcterms:modified` accepts. */
private fun isoTimestamp(epochMillis: Long): String = ISO_UTC.format(Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS))

/** Chapter heading: the parsed title, else a 1-based positional fallback. */
private fun headingOf(chapter: ExportChapter): String = chapter.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter ${chapter.index + 1}"

/**
 * The artifact's paragraphs in order: per chapter its heading (rendered by [chapterLine])
 * and then every passage's paragraphs. Writers join consecutive paragraphs with one
 * blank line and end the file with a single newline.
 */
private fun bodyParagraphs(
    document: ExportDocument,
    chapterLine: (ExportChapter) -> String,
): List<String> =
    buildList {
        for (chapter in document.chapters) {
            add(chapterLine(chapter))
            for (passage in chapter.passages) addAll(passageParagraphs(passage, document.shape))
        }
    }

/**
 * One passage's paragraphs: the original (BILINGUAL only), then the translation with
 * every line marked `"> "` in BILINGUAL so the two texts stay tellable apart in the
 * artifact; TRANSLATED_ONLY writes the translation unmarked and drops the original.
 */
private fun passageParagraphs(
    passage: ExportPassage,
    shape: ExportShape,
): List<String> {
    val translated =
        passage.translated.lines().joinToString("\n") { line ->
            if (shape == ExportShape.BILINGUAL) "> $line" else line
        }
    return when (shape) {
        ExportShape.TRANSLATED_ONLY -> listOf(translated)
        ExportShape.BILINGUAL -> listOf(passage.original, translated)
    }
}

// ---------------------------------------------------------------------------
// Markdown / plain text
// ---------------------------------------------------------------------------

/**
 * UTF-8 Markdown: the identity line as an HTML comment, `##` chapter sections, and the
 * translation as a `> ` blockquote in BILINGUAL. Pure text — re-importing the artifact
 * adds no front-matter chapter, and a fixed document always yields the same bytes.
 */
object MarkdownExportWriter : ExportWriter {
    override fun write(document: ExportDocument): ByteArray {
        val paragraphs =
            listOf("<!-- ${identityOf(document)} -->") + bodyParagraphs(document) { "## ${headingOf(it)}" }
        return (paragraphs.joinToString("\n\n") + "\n").toByteArray(Charsets.UTF_8)
    }
}

/**
 * UTF-8 plain text: the identity line as a bare first line and bare chapter heading
 * lines, no markup a plain reader would show verbatim.
 */
object PlainTextExportWriter : ExportWriter {
    override fun write(document: ExportDocument): ByteArray {
        val paragraphs = listOf(identityOf(document)) + bodyParagraphs(document) { headingOf(it) }
        return (paragraphs.joinToString("\n\n") + "\n").toByteArray(Charsets.UTF_8)
    }
}

// ---------------------------------------------------------------------------
// EPUB 3
// ---------------------------------------------------------------------------

private const val MIMETYPE_PATH = "mimetype"
private val MIMETYPE = "application/epub+zip".toByteArray(Charsets.US_ASCII)
private const val CONTAINER_PATH = "META-INF/container.xml"
private const val OPF_PATH = "OEBPS/content.opf"
private const val NAV_PATH = "OEBPS/nav.xhtml"
private const val CHAPTER_PATH_PREFIX = "OEBPS/chapter-"
private const val XML_PROLOGUE = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"

/**
 * EPUB 3 container: OCF `mimetype` (first entry, STORED), container descriptor, package
 * (the translation's identity in `dc:description`), nav document, then one XHTML per
 * chapter. Every entry's timestamp comes from [ExportDocument.generatedAtEpochMillis],
 * so a fixed document produces the same bytes twice.
 */
object EpubExportWriter : ExportWriter {
    override fun write(document: ExportDocument): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val time = document.generatedAtEpochMillis
            writeStored(zip, MIMETYPE_PATH, MIMETYPE, time)
            writeEntry(zip, CONTAINER_PATH, containerXml(), time)
            writeEntry(zip, OPF_PATH, contentOpf(document), time)
            writeEntry(zip, NAV_PATH, navXhtml(document), time)
            for (chapter in document.chapters) {
                writeEntry(zip, chapterPath(chapter), chapterXhtml(document, chapter), time)
            }
        }
        return out.toByteArray()
    }
}

private fun chapterPath(chapter: ExportChapter): String = "$CHAPTER_PATH_PREFIX${chapter.index}.xhtml"

private fun containerXml(): String =
    buildString {
        append(XML_PROLOGUE).append('\n')
        append("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">")
        append("<rootfiles>")
        append("<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>")
        append("</rootfiles></container>")
    }

/** [ExportDocument.translator] recorded where a reader shows the book's provenance. */
private fun descriptionOf(document: ExportDocument): String =
    "Machine translation produced on device by Ayvu (translator: ${document.translator}); unrevised."

private fun contentOpf(document: ExportDocument): String =
    buildString {
        val language = escapeXml(document.language)
        append(XML_PROLOGUE).append('\n')
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"pub-id\"")
        append(" xml:lang=\"$language\">\n")
        append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("    <dc:identifier id=\"pub-id\">urn:ayvu:${escapeXml(document.bookId)}</dc:identifier>\n")
        append("    <dc:title>${escapeXml(document.title)}</dc:title>\n")
        for (author in document.authors) {
            append("    <dc:creator>${escapeXml(author)}</dc:creator>\n")
        }
        append("    <dc:language>$language</dc:language>\n")
        append("    <dc:description>${escapeXml(descriptionOf(document))}</dc:description>\n")
        append("    <meta property=\"dcterms:modified\">${isoTimestamp(document.generatedAtEpochMillis)}</meta>\n")
        append("  </metadata>\n")
        append("  <manifest>\n")
        append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        for (chapter in document.chapters) {
            append("    <item id=\"chapter-${chapter.index}\" href=\"chapter-${chapter.index}.xhtml\"")
            append(" media-type=\"application/xhtml+xml\"/>\n")
        }
        append("  </manifest>\n")
        append("  <spine>\n")
        for (chapter in document.chapters) {
            append("    <itemref idref=\"chapter-${chapter.index}\"/>\n")
        }
        append("  </spine>\n")
        append("</package>\n")
    }

private fun navXhtml(document: ExportDocument): String =
    buildString {
        val language = escapeXml(document.language)
        append(XML_PROLOGUE).append('\n')
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
        append(" xml:lang=\"$language\" lang=\"$language\">\n")
        append("  <head><title>${escapeXml(document.title)}</title></head>\n")
        append("  <body>\n    <nav epub:type=\"toc\">\n      <ol>\n")
        for (chapter in document.chapters) {
            append("        <li><a href=\"chapter-${chapter.index}.xhtml\">${escapeXml(headingOf(chapter))}</a></li>\n")
        }
        append("      </ol>\n    </nav>\n  </body>\n</html>\n")
    }

private fun chapterXhtml(
    document: ExportDocument,
    chapter: ExportChapter,
): String =
    buildString {
        val language = escapeXml(document.language)
        val heading = escapeXml(headingOf(chapter))
        val bilingual = document.shape == ExportShape.BILINGUAL
        val translationAttributes = if (bilingual) " class=\"translation\" xml:lang=\"$language\"" else ""
        append(XML_PROLOGUE).append('\n')
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$language\" lang=\"$language\">\n")
        append("  <head><title>$heading</title></head>\n")
        append("  <body>\n")
        append("    <h1>$heading</h1>\n")
        for (passage in chapter.passages) {
            if (bilingual) append("    <p>${escapeXml(passage.original)}</p>\n")
            append("    <p$translationAttributes>${escapeXml(passage.translated)}</p>\n")
        }
        append("  </body>\n</html>\n")
    }

/** One DEFLATED entry; its time is the document's, never the wall clock. */
private fun writeEntry(
    zip: ZipOutputStream,
    path: String,
    content: String,
    time: Long,
) {
    val entry = ZipEntry(path)
    entry.time = time
    zip.putNextEntry(entry)
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

/** `mimetype` must be STORED (a DEFLATED one makes the container invalid) and carry its own size + CRC. */
private fun writeStored(
    zip: ZipOutputStream,
    path: String,
    bytes: ByteArray,
    time: Long,
) {
    val entry = ZipEntry(path)
    entry.method = ZipEntry.STORED
    entry.time = time
    entry.size = bytes.size.toLong()
    entry.compressedSize = bytes.size.toLong()
    entry.crc = CRC32().apply { update(bytes) }.value
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

/**
 * The XML text/attribute escaper for every XHTML and OPF part. The repo only
 * *un*escapes (`OpfBookReader.decodeEntities`), so writing needs its own.
 */
private fun escapeXml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
