package io.github.moronigranja.ayvu.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OperationRunnerTest {
    @Test
    fun `default cancel label is Stop`() {
        val spec = OperationSpec(id = "x", channel = OperationChannel.DOWNLOADS, title = "Ayvu — x")

        assertEquals("Stop", spec.cancelLabel)
    }

    @Test
    fun `channels are exactly downloads and import`() {
        assertEquals(
            listOf("DOWNLOADS", "IMPORT"),
            OperationChannel.entries.map { it.name },
        )
    }
}
