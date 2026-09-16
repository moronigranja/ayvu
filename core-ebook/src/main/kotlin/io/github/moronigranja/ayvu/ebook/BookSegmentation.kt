package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage

/**
 * Passage segmentation stage — the grain contract for matching and resume.
 *
 * The **passage is the unit of share-and-identify matching and of playback resume**,
 * so its grain directly sets match precision (a snippet lands on the passage that
 * contains it; resuming starts at that passage's beginning). Rules:
 *
 * - **Paragraph grain**: each block from the parser is its own passage (already the
 *   extraction contract). Passages longer than [DEFAULT_MAX_PASSAGE_WORDS] are split
 *   at sentence boundaries into smaller chunks, so a long paragraph still yields a
 *   precise resume target. Text without usable sentence boundaries stays whole.
 * - **Front/back matter**: a contiguous leading run of front-matter chapters (Title
 *   Page, Copyright, Table of Contents, Dedication, …) and a contiguous trailing run
 *   of back matter (Index, About the Author, …) are dropped — contiguous, so a run
 *   of any length is removed (books list the TOC at any spine depth), while a novel
 *   whose *middle* chapter is literally called "Index" is untouched. If stripping
 *   would remove the whole book, the book is returned unchanged.
 * - **Passage-level matter (I1)**: for the kept chapters, a contiguous leading run of
 *   front-matter passages (cover, title, copyright, contents, …) and a contiguous
 *   trailing run of back-matter passages (about the author, index, …) are dropped at
 *   passage grain — so a single-chapter book no longer reads its furniture aloud and
 *   resume starts at the first story passage.
 * - **Empty passages**: any passage whose text contains no letters (e.g. a
 *   scene-break marker "* * *" or "···") is dropped — otherwise synthesis reads it
 *   aloud as noise.
 * - **Dense renumbering**: kept chapters are renumbered contiguously from 0, so the
 *   reader/layout never holds empty chapter slots and `chapterIndex` is always a
 *   valid list position (the reader's chapter menu and resume rows use it).
 *
 * **Contract with TextIndex** (see docs/features/share-and-identify.md): the import
 * pipeline MUST run [segment] on a parsed book before `TextIndex.add(...)`. Passages
 * must be stable across re-parses of the same file; renumbering is stable for a given
 * file (same content → same kept set → same indexes).
 */
object BookSegmentation {
    const val DEFAULT_MAX_PASSAGE_WORDS = 100

    private val FRONT_MATTER =
        setOf(
            "title page",
            "copyright",
            "colophon",
            "table of contents",
            "contents",
            "dedication",
            "epigraph",
            "cover",
            "half title",
        )
    private val BACK_MATTER =
        setOf(
            "about the author",
            "also by",
            "also available",
            "books by",
            "advertisement",
            "advertisements",
            "index",
        )

    fun segment(
        book: Book,
        maxPassageWords: Int = DEFAULT_MAX_PASSAGE_WORDS,
    ): Book {
        if (book.chapters.isEmpty()) return book
        // By containment, not equality: real spines name furniture liberally
        // ("Copyright Notice", "Table of Contents", "Books by the Author").
        val isFrontMatter = { chapter: Chapter ->
            val key =
                chapter.title
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
            key.isNotEmpty() && FRONT_MATTER.any { key.contains(it) }
        }
        val isBackMatter = { chapter: Chapter ->
            val key =
                chapter.title
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
            key.isNotEmpty() && BACK_MATTER.any { key.contains(it) }
        }
        // Strip a contiguous leading front-matter run and trailing back-matter run.
        var start = 0
        while (start < book.chapters.size && isFrontMatter(book.chapters[start])) start++
        var end = book.chapters.size
        while (end > start && isBackMatter(book.chapters[end - 1])) end--
        // Passage-level matter strip on the kept chapters (I1), then renumber +
        // split long passages + drop letter-free passages.
        val kept =
            stripPassageMatter(book.chapters.subList(start, end))
                .mapIndexed { index, chapter ->
                    chapter.copy(
                        index = index,
                        passages =
                            splitLongPassages(chapter.passages, maxPassageWords)
                                .filter { it.text.any { c -> c.isLetter() } },
                    )
                }
        // Safety net: never strip an entire book.
        if (kept.isEmpty()) return book
        val base = book.copy(chapters = kept)
        return splitChaptersByHeading(base)
    }

    /**
     * Passage-grain front/back matter strip (I1): drop a contiguous leading run of
     * front-matter passages on the first chapter and a contiguous trailing run of
     * back-matter passages on the last chapter. A middle chapter mentioning "Index"
     * or "Copyright" is untouched. If stripping would empty any chapter, that
     * chapter's original passages are restored (whole-book guard), preserving
     * deterministic re-parse stability.
     */
    private fun stripPassageMatter(chapters: List<Chapter>): List<Chapter> {
        if (chapters.isEmpty()) return chapters

        fun isMatterForPassage(
            p: TextPassage,
            keys: Set<String>,
        ): Boolean {
            val key = p.text.trim().lowercase()
            return key.isNotEmpty() && keys.any { key.contains(it) }
        }
        return chapters.mapIndexed { i, ch ->
            val frontKeys = FRONT_MATTER
            val backKeys = BACK_MATTER
            val passages =
                if (chapters.size == 1) {
                    // one chapter: strip a leading front run then a trailing back run on the SAME list
                    val afterFront = ch.passages.dropWhile { isMatterForPassage(it, frontKeys) }
                    afterFront.dropLastWhile { isMatterForPassage(it, backKeys) }
                } else if (i == 0) {
                    ch.passages.dropWhile { isMatterForPassage(it, frontKeys) } // leading run only on first
                } else if (i == chapters.lastIndex) {
                    ch.passages.dropLastWhile { isMatterForPassage(it, backKeys) } // trailing run only on last
                } else {
                    ch.passages
                }
            // Whole-book guard: never emit an empty chapter.
            if (passages.isEmpty()) ch else ch.copy(passages = passages)
        }
    }

    /**
     * Smart chapter detection (I2): when a book parses to exactly one chapter, look for
     * credible chapter headings and split them into their own chapters. Only a detached
     * single-chapter (monolithic) book is eligible. A book with no headings, a lone
     * heading, or mixed heading kinds stays as-is. Headings become chapter titles (TTS
     * skips the title field), the heading passages are removed from the bodies so they
     * are not read aloud twice, and resulting chapters are renumbered contiguously.
     */
    private fun splitChaptersByHeading(book: Book): Book {
        if (book.chapters.size != 1) return book
        val single = book.chapters.single()
        val headingIdx =
            single.passages.mapIndexedNotNull { i, p ->
                headingKind(p.text.trim())?.let { i to it }
            }
        // Minimal evidence: at least two headings for ANY split; and headings must be
        // a single uniform kind (mixing kinds → do not split).
        if (headingIdx.size < 2) return book
        if (headingIdx.map { it.second }.toSet().size != 1) return book

        val positions = headingIdx.map { it.first }
        val chapters = mutableListOf<Chapter>()
        // Leading prologue before the first heading (null title, kept only if non-empty).
        val lead = single.passages.subList(0, positions[0])
        if (lead.isNotEmpty()) chapters += Chapter(index = 0, title = null, passages = lead)
        positions.indices.forEach { k ->
            val start = positions[k]
            val end = if (k + 1 < positions.size) positions[k + 1] else single.passages.size
            chapters +=
                Chapter(
                    index = 0,
                    title = single.passages[start].text.trim(),
                    passages = single.passages.subList(start + 1, end),
                )
        }
        // A book of only headings must not divide into empty chapters.
        if (chapters.all { it.passages.isEmpty() }) return book
        val renumbered = chapters.mapIndexed { i, c -> c.copy(index = i) }
        return book.copy(chapters = renumbered)
    }

    /**
     * Classify a line as a chapter heading: kind A (chapter/part keywords across the
     * Kokoro voice languages + CJK/Hindi forms), kind B (all-caps runs, Latin only), or
     * kind C (numeric "N. Cap" lines, Latin only). CJK/Hindi have no letter case, so
     * kinds B/C do not apply to them — [HEADING_A]'s CJK/Devanagari forms are their detector.
     */
    private fun headingKind(text: String): Char? =
        when {
            HEADING_A.matches(text) -> 'A'
            KIND_B.matches(text) -> 'B'
            KIND_C.matches(text) -> 'C'
            else -> null
        }

    /** Split over-long passages at sentence boundaries; paragraph grain otherwise untouched. */
    fun splitLongPassages(
        passages: List<TextPassage>,
        maxPassageWords: Int,
    ): List<TextPassage> {
        if (maxPassageWords <= 0) return passages
        return passages.flatMap { passage ->
            if (wordCount(passage.text) <= maxPassageWords) {
                listOf(passage)
            } else {
                val sentences =
                    passage.text
                        .split(SENTENCE_SPLIT)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (sentences.size <= 1) {
                    listOf(passage) // no usable boundaries: keep whole rather than mid-sentence cuts
                } else {
                    val chunks = mutableListOf<String>()
                    val current = StringBuilder()
                    var currentWords = 0
                    for (sentence in sentences) {
                        val sentenceWords = wordCount(sentence)
                        if (currentWords > 0 && currentWords + sentenceWords > maxPassageWords) {
                            chunks += current.toString()
                            current.clear()
                            currentWords = 0
                        }
                        if (currentWords > 0) current.append(' ')
                        current.append(sentence)
                        currentWords += sentenceWords
                    }
                    if (currentWords > 0) chunks += current.toString()
                    chunks.map(::TextPassage)
                }
            }
        }
    }

    fun wordCount(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    /**
     * Kind A heading — multilingual chapter/part keywords across the nine Kokoro voice
     * families (en/fr/es/pt/it/ja/zh/hi) followed by an optional ordinal and any tail.
     * The Devanagari keywords intentionally carry NO \b: Java's \b is ASCII-only, so
     * `अध्याय\b` would never match a Devanagari word boundary.
     */
    private val HEADING_A =
        Regex(
            "^(?:chapter\\b|ch\\.?\\b|chap\\.?\\b|part\\b|parte\\b|partie\\b|livre\\b|livro\\b|" +
                "libro\\b|cap[ií]tulo\\b|capitulo\\b|capitolo\\b|chapitre\\b|" +
                "sección\\b|seccion\\b|seção\\b|secao\\b|sezione\\b|segment\\b|" +
                "अध्याय|प्रकरण|भाग|" +
                "第\\s*(?:[0-9０-９]+|[一二三四五六七八九十百千〇]+)\\s*[章巻話節节部篇部])" +
                "\\s*" +
                "(?:[:\\.\\-–]?\\s*(?:[0-9０-９]{1,4}|[ivxlcdm]+|[一二三四五六七八九十百千〇]+|[०-९]+))?" +
                ".*$",
            RegexOption.IGNORE_CASE,
        )

    /** Kind B — all-caps Latin runs that can read as headings (e.g. "THE MILL"). */
    private val KIND_B = Regex("""^[A-Z0-9 ]{2,120}$""")

    /** Kind C — Latin "N. Capitalised" numeric heading lines. */
    private val KIND_C = Regex("""^\d{1,3}[.)]\s+[A-Z].*""")

    /** Sentence boundary: punctuation + whitespace, only when a sentence truly continues
     *  (uppercase letter, quote, digit) — so "Dr. Watson" and "e.g." survive. */
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?…])\s+(?=["'«“”‘’A-ZÀ-ÖØ-Þ0-9])""")
    private val WHITESPACE = Regex("\\s+")
}
