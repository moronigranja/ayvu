package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import androidx.room.Room
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * D4 selection wiring (decisions #154 addendum): the engine seam routes the
 * persisted `tts_engine` setting to the right runtime — piper-v1 opens
 * through [PiperRuntime] and Kokoro is never touched; voice resolution follows
 * the decisions #144 availability shape (engine-exposed ids pass through,
 * anything else falls back to the engine's default voice); Piper is PRIMARY,
 * so it is not degraded and its failure reason surfaces through the same seam
 * Kokoro's does. A selected engine with missing packs stays null + typed
 * reason — no auto-switching (#154).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineSelectorPiperTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private lateinit var settings: AppSettings

    /** Kokoro must be untouched while piper-v1 is selected. */
    private class FakeKokoroRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        val touched = AtomicInteger()

        override fun engine(): TTSEngine? {
            touched.incrementAndGet()
            return null
        }

        override val failureReason: String? = null
    }

    private class FakePiperRuntime(
        context: Context,
        settings: AppSettings,
    ) : PiperRuntime(context, settings) {
        val touched = AtomicInteger()
        private val engine = RecordingEngine()

        override fun engine(): TTSEngine? {
            touched.incrementAndGet()
            return engine
        }

        override val failureReason: String? = "Piper voice model not ready"
    }

    private class RecordingEngine : TTSEngine {
        override val spec = EngineSpec("piper-v1", "Piper", EngineTier.PRIMARY, setOf("en", "de"))
        override val packs: List<TtsPack> = emptyList()

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(1_000), 22_050, 1, segments = null)
    }

    private val onUnusedSystemTts =
        object : dagger.Lazy<TTSEngine> {
            override fun get(): TTSEngine = error("system tts must not be used in these tests")
        }

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        settings = AppSettings(SettingsStore(database.settingsDao()))
        translateRuntime = TranslateRuntime(context, settings)
    }

    private lateinit var translateRuntime: TranslateRuntime

    @After
    fun tearDown() {
        database.close()
    }

    private fun selector(
        kokoro: FakeKokoroRuntime,
        piper: FakePiperRuntime,
    ): EngineSelector = EngineSelector(kokoro, piper, translateRuntime, onUnusedSystemTts, settings, FakeTranslationService())

    @Test
    fun `piper-v1 routes the engine through PiperRuntime and never touches Kokoro`() {
        runBlocking { settings.setTtsEngine(SettingsStore.PIPER_ENGINE) }
        val kokoro = FakeKokoroRuntime(context, settings)
        val piper = FakePiperRuntime(context, settings)
        val selector = selector(kokoro, piper)

        val engine = selector.engine()
        assertTrue(engine is RecordingEngine)
        assertEquals("kokoro is never realized under piper-v1", 0, kokoro.touched.get())
        assertEquals(1, piper.touched.get())
        assertFalse("piper is a PRIMARY engine class, not degraded (#154)", selector.isDegraded)
        assertEquals("piper's typed prerequisite reason surfaces", "Piper voice model not ready", selector.failureReason)
    }

    @Test
    fun `voice resolution follows the availability shape`() {
        // piper-v1: engine-exposed ids pass through, a Kokoro name falls back
        // to the engine's default voice (decisions #144 shape).
        runBlocking { settings.setTtsEngine(SettingsStore.PIPER_ENGINE) }
        val piper = FakePiperRuntime(context, settings)
        val selector = selector(FakeKokoroRuntime(context, settings), piper)
        assertEquals("en_US-lessac-medium", selector.resolveVoice("af_heart"))
        assertEquals("de_DE-thorsten-high", selector.resolveVoice("de_DE-thorsten-high"))
        assertEquals("en_US-lessac-medium", selector.resolveVoice("en_US-lessac-medium"))
        assertSame(piper.engine(), selector.engine())

        // kokoro: byte-for-byte passthrough (D1 semantics unchanged).
        runBlocking { settings.setTtsEngine(SettingsStore.DEFAULT_TTS_ENGINE) }
        assertEquals("af_heart", selector.resolveVoice("af_heart"))
        assertEquals("en_US-lessac-medium", selector.resolveVoice("en_US-lessac-medium"))

        // system-tts: passthrough (the device voice ignores the id today).
        runBlocking { settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE) }
        assertEquals("af_heart", selector.resolveVoice("af_heart"))
        assertTrue(selector.isDegraded)
    }
}
