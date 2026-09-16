package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.ebook.ZipEntries.lookup
import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Shared "OPF package → [Book]" pipeline used by the EPUB and KF8 parsers: container
 * discovery (EPUB), OPF metadata/spine, NCX + nav chapter titles, and lenient XHTML
 * text extraction into paragraph passages.
 *
 * Character handling and structure are shared intentionally — one convention for both
 * formats, so fixes apply everywhere.
 */
internal object OpfBookReader {
    /** EPUB: locate content.opf through META-INF/container.xml. */
    fun findOpfPath(entries: Map<String, ByteArray>): String {
        val containerBytes =
            entries.lookup("META-INF/container.xml")
                ?: throw EBookParseException("META-INF/container.xml is missing")
        // The container is a fixed, tiny schema — extract full-path without an
        // XML/DOM parse. Host Xerces and Android's Expat-backed DOM disagree on
        // doctype/single-quote/BOM handling, and a broken container.xml must
        // not depend on which parser the platform supplies (S-debug, 2026-08-26).
        val fullPath =
            extractFullPath(String(containerBytes, Charsets.UTF_8))
                ?: throw EBookParseException("container.xml has no rootfile full-path")
        return ZipEntries.normalizePath(fullPath)
    }

    private val FULL_PATH_RE = Regex("""full-path\s*=\s*["']([^"']+)["']""")

    /** The rootfile element's full-path attribute, single- or double-quoted. */
    internal fun extractFullPath(containerXml: String): String? = FULL_PATH_RE.find(containerXml)?.groupValues?.get(1)

    fun parseBook(
        id: String,
        entries: Map<String, ByteArray>,
        opfPath: String,
        fallbackTitle: String,
    ): Book {
        val opfBytes =
            entries.lookup(opfPath)
                ?: throw EBookParseException("OPF not found in container: $opfPath")
        val opf = parseOpf(opfBytes, opfPath)
        if (opf.spineHrefs.isEmpty()) throw EBookParseException("spine is empty")

        val toc = loadTocTitles(entries, opf)
        val chapters = mutableListOf<Chapter>()
        var chapterIndex = 0
        for (href in opf.spineHrefs) {
            val htmlBytes = entries.lookup(href) ?: continue // broken spine: skip, never crash
            val html = String(htmlBytes, Charsets.UTF_8)
            val passages = extractParagraphs(html).map(::TextPassage)
            if (passages.isEmpty()) continue // decorative/blank chapter
            chapters += Chapter(chapterIndex++, toc[href] ?: firstHeading(html), passages)
        }
        if (chapters.isEmpty()) throw EBookParseException("no readable chapters in spine")

        return Book(
            id = id,
            title = opf.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            authors = opf.authors,
            chapters = chapters,
        )
    }

    /**
     * Standard EPUB cover artwork: EPUB2 `<meta name="cover" content="…"/>`
     * (resolving the manifest item), else EPUB3 `properties="cover-image"`.
     * Null when absent or not an image item — never a parse failure.
     */
    internal fun coverImage(
        entries: Map<String, ByteArray>,
        opfPath: String,
    ): ByteArray? {
        val opfBytes = entries.lookup(opfPath) ?: return null
        val doc = runCatching { parseXml(opfBytes, "content.opf") }.getOrNull() ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")
        var declaredId: String? = null
        for (meta in doc.elementsByLocalName("meta")) {
            if (meta.getAttribute("name").equals("cover", ignoreCase = true)) {
                declaredId = meta.getAttribute("content").ifBlank { null }
            }
        }
        for (item in doc.elementsByLocalName("item")) {
            val id = item.getAttribute("id")
            val isCover = declaredId?.let { it == id } == true || item.attributeTokens("properties").contains("cover-image")
            if (!isCover || !item.getAttribute("media-type").startsWith("image/")) continue
            val href = item.getAttribute("href").ifBlank { continue }
            return entries.lookup(resolvePath(opfDir, href))
        }
        return null
    }

    // ------------------------------------------------------------------
    // OPF
    // ------------------------------------------------------------------

    private data class Opf(
        val title: String?,
        val authors: List<String>,
        val spineHrefs: List<String>,
        val ncxHref: String?,
        val navHref: String?,
    )

    private fun parseOpf(
        bytes: ByteArray,
        opfPath: String,
    ): Opf {
        val doc = parseXml(bytes, "content.opf")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = linkedMapOf<String, String>()
        var ncxHref: String? = null
        var navHref: String? = null
        for (item in doc.elementsByLocalName("item")) {
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                val resolved = resolvePath(opfDir, href)
                manifest[id] = resolved
                if (item.getAttribute("media-type") == "application/x-dtbncx+xml") ncxHref = resolved
                if (item.attributeTokens("properties").contains("nav")) navHref = resolved
            }
        }

        val spineHrefs = doc.elementsByLocalName("itemref").mapNotNull { manifest[it.getAttribute("idref")] }
        val title = doc.elementsByLocalName("title").firstNotNullOfOrNull { it.textContent.trim().ifEmpty { null } }
        val authors = doc.elementsByLocalName("creator").mapNotNull { it.textContent.trim().ifEmpty { null } }
        return Opf(title, authors, spineHrefs, ncxHref, navHref)
    }

    // ------------------------------------------------------------------
    // TOC (EPUB2 NCX + EPUB3 nav), chapter titles
    // ------------------------------------------------------------------

    private fun loadTocTitles(
        entries: Map<String, ByteArray>,
        opf: Opf,
    ): Map<String, String> {
        val titles = linkedMapOf<String, String>()

        opf.ncxHref?.let { href ->
            entries.lookup(href)?.let { bytes ->
                runCatching { parseXml(bytes, "toc.ncx") }.getOrNull()?.let { doc ->
                    val dir = href.substringBeforeLast('/', "")
                    for (point in doc.elementsByLocalName("navPoint")) {
                        // <navPoint><navLabel><text>…</text></navLabel><content src="…"/></navPoint>
                        val src =
                            point
                                .descendantsByLocalName("content")
                                .firstOrNull()
                                ?.getAttribute("src")
                                ?.substringBefore('#')
                                ?.trim()
                        val label =
                            point
                                .descendantsByLocalName("text")
                                .firstOrNull()
                                ?.textContent
                                ?.trim()
                        if (!src.isNullOrEmpty() && !label.isNullOrEmpty()) {
                            titles[resolvePath(dir, src)] = label
                        }
                    }
                }
            }
        }

        opf.navHref?.let { href ->
            entries.lookup(href)?.let { bytes ->
                runCatching { parseXml(bytes, "nav") }.getOrNull()?.let { doc ->
                    val dir = href.substringBeforeLast('/', "")
                    val tocNav =
                        doc.elementsByLocalName("nav").firstOrNull { it.attributeTokens("type").contains("toc") }
                            ?: doc.elementsByLocalName("nav").firstOrNull()
                    if (tocNav != null) {
                        for (link in tocNav.descendantsByLocalName("a")) {
                            val target = link.getAttribute("href").substringBefore('#').trim()
                            val label = link.textContent.trim()
                            if (target.isNotEmpty() && label.isNotEmpty()) {
                                titles[resolvePath(dir, target)] = label
                            }
                        }
                    }
                }
            }
        }

        return titles
    }

    private fun firstHeading(html: String): String? {
        val match = HEADING_RE.find(html) ?: return null
        val text = decodeEntities(TAG_RE.replace(match.groupValues[1], "")).trim()
        return text.ifEmpty { null }
    }

    // ------------------------------------------------------------------
    // Chapter text extraction (lenient — malformed XHTML is common)
    // ------------------------------------------------------------------

    fun extractParagraphs(html: String): List<String> {
        var s = html
        s = SKIP_BLOCKS.replace(s, "") // head/script/style content must never leak into text
        s = CONTROL_CHARS.replace(s, " ") // MOBI control bytes and friends
        // Block boundaries get a sentinel first, so pretty-printed XHTML (a <p> spanning
        // lines) stays ONE passage: raw whitespace collapses to single spaces afterwards.
        s = BLOCK_BOUNDARY.replace(s, BOUNDARY)
        s = TAG_RE.replace(s, "")
        s = WHITESPACE.replace(s, " ")
        s = decodeEntities(s) // after tag strip: attribute entities are gone with their tags
        s = s.replace(BOUNDARY, "\n")
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // XML (XXE-hardened) + entities
    // ------------------------------------------------------------------

    fun parseXmlPublic(
        bytes: ByteArray,
        what: String,
    ): Document = parseXml(bytes, what)

    private fun parseXml(
        bytes: ByteArray,
        what: String,
    ): Document {
        // Real-world pre-processing before the DOM parse (S-debug, 2026-08-26):
        // - a DOCTYPE is STRIPPED: host Xerces and Android's Expat disagree on
        //   doctype/feature handling, and removal also guarantees external DTDs
        //   are never fetched (the S22 is offline; a fetch killed the import);
        // - decode only the entities the XML parser cannot handle; XML-valid
        //   &amp;/&lt;/&#160; … stay for the parser — decoding them first
        //   corrupts the document ("Love &amp; Romance" became a bare '&'
        //   and the SAX parse died, 2026-08-27).
        val prepared = normalizeDeclaration(stripDoctype(String(bytes, Charsets.UTF_8)))
        val decoded = decodeNonXmlEntities(prepared)
        try {
            return newDocumentBuilder().parse(ByteArrayInputStream(decoded.toByteArray(Charsets.UTF_8)))
        } catch (e: SAXException) {
            throw EBookParseException("malformed $what", e)
        } catch (e: Exception) {
            throw EBookParseException("could not read $what", e)
        }
    }

    private const val SINGLE_QUOTE = '\''
    private const val DOUBLE_QUOTE = '"'
    private val XML_DECL_RE = Regex("""<\?xml[^>]*\?>""", RegexOption.IGNORE_CASE)

    private fun normalizeDeclaration(s: String): String = XML_DECL_RE.replace(s) { it.value.replace(SINGLE_QUOTE, DOUBLE_QUOTE) }

    /**
     * Removes a preamble `<!DOCTYPE …>` (internal subset included), case-
     * insensitive. Doctype is only legal before the root element, so anything
     * shaped like it AFTER the root opens (attribute values, text) is
     * untouched — the scan stops at the first element open.
     */
    internal fun stripDoctype(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] != '<') {
                sb.append(s[i])
                i++
                continue
            }
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                next.isLetter() -> { // the root element: preamble is over
                    sb.append(s, i, s.length)
                    return sb.toString()
                }
                next == '?' -> { // XML declaration: keep as-is (normalized later)
                    sb.append(s[i])
                    i++
                }
                next == '!' -> {
                    if (s.regionMatches(i + 2, "DOCTYPE", 0, 7, ignoreCase = true)) {
                        val end = doctypeEnd(s, i)
                        if (end < 0) return s
                        i = end
                        // dropped: the doctype is gone, nothing appended
                    } else {
                        val end = s.indexOf('>', i)
                        if (end < 0) return s
                        sb.append(s, i, end + 1)
                        i = end + 1
                    }
                }
                else -> { // stray '<' before the root: keep
                    sb.append(s[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Index just past the doctype's closing `>` (bracket-aware), or -1. */
    private fun doctypeEnd(
        s: String,
        start: Int,
    ): Int {
        var depth = 0
        var i = start + 2
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' ->
                    if (depth > 0) {
                        depth--
                    } else {
                        i += 2
                        return i
                    } // ']>'
                '>' -> if (depth == 0) return i + 1
            }
            i++
        }
        return -1
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: ParserConfigurationException) {
            // Feature unsupported: parse anyway; none of the external-entity
            // features below can then be active either (single factory instance).
        }
        // Android's Expat-backed JAXP factory THROWS UnsupportedOperationException
        // on these two instead of accepting them (S-debug, 2026-08-26: the
        // S22 import died with "does not support specification Unknown v0.0").
        // Tolerate either flavor; skipping them is safe because doctypes are
        // stripped before parse, so no external/entity expansion is reachable.
        factoryConfigTolerant { factory.isXIncludeAware = false }
        factoryConfigTolerant { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder()
    }

    private inline fun <T> factoryConfigTolerant(block: () -> T) {
        try {
            block()
        } catch (_: UnsupportedOperationException) {
            // Android: the property is unsupported — the default is what we want anyway.
        } catch (_: ParserConfigurationException) {
            // Property rejected — same outcome.
        }
    }

    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to "\u00A0",
            "copy" to "\u00A9",
            "reg" to "\u00AE",
            "trade" to "\u2122",
            "mdash" to "\u2014",
            "ndash" to "\u2013",
            "hellip" to "\u2026",
            "lsquo" to "\u2018",
            "rsquo" to "\u2019",
            "ldquo" to "\u201C",
            "rdquo" to "\u201D",
            "bull" to "\u2022",
            "middot" to "\u00B7",
            "deg" to "\u00B0",
            "agrave" to "\u00E0",
            "eacute" to "\u00E9",
            "egrave" to "\u00E8",
            "iacute" to "\u00ED",
            "oacute" to "\u00F3",
            "uacute" to "\u00FA",
            "auml" to "\u00E4",
            "ouml" to "\u00F6",
            "uuml" to "\u00FC",
            "ccedil" to "\u00E7",
            "ntilde" to "\u00F1",
            "szlig" to "\u00DF",
            "laquo" to "\u00AB",
            "raquo" to "\u00BB",
            "times" to "\u00D7",
            "divide" to "\u00F7",
            "para" to "\u00B6",
            "sect" to "\u00A7",
            "dagger" to "\u2020",
            "Dagger" to "\u2021",
            "permil" to "\u2030",
            "prime" to "\u2032",
            "Prime" to "\u2033",
        )

    private val ENTITY_RE = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    /** Shared by OPF/NCX XHTML parsing and MobiParser's MOBI7 NCX index labels. */
    internal fun decodeEntities(s: String): String =
        ENTITY_RE.replace(s) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") -> body.substring(2).toIntOrNull(16)?.let(::codepointString)
                body.startsWith("#") -> body.substring(1).toIntOrNull(10)?.let(::codepointString)
                else -> NAMED_ENTITIES[body]
            } ?: match.value
        }

    /** The five entities XML itself defines; the parser resolves these. */
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * Pre-parse entity pass for XML documents — decodes only what the XML parser
     * cannot: HTML-named extras (&nbsp; &mdash; …) become their characters, and a
     * bare '&' is escaped to &amp; so the document stays well-formed. XML-valid
     * entities (&amp; &lt; &quot; &apos; &gt;, numeric refs) are left for the
     * parser, which resolves them into textContent — decoding them first corrupts
     * the markup (a valid &amp; becomes an illegal bare '&', S-device bug 2026-08-27).
     */
    internal fun decodeNonXmlEntities(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] != '&') {
                sb.append(s[i])
                i++
                continue
            }
            val match = ENTITY_RE.find(s, i)
            if (match != null && match.range.first == i) {
                val body = match.groupValues[1]
                when {
                    body.startsWith("#") || body in XML_ENTITIES -> sb.append(match.value)
                    else -> sb.append(NAMED_ENTITIES[body] ?: match.value)
                }
                i = match.range.last + 1
            } else {
                sb.append("&amp;") // bare '&': escape so the document stays well-formed
                i++
            }
        }
        return sb.toString()
    }

    private fun codepointString(codepoint: Int): String? = if (codepoint in 0x1..0x10FFFF) String(Character.toChars(codepoint)) else null

    // ------------------------------------------------------------------
    // DOM helpers + path utils + regexes
    // ------------------------------------------------------------------

    private fun Document.elementsByLocalName(name: String): List<Element> {
        val out = mutableListOf<Element>()

        fun walk(root: Element) {
            for (i in 0 until root.childNodes.length) {
                val node = root.childNodes.item(i)
                if (node is Element) {
                    if (node.localName == name || node.nodeName == name) out += node
                    walk(node)
                }
            }
        }
        documentElement?.let(::walk)
        return out
    }

    private fun Element.descendantsByLocalName(name: String): List<Element> {
        val out = mutableListOf<Element>()

        fun walk(node: org.w3c.dom.Node) {
            for (i in 0 until node.childNodes.length) {
                val child = node.childNodes.item(i)
                if (child is Element) {
                    if (child.localName == name || child.nodeName == name) out += child
                    walk(child)
                }
            }
        }
        walk(this)
        return out
    }

    /** Token list of the given attribute (prefix-agnostic: "properties" matches "properties",
     *  "type" matches "epub:type"). Namespace-aware DOM safe. */
    private fun Element.attributeTokens(attributeLocalName: String): Set<String> {
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute?.nodeName?.substringAfterLast(':') == attributeLocalName) {
                return attribute.nodeValue
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }
        return emptySet()
    }

    private fun resolvePath(
        baseDir: String,
        href: String,
    ): String = ZipEntries.normalizePath(if (href.startsWith('/')) href else "$baseDir/$href")

    /** Private-use char unlikely in real text; scrubbed by CONTROL_CHARS if the source had it. */
    private const val BOUNDARY = "\u0001"

    private val SKIP_BLOCKS = Regex("(?is)<(head|script|style)[\\s\\S]*?</\\1>")
    private val BLOCK_BOUNDARY =
        Regex(
            "(?i)<(p|div|li|blockquote|section|article|h[1-6]|tr)[^>]*>|" +
                "</(p|div|li|blockquote|section|article|h[1-6]|tr|br)>|<br\\s*/?>",
        )
    private val TAG_RE = Regex("<[^>]+>")
    private val CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")
    private val HEADING_RE = Regex("(?i)<h[1-6][^>]*>([\\s\\S]*?)</h[1-6]>")
    private val WHITESPACE = Regex("\\s+")
}
