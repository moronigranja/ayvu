package io.github.moronigranja.ayvu.player

/** Page boundaries for one chapter layout: the line each page starts at. */
class PageLayout internal constructor(
    private val starts: IntArray,
) {
    val pageCount: Int get() = starts.size

    fun startLine(page: Int): Int = starts[page.coerceIn(0, starts.lastIndex)]

    /** The page containing [line]. */
    fun pageOf(line: Int): Int {
        val target = line.coerceAtLeast(0)
        var lo = 0
        var hi = starts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * Text-pagination geometry for the paginated reader (decisions #52): pages
 * are contiguous line ranges of a fixed-line-height text. A page holds as
 * many lines as fit the viewport — the break lands exactly where the text
 * would overflow, and a chapter always starts on a fresh page (its first
 * page reserves headroom for the chapter title).
 *
 * [layout] additionally honors [keepTogether]: ascending, non-overlapping
 * line ranges that no page break may fall inside (a translated passage's
 * Original+Translation block group). A group that fits the lines left on the
 * current page stays there; otherwise it starts a new page; a group taller
 * than a full page is split by capacity.
 */
object TextPagination {
    /** Lines that fit a viewport; the chapter's first page reserves [reservedPx] (title). */
    fun linesPerPage(
        viewportHeight: Int,
        lineHeightPx: Int,
        reservedPx: Int = 0,
    ): Int = maxOf(1, (viewportHeight - reservedPx) / maxOf(1, lineHeightPx))

    /**
     * @param keepTogether ascending, non-overlapping line ranges that no page break may fall
     *   inside (a translated passage's Original+Translation block group). A group that fits the
     *   lines left on the current page stays there; otherwise it starts a new page; a group taller
     *   than a full page is split by capacity.
     */
    fun layout(
        totalLines: Int,
        firstPageLines: Int,
        fullPageLines: Int,
        keepTogether: List<IntRange>,
    ): PageLayout {
        if (totalLines <= 0) return PageLayout(intArrayOf(0))
        val starts = ArrayList<Int>()
        starts += 0
        var line = 0
        var cap = maxOf(1, firstPageLines) // lines left on the current page
        while (line < totalLines) {
            if (cap <= 0) {
                starts += line
                cap = fullPageLines
                continue
            }
            val g = keepTogether.firstOrNull { it.first == line }
            if (g != null) {
                val len = g.last - g.first + 1
                if (len <= cap) {
                    line = g.last + 1
                    cap -= len
                    continue
                }
                val fullCapacity = if (starts.size == 1) firstPageLines else fullPageLines
                if (cap < fullCapacity) {
                    starts += line
                    cap = fullPageLines
                    continue
                }
                // the group alone exceeds a full page: fall through and split by capacity
            }
            val nextGroup = keepTogether.firstOrNull { it.first > line }?.first ?: totalLines
            val step = minOf(cap, nextGroup - line, totalLines - line)
            line += step
            cap -= step
        }
        return PageLayout(starts.toIntArray())
    }
}
