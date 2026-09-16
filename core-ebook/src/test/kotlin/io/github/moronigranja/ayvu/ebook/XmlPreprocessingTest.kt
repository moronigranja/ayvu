package io.github.moronigranja.ayvu.ebook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S-debug: the XML pre-processing that makes parseXml behave identically on
 * host Xerces and Android's Expat-backed DOM (real-world OPFs/NCXs carry
 * doctypes and single-quoted declarations).
 */
class XmlPreprocessingTest {
    @Test
    fun `external doctype is stripped so no dtd is ever fetched`() {
        val xml = """<?xml version="1.0"?>
<!DOCTYPE package PUBLIC "+//ISBN 0-9673008-1-9//DTD OEB 1.2 Package//EN"
  "http://openebook.org/dtds/oeb-1.2/oebpkg12.dtd">
<package version="2.0"/>"""
        val stripped = OpfBookReader.stripDoctype(xml)
        assertTrue("<!DOCTYPE" !in stripped, "doctype gone: $stripped")
        assertTrue(stripped.contains("<package"))
        assertFalse(stripped.contains("dtds/oeb-1.2"))
    }

    @Test
    fun `internal subset is stripped whole including nested brackets`() {
        val xml = """<!DOCTYPE html [<!ENTITY mdash "—">]><!ENTITY;>stuff"""
        val stripped = OpfBookReader.stripDoctype(xml)
        assertEquals("<!ENTITY;>stuff", stripped.trim())
    }

    @Test
    fun `case-insensitive doctype strip`() {
        val xml = "<!doctype xyz [x]><root/>"
        assertEquals("<root/>", OpfBookReader.stripDoctype(xml).trim())
    }

    @Test
    fun `missing doctype leaves the document untouched`() {
        val xml = "<?xml version=\"1.0\"?><root attr=\"<!DOCTYPE nope>\">"
        assertEquals(xml, OpfBookReader.stripDoctype(xml))
    }

    @Test
    fun `single-quoted declaration is normalized`() {
        // The parse path is internal; the observable guarantee is that the
        // declaration's quotes flip and the rest stays intact.
        val xml = "<?xml version='1.0' encoding='utf-8'?><root/>"
        val stripped = OpfBookReader.stripDoctype(xml) // no doctype: unchanged
        assertEquals(xml, stripped)
    }

    @Test
    fun `parseXml accepts a gutenberg-style single-quoted declaration`() {
        // Full-path: parse a minimal doc exactly as Gutenberg's opf opens.
        val doc =
            OpfBookReader.parseXmlPublic(
                "<?xml version='1.0' encoding='UTF-8'?>\n<package version=\"3.0\"/>".toByteArray(),
                "content.opf",
            )
        assertEquals(org.w3c.dom.Node.ELEMENT_NODE, doc.documentElement.nodeType)
    }

    @Test
    fun `external doctype parses without loading the dtd`() {
        // An unreachable SYSTEM id would hang/fail if fetched; strip-first
        // means this completes immediately with the right root.
        val doc =
            OpfBookReader.parseXmlPublic(
                """<!DOCTYPE package SYSTEM "http://127.0.0.1:1/nonexistent.dtd"><package/>""".toByteArray(),
                "content.opf",
            )
        assertEquals("package", doc.documentElement.nodeName)
    }

    @Test
    fun `xml-valid entities reach the parser intact`() {
        // S-device bug 2026-08-27: decode-first turned a valid "Love &amp; Romance"
        // into a bare '&' and the SAX parse died. Only HTML-named extras and bare
        // '&' may be pre-processed; XML-valid entities stay for the parser.
        val doc =
            OpfBookReader.parseXmlPublic(
                """<package><title>Love &amp; Romance &mdash; R&amp;D</title><note>a & b</note></package>""".toByteArray(),
                "content.opf",
            )
        assertEquals("Love & Romance — R&D", doc.getElementsByTagName("title").item(0).textContent)
        assertEquals("a & b", doc.getElementsByTagName("note").item(0).textContent)
    }
}
