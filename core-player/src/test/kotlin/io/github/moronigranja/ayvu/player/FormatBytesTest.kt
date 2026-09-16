package io.github.moronigranja.ayvu.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The storage-transparency formatter every size surface shares (decisions #44).
 * The GB case is the device-smoke regression from the signed 0.1.1 build: a
 * 760 GB free-space figure rendered as "760321.4 MB free" — the branch was
 * simply missing, so anything past 1 GiB read as an absurd MB count.
 */
class FormatBytesTest {
    @Test
    fun `picks the largest unit the value fills`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1_024))
        assertEquals("1.0 MB", formatBytes(1_048_576))
        assertEquals("1.0 GB", formatBytes(1_073_741_824))
    }

    @Test
    fun `each boundary keeps the smaller unit until it is reached`() {
        assertEquals("1023 B", formatBytes(1_023))
        assertEquals("1024 KB", formatBytes(1_048_575))
        assertEquals("1024.0 MB", formatBytes(1_073_741_823))
    }

    @Test
    fun `the reported free-space figure reads as gigabytes`() {
        // 760321.4 MB is what the setup screen printed for this device's free space.
        val freeSpaceBytes = (760_321.4 * 1_048_576).toLong()
        assertEquals("742.5 GB", formatBytes(freeSpaceBytes))
    }
}
