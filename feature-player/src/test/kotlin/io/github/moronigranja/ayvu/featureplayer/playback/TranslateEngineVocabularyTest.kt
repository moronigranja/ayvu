package io.github.moronigranja.ayvu.featureplayer.playback

import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.pregen.PregenKey
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget
import io.github.moronigranja.ayvu.tts.translate.TranslatePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translate engine vocabulary is split across three modules that cannot see
 * each other: the engine registry (core-translate), the cache-key constants
 * (core-player) and the stored default (core-persistence). This test is the
 * join, and it is the guard for the failure that matters — if those values
 * drift, one engine's audio or text can be served for another engine's request
 * (decisions #182: the engine is a user option and its id IS the cache key).
 */
class TranslateEngineVocabularyTest {
    @Test
    fun `every engine id is a known cache-key translator`() {
        assertEquals(
            listOf(PregenKey.LFM_TRANSLATOR, PregenKey.LFM26B_TRANSLATOR),
            TranslatePacks.all.map { it.id },
        )
    }

    @Test
    fun `the stored default selects the shipped engine`() {
        assertEquals(TranslatePacks.shipped.id, TranslatePacks.DEFAULT_ID)
        assertEquals(TranslatePacks.DEFAULT_ID, SettingsStore.DEFAULT_TRANSLATE_ENGINE)
    }

    @Test
    fun `an absent or unknown selection resolves to the shipped engine`() {
        assertEquals(TranslatePacks.shipped, TranslatePacks.byId(null))
        assertEquals(TranslatePacks.shipped, TranslatePacks.byId("no-such-engine"))
    }

    @Test
    fun `each engine owns one unique pack, spec and bundle root`() {
        assertEquals(
            TranslatePacks.all.size,
            TranslatePacks.packs
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(TranslatePacks.all.size, TranslatePacks.engineIds.size)
        assertEquals(
            TranslatePacks.all.size,
            TranslatePacks.all
                .map { it.bundleDirName }
                .distinct()
                .size,
        )
        TranslatePacks.all.forEach { engine ->
            assertEquals(engine.spec.id, engine.pack.engineId)
            assertTrue(engine.spec.id in TranslatePacks.engineIds)
            assertEquals(engine, TranslatePacks.byPackId(engine.pack.id))
        }
    }

    @Test
    fun `a registry engine id round-trips as the audio cache segment`() {
        TranslatePacks.all.forEach { engine ->
            val key =
                PregenKey(
                    bookId = "b1",
                    chapterIndex = 0,
                    passageIndex = 0,
                    voice = "af_heart",
                    speed = 1.0,
                    target = TranslationTarget("pt-BR", engine.id),
                )
            val parsed = PregenKey.parse(key.toString())
            assertEquals(engine.id, parsed?.target?.translator)
            assertEquals("pt-BR", parsed?.target?.lang)
        }
    }

    @Test
    fun `the size label is derived from the pack descriptor`() {
        assertEquals("~730 MB", TranslatePacks.shipped.sizeLabel)
        assertEquals("~1.7 GB", TranslatePacks.better.sizeLabel)
    }
}
