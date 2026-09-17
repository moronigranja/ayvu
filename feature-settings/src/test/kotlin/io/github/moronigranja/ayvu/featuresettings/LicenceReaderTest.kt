package io.github.moronigranja.ayvu.featuresettings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Release 0.1.1 (audit B): the About → Licences read path. A host JVM cannot
 * open `AssetManager`, which is why [LicenceReader] reads through the
 * [LicenceAssetSource] edge; this test drives the production reader over the
 * repo-root files `:app`'s `copyLicenceAssets` copies into the APK's assets —
 * the same names, the same bytes — and pins the failure shape: a missing or
 * empty asset is a typed [LicenceText.Failed], never a blank page.
 */
class LicenceReaderTest {
    /** Walks up from the working directory (the module dir under Gradle) to the
     * repo root, the same helper the piper smoke test uses. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: error("repo root not found above ${System.getProperty("user.dir")}")
    }

    /** The packaging task's source files, served like the APK's assets are. */
    private fun repoBackedReader(): LicenceReader =
        LicenceReader { assetName ->
            val file = File(repoRoot(), assetName)
            if (!file.isFile) throw IOException("asset $assetName missing at ${file.path}")
            file.inputStream()
        }

    private fun loaded(licence: LicenceText): LicenceText.Loaded =
        licence as? LicenceText.Loaded ?: error("expected a loaded document, got $licence")

    @Test
    fun `the bundled licence and notices read as non-empty text`() {
        val documents = repoBackedReader().readAll()

        assertEquals(
            listOf("LICENSE", "NOTICE.md"),
            documents.map { it.document.assetName },
            "the viewer order is the app's own licence first, then the notices",
        )

        val license = loaded(documents[0]).text
        val notice = loaded(documents[1]).text
        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"), "LICENSE is not the GPL text")
        assertTrue(license.contains("Version 3, 29 June 2007"), "LICENSE is not GPL-3.0")
        assertTrue(notice.contains("Third-party notices"), "NOTICE.md is not the notices document")
        // The notices carry non-ASCII attribution marks: the read must decode UTF-8.
        assertTrue(notice.contains("©"), "NOTICE.md lost its copyright sign in the decode")
    }

    @Test
    fun `an absent asset is a typed failure naming the document`() {
        val results = LicenceReader { throw IOException("asset missing") }.readAll()

        assertEquals(listOf("LICENSE", "NOTICE.md"), results.map { it.document.assetName })
        results.forEach { result ->
            val failed = result as? LicenceText.Failed ?: error("expected a typed failure, got $result")
            assertTrue(failed.reason.isNotBlank(), "a failure must say why")
        }
    }

    @Test
    fun `an empty asset is a failure, not a blank page`() {
        val results = LicenceReader { ByteArrayInputStream(ByteArray(0)) }.readAll()

        results.forEach { result ->
            val failed = result as? LicenceText.Failed ?: error("expected a typed failure, got $result")
            assertFalse(failed.reason.isBlank())
        }
    }

    @Test
    fun `the reader returns the asset bytes verbatim`() {
        val text = "line one\nline two \u2014 \u00a9 2026\n"
        val reader = LicenceReader { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }

        val loaded = loaded(reader.read(LicenceDocuments.notice))

        assertEquals(text, loaded.text)
    }
}
