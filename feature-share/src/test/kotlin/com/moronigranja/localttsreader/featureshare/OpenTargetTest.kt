package com.moronigranja.localttsreader.featureshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** S3: the extras contract the share gate writes and MainActivity reads. */
class OpenTargetTest {
    @Test
    fun `parses a full target`() {
        val target = OpenTarget.fromExtras("b1", 3, 5)
        assertEquals(OpenTarget("b1", 3, 5), target)
    }

    @Test
    fun `missing book id is null`() {
        assertNull(OpenTarget.fromExtras(null, 3, 5))
        assertNull(OpenTarget.fromExtras("", 3, 5))
        assertNull(OpenTarget.fromExtras("   ", 3, 5))
    }

    @Test
    fun `absent chapter and passage coerce to zero`() {
        assertEquals(OpenTarget("b1", 0, 0), OpenTarget.fromExtras("b1", -1, -1))
        assertEquals(OpenTarget("b1", 0, 2), OpenTarget.fromExtras("b1", -1, 2))
        assertEquals(OpenTarget("b1", 1, 0), OpenTarget.fromExtras("b1", 1, -1))
    }
}
