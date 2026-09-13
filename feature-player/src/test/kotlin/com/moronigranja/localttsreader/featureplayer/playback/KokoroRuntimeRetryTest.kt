package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingEntity
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QW3 host tests (playa's harness shape): the engine-failure retry seam in
 * [KokoroRuntime].
 *
 * The real prerequisite guards run against the Robolectric filesDir — files
 * are staged/unstaged exactly like the async pack staging would — while
 * [KokoroRuntime.openEngine] is overridden (the production open needs the
 * 325 MB model + espeak bundle; the open seam mirrors the A57/PregenWorker
 * engine-override shape).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KokoroRuntimeRetryTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private class FakeEngine : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            SynthesisOutcome.Audio(ByteArray(100), 24_000, 1, null)
    }

    /** Opens are counted and return the fake engine. */
    private class RetryRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(
            context,
            settings,
        ) {
        val engine = FakeEngine()
        var opens = 0
        override fun openEngine(): TTSEngine {
            opens++
            return engine
        }
    }

    /** Every open throws like a corrupt model would; attempts are counted. */
    private class CorruptRuntime(
        context: Context,
        settings: AppSettings,
    ) : KokoroRuntime(
            context,
            settings,
        ) {
        var opens = 0
        override fun openEngine(): TTSEngine {
            opens++
            throw IllegalStateException("corrupt model: session open failed")
        }
    }

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
        override suspend fun delete(key: String) {
            rows.remove(key)
        }
    }

    private fun settings(): AppSettings = AppSettings(SettingsStore(FakeSettingsDao()))

    /** Stages/unstages the model + voices packs and the espeak bundle the
     * real [KokoroRuntime.missingPrerequisites] guards probe. */
    private fun stagePacks(staged: Boolean) {
        val root = context.filesDir
        for (pack in listOf(KokoroPacks.model, KokoroPacks.voices)) {
            val target = PackCache(root).targetFile(pack)
            if (staged) {
                target.parentFile?.mkdirs()
                target.writeBytes(byteArrayOf(1))
            } else {
                target.delete()
            }
        }
        val espeakLib = File(root, "espeak/libespeak-ng.so")
        val espeakData = File(root, "espeak/espeak-ng-data")
        if (staged) {
            espeakLib.parentFile?.mkdirs()
            espeakLib.writeBytes(byteArrayOf(1))
            espeakData.mkdirs()
        } else {
            espeakLib.delete()
            espeakData.deleteRecursively()
        }
    }

    @Before
    fun setUp() {
        stagePacks(staged = false)
    }

    /** QW3 core: a first play before the async staging lands fails with the
     * missing-prerequisite reason; once the files exist the same runtime
     * opens, and the success clears the latched failure. */
    @Test
    fun `prerequisite failure retries once the packs are staged and success clears failure`() {
        val runtime = RetryRuntime(context, settings())

        assertNull("first play before the async pack staging lands", runtime.engine())
        assertTrue(
            "missing-prerequisite reason is reported: ${runtime.failureReason}",
            runtime.failureReason!!.contains("model pack not ready"),
        )
        assertEquals("a missing prerequisite never consumes the retry cap", 0, runtime.opens)

        stagePacks(staged = true) // the async staging completes
        assertSame(runtime.engine, runtime.engine())
        assertEquals("one successful open", 1, runtime.opens)
        assertNull("success clears the latched failure", runtime.failureReason)
    }

    /** The staging window may span several play taps; none of them may burn
     * the corrupt-model cap, or a slow staging would permanently disable the
     * engine — the QW3 regression this path defends. */
    @Test
    fun `repeated plays during the staging window still recover once the files exist`() {
        val runtime = RetryRuntime(context, settings())
        repeat(5) { assertNull(runtime.engine()) }
        assertTrue(runtime.failureReason!!.contains("model pack not ready"))

        stagePacks(staged = true)
        assertNotNull("staging-window taps must not burn the cap", runtime.engine())
        assertEquals(1, runtime.opens)
    }

    /** A genuinely corrupt model (files present, open throws): capped retries
     * per process, then terminal — play taps must not hot-loop the open. */
    @Test
    fun `genuine open failures are capped and stay terminal with the failure reason`() {
        stagePacks(staged = true) // files present → failures are open failures, not prerequisites
        val runtime = CorruptRuntime(context, settings())

        repeat(6) { assertNull(runtime.engine()) }

        assertEquals(
            "open attempted at most ${KokoroRuntime.MAX_FAILED_OPEN_ATTEMPTS} times",
            KokoroRuntime.MAX_FAILED_OPEN_ATTEMPTS,
            runtime.opens,
        )
        assertEquals("corrupt model: session open failed", runtime.failureReason)
    }
}