package com.moronigranja.localttsreader.featureshare

import com.moronigranja.localttsreader.locate.IndexRebuilder
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.ocr.OcrEngine
import com.moronigranja.localttsreader.ocr.OcrImage
import com.moronigranja.localttsreader.ocr.OcrResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S2: the share pipeline — text, image+OCR, threshold, cold-start gate. */
class ShareSnippetResolverTest {
    private val book =
        Book(
            id = "anna",
            title = "Anna Karenina",
            chapters =
                listOf(
                    Chapter(
                        0,
                        "All happy families",
                        listOf(
                            TextPassage("All happy families are alike; each unhappy family is unhappy in its own way."),
                        ),
                    ),
                    Chapter(
                        1,
                        "The Oblonskys",
                        listOf(TextPassage("Everything was in confusion in the Oblonskys' house.")),
                    ),
                ),
        )

    private class FakeOcr(
        var text: String = "",
        var languages: List<String>? = null,
    ) : OcrEngine {
        var calls = 0

        override suspend fun recognize(
            image: OcrImage,
            languages: List<String>,
        ): OcrResult {
            calls++
            this.languages = languages
            return OcrResult(text, if (text.isBlank()) 0.0 else 0.95)
        }
    }

    private inner class Harness(
        threshold: () -> Double = { 0.6 },
        var ocr: OcrEngine? = null,
        var ocrLanguages: () -> List<String> = { listOf("eng") },
    ) {
        val index = TextIndex()
        val gate = IndexRebuilder(index)
        val resolver = ShareSnippetResolver(index, gate, threshold, ocr, ocrLanguages)

        fun rebuild() =
            gate.rebuild(
                listOf(
                    com.moronigranja.localttsreader.model.CachedBook(
                        id = book.id,
                        title = book.title,
                        authors = emptyList(),
                        passages =
                            listOf(
                                com.moronigranja.localttsreader.model.CachedPassage(
                                    chapterIndex = 0,
                                    chapterTitle = "All happy families",
                                    passageIndex = 0,
                                    text = "All happy families are alike; each unhappy family is unhappy in its own way.",
                                ),
                                com.moronigranja.localttsreader.model.CachedPassage(
                                    chapterIndex = 1,
                                    chapterTitle = "The Oblonskys",
                                    passageIndex = 0,
                                    text = "Everything was in confusion in the Oblonskys' house.",
                                ),
                            ),
                    ),
                ),
            )
    }

    @Test
    fun `verbatim text share resolves to the right passage`() =
        runTest {
            val h = Harness()
            h.rebuild()
            val resolution =
                h.resolver.resolve(
                    ShareInput.Text("All happy families are alike; each unhappy family is unhappy in its own way."),
                )
            assertTrue(resolution is ShareResolution.Found, "was: $resolution")
            val found = resolution as ShareResolution.Found
            assertEquals("anna", found.bookId)
            assertEquals("Anna Karenina", found.bookTitle)
            assertEquals(0, found.chapterIndex)
            assertEquals("All happy families", found.chapterTitle)
            assertEquals(0, found.passageIndex)
            assertTrue(found.confidence >= 0.99)
        }

    @Test
    fun `above-threshold OCR-noisy text still resolves`() =
        runTest {
            val h = Harness()
            h.rebuild()
            val noisy = "ALL HAPPY FAMILIES ARE ALIKE; EACH UNHAPPY FAMILY IS UNHAPPY IN ITS OWN WAY."
            val resolution = h.resolver.resolve(ShareInput.Text(noisy))
            assertTrue(resolution is ShareResolution.Found, "was: $resolution")
            assertEquals(0, (resolution as ShareResolution.Found).chapterIndex)
        }

    @Test
    fun `below-threshold text is not found with the closest candidate`() =
        runTest {
            val h = Harness(threshold = { 1.000001 }) // above a verbatim 1.0 score
            h.rebuild()
            val resolution =
                h.resolver.resolve(
                    ShareInput.Text("All happy families are alike; each unhappy family is unhappy in its own way."),
                )
            assertTrue(resolution is ShareResolution.NotFound, "was: $resolution")
            val notFound = resolution as ShareResolution.NotFound
            assertEquals(ShareResolution.Reason.NO_MATCH, notFound.reason)
            assertNotNull(notFound.closest, "closest hint present")
            assertEquals("Anna Karenina", notFound.closest!!.bookTitle)
            assertTrue(notFound.closest!!.confidence < 1.000001)
            assertEquals(1.0, notFound.closest!!.confidence)
        }

    @Test
    fun `blank and punctuation-only shares are never matched`() =
        runTest {
            val h = Harness()
            h.rebuild()
            val blank = h.resolver.resolve(ShareInput.Text("   \n  "))
            assertEquals(ShareResolution.Reason.BLANK, (blank as ShareResolution.NotFound).reason)
            val punct = h.resolver.resolve(ShareInput.Text("!!!...???"))
            assertEquals(ShareResolution.Reason.NO_MATCH, (punct as ShareResolution.NotFound).reason)
        }

    @Test
    fun `image share is downscaled, OCRed and matched`() =
        runTest {
            val fake = FakeOcr(text = "ALL HAPPY FAMILIES ARE ALIKE EACH UNHAPPY FAMILY IS UNHAPPY")
            val h = Harness(ocr = fake)
            h.rebuild()
            // A full-resolution raster (S22-portrait proportions) built by arithmetic.
            val big = OcrImage(1440, 3088, IntArray(1440 * 3088))
            val resolution = h.resolver.resolve(ShareInput.Image(big))
            assertTrue(resolution is ShareResolution.Found, "was: $resolution")
            val found = resolution as ShareResolution.Found
            assertEquals("anna", found.bookId)
            assertEquals("eng", fake.languages!!.first())
        }

    @Test
    fun `image share with blank OCR is not readable`() =
        runTest {
            val h = Harness(ocr = FakeOcr(text = ""))
            h.rebuild()
            val resolution = h.resolver.resolve(ShareInput.Image(OcrImage(2, 2, IntArray(4))))
            assertEquals(ShareResolution.Reason.NO_READABLE_TEXT, (resolution as ShareResolution.NotFound).reason)
        }

    @Test
    fun `image share without an OCR engine is unavailable`() =
        runTest {
            val h = Harness(ocr = null)
            h.rebuild()
            val resolution = h.resolver.resolve(ShareInput.Image(OcrImage(2, 2, IntArray(4))))
            assertEquals(ShareResolution.Reason.OCR_UNAVAILABLE, (resolution as ShareResolution.NotFound).reason)
        }

    @Test
    fun `unrelated text is not found with no candidate`() =
        runTest {
            val h = Harness()
            h.rebuild()
            val resolution = h.resolver.resolve(ShareInput.Text("quantum entanglement emitted zephyrs"))
            assertTrue(resolution is ShareResolution.NotFound)
            // The matcher always returns its best line — the unrelated snippet's
            // best sits at ~0: the hint still names the book but with a null score.
            val closest = (resolution as ShareResolution.NotFound).closest
            assertNotNull(closest)
            assertEquals(0.0, closest!!.confidence)
        }

    @Test
    fun `cold start waits for the index rebuild before querying`() =
        runTest {
            val h = Harness()
            // No rebuild() yet: the share arrives before the launch-time rebuild.
            // runTest's TestScope is a CoroutineScope: use its members (plain
            // async), so the share arrives before the app's rebuild lands.
            val d =
                async {
                    h.resolver.resolve(ShareInput.Text("Everything was in confusion in the Oblonskys' house."))
                }
            yield()
            h.rebuild()
            val resolution = d.await()
            assertTrue(resolution is ShareResolution.Found, "resolved after the gate: $resolution")
        }
}
