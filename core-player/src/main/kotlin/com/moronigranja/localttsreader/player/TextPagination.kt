package com.moronigranja.localttsreader.player

/**
 * Text-pagination geometry for the paginated reader (decisions #52): pages
 * are contiguous line ranges of a fixed-line-height text. A page holds as
 * many lines as fit the viewport — the break lands exactly where the text
 * would overflow, and a chapter always starts on a fresh page (its first
 * page reserves headroom for the chapter title).
 */
object TextPagination {
    /** Lines that fit a viewport; the chapter's first page reserves [reservedPx] (title). */
    fun linesPerPage(
        viewportHeight: Int,
        lineHeightPx: Int,
        reservedPx: Int = 0,
    ): Int = maxOf(1, (viewportHeight - reservedPx) / maxOf(1, lineHeightPx))

    /** The page containing [line] (0-based line in the chapter text). */
    fun pageOf(
        line: Int,
        firstPageLines: Int,
        fullPageLines: Int,
    ): Int {
        if (line < firstPageLines || fullPageLines <= 0) return 0
        return 1 + (line - firstPageLines) / fullPageLines
    }

    /** Total pages for a chapter of [totalLines] lines (always at least one). */
    fun totalPages(
        totalLines: Int,
        firstPageLines: Int,
        fullPageLines: Int,
    ): Int {
        if (totalLines <= 0) return 1
        val rest = maxOf(0, totalLines - firstPageLines)
        return 1 + (rest + fullPageLines - 1) / maxOf(1, fullPageLines)
    }

    /** First line of [page] in the chapter text. */
    fun pageStartLine(
        page: Int,
        firstPageLines: Int,
        fullPageLines: Int,
    ): Int = if (page <= 0) 0 else firstPageLines + (page - 1) * fullPageLines
}
