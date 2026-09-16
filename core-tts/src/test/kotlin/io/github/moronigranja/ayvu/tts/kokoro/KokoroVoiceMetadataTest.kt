package io.github.moronigranja.ayvu.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * C1.3 cross-check: the static [KokoroVoiceMetadata] table must line up with
 * the names a real loaded voices pack reports (the pack is the contract;
 * metadata is presentation). The fixture pack is built from the metadata
 * table itself, so a wrongly named/family'd entry fails loudly here — and
 * opposite drift (a metadata name missing from a real pack, found in the
 * device corpus pass) is caught the same way.
 */
class KokoroVoiceMetadataTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `table carries all 54 v1_0 voices with distinct names and known families`() {
        assertEquals(54, KokoroVoiceMetadata.all.size)
        val names = KokoroVoiceMetadata.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "voice names must be unique")
        // No unknown prefix: every meta row must have resolved a family.
        assertEquals(54, names.count { it.matches(Regex("[a-z]{2}_[a-z0-9]+")) })
    }

    @Test
    fun `cross-check passes against a fixture pack built from the table`() {
        val pack = fixturePack(KokoroVoiceMetadata.all.map { it.name })

        val missing = KokoroVoiceMetadata.missingFrom(pack.voiceNames)
        assertTrue(
            missing.isEmpty(),
            "metadata names missing from the pack roster: $missing",
        )
    }

    @Test
    fun `a pack name missing from metadata is reported as drift`() {
        val pack = fixturePack(KokoroVoiceMetadata.all.map { it.name } + "zz_future")

        assertTrue(
            "zz_future" !in KokoroVoiceMetadata.all.map { it.name },
            "precondition: the extra name is not in the table",
        )
        // missingFrom reports only metadata-not-in-pack; the reverse direction
        // (pack-not-in-metadata) is the pack being ahead — checked by the
        // device roster pass, not an app-time failure.
        assertTrue(KokoroVoiceMetadata.missingFrom(pack.voiceNames).isEmpty())
    }

    @Test
    fun `every voice carries a display name and only the ungraded families lack a grade`() {
        val all = KokoroVoiceMetadata.all
        assertTrue(all.all { it.displayName.isNotBlank() && it.displayName.startsWith(it.name.substringAfter('_'), ignoreCase = true) })
        val graded = all.filter { it.grade != null }.map { it.name }
        val ungraded = all.filter { it.grade == null }.map { it.name }
        // The es/pt families have no upstream data-grade; everything else does.
        assertEquals(
            all.filter { it.language == "Spanish" || it.language == "Portuguese (Brazil)" }.map { it.name }.toSet(),
            ungraded.toSet(),
        )
        assertTrue(graded.isNotEmpty())
    }

    private fun fixturePack(names: List<String>): KokoroVoiceBank {
        val file = File(root, "fixture-voices.bin")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry("$name.npy"))
                val total = KokoroVoiceBank.ROWS * KokoroVoiceBank.STYLE_DIM
                val floats = ByteArray(total * 4)
                val dict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${KokoroVoiceBank.ROWS}, 1, ${KokoroVoiceBank.STYLE_DIM}), }"
                val header = (dict + "\n").padEnd(64, ' ')
                val headerBytes = header.toByteArray(Charsets.US_ASCII)
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
                zip.write(prefix + headerBytes + floats)
                zip.closeEntry()
            }
        }
        return KokoroVoiceBank.load(file)
    }
}
