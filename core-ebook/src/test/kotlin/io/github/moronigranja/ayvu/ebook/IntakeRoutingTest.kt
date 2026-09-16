package io.github.moronigranja.ayvu.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F4 external-file intake routing: the pure decisions behind ACTION_VIEW
 * (resolveFile) and ACTION_SEND (routeSend). The extension gate is the single
 * source of truth — no second extension list (EBookFormats).
 */
class IntakeRoutingTest {
    // ---- resolveFile (ACTION_VIEW gateway) --------------------------------

    @Test
    fun `resolveFile imports every supported extension`() {
        for (name in listOf(
            "book.epub",
            "book.txt",
            "book.md",
            "book.markdown",
            "book.azw3",
            "book.kf8",
            "book.mobi",
            "book.azw",
        )) {
            val verdict = IntakeRouting.resolveFile(name)
            assertInstanceOf(IntakeRouting.IntakeVerdict.Import::class.java, verdict, name)
            assertEquals(name, (verdict as IntakeRouting.IntakeVerdict.Import).displayName)
        }
    }

    @Test
    fun `resolveFile handles case-insensitively and spaces`() {
        assertInstanceOf(
            IntakeRouting.IntakeVerdict.Import::class.java,
            IntakeRouting.resolveFile("My Book.EPUB"),
        )
    }

    @Test
    fun `resolveFile gates kfx with DRM guidance`() {
        val verdict = IntakeRouting.resolveFile("book.kfx")
        val guidance =
            assertInstanceOf(
                IntakeRouting.IntakeVerdict.Guidance::class.java,
                verdict,
            )
        assertTrue(guidance.message.contains("DRM"))
    }

    @Test
    fun `resolveFile gates unknown formats with typed guidance - never silent`() {
        val verdict = IntakeRouting.resolveFile("book.pdf")
        val guidance =
            assertInstanceOf(
                IntakeRouting.IntakeVerdict.Guidance::class.java,
                verdict,
            )
        assertTrue(guidance.message.isNotBlank())
        assertEquals("book.pdf", guidance.displayName)
    }

    @Test
    fun `resolveFile treats blank or missing names as guidance`() {
        assertInstanceOf(
            IntakeRouting.IntakeVerdict.Guidance::class.java,
            IntakeRouting.resolveFile(""),
        )
        assertInstanceOf(
            IntakeRouting.IntakeVerdict.Guidance::class.java,
            IntakeRouting.resolveFile(null),
        )
    }

    // ---- routeSend (ACTION_SEND triage) -----------------------------------

    @Test
    fun `routeSend resolves when there is no stream - even for ebook mime`() {
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = false, mimeType = "application/epub+zip", displayName = "b.epub"),
        )
    }

    @Test
    fun `routeSend imports a stream with a supported extension regardless of mime`() {
        assertEquals(
            IntakeRouting.SendRoute.Import("b.epub"),
            IntakeRouting.routeSend(hasStream = true, mimeType = "application/octet-stream", displayName = "b.epub"),
        )
        assertEquals(
            IntakeRouting.SendRoute.Import("b.txt"),
            IntakeRouting.routeSend(hasStream = true, mimeType = null, displayName = "b.txt"),
        )
    }

    @Test
    fun `routeSend gates kfx streams with DRM guidance`() {
        val route = IntakeRouting.routeSend(hasStream = true, mimeType = "application/octet-stream", displayName = "b.kfx")
        val guidance = assertInstanceOf(IntakeRouting.SendRoute.Guidance::class.java, route)
        assertTrue(guidance.message.contains("DRM"))
    }

    @Test
    fun `routeSend forwards an ebook-mime stream with no usable name - the gateway gates it`() {
        // content://…/document/42 with an epub MIME: no extension, but it is
        // clearly a book file — the gateway still shows typed guidance.
        assertEquals(
            IntakeRouting.SendRoute.Import(""),
            IntakeRouting.routeSend(hasStream = true, mimeType = "application/epub+zip", displayName = null),
        )
    }

    @Test
    fun `routeSend resolves a text share (no extension, text mime)`() {
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = true, mimeType = "text/plain", displayName = null),
        )
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = true, mimeType = "text/plain", displayName = ""),
        )
    }

    @Test
    fun `routeSend resolves unknown documents and images`() {
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = true, mimeType = "application/pdf", displayName = "doc.pdf"),
        )
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = true, mimeType = "image/png", displayName = "shot.png"),
        )
    }

    @Test
    fun `routeSend resolves octet-stream with no usable name - it is not an advertised book mime`() {
        assertEquals(
            IntakeRouting.SendRoute.Resolve,
            IntakeRouting.routeSend(hasStream = true, mimeType = "application/octet-stream", displayName = null),
        )
    }
}
