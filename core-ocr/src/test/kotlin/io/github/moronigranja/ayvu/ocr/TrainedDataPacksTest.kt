package io.github.moronigranja.ayvu.ocr

import io.github.moronigranja.ayvu.tts.PackKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** S1: the traineddata descriptors are fully pinned and registry-shaped. */
class TrainedDataPacksTest {
    @Test
    fun `six languages, each a complete pinned descriptor`() {
        assertEquals(listOf("eng", "spa", "fra", "deu", "por", "ita"), TrainedDataPacks.all.map { it.id })
        for (pack in TrainedDataPacks.all) {
            assertEquals(TrainedDataPacks.ENGINE_ID, pack.engineId)
            assertEquals(PackKind.LANGUAGE, pack.kind)
            assertTrue(pack.url.startsWith("https://"), "${pack.id} served over HTTPS")
            assertEquals(64, pack.sha256Hex.length, "${pack.id} sha256 length")
            assertTrue(pack.sha256Hex.all { it in "0123456789abcdefABCDEF" }, "${pack.id} sha256 hex")
            assertTrue(pack.sizeBytes > 0)
        }
    }

    @Test
    fun `pack ids are globally unique`() {
        val ids = TrainedDataPacks.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    /** A re-pin is only valid as a whole: every URL must come from the same
     *  `tessdata_fast` release the SHA-256 table in the descriptor was measured
     *  against, or a mixed pin would verify the wrong bytes. */
    @Test
    fun `every url comes from the pinned tessdata_fast release`() {
        for (pack in TrainedDataPacks.all) {
            assertTrue(
                pack.url.startsWith("https://github.com/tesseract-ocr/tessdata_fast/raw/${TrainedDataPacks.RELEASE}/"),
                "${pack.id} url: ${pack.url}",
            )
        }
    }

    @Test
    fun `engine spec advertises the six languages`() {
        assertEquals(TrainedDataPacks.all.map { it.id }.toSet(), TrainedDataPacks.spec.languages)
    }
}
