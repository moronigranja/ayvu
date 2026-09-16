package io.github.moronigranja.ayvu.tts.kokoro

import io.github.moronigranja.ayvu.tts.DefaultEngines
import io.github.moronigranja.ayvu.tts.DownloadOutcome
import io.github.moronigranja.ayvu.tts.JdkHttpTransport
import io.github.moronigranja.ayvu.tts.PackCache
import io.github.moronigranja.ayvu.tts.PackDownloader
import io.github.moronigranja.ayvu.tts.PackRegistry
import io.github.moronigranja.ayvu.tts.PackStatus
import io.github.moronigranja.ayvu.tts.SynthesisOutcome
import io.github.moronigranja.ayvu.tts.SynthesisRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * T2 RTF baseline: downloads the pinned Kokoro packs through the real
 * registry/downloader (the T1 stack), opens the engine and synthesizes the
 * same samples the reference python pipeline produced (oracle), reporting
 * wall-time RTF per sample plus pipeline invariants.
 *
 * Usage: `./gradlew :core-tts:kokoroBenchmark [-PkokoroCache=<dir> -PkokoroOracle=<dir>]`.
 * The oracle dir holds oracle_<lang>_<voice>.npy float32 references for
 * audio comparison.
 */
fun main(args: Array<String>) {
    val (cacheRoot, oracleDir) =
        when {
            args.size >= 2 -> args[0] to File(args[1])
            args.size == 1 -> args[0] to null
            else -> File(System.getProperty("user.home"), ".cache/local-tts-reader/packs").absolutePath to null
        }

    val cache = PackCache(File(cacheRoot))
    val registry = PackRegistry(cache, PackDownloader(cache, JdkHttpTransport()), DefaultEngines.descriptors)

    runBlocking {
        for (pack in KokoroPacks.all) {
            val status =
                registry.packs.value
                    .first { it.pack.id == pack.id }
                    .status
            if (status == PackStatus.Ready) {
                println("pack ${pack.id}: already verified on disk")
            } else {
                println("pack ${pack.id}: downloading (${pack.sizeBytes} bytes)")
                when (val outcome = registry.download(pack.id)) {
                    is DownloadOutcome.Ready -> println("pack ${pack.id}: ready")
                    is DownloadOutcome.AlreadyCached -> println("pack ${pack.id}: cached")
                    is DownloadOutcome.Failed -> error("pack ${pack.id} download failed: ${outcome.reason}")
                }
            }
        }
    }

    val engine =
        KokoroEngine.open(
            spec = DefaultEngines.kokoro,
            packs = KokoroPacks.all,
            modelFile = cache.targetFile(KokoroPacks.model),
            voicesFile = cache.targetFile(KokoroPacks.voices),
        )

    // Pipeline invariants against the pinned artifacts.
    val embedded = OrtKokoroSession.open(cache.targetFile(KokoroPacks.model)).use { it.embeddedVocab }
    val resource = KokoroVocabulary.resource()
    check(embedded == resource) { "embedded vocab diverges from the packaged resource" }
    println("vocab: ${embedded.size} entries, embedded == packaged")
    val voiceBank = KokoroVoiceBank.load(cache.targetFile(KokoroPacks.voices))
    println("voices: ${voiceBank.voiceNames.size} (${voiceBank.voiceNames.sorted().take(5).joinToString(", ")}…)")

    val samples =
        listOf(
            Triple("en-us", "af_heart", "Hello, world! This is a test of the Kokoro speech pipeline on the JVM."),
            Triple("pt-br", "pf_dora", "Olá! Este é um teste do motor de voz Kokoro em português brasileiro."),
            Triple("fr-fr", "ff_siwis", "Bonjour, ceci est un test du moteur de synthèse vocale."),
        )

    val phonemizer = EspeakPhonemizer.load()
    val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())
    for ((lang, voice, text) in samples) {
        // Phoneme-level fidelity: the same system espeak must produce the
        // reference pipeline's exact phonemes for the sample texts.
        val phonemes = tokenizer.phonemize(phonemizer, text, lang)
        println("phonemes[$lang/$voice]: $phonemes")

        var outcome: SynthesisOutcome? = null
        val millis =
            measureTimeMillis {
                outcome = runBlocking { engine.synthesize(SynthesisRequest(text, voice)) }
            }
        val result = outcome!!
        if (result is SynthesisOutcome.Failed) {
            println("SYNTHESIS FAILED[$lang/$voice]: ${result.reason}")
            continue
        }
        val audio = result as SynthesisOutcome.Audio
        val seconds = audio.pcm.size / 2.0 / KokoroEngine.SAMPLE_RATE
        println(
            "audio[$lang/$voice]: ${audio.pcm.size / 2} samples (${"%.2f".format(seconds)}s), " +
                "RTF ${"%.3f".format(millis / 1000.0 / seconds)}",
        )

        oracleDir?.let { dir ->
            val oracle = File(dir, "oracle_${lang}_$voice.npy")
            if (oracle.isFile) {
                val (correlation, maxDiff) = compareWithOracle(engine, text, voice, oracle)
                println("oracle[$lang/$voice]: max abs diff ${"%.2e".format(maxDiff)}, correlation ${"%.6f".format(correlation)}")
            } else {
                println("oracle[$lang/$voice]: ${oracle.name} not found, skipping audio comparison")
            }
        }
    }

    engine.close()
}

private fun compareWithOracle(
    engine: KokoroEngine,
    text: String,
    voice: String,
    oracleFile: File,
): Pair<Double, Double> {
    val outcome = runBlocking { engine.synthesize(SynthesisRequest(text, voice)) }
    val audio = outcome as SynthesisOutcome.Audio
    val actual = FloatArray(audio.pcm.size / 2)
    val buffer = ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (i in actual.indices) actual[i] = buffer.get() / 32768.0f

    val reference = readNpyFloats(oracleFile)
    val n = minOf(actual.size, reference.size)
    if (actual.size != reference.size) {
        println("  WARNING: length mismatch JVM=${actual.size} oracle=${reference.size}")
    }
    var maxDiff = 0.0f
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in 0 until n) {
        val d = abs(actual[i] - reference[i])
        if (d > maxDiff) maxDiff = d
        dot += actual[i].toDouble() * reference[i]
        normA += actual[i].toDouble() * actual[i]
        normB += reference[i].toDouble() * reference[i]
    }
    val correlation = if (n == 0 || normA == 0.0 || normB == 0.0) 0.0 else dot / sqrt(normA * normB)
    return correlation to maxDiff.toDouble()
}

private fun readNpyFloats(file: File): FloatArray {
    val data = file.readBytes()
    require(data.size >= 10 && data[0] == 0x93.toByte() && data[1] == 'N'.code.toByte()) { "not an npy file: $file" }
    val version = data[6].toInt()
    val headerLength =
        when (version) {
            1 -> (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
            else ->
                (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8) or
                    ((data[10].toInt() and 0xFF) shl 16) or ((data[11].toInt() and 0xFF) shl 24)
        }
    val offset = if (version == 1) 10 else 12
    val floats = FloatArray((data.size - offset - headerLength) / 4)
    ByteBuffer
        .wrap(data, offset + headerLength, floats.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()
        .get(floats)
    return floats
}
