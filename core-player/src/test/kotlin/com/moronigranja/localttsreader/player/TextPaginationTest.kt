package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Paginated-reader geometry (decisions #52): overflow page breaks, chapter
 * first-page title reservation, page/line mapping.
 */
class TextPaginationTest {
    @Test
    fun `lines per page floors to whole lines`() {
        assertEquals(10, TextPagination.linesPerPage(300, 30))
        assertEquals(10, TextPagination.linesPerPage(301, 30))
        assertEquals(11, TextPagination.linesPerPage(330, 30))
    }

    @Test
    fun `first page reserves the title headroom`() {
        // 400px viewport, 30px lines, 100px title → 10 lines on page 0.
        assertEquals(10, TextPagination.linesPerPage(400, 30, reservedPx = 100))
        // At least one line always fits.
        assertEquals(1, TextPagination.linesPerPage(10, 30, reservedPx = 100))
    }

    @Test
    fun `conservative bottom reserve floors at the boundary`() {
        // The reader's bottom reserve is the MEASURED footer height plus one
        // line of slack (decision: one line lost on the last page beats a
        // clipped line). Exactly n lines + that reserve fit; one px short the
        // reserve boundary floors to n-1 — the floor must never squeeze a
        // line into the reserved footer space.
        val lineHeight = 30
        val conservativeReserve = 46 + lineHeight // measured footer + slack
        assertEquals(5, TextPagination.linesPerPage(5 * lineHeight + conservativeReserve, lineHeight, reservedPx = conservativeReserve))
        assertEquals(4, TextPagination.linesPerPage(5 * lineHeight + conservativeReserve - 1, lineHeight, reservedPx = conservativeReserve))
    }

    @Test
    fun `page of a line splits after the first page`() {
        // Page 0: lines 0..9 (10 lines); later pages: 20 lines each.
        assertEquals(0, TextPagination.pageOf(0, 10, 20))
        assertEquals(0, TextPagination.pageOf(9, 10, 20))
        assertEquals(1, TextPagination.pageOf(10, 10, 20))
        assertEquals(1, TextPagination.pageOf(29, 10, 20))
        assertEquals(2, TextPagination.pageOf(30, 10, 20))
        assertEquals(2, TextPagination.pageOf(49, 10, 20))
    }

    @Test
    fun `total pages counts the remainder page`() {
        assertEquals(1, TextPagination.totalPages(0, 10, 20))
        assertEquals(1, TextPagination.totalPages(10, 10, 20))
        assertEquals(3, TextPagination.totalPages(50, 10, 20)) // 10 + 20 + 20
        assertEquals(4, TextPagination.totalPages(51, 10, 20)) // + 1 remainder line
    }

    @Test
    fun `page start line advances past the first page`() {
        assertEquals(0, TextPagination.pageStartLine(0, 10, 20))
        assertEquals(10, TextPagination.pageStartLine(1, 10, 20))
        assertEquals(30, TextPagination.pageStartLine(2, 10, 20))
    }
}
