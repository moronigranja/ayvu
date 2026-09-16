package io.github.moronigranja.ayvu.ebook

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds in-memory EPUB containers (and their parts) for parser tests.
 */
object EpubFixture {
    const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Zip with binary entries (cover images); strings are UTF-8 encoded. */
    fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun opf(
        title: String? = null,
        authors: List<String> = emptyList(),
        spine: List<Pair<String, String>> = emptyList(), // id to href
        ncxHref: String? = null,
        navHref: String? = null,
        coverItem: Pair<String, String>? = null, // id to href — EPUB2 <meta name="cover"> + jpeg item
        epub3CoverHref: String? = null, // EPUB3: item with properties="cover-image"
    ): String {
        val manifest =
            buildString {
                for ((id, href) in spine) {
                    append("""<item id="$id" href="$href" media-type="application/xhtml+xml"/>""")
                }
                ncxHref?.let {
                    append("""<item id="ncx" href="$it" media-type="application/x-dtbncx+xml"/>""")
                }
                navHref?.let {
                    append("""<item id="nav" href="$it" media-type="application/xhtml+xml" properties="nav"/>""")
                }
                coverItem?.let { (id, href) ->
                    append("""<item id="$id" href="$href" media-type="image/jpeg"/>""")
                }
                epub3CoverHref?.let {
                    append("""<item id="cover-image" href="$it" media-type="image/jpeg" properties="cover-image"/>""")
                }
            }
        val coverMeta = coverItem?.let { (id, _) -> """<meta name="cover" content="$id"/>""" } ?: ""
        val titleXml = title?.let { "<dc:title>$it</dc:title>" } ?: ""
        val spineXml = spine.joinToString("") { (id, _) -> """<itemref idref="$id"/>""" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">fixture</dc:identifier>
    $coverMeta
    $titleXml
    ${authors.joinToString("\n") { "<dc:creator>$it</dc:creator>" }}
  </metadata>
  <manifest>$manifest</manifest>
  <spine toc="ncx">$spineXml</spine>
</package>"""
    }

    fun ncx(entries: List<Pair<String, String>>): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
${entries.mapIndexed { i, (src, label) ->
            """    <navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>$label</text></navLabel><content src="$src"/></navPoint>"""
        }.joinToString("\n")}
  </navMap>
</ncx>"""

    fun navDoc(entries: List<Pair<String, String>>): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>Table of Contents</title></head>
  <body>
    <nav epub:type="toc">
${entries.map { (href, label) -> """      <a href="$href">$label</a>""" }.joinToString("\n")}
    </nav>
  </body>
</html>"""

    fun chapterHtml(
        heading: String? = null,
        paragraphs: List<String>,
    ): String =
        buildString {
            append(
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture Book</title><style>p { color: red }</style></head><body>""",
            )
            heading?.let { append("<h2>$it</h2>") }
            for (p in paragraphs) append("<p>$p</p>")
            append("</body></html>")
        }
}
