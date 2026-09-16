package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KokoroVoiceBankTest {
    @TempDir
    lateinit var tempDir: File

    /** Writes an .npz exactly the way numpy does: zip members "<name>.npy". */
    private fun writeNpz(
        file: File,
        sizes: List<Pair<String, IntArray>>,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, rows) in sizes) {
                zip.putNextEntry(ZipEntry("$name.npy"))
                zip.write(npyBytes(rows))
                zip.closeEntry()
            }
        }
    }

    private fun npyBytes(rows: IntArray): ByteArray {
        val total = rows.fold(1) { acc, r -> acc * r }
        val floats = ByteArray(total * 4)
        val buffer = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until total) buffer.put((i * 0.5f) % 1.0f)
        val dict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${rows.joinToString(", ")}, " + "1, 1), }"
        val headerBytes = (dict + "\n").padEnd(64, ' ').toByteArray(Charsets.US_ASCII)
        // numpy .npy v1: magic + version + 2-byte little-endian header length.
        val prefix =
            byteArrayOf(
                0x93.toByte(),
                'N'.code.toByte(),
                'U'.code.toByte(),
                'M'.code.toByte(),
                'P'.code.toByte(),
                'Y'.code.toByte(),
                1,
                0,
            ) +
                byteArrayOf((headerBytes.size and 0xFF).toByte(), ((headerBytes.size shr 8) and 0xFF).toByte())
        return prefix + headerBytes + floats
    }

    @Test
    fun `loads voices and serves style rows`() {
        val file = File(tempDir, "voices.bin")
        writeNpz(file, listOf("v_a" to intArrayOf(ROWS_I, STYLE_I), "v_b" to intArrayOf(ROWS_I, STYLE_I)))
        val bank = KokoroVoiceBank.load(file)

        assertEquals(setOf("v_a", "v_b"), bank.voiceNames)
        val row = bank.styleFor("v_a", length = 20)!!
        assertEquals(STYLE_I, row.size)
        assertTrue(row.contentEquals(bank.style("v_a")!!.copyOfRange(19 * STYLE_I, 20 * STYLE_I)), "row = voice[min(len,510)-1]")
        // Lengths beyond the tensor clamp to the last row (reference _style_for).
        val last = bank.styleFor("v_a", length = 900)!!
        assertTrue(last.contentEquals(bank.style("v_a")!!.copyOfRange((ROWS_I - 1) * STYLE_I, ROWS_I * STYLE_I)))
        assertNull(bank.style("missing"))
        assertNull(bank.styleFor("missing", 5))
    }

    @Test
    fun `rejects corrupted and empty packs`() {
        val garbage = File(tempDir, "garbage.bin")
        garbage.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { KokoroVoiceBank.load(garbage) }

        val empty = File(tempDir, "empty.bin")
        ZipOutputStream(empty.outputStream()).use { }
        assertThrows(IllegalArgumentException::class.java) { KokoroVoiceBank.load(empty) }

        // Wrong tensor shape (not 510×256).
        val wrong = File(tempDir, "wrong.bin")
        writeNpz(wrong, listOf("v_a" to intArrayOf(4, STYLE_I)))
        assertThrows(IllegalArgumentException::class.java) { KokoroVoiceBank.load(wrong) }
    }

    private companion object {
        const val ROWS_I = KokoroVoiceBank.ROWS
        const val STYLE_I = KokoroVoiceBank.STYLE_DIM
    }
}
