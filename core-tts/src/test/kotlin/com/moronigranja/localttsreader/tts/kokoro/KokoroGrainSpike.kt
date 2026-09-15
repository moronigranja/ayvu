package com.moronigranja.localttsreader.tts.kokoro

import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.system.measureTimeMillis

/**
 * Spike A (decisions #31): sentence-grain synthesis vs one paragraph blob.
 *
 * T4 carry-over note 2 asks: "sentence-grain synthesis vs one PCM blob +
 * anchors for the highlight — affects engine output and future cache keys".
 * This harness measures the observable differences on the real pinned model:
 * duration drift, boundary pauses, per-call pad overhead, RTF, and window
 * counts — the inputs to the engine-contract decision.
 *
 * It also writes the on-device corpus for the spike-tts Kokoro benchmark
 * (decisions #30): raw espeak-ng phonemes per corpus text (before the
 * tokenizer's vocab filter, which the device pipeline applies exactly once).
 *
 * Usage: ./gradlew :core-tts:kokoroGrainSpike [-PkokoroCache=<dir>]
 */
fun main(args: Array<String>) {
    val cacheRoot =
        args.getOrNull(0)
            ?: File(System.getProperty("user.home"), ".cache/local-tts-reader/packs").absolutePath
    val cache = PackCache(File(cacheRoot))

    val engine =
        KokoroEngine.open(
            spec = DefaultEngines.kokoro,
            packs = KokoroPacks.all,
            modelFile = cache.targetFile(KokoroPacks.model),
            voicesFile = cache.targetFile(KokoroPacks.voices),
        )
    val phonemizer = EspeakPhonemizer.load()
    val tokenizer = KokoroTokenizer(KokoroVocabulary.resource())

    data class Passage(
        val language: String,
        val voice: String,
        val sentences: List<String>,
    ) {
        val blob: String get() = sentences.joinToString(" ")
    }

    val passages =
        listOf(
            Passage(
                "en-us",
                "af_heart",
                listOf(
                    "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                    "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered as the rightful property of some one or other of their daughters.",
                    "My dear Bennet, said his lady to him one day, have you heard that Netherfield Park is let at last?",
                    "Bennet replied that he had not.",
                    "But it is, returned she, for Long has just been here, and she told me all about it.",
                    "The day passed in the usual business of the neighbourhood, and the evening brought the whole party together again.",
                ),
            ),
            Passage(
                "pt-br",
                "pf_dora",
                listOf(
                    "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu.",
                    "Cumprimentou-me, sentou-se ao pé de mim, falou da lua e dos ministros, e acabou recitando-me versos.",
                    "A viagem era curta, e os versos pode ser que não fossem inteiramente maus.",
                    "Sucedeu, porém, que eu cansasse deles, e o rapaz falava com tal entusiasmo, que eu fiquei aborrecido.",
                ),
            ),
        )

    // Device corpus: raw phonemes (pre-vocab-filter) per passage blob, for
    // the spike-tts Kokoro benchmark on a phone without espeak-ng. The device
    // synthesizes the blob in one call (decision #31), so the passthrough
    // phonemizer must answer for the joined passage text, not its sentences.
    val deviceCorpus = ArrayList<String>()
    for (passage in passages) {
        val raw = phonemizer.phonemize(passage.blob, passage.language)
        deviceCorpus += "${passage.blob}\t${passage.language}\t$raw"
    }
    val corpusFile = File(cacheRoot, "kokoro-device-corpus.tsv")
    corpusFile.writeText(deviceCorpus.joinToString("\n") + "\n")
    println("device corpus: ${deviceCorpus.size} entries -> $corpusFile")

    for (passage in passages) {
        println("\n=== ${passage.language} / ${passage.voice} ===")

        val blobPhonemes = tokenizer.phonemize(phonemizer, passage.blob, passage.language)
        val sentencePhonemes = passage.sentences.map { tokenizer.phonemize(phonemizer, it, passage.language) }
        val blobWindows = PhonemeChunker.split(blobPhonemes).size
        val sentenceWindows = sentencePhonemes.sumOf { PhonemeChunker.split(it).size }
        println("windows: blob=$blobWindows, per-sentence sum=$sentenceWindows")

        val blob = synthesize(engine, passage.blob, passage.voice)
        writeWav(File(cacheRoot, "blob-${passage.language}.wav"), blob.audio, 24000)
        val sentences = passage.sentences.map { synthesize(engine, it, passage.voice) }

        val blobDur = blob.samples / SAMPLE_RATE
        val joinedDur = sentences.sumOf { it.samples } / SAMPLE_RATE
        val drift = joinedDur - blobDur
        println(
            "duration: blob=%.2fs joined=%.2fs drift=%+.2fs (%+.1f%%)".format(
                blobDur,
                joinedDur,
                drift,
                100.0 * drift / blobDur,
            ),
        )
        val joinedRtf = sentences.sumOf { it.milliseconds } / 1000.0 / joinedDur
        println(
            "rtf: blob=%.3f joined=%.3f (+%.1f%%)".format(
                blob.rtf,
                joinedRtf,
                100.0 * (joinedRtf - blob.rtf) / blob.rtf,
            ),
        )

        for ((index, sentence) in sentences.withIndex()) {
            println(
                "  s${index + 1}: %-3d words %5d samples (%6.2fs) %6d ms rtf=%.3f leading=%.0fms trailing=%.0fms".format(
                    passage.sentences[index].split(Regex("\\s+")).size,
                    sentence.samples,
                    sentence.samples / SAMPLE_RATE,
                    sentence.milliseconds,
                    sentence.rtf,
                    sentence.leadingQuiet() * 1000.0,
                    sentence.trailingQuiet() * 1000.0,
                ),
            )
        }

        // Boundary pauses: quiet runs >= 200 ms separate the ~250 ms sentence
        // pauses from the ~100 ms clause pauses the model renders.
        val blobBoundaries = blob.boundaryPauses()
        val joinedBoundaries = joinedBoundaries(sentences)
        println("boundary pauses: blob=${blobBoundaries.size}, joined=${joinedBoundaries.size}")
        println("  blob:   ${blobBoundaries.joinToString(", ") { "%.0fms".format(it * 1000.0) }}")
        println("  joined: ${joinedBoundaries.joinToString(", ") { "%.0fms".format(it * 1000.0) }}")

        // Decision #31 contract on the real model: sentence anchors from the
        // engine must land inside the rendered pauses — each interior anchor
        // boundary should sit at the start of a quiet run >= 150 ms (the
        // pause the mark caused), within ~50 ms (frame + trim tolerance).
        blob.segments?.let { segments ->
            require(segments.size == passage.sentences.size) {
                "anchor count ${segments.size} != sentences ${passage.sentences.size}"
            }
            // An interior boundary is the next sentence's first phoneme, which
            // sits at the END of the pause run the mark caused — compare
            // against run ends, not starts.
            val quietEnds =
                quietRuns(blob.audio)
                    .filter { it.seconds() >= 0.150 }
                    .map { it.endSample / SAMPLE_RATE }
            val drifts =
                segments.dropLast(1).map { boundary ->
                    quietEnds.minOf { kotlin.math.abs(it - boundary.endSeconds) }
                }
            println(
                "anchors: ${segments.size} spans, boundary-pause drift max=%.0f ms".format(
                    (drifts.maxOrNull() ?: 0.0) * 1000.0,
                ),
            )
            val starts = segments.map { "%.2f".format(it.startSeconds) }.joinToString(", ")
            println("  spans (s): $starts")
        } ?: println("anchors: NOT PRESENT (no duration output?)")

        // Per-sentence in-blob duration, when boundary alignment is clean.
        if (blobBoundaries.size == passage.sentences.size - 1) {
            print("  in-blob v standalone (ms): ")
            for (i in sentences.indices) {
                val stand = sentences[i].samples / SAMPLE_RATE
                val inBlob = if (i < blobBoundaries.size) blob.sentenceSpan(i, blobBoundaries) else null
                val delta = inBlob?.let { (stand - it) * 1000.0 }
                print(
                    "s${i + 1} stand=%.0f blob=%s (Δ%+d)  ".format(
                        stand * 1000.0,
                        inBlob?.let { "%.0f".format(it * 1000.0) } ?: "n/a",
                        delta?.toInt() ?: 0,
                    ),
                )
            }
            println()
        } else {
            println("  boundary count mismatch — in-blob split not attempted")
        }
    }
    engine.close()
}

private const val SAMPLE_RATE = 24000.0

private data class Synthesized(
    val milliseconds: Long,
    val samples: Int,
    val audio: FloatArray,
    val segments: List<SegmentAnchor>?,
) {
    val rtf: Double get() = milliseconds / 1000.0 / (samples / SAMPLE_RATE)

    fun leadingQuiet(): Double =
        quietRuns(audio).firstOrNull()?.let {
            if (it.startSample == 0) it.seconds() else 0.0
        } ?: 0.0

    fun trailingQuiet(): Double =
        quietRuns(audio).lastOrNull()?.let {
            if (it.endSample == audio.size) it.seconds() else 0.0
        } ?: 0.0

    /** Quiet runs >= 200 ms, as pause lengths in seconds, in order. */
    fun boundaryPauses(): List<Double> = quietRuns(audio).filter { it.seconds() >= 0.200 }.map { it.seconds() }
}

private data class QuietRun(
    val startSample: Int,
    val endSample: Int,
) {
    fun seconds(): Double = (endSample - startSample) / SAMPLE_RATE
}

private fun synthesize(
    engine: KokoroEngine,
    text: String,
    voice: String,
): Synthesized {
    var outcome: SynthesisOutcome? = null
    val millis =
        measureTimeMillis {
            outcome = runBlocking { engine.synthesize(SynthesisRequest(text, voice)) }
        }
    val audio =
        outcome as? SynthesisOutcome.Audio
            ?: error("synthesis failed for '$text': $outcome")
    val samples = FloatArray(audio.pcm.size / 2)
    val buffer = ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (i in samples.indices) samples[i] = buffer.get() / 32768.0f
    return Synthesized(millis, samples.size, samples, audio.segments)
}

private fun joinedBoundaries(sentences: List<Synthesized>): List<Double> {
    // A sentence call ends with its own sentence pause: take each sentence's
    // trailing quiet run, but only when it is a real pause (>= 100 ms), and
    // skip the last sentence (no join after it).
    return sentences.dropLast(1).map { it.trailingQuiet() }.filter { it >= 0.100 }
}

/**
 * Duration of sentence [index] inside the blob, measured between the centers
 * of the surrounding boundary pauses ([boundaries] in seconds from the start).
 */
private fun Synthesized.sentenceSpan(
    index: Int,
    boundaries: List<Double>,
): Double {
    val start = if (index == 0) boundaries.first() / 2.0 else (boundaries[index - 1] + boundaries[index]) / 2.0
    val end =
        if (index ==
            boundaries.size
        ) {
            boundaries.last() + (samples / SAMPLE_RATE - boundaries.last()) / 2.0
        } else {
            start +
                (boundaries[index] - boundaries[index - 1]) / 2.0
        }
    return end - start
}

private fun writeWav(
    file: File,
    audio: FloatArray,
    sampleRate: Int,
) {
    val header = java.io.ByteArrayOutputStream()

    fun writeLe(
        value: Int,
        bytes: Int,
    ) {
        for (i in 0 until bytes) header.write((value shr (8 * i)) and 0xFF)
    }
    writeLe(0x46464952, 4) // RIFF
    writeLe(36 + audio.size * 2, 4)
    writeLe(0x45564157, 4) // WAVE
    writeLe(0x20746d66, 4) // fmt
    writeLe(16, 4)
    writeLe(1, 2)
    writeLe(1, 2)
    writeLe(sampleRate, 4)
    writeLe(sampleRate * 2, 4)
    writeLe(2, 2)
    writeLe(16, 2)
    writeLe(0x61746164, 4) // data
    writeLe(audio.size * 2, 4)
    val out = java.io.ByteArrayOutputStream(audio.size * 2 + 44)
    out.write(header.toByteArray())
    val le =
        java.nio.ByteBuffer
            .allocate(audio.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (sample in audio) le.putShort((sample * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
    out.write(le.array())
    file.writeBytes(out.toByteArray())
}

private fun quietRuns(audio: FloatArray): List<QuietRun> {
    val frame = 240 // 10 ms at 24 kHz
    val peak = audio.maxOfOrNull { abs(it) } ?: 0f
    if (peak <= 0f) return emptyList()
    val threshold = peak * 10.0.pow(-40.0 / 20.0)
    val runs = mutableListOf<QuietRun>()
    var runStart = -1
    for (frameIndex in 0 until audio.size / frame) {
        val from = frameIndex * frame
        var max = 0f
        for (j in from until from + frame) max = maxOf(max, abs(audio[j]))
        val quiet = max < threshold
        if (quiet && runStart < 0) runStart = frameIndex
        if (!quiet && runStart >= 0) {
            runs += QuietRun(runStart * frame, frameIndex * frame)
            runStart = -1
        }
    }
    if (runStart >= 0) runs += QuietRun(runStart * frame, audio.size)
    return runs
}
