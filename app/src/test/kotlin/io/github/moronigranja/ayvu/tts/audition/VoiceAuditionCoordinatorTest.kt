package io.github.moronigranja.ayvu.tts.audition

import android.content.Context
import io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector
import io.github.moronigranja.ayvu.featureplayer.playback.KokoroRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.PassageOutput
import io.github.moronigranja.ayvu.featureplayer.playback.PiperRuntime
import io.github.moronigranja.ayvu.featureplayer.playback.TranslateRuntime
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.SettingEntity
import io.github.moronigranja.ayvu.persistence.SettingsDao
import io.github.moronigranja.ayvu.persistence.SettingsStore
import io.github.moronigranja.ayvu.player.AuditionStage
import io.github.moronigranja.ayvu.player.PlaybackStateHolder
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PlayerPhase
import io.github.moronigranja.ayvu.tts.EngineSpec
import io.github.moronigranja.ayvu.tts.EngineTier
import io.github.moronigranja.ayvu.tts.SegmentAnchor
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.TTSEngine
import io.github.moronigranja.ayvu.tts.TtsPack
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C2 audition coordinator host test (Robolectric for a real Context): one
 * sample at a time, narration captured + resumed only if it was playing,
 * missing assets fail typed, and preview audio is ephemeral (goes to the
 * fake output, never to a cache/progress). The coordinator's jobs run on the
 * test's background scope so the completion poll is deterministic under
 * virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceAuditionCoordinatorTest {
    class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = rows[key]

        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }

        override suspend fun all(): List<SettingEntity> = rows.map { (key, value) -> SettingEntity(key, value) }

        override suspend fun putAll(settings: List<SettingEntity>) {
            settings.forEach { rows[it.key] = it.value }
        }

        override suspend fun delete(key: String) {
            rows.remove(key)
        }

        override suspend fun deleteAll(keys: List<String>) {
            keys.forEach { rows.remove(it) }
        }
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var settings: AppSettings
    private lateinit var engine: FakeEngine
    private lateinit var output: FakeOutput
    private lateinit var commands: RecordingCommands

    @Before
    fun setUp() {
        settings = AppSettings(SettingsStore(FakeSettingsDao()))
        engine = FakeEngine()
        output = FakeOutput()
        commands = RecordingCommands()
        PlaybackStateHolder.reset()
    }

    private fun TestScope.coordinator(): VoiceAuditionCoordinator =
        VoiceAuditionCoordinator(
            selector =
                EngineSelector(
                    FakeRuntime(context, engine, settings),
                    PiperRuntime(context, settings),
                    TranslateRuntime(context, settings),
                    object : dagger.Lazy<TTSEngine> {
                        override fun get(): TTSEngine = error("system tts unused")
                    },
                    settings,
                    stubTranslationService,
                ),
            output = output,
            commands = commands,
            appScope = this,
            ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            settings = settings,
        )

    /** The audition path never touches the translate seam — a stub suffices. */
    private val stubTranslationService =
        object : io.github.moronigranja.ayvu.player.pregen.TranslationService {
            override val translatePossible: Boolean = true
            override val ready: kotlinx.coroutines.flow.Flow<io.github.moronigranja.ayvu.player.pregen.TranslationReady> =
                kotlinx.coroutines.flow.emptyFlow()

            override suspend fun cached(
                bookId: String,
                chapter: Int,
                passage: Int,
                target: io.github.moronigranja.ayvu.player.pregen.TranslationTarget,
            ): String? = null

            override suspend fun translate(
                bookId: String,
                chapter: Int,
                passage: Int,
                target: io.github.moronigranja.ayvu.player.pregen.TranslationTarget,
            ): String? = null

            override fun prefetch(
                bookId: String,
                chapter: Int,
                passages: List<Int>,
                target: io.github.moronigranja.ayvu.player.pregen.TranslationTarget,
            ) = Unit
        }

    private class FakeRuntime(
        context: Context,
        private val engine: TTSEngine,
        settings: AppSettings,
    ) : KokoroRuntime(context, settings) {
        override fun engine(): TTSEngine? = engine

        override val failureReason: String? = null
    }

    private open class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        var requested: SynthesisRequest? = null
        open var fail: Boolean = false

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            requested = request
            return if (fail) {
                SynthesisOutcome.Failed("boom")
            } else {
                SynthesisOutcome.Audio(ByteArray(2_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
            }
        }
    }

    private class FakeOutput : PassageOutput {
        var played = false

        override fun play(
            pcm: ByteArray,
            sampleRate: Int,
            speed: Double,
        ) {
            played = true
            positionSamples = pcm.size / 2 // completes the poll immediately
        }

        override fun stop() = Unit

        override var positionSamples: Int = 0

        override fun setVolume(multiplier: Float) = Unit
    }

    private class RecordingCommands : PlayerCommands {
        val calls = mutableListOf<String>()

        override fun play(bookId: String) {
            calls.add("play")
        }

        override fun playAt(
            bookId: String,
            chapterIndex: Int,
            passageIndex: Int,
        ) {
            calls.add("playAt")
        }

        override fun changeVoice(voice: String) {
            calls.add("changeVoice")
        }

        override fun resume() {
            calls.add("resume")
        }

        override fun pause() {
            calls.add("pause")
        }

        override fun stop() {
            calls.add("stop")
        }

        override fun seekForward() {
            calls.add("seekForward")
        }

        override fun seekBackward() {
            calls.add("seekBackward")
        }
    }

    @Test
    fun `preview synthesizes the fixed phrase with the voice and plays it`() =
        runTest {
            val c = coordinator()
            PlaybackStateHolder.update { it.copy(phase = PlayerPhase.LOADING) }
            c.preview("af_heart")
            advanceUntilIdle()
            assertEquals("af_heart", engine.requested?.voice)
            assertTrue(engine.requested?.text?.isNotBlank() == true)
            assertTrue(output.played)
        }

    @Test
    fun `narration paused during audition and resumed when it was playing`() =
        runTest {
            val c = coordinator()
            PlaybackStateHolder.update { it.copy(phase = PlayerPhase.PLAYING) }
            c.preview("af_heart")
            advanceUntilIdle()
            assertTrue("narration should be paused during a sample", commands.calls.contains("pause"))
            assertTrue("narration should resume after the sample", commands.calls.contains("resume"))
        }

    @Test
    fun `nothing pauses or resumes when narration was not playing`() =
        runTest {
            val c = coordinator()
            PlaybackStateHolder.update { it.copy(phase = PlayerPhase.IDLE) }
            c.preview("af_heart")
            advanceUntilIdle()
            assertTrue(commands.calls.none { it == "pause" || it == "resume" })
        }

    @Test
    fun `missing assets fail typed instead of playing`() =
        runTest {
            engine = FakeEngine().also { it.fail = true }
            val c = coordinator()
            c.preview("af_heart")
            advanceUntilIdle()
            assertEquals("af_heart", c.state.value.voice)
            assertTrue(c.state.value.stage is AuditionStage.Failed)
            assertTrue(!output.played)
        }
}
