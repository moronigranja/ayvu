package io.github.moronigranja.ayvu.tts.piper

import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.DownloadOutcome
import io.github.moronigranja.ayvu.tts.JdkHttpTransport
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import io.github.moronigranja.ayvu.tts.kokoro.EspeakPhonemizer
import io.github.moronigranja.ayvu.tts.kokoro.NormalizingPhonemizer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/**
 * D4 smoke: the real [PiperEngine] end-to-end on the JVM — the pinned lessac
 * packs download/verify through the real registry (the pack flow), the engine
 * opens over them, and one passage synthesizes through host espeak-ng 1.52 →
 * the verified id rule → the real ORT VITS session. Reports duration, finiteness
 * and wall-time RTF, and writes a playable WAV next to the report.
 *
 * Usage: `./gradlew :core-tts:piperSmoke [-PpiperCache=<dir>]`
 * (cache defaults to ~/.cache/local-tts-reader/packs; the 63 MB model downloads
 * once through the pinned descriptor).
 */
fun main(args: Array<String>) {
    val cacheRoot = args.firstOrNull() ?: File(System.getProperty("user.home"), ".cache/local-tts-reader/packs").absolutePath
    val cache = PackCache(File(cacheRoot))
    val registry = PackRegistry(cache, PackDownloader(cache, JdkHttpTransport()), DefaultEngines.descriptors)

    val voice = PiperVoices.LESSAC
    val packs = PiperPacks.forVoice(voice)
    runBlocking {
        for (pack in packs) {
            val status =
                registry.packs.value
                    .first { it.pack.id == pack.id }
                    .status
            if (status == PackStatus.Ready) {
                println("pack ${pack.id}: already verified on disk")
            } else {
                println("pack ${pack.id}: downloading (${pack.sizeBytes} bytes)")
                when (val outcome = registry.download(pack.id)) {
                    is DownloadOutcome.Ready -> println("pack ${pack.id}: ready (sha256 verified)")
                    is DownloadOutcome.AlreadyCached -> println("pack ${pack.id}: cached")
                    is DownloadOutcome.Failed -> error("pack ${pack.id} download failed: ${outcome.reason}")
                }
            }
        }
    }

    val engine =
        PiperEngine.open(
            spec = DefaultEngines.piper,
            packs = packs,
            voice = voice,
            modelFile = cache.targetFile(PiperPacks.lessacModel),
            configFile = cache.targetFile(PiperPacks.lessacConfig),
            phonemizer = NormalizingPhonemizer(EspeakPhonemizer.load()),
        )

    // The opening of Pride and Prejudice — the same en-US text the D4 probe
    // measured on the HiBreak (docs/corpus/corpus.tsv line 1's source).
    val text =
        "It is a truth universally acknowledged, that a single man in possession of a " +
            "good fortune, must be in want of a wife. However little known the feelings or " +
            "views of such a man may be on his first entering a neighbourhood, this truth is " +
            "so well fixed in the minds of the surrounding families, that he is considered " +
            "as the rightful property of some one or other of their daughters."

    // Warmup pays the first inference (graph init); the measured pass is the
    // second synthesis of the same passage, like the D4 probe's runs.
    runBlocking { engine.synthesize(SynthesisRequest("Hello, world!", voice)) }

    var outcome: SynthesisOutcome? = null
    val millis = measureTimeMillis { outcome = runBlocking { engine.synthesize(SynthesisRequest(text, voice)) } }
    when (val result = outcome!!) {
        is SynthesisOutcome.Failed -> error("synthesis failed: ${result.reason}")
        is SynthesisOutcome.Unavailable -> error("packs not ready")
        is SynthesisOutcome.Audio -> {
            val floats = pcmFloats(result.pcm)
            val seconds = result.pcm.size / 2.0 / result.sampleRateHz
            check(result.sampleRateHz == 22_050) { "sample rate must be the voice json's 22050" }
            check(result.segments == null) { "segments must be null (decisions #30b)" }
            val finite = floats.all { it.isFinite() }
            println(
                "audio[$voice]: ${floats.size} samples (${"%.2f".format(seconds)}s @ ${result.sampleRateHz} Hz), " +
                    "finite=$finite, segments=${result.segments}, " +
                    "RTF ${"%.3f".format(millis / 1000.0 / seconds)}",
            )
            // docs/prints/ evidence is repo-rooted; gradle runs this from the module dir.
            val wav = File(repoRoot(), "docs/prints/d4/piper-engine-smoke.wav")
            wav.parentFile?.mkdirs()
            writeWav(wav, floats, result.sampleRateHz)
            println("wav written: $wav")
        }
    }
    engine.close()
}

/** 16-bit little-endian PCM bytes back to floats in [-1, 1). */
private fun pcmFloats(pcm: ByteArray): FloatArray {
    val floats = FloatArray(pcm.size / 2)
    val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (i in floats.indices) floats[i] = shorts.get() / 32768.0f
    return floats
}

/** Minimal 16-bit mono PCM WAV writer for the listening set. */
private fun writeWav(
    file: File,
    floats: FloatArray,
    sampleRate: Int,
) {
    val pcm = ByteArray(floats.size * 2)
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (f in floats) buffer.put((f * 32767.0f).toInt().toShort())
    val total = 44 + pcm.size
    RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        out.write(
            byteArrayOf(
                'R'.code.toByte(),
                'I'.code.toByte(),
                'F'.code.toByte(),
                'F'.code.toByte(),
            ) +
                leInt(total - 8) +
                byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()) +
                byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()) +
                leInt(16) +
                leShort(1) +
                leShort(1) +
                leInt(sampleRate) +
                leInt(sampleRate * 2) +
                leShort(2) +
                leShort(16) +
                byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()) +
                leInt(pcm.size),
        )
        out.write(pcm)
        check(out.length() == total.toLong()) { "wav header wrote ${out.length()} of $total bytes" }
    }
}

private fun leInt(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

private fun leShort(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

/** Walks up from the working directory to the repo root (has .git). */
private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null && !File(dir, ".git").isDirectory) dir = dir.parentFile
    return dir ?: File(".")
}
