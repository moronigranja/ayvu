package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Paginated-reader geometry (decisions #52): overflow page breaks, chapter
 * first-page title reservation, keep-together passage groups.
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
    fun `layout without groups splits on capacity`() {
        // Parity with the retired arithmetic: page 0 = 10 lines, later pages 20.
        val layout = TextPagination.layout(50, 10, 20, emptyList())
        assertEquals(3, layout.pageCount)
        assertEquals(0, layout.startLine(0))
        assertEquals(10, layout.startLine(1))
        assertEquals(30, layout.startLine(2))
        assertEquals(0, layout.pageOf(9))
        assertEquals(1, layout.pageOf(10))
        assertEquals(2, layout.pageOf(49))
    }

    @Test
    fun `layout keeps a passage group on one page`() {
        // Group 8..12 cannot fit on page 0 (lines 8-9 left) → new page at 8;
        // the group then fills 20 lines to 27, the next page starts at 28 and
        // the tail (48-49) needs its own page.
        val layout = TextPagination.layout(50, 10, 20, listOf(8 until 13))
        assertEquals(4, layout.pageCount)
        assertEquals(0, layout.startLine(0))
        assertEquals(8, layout.startLine(1))
        assertEquals(28, layout.startLine(2))
        assertEquals(48, layout.startLine(3))
        assertEquals(0, layout.pageOf(7))
        assertEquals(1, layout.pageOf(8))
        assertEquals(1, layout.pageOf(12))
        assertEquals(1, layout.pageOf(13))
    }

    @Test
    fun `layout splits an oversized group by capacity`() {
        // A 25-line group on a 10-line first page cannot be kept together:
        // it degrades to a plain capacity split.
        val layout = TextPagination.layout(30, 10, 20, listOf(0 until 25))
        assertEquals(2, layout.pageCount)
        assertEquals(10, layout.startLine(1))
    }

    @Test
    fun `layout starts a page for a group beyond the last line`() {
        // The group (10..29) does not fit page 0's remaining lines → page 1
        // starts at 10, and the group occupies 10..24 (the chapter's tail).
        val layout = TextPagination.layout(25, 10, 20, listOf(10 until 30))
        assertEquals(2, layout.pageCount)
        assertEquals(10, layout.startLine(1))
    }

    @Test
    fun `exactly filling a page still starts a new one`() {
        // Group 0..9 exactly fills page 0; the 10th line cannot join an
        // already-full page, so page 1 starts at 10.
        val layout = TextPagination.layout(30, 10, 20, listOf(0 until 10))
        assertEquals(2, layout.pageCount)
        assertEquals(10, layout.startLine(1))
    }
}