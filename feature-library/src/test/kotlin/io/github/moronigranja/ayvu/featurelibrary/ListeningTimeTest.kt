package io.github.moronigranja.ayvu.featurelibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Phase K item 3: the pregen dialog's free-form listening-time entry must
 * resolve to whole listening minutes (the [PregenBudget] unit) or reject
 * cleanly — a typo must never start a silent whole-book run. */
class ListeningTimeTest {
    @Test
    fun `plain numbers are minutes`() {
        assertEquals(45L, parseListeningMinutes("45"))
        assertEquals(90L, parseListeningMinutes(" 90 "))
        assertEquals(5L, parseListeningMinutes("5m"))
        assertEquals(120L, parseListeningMinutes("120m"))
    }

    @Test
    fun `hours and minutes combine`() {
        assertEquals(60L, parseListeningMinutes("1h"))
        assertEquals(90L, parseListeningMinutes("1h30"))
        assertEquals(90L, parseListeningMinutes("1h30m"))
        assertEquals(75L, parseListeningMinutes("1h 15m"))
        assertEquals(150L, parseListeningMinutes("2.5h"))
        assertEquals(61L, parseListeningMinutes("60.5m"))
    }

    @Test
    fun `longer unit spellings and comma decimals parse`() {
        assertEquals(90L, parseListeningMinutes("1.5H"))
        assertEquals(90L, parseListeningMinutes("1,5h"))
        assertEquals(60L, parseListeningMinutes("1 hour"))
        assertEquals(90L, parseListeningMinutes("1 hour 30 minutes"))
        assertEquals(45L, parseListeningMinutes("45 min"))
    }

    @Test
    fun `rejects empty garbled and non-positive entries`() {
        assertNull(parseListeningMinutes(""))
        assertNull(parseListeningMinutes("   "))
        assertNull(parseListeningMinutes("abc"))
        assertNull(parseListeningMinutes("1.5")) // unitless fraction is ambiguous
        assertNull(parseListeningMinutes("0"))
        assertNull(parseListeningMinutes("-5"))
        assertNull(parseListeningMinutes("1h-30m"))
        assertNull(parseListeningMinutes("45m 1h"))
    }

    @Test
    fun `zero budgets reject and one minute is the floor`() {
        assertNull(parseListeningMinutes("0h"))
        assertNull(parseListeningMinutes("0m"))
        assertEquals(30L, parseListeningMinutes("0.5h"))
        assertEquals(1L, parseListeningMinutes("1m"))
    }
}
