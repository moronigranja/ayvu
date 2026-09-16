package io.github.moronigranja.ayvu.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** S-debug: container.xml full-path extraction must be parser-independent. */
class ContainerFullPathTest {
    @Test
    fun `double-quoted full-path`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        assertEquals("OEBPS/content.opf", OpfBookReader.extractFullPath(xml))
    }

    @Test
    fun `single-quoted attributes parse`() {
        // Gutenberg-style declarations: legal XML, hostile to naive parsers.
        val xml =
            "<?xml version='1.0' encoding='utf-8'?>\n" +
                "<container><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>"
        assertEquals("content.opf", OpfBookReader.extractFullPath(xml))
    }

    @Test
    fun `utf-8 BOM before the declaration is tolerated`() {
        val xml =
            "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?><container>..." +
                "<rootfile full-path=\"OPS/package.opf\"/></container>"
        assertEquals("OPS/package.opf", OpfBookReader.extractFullPath(xml))
    }

    @Test
    fun `doctype before the rootfile does not matter`() {
        val xml = """<!DOCTYPE container PUBLIC "-//W3C//DTD OEB 1.0 Container//EN" "container.dtd">
<container><rootfiles><rootfile full-path="   ebook/book.opf   "/></rootfiles></container>"""
        // Whitespace inside the quotes is part of the value; the caller
        // normalizes the path — extraction stays faithful.
        assertEquals("   ebook/book.opf   ", OpfBookReader.extractFullPath(xml))
    }

    @Test
    fun `no rootfile yields null`() {
        assertNull(OpfBookReader.extractFullPath("<container><rootfiles/></container>"))
        assertNull(OpfBookReader.extractFullPath("not xml at all"))
    }

    @Test
    fun `findOpfPath surfaces a typed error for a missing container`() {
        val thrown =
            org.junit.jupiter.api.assertThrows<EBookParseException> {
                OpfBookReader.findOpfPath(emptyMap())
            }
        assertEquals("META-INF/container.xml is missing", thrown.message)
    }
}
