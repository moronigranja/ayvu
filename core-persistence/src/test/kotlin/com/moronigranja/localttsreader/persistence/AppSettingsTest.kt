package com.moronigranja.localttsreader.persistence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1: AppSettings mirrors the store, defaults first, writes land everywhere. */
class AppSettingsTest {
    private class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = rows[key]

        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }

        override suspend fun all(): List<SettingEntity> = rows.map { (key, value) -> SettingEntity(key, value) }

        override suspend fun putAll(settings: List<SettingEntity>) {
            settings.forEach { rows[it.key] = it.value }
        }

        override suspend fun deleteAll(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }
    }

    @Test
    fun defaultsHoldBeforeReload() {
        val settings = AppSettings(SettingsStore(FakeSettingsDao()))
        assertEquals(SettingsStore.DEFAULT_VOICE, settings.state.value.voice)
        assertEquals(ThemeMode.SYSTEM, settings.state.value.theme)
        assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.state.value.threshold, 0.0)
        assertEquals(listOf("eng"), settings.state.value.ocrLanguages)
        assertEquals(emptyList<String>(), settings.state.value.favorites)
        assertEquals(SettingsStore.DEFAULT_TTS_THREADS, settings.state.value.ttsThreads)
    }

    @Test
    fun reloadPicksUpPersistedValues() =
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            store.setVoice("bm_george")
            store.setThemeMode(ThemeMode.DARK)
            store.setMatchThreshold(0.72)
            store.setOcrLanguages(listOf("eng", "spa"))
            store.setFavoriteVoices(listOf("af_heart", "bm_george"))
            store.setTtsThreads(2)

            val settings = AppSettings(store)
            settings.reload()
            assertEquals("bm_george", settings.state.value.voice)
            assertEquals(ThemeMode.DARK, settings.state.value.theme)
            assertEquals(0.72, settings.state.value.threshold, 0.0)
            assertEquals(listOf("eng", "spa"), settings.state.value.ocrLanguages)
            assertEquals(listOf("af_heart", "bm_george"), settings.state.value.favorites)
            assertEquals(2, settings.state.value.ttsThreads)
        }

    @Test
    fun writesFlowThroughToTheStoreAndHotPath() =
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            val settings = AppSettings(store)

            settings.setVoice("pf_dora")
            settings.setThemeMode(ThemeMode.LIGHT)
            settings.setMatchThreshold(0.5)
            settings.setOcrLanguages(listOf("deu"))

            assertEquals("pf_dora", settings.state.value.voice)
            assertEquals("pf_dora", store.voice())
            assertEquals(ThemeMode.LIGHT, settings.state.value.theme)
            assertEquals(0.5, settings.state.value.threshold, 0.0)
            assertEquals(0.5, store.matchThreshold(), 0.0)
            assertEquals(listOf("deu"), settings.state.value.ocrLanguages)
            assertEquals(listOf("deu"), store.ocrLanguages())
        }

    @Test
    fun `tts threads write through clamped and invalid values fall back`() {
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            val settings = AppSettings(store)

            settings.setTtsThreads(2)
            assertEquals(2, settings.state.value.ttsThreads)
            assertEquals(2, store.ttsThreads())

            // Out-of-range writes clamp instead of storing nonsense.
            settings.setTtsThreads(24)
            assertEquals(SettingsStore.MAX_TTS_THREADS, settings.state.value.ttsThreads)
            assertEquals(SettingsStore.MAX_TTS_THREADS, store.ttsThreads())
            settings.setTtsThreads(0)
            assertEquals(SettingsStore.MIN_TTS_THREADS, settings.state.value.ttsThreads)

            // Corrupt persisted values read as the default (V1 rule: a bad value
            // and a missing row are equivalent, user-recoverable from the UI).
            dao.rows[SettingsStore.KEY_TTS_THREADS] = "lots"
            assertEquals(SettingsStore.DEFAULT_TTS_THREADS, store.ttsThreads())
            settings.reload()
            assertEquals(SettingsStore.DEFAULT_TTS_THREADS, settings.state.value.ttsThreads)
        }
    }

    @Test
    fun toggleFavoriteAddsAndRemoves() =
        runBlocking {
            val settings = AppSettings(SettingsStore(FakeSettingsDao()))
            settings.toggleFavorite("af_heart")
            assertTrue("af_heart" in settings.state.value.favorites)
            settings.toggleFavorite("af_heart")
            assertFalse("af_heart" in settings.state.value.favorites)
        }

    // ------------------------------------------------------------------
    // Per-book voice override (decisions #144, Phase K item 5): the hot
    // path reads the mirror with no database; writes land everywhere.
    // ------------------------------------------------------------------

    @Test
    fun `book voice override mirrors and survives reload`() =
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            val settings = AppSettings(store)
            assertNull("no override before any write", settings.bookVoice("b1"))

            settings.setBookVoice("b1", "de_DE-thorsten-high")
            assertEquals("non-suspend hot-path read", "de_DE-thorsten-high", settings.bookVoice("b1"))
            assertEquals("global default untouched", SettingsStore.DEFAULT_VOICE, settings.state.value.voice)

            // A cold mirror (a fresh AppSettings over the same store) reloads it.
            var restarted = AppSettings(store)
            restarted.reload()
            assertEquals("de_DE-thorsten-high", restarted.bookVoice("b1"))

            settings.setBookVoice("b1", null)
            assertNull(settings.bookVoice("b1"))
            restarted = AppSettings(store)
            restarted.reload()
            assertNull(restarted.bookVoice("b1"))
        }

    // ------------------------------------------------------------------
    // RTF tri-state (item 8, D2): derived from ACCUMULATED wall/audio
    // samples; a verdict needs >= 10 s of rendered audio (#93).
    // ------------------------------------------------------------------

    @Test
    fun `rtf stays unmeasured below the ten second audio gate`() =
        runBlocking {
            val settings = AppSettings(SettingsStore(FakeSettingsDao()))
            // A real Preview: ~1.2 s of audio contributed once.
            settings.setRtfSample(2_000L, 1_200L)
            assertNull(settings.state.value.realtimeCapable)
            // 9 s of audio in total — still under the gate even at wall == audio.
            settings.setRtfSample(8_000L, 7_800L)
            assertNull(settings.state.value.realtimeCapable)
        }

    @Test
    fun `rtf is realtime at wall equal to audio once the gate is crossed`() =
        runBlocking {
            val settings = AppSettings(SettingsStore(FakeSettingsDao()))
            settings.setRtfSample(6_000L, 6_000L)
            settings.setRtfSample(4_000L, 4_001L) // accumulates to 10.001 s audio
            assertEquals(true, settings.state.value.realtimeCapable)
        }

    @Test
    fun `rtf is slow when wall exceeds audio past the gate`() =
        runBlocking {
            val settings = AppSettings(SettingsStore(FakeSettingsDao()))
            // 20 s of wall for 10 s of audio — RTF 2.0, the HiBreak profile.
            settings.setRtfSample(20_000L, 10_000L)
            assertEquals(false, settings.state.value.realtimeCapable)
        }

    @Test
    fun `rtf samples accumulate and the verdict survives reload`() =
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            val settings = AppSettings(store)
            settings.setRtfSample(3_000L, 5_000L)
            settings.setRtfSample(3_000L, 5_000L) // accumulated: 6 s wall / 10 s audio
            assertEquals(6_000L, store.rtfWallMs())
            assertEquals(10_000L, store.rtfAudioMs())
            assertEquals(true, settings.state.value.realtimeCapable)

            // A cold restart derives the same verdict from the persisted pair.
            val restarted = AppSettings(SettingsStore(dao))
            restarted.reload()
            assertEquals(true, restarted.state.value.realtimeCapable)
        }

    @Test
    fun `translate voice round-trips store and mirror and clears with null`() =
        runBlocking {
            val dao = FakeSettingsDao()
            val store = SettingsStore(dao)
            val settings = AppSettings(store)

            settings.setTranslateVoice("es_ES-davefx-medium")
            assertEquals("es_ES-davefx-medium", store.translateVoice())
            assertEquals("es_ES-davefx-medium", settings.translateVoice())
            assertEquals("es_ES-davefx-medium", settings.state.value.translateVoice)

            settings.setTranslateVoice(null)
            assertNull(store.translateVoice())
            assertNull(settings.translateVoice())

            // The blank write clears too (mirror + store).
            settings.setTranslateVoice(" ")
            assertNull(store.translateVoice())
            assertNull(settings.state.value.translateVoice)

            // A cold restart reads the persisted voice back.
            settings.setTranslateVoice("pm_alex")
            val restarted = AppSettings(SettingsStore(dao))
            restarted.reload()
            assertEquals("pm_alex", restarted.translateVoice())
        }
}
