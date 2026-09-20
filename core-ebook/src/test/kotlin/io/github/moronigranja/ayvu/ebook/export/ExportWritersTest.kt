package io.github.moronigranja.ayvu.ebook.export

import io.github.moronigranja.ayvu.ebook.EpubParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The artifact contract: exact text for Markdown/plain text, a valid EPUB container
 * that round-trips through [EpubParser], and byte-stable output for a fixed document.
 */
class ExportWritersTest {
    // ------------------------------------------------------------------
    // Text formats — whole-string equality for a fixed fixture
    // ------------------------------------------------------------------

    @Test
    fun `markdown export is exact in both shapes`() {
        assertEquals(MARKDOWN_TRANSLATED_ONLY, utf8(export(ExportFormat.MARKDOWN, ExportShape.TRANSLATED_ONLY)))
        assertEquals(MARKDOWN_BILINGUAL, utf8(export(ExportFormat.MARKDOWN, ExportShape.BILINGUAL)))
    }

    @Test
    fun `plain text export is exact in both shapes`() {
        assertEquals(PLAIN_TEXT_TRANSLATED_ONLY, utf8(export(ExportFormat.PLAIN_TEXT, ExportShape.TRANSLATED_ONLY)))
        assertEquals(PLAIN_TEXT_BILINGUAL, utf8(export(ExportFormat.PLAIN_TEXT, ExportShape.BILINGUAL)))
    }

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    @Test
    fun `epub container holds the parts in order with a stored mimetype`() {
        val parts = zipParts(export(ExportFormat.EPUB, ExportShape.BILINGUAL))

        assertEquals(
            listOf(
                "mimetype",
                "META-INF/container.xml",
                "OEBPS/content.opf",
                "OEBPS/nav.xhtml",
                "OEBPS/chapter-0.xhtml",
                "OEBPS/chapter-1.xhtml",
            ),
            parts.map { it.name },
        )

        val mimetype = parts.first()
        assertEquals(ZipEntry.STORED, mimetype.method)
        assertEquals(20, mimetype.bytes.size)
        assertArrayEquals("application/epub+zip".toByteArray(Charsets.US_ASCII), mimetype.bytes)

        val generatedAt = Instant.parse(GENERATED_AT).toEpochMilli()
        assertTrue(parts.all { it.time == generatedAt }, "entry time must come from the document, not the clock")

        val opf = utf8(parts.first { it.name == "OEBPS/content.opf" }.bytes)
        assertTrue(opf.contains("<dc:language>pt-BR</dc:language>"))
        assertTrue(opf.contains("Machine translation produced on device by Ayvu (translator: lfm12b); unrevised."))
        assertTrue(opf.contains("<dc:title>Light Years</dc:title>"))

        // Every XML part must be well-formed; parse() throws otherwise.
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (part in parts.filter { it.name != "mimetype" }) {
            builder.parse(ByteArrayInputStream(part.bytes))
        }
    }

    @Test
    fun `epub round-trips through the epub parser`() {
        val translatedOnly = EpubParser.parse(export(ExportFormat.EPUB, ExportShape.TRANSLATED_ONLY))
        assertEquals("Light Years", translatedOnly.title)
        assertEquals(listOf("Elisa Shua Dusapin"), translatedOnly.authors)
        assertEquals(listOf("Chapter One", "Chapter 2"), translatedOnly.chapters.map { it.title })
        val texts = translatedOnly.chapters.flatMap { chapter -> chapter.passages.map { it.text } }
        assertTrue(texts.contains("A casa estava silenciosa."))
        assertTrue(texts.contains("Ela abriu a porta."))
        assertTrue(texts.contains("Lá fora, chovia. A rua estava vazia."))
        assertFalse(texts.any { it.contains("The house was quiet.") }) // TRANSLATED_ONLY drops the original

        val bilingual = EpubParser.parse(export(ExportFormat.EPUB, ExportShape.BILINGUAL))
        val bilingualTexts = bilingual.chapters.flatMap { chapter -> chapter.passages.map { it.text } }
        assertTrue(bilingualTexts.contains("The house was quiet."))
        assertTrue(bilingualTexts.contains("A casa estava silenciosa."))
    }

    @Test
    fun `epub escapes xml special characters in text and attributes`() {
        val document =
            fixture(ExportShape.BILINGUAL).copy(
                title = "A & B <tag> \"quoted\" 'apostrophe'",
                authors = listOf("M. & O. <Editions>"),
                chapters =
                    listOf(
                        ExportChapter(
                            index = 0,
                            title = "C & C",
                            passages =
                                listOf(
                                    ExportPassage(
                                        original = "<p>original</p>",
                                        translated = "5 < 6 & 7 > 4 — \"así\" 'dijo'",
                                    ),
                                ),
                        ),
                    ),
            )

        val bytes = ExportWriters.of(ExportFormat.EPUB).write(document)
        for (part in zipParts(bytes).filter { it.name != "mimetype" }) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(part.bytes))
        }

        val book = EpubParser.parse(bytes)
        assertEquals(document.title, book.title)
        assertEquals(document.authors, book.authors)
        assertEquals(listOf("C & C"), book.chapters.map { it.title })
        val texts = book.chapters[0].passages.map { it.text }
        assertTrue(texts.contains("<p>original</p>"))
        assertTrue(texts.contains("5 < 6 & 7 > 4 — \"así\" 'dijo'"))
    }

    @Test
    fun `writing the same document twice yields identical bytes`() {
        for (format in ExportFormat.entries) {
            val first = export(format, ExportShape.BILINGUAL)
            val second = export(format, ExportShape.BILINGUAL)
            assertArrayEquals(first, second, "$format is not byte-stable")
        }
    }

    // ------------------------------------------------------------------
    // Fixture + helpers
    // ------------------------------------------------------------------

    private fun export(
        format: ExportFormat,
        shape: ExportShape,
    ): ByteArray = ExportWriters.of(format).write(fixture(shape))

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}

private const val GENERATED_AT = "2026-09-20T12:00:00Z"

private const val IDENTITY =
    "Ayvu translation export v1 · Light Years · Portuguese (Brazil) · lfm12b · 2026-09-20T12:00:00Z"

/** 2 chapters (the second untitled, to exercise the positional fallback) and 3 passages. */
private fun fixture(shape: ExportShape): ExportDocument =
    ExportDocument(
        bookId = "book-1",
        title = "Light Years",
        authors = listOf("Elisa Shua Dusapin"),
        language = "pt-BR",
        languageLabel = "Portuguese (Brazil)",
        translator = "lfm12b",
        shape = shape,
        generatedAtEpochMillis = Instant.parse(GENERATED_AT).toEpochMilli(),
        chapters =
            listOf(
                ExportChapter(
                    index = 0,
                    title = "Chapter One",
                    passages =
                        listOf(
                            ExportPassage(original = "The house was quiet.", translated = "A casa estava silenciosa."),
                        ),
                ),
                ExportChapter(
                    index = 1,
                    title = null,
                    passages =
                        listOf(
                            ExportPassage(original = "She opened the door.", translated = "Ela abriu a porta."),
                            ExportPassage(
                                original = "Outside, it was raining.",
                                translated = "Lá fora, chovia.\nA rua estava vazia.",
                            ),
                        ),
                ),
            ),
    )

private val MARKDOWN_TRANSLATED_ONLY =
    """
    <!-- $IDENTITY -->

    ## Chapter One

    A casa estava silenciosa.

    ## Chapter 2

    Ela abriu a porta.

    Lá fora, chovia.
    A rua estava vazia.
    """.trimIndent() + "\n"

private val MARKDOWN_BILINGUAL =
    """
    <!-- $IDENTITY -->

    ## Chapter One

    The house was quiet.

    > A casa estava silenciosa.

    ## Chapter 2

    She opened the door.

    > Ela abriu a porta.

    Outside, it was raining.

    > Lá fora, chovia.
    > A rua estava vazia.
    """.trimIndent() + "\n"

private val PLAIN_TEXT_TRANSLATED_ONLY =
    """
    $IDENTITY

    Chapter One

    A casa estava silenciosa.

    Chapter 2

    Ela abriu a porta.

    Lá fora, chovia.
    A rua estava vazia.
    """.trimIndent() + "\n"

private val PLAIN_TEXT_BILINGUAL =
    """
    $IDENTITY

    Chapter One

    The house was quiet.

    > A casa estava silenciosa.

    Chapter 2

    She opened the door.

    > Ela abriu a porta.

    Outside, it was raining.

    > Lá fora, chovia.
    > A rua estava vazia.
    """.trimIndent() + "\n"

/** One zip entry as it comes back out of the artifact: order, method, time, bytes. */
private data class ZipPart(
    val name: String,
    val method: Int,
    val time: Long,
    val bytes: ByteArray,
)

private fun zipParts(bytes: ByteArray): List<ZipPart> {
    val parts = mutableListOf<ZipPart>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            parts += ZipPart(entry.name, entry.method, entry.time, zip.readBytes())
        }
    }
    return parts
}
