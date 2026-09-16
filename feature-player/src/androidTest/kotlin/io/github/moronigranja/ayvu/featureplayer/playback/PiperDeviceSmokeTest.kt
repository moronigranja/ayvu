package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.EspeakPhonemizer
import io.github.moronigranja.ayvu.tts.kokoro.NormalizingPhonemizer
import io.github.moronigranja.ayvu.tts.piper.PiperEngine
import io.github.moronigranja.ayvu.tts.piper.PiperPacks
import io.github.moronigranja.ayvu.tts.piper.PiperVoices
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * D4 device smoke (decisions #154): the REAL `PiperEngine` on-device — packs
 * staged per build.md "D4 piper-v1 staging", the one-voice instance opened
 * over the staged files, one passage synthesized, 22.05 kHz PCM asserted and
 * RTF/PSS recorded with a WAV for listening. Staging args mirror the D1
 * scaffold: `-e stage /data/local/tmp/ayvu-d1`.
 */
@RunWith(AndroidJUnit4::class)
class PiperDeviceSmokeTest {
    companion object {
        const val TAG = "PiperSmoke"
    }

    private fun stage(from: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(from)
        check(src.isDirectory) { "stage dir missing: $from" }
        val cache = PackCache(context.filesDir)
        val model = cache.targetFile(PiperPacks.lessacModel)
        val config = cache.targetFile(PiperPacks.lessacConfig)
        model.parentFile?.mkdirs()
        config.parentFile?.mkdirs()
        File(src, "piper-lessac.onnx").copyTo(model, overwrite = true)
        File(src, "piper-lessac.onnx.json").copyTo(config, overwrite = true)
        val espeak = File(context.filesDir, "espeak").apply { mkdirs() }
        File(src, "libespeak-ng.so").copyTo(File(espeak, "libespeak-ng.so"), overwrite = true)
        val data = File(src, "espeak-ng-data")
        if (data.isDirectory) data.copyRecursively(File(espeak, "espeak-ng-data"), overwrite = true)
        Log.d(TAG, "staged piper lessac packs + espeak from $from")
    }

    @Test
    fun piperSynthesizesOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        args.getString("stage")?.let { stage(it) }
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir

        val cache = PackCache(context.filesDir)
        val model = cache.targetFile(PiperPacks.lessacModel)
        val config = cache.targetFile(PiperPacks.lessacConfig)
        assertTrue("piper model not staged at $model", model.isFile)
        assertTrue("piper config not staged at $config", config.isFile)

        val espeakLib = File(context.filesDir, "espeak/libespeak-ng.so")
        val espeakData = File(context.filesDir, "espeak/espeak-ng-data")
        assertTrue("espeak bundle not staged", espeakLib.isFile && espeakData.isDirectory)

        val openMs = System.currentTimeMillis()
        PiperEngine
            .open(
                spec = DefaultEngines.piper,
                packs = PiperPacks.all,
                voice = PiperVoices.LESSAC,
                modelFile = model,
                configFile = config,
                phonemizer =
                    NormalizingPhonemizer(
                        EspeakPhonemizer(libraryPath = espeakLib.absolutePath, dataPath = espeakData.absolutePath),
                    ),
                sessionFactory = { it.setIntraOpNumThreads(4) },
            ).use { engine ->
                val openTook = System.currentTimeMillis() - openMs
                val text =
                    "It is a truth universally acknowledged, that a single man in " +
                        "possession of a good fortune, must be in want of a wife."
                val t0 = System.currentTimeMillis()
                val outcome = runBlocking { engine.synthesize(SynthesisRequest(text, voice = PiperVoices.LESSAC)) }
                val wall = System.currentTimeMillis() - t0

                val audio = outcome as? SynthesisOutcome.Audio
                assertTrue("synthesis failed: $outcome", audio != null)
                // Pcm16 is core-tts-internal; decode the little-endian s16 mono here.
                val samples =
                    FloatArray(audio!!.pcm.size / 2) { i ->
                        ((audio.pcm[2 * i].toInt() and 0xFF) or (audio.pcm[2 * i + 1].toInt() shl 8)) / 32768f
                    }
                val sampleRate = audio.sampleRateHz
                var rms = 0.0
                for (s in samples) rms += s.toDouble() * s
                rms = sqrt(rms / samples.size)
                val audioS = samples.size / sampleRate.toDouble()
                val rtf = wall / 1000.0 / audioS

                val mem = Debug.MemoryInfo()
                Debug.getMemoryInfo(mem)
                val result =
                    JSONObject()
                        .put("device", Build.MODEL)
                        .put("voice", PiperVoices.LESSAC)
                        .put("open_ms", openTook)
                        .put("wall_ms", wall)
                        .put("audio_seconds", audioS)
                        .put("rtf", rtf)
                        .put("rms", rms)
                        .put("sample_rate", sampleRate)
                        .put("segments", audio.segments?.size ?: -1)
                        .put("total_pss_kb", mem.totalPss)
                        .put("vm_hwm_kb", readVmHwm())
                File(outDir, "d4_piper_smoke.json").writeText(result.toString(1))
                writeWav(File(outDir, "d4_piper_engine_smoke.wav"), samples, sampleRate)
                Log.d(
                    TAG,
                    "piper smoke: ${"%.2f".format(audioS)}s audio in $wall ms " +
                        "(RTF ${"%.3f".format(rtf)}), rms=${"%.4f".format(rms)}",
                )
                assertTrue("audio too short: $audioS s", audioS > 3.0)
            }
    }

    private fun writeWav(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
    ) {
        val dataSize = samples.size * 2
        val out =
            java.nio.ByteBuffer
                .allocate(44 + dataSize)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        out
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
        out
            .putInt(sampleRate)
            .putInt(sampleRate * 2)
            .putShort(2)
            .putShort(16)
        out.put("data".toByteArray()).putInt(dataSize)
        for (s in samples) out.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.writeBytes(out.array())
    }

    private fun readVmHwm(): Long =
        try {
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("VmHWM") }
                .split(Regex("\\s+"))[1]
                .toLong()
        } catch (_: Throwable) {
            -1
        }
}
