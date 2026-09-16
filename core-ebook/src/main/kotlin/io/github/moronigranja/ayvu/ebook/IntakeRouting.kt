package io.github.moronigranja.ayvu.ebook

/**
 * F4 external-file intake routing (roadmap F4): pure decisions for the two
 * intent entry points, shared by feature-library (ACTION_VIEW gateway +
 * forwarded book shares) and feature-share (ACTION_SEND triage). The
 * extension gate is the single source of truth — no second extension list
 * (F2/F3 pattern, [EBookFormats]). MIME mismatches are expected (file
 * managers type books inconsistently), so a supported extension always wins.
 *
 * - ACTION_VIEW: any file that reaches the gateway with a supported extension
 *   imports; kfx/DRM/unsupported get typed guidance, never a silent no-op.
 * - ACTION_SEND: a share is triaged — text/image shares keep the existing
 *   resolve path; book files (supported extension, or an ebook MIME with an
 *   unusable name) forward to the import gateway; kfx/DRM get guidance.
 */
object IntakeRouting {
    /** Custom action used by feature-share to forward a book-file share to the
     * import gateway (package-qualified explicit intent — no feature-to-feature
     * compile edge, A6; no extra share-sheet entry). */
    const val ACTION_IMPORT_BOOK = "io.github.moronigranja.ayvu.action.IMPORT_BOOK"

    /** MIMEs the ACTION_VIEW gateway advertises. octet-stream included: file
     * managers type books as octet-stream (the extension gate is the backstop). */
    val viewMimes =
        setOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/octet-stream",
        )

    /** The MIMEs a share stream with NO usable extension can still be trusted as
     * a book file (container types). text MIMEs are excluded on purpose: a text
     * share with an unusable name must keep resolving, not forward to import. */
    val sendMimes =
        setOf(
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
        )

    private const val MSG_UNSUPPORTED = "format not supported"
    private const val MSG_KFX = ".kfx and DRM-protected books are not supported"

    sealed interface IntakeVerdict {
        /** Import this file through the shared batch importer. */
        data class Import(
            val displayName: String,
        ) : IntakeVerdict

        /** Show typed guidance — never a silent no-op. */
        data class Guidance(
            val displayName: String,
            val message: String,
        ) : IntakeVerdict
    }

    /** The file gate (shared by both entry points): extension decides. */
    fun resolveFile(displayName: String?): IntakeVerdict {
        val name = displayName?.ifBlank { null } ?: return IntakeVerdict.Guidance("file", MSG_UNSUPPORTED)
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            EBookFormats.parserFor(name) != null -> IntakeVerdict.Import(name)
            ext == "kfx" -> IntakeVerdict.Guidance(name, MSG_KFX)
            else -> IntakeVerdict.Guidance(name, MSG_UNSUPPORTED)
        }
    }

    sealed interface SendRoute {
        /** Keep the existing text/image resolve path (S2 — OCR/index match). */
        data object Resolve : SendRoute

        /** A supported book file (or ebook-MIME stream): forward to the import gateway. */
        data class Import(
            val displayName: String,
        ) : SendRoute

        /** A book file we cannot read (kfx/DRM/unsupported): show typed guidance. */
        data class Guidance(
            val displayName: String,
            val message: String,
        ) : SendRoute
    }

    /**
     * The ACTION_SEND triage: a stream whose display name has a supported
     * extension imports; a stream with an ebook MIME but no supported name
     * still forwards (the gateway shows guidance — never silent); anything
     * else (text share, image, unknown document) resolves as today.
     */
    fun routeSend(
        hasStream: Boolean,
        mimeType: String?,
        displayName: String?,
    ): SendRoute {
        if (!hasStream) return SendRoute.Resolve
        val name = displayName ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            EBookFormats.parserFor(name) != null -> SendRoute.Import(name)
            ext == "kfx" -> SendRoute.Guidance(name, MSG_KFX)
            mimeType in sendMimes -> SendRoute.Import(name) // ebook MIME, no usable name → gateway still gates
            else -> SendRoute.Resolve
        }
    }
}
