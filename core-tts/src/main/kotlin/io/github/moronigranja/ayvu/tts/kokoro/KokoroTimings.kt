package io.github.moronigranja.ayvu.tts.kokoro

import io.github.moronigranja.ayvu.tts.SegmentAnchor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** When a phoneme is spoken, in seconds from the start of the audio. */
data class Timing(
    val phoneme: Char,
    val start: Double,
    val end: Double,
)

/**
 * Timing bookkeeping from the model's per-token `duration` output, ported from
 * kokoro-onnx (`sliding.token_edges` / `timings`, `pauses.insert`). Only
 * consulted when the graph reports durations (the pinned v1.1 export does).
 */
object KokoroTimings {
    /**
     * Sample offset of every token boundary, the leading pad (BOS) included.
     * [duration] is the model's frame count per token.
     */
    fun tokenEdges(
        duration: IntArray,
        samples: Int,
    ): IntArray {
        val total = duration.fold(0L) { sum, frames -> sum + frames }
        if (total <= 0) return IntArray(duration.size + 1)
        val edges = IntArray(duration.size + 1)
        var cumulative = 0L
        for (i in duration.indices) {
            cumulative += duration[i]
            edges[i + 1] = (cumulative * samples / total.toDouble()).roundToInt()
        }
        return edges
    }

    /** Pairs each [phonemes] char with the audio span it occupies. */
    fun timings(
        phonemes: String,
        edges: IntArray,
        sampleRate: Int,
    ): List<Timing> {
        val result = ArrayList<Timing>(phonemes.length)
        for (i in phonemes.indices) {
            if (i + 1 >= edges.size) break
            result +=
                Timing(
                    phonemes[i],
                    edges[i].toDouble() / sampleRate,
                    edges[i + 1].toDouble() / sampleRate,
                )
        }
        return result
    }

    /** The pause the text asks for after [phoneme]. */
    fun wantedAfter(
        phoneme: Char,
        sentence: Double,
        clause: Double,
    ): Double =
        when (phoneme) {
            in SENTENCE_MARKS -> sentence
            in CLAUSE_MARKS -> clause
            else -> 0.0
        }

    // A frame this far below the loudest frame counts as part of a pause.
    // Measuring against the loudest sample instead reads speech as near
    // silence and pads gaps that were already long enough.
    private const val QUIET_DB = -40.0
    private const val FRAME = 0.01

    // The model usually renders the gap a mark causes just before the mark's
    // own timing ends, sometimes just after, so the pause is looked for on
    // both sides.
    private const val REACH = 0.15

    /** Characters that end a sentence (the read-along segmentation unit). */
    const val SENTENCE_MARKS = ".!?…"
    private const val CLAUSE_MARKS = ",;:"

    /**
     * Contiguous sentence spans over [timings] — the pause-shifted list from
     * [insertPauses], so the boundaries are exact positions in the final
     * audio: sentence *i* goes from its first phoneme's start to the next
     * sentence's first phoneme's start, the last to [totalSeconds]. Gap-free
     * by construction; a phoneme stream without sentence marks is one span.
     */
    fun sentenceSegments(
        timings: List<Timing>,
        totalSeconds: Double,
    ): List<SegmentAnchor> {
        if (timings.isEmpty()) return emptyList()
        val starts = ArrayList<Double>()
        starts += timings.first().start
        for (index in 1 until timings.size) {
            if (timings[index - 1].phoneme in SENTENCE_MARKS) starts += timings[index].start
        }
        val segments = ArrayList<SegmentAnchor>(starts.size)
        for (i in starts.indices) {
            val end = if (i + 1 < starts.size) starts[i + 1] else totalSeconds
            segments += SegmentAnchor(starts[i], end)
        }
        return segments
    }

    /**
     * Lengthens the pause after every mark and moves later timings along —
     * `pauses.insert` verbatim: only the quiet run the mark sits in counts,
     * and the silence is spliced *inside* it (cutting into sound to force a
     * gap is audible; a mark the model ran straight through is one it did not
     * mean to stop on).
     */
    fun insertPauses(
        audio: FloatArray,
        timings: List<Timing>,
        sampleRate: Int,
        sentence: Double,
        clause: Double,
    ): Pair<FloatArray, List<Timing>> {
        if (timings.isEmpty() || (sentence == 0.0 && clause == 0.0)) return audio to timings

        val frame = maxOf(1, (FRAME * sampleRate).toInt())
        val quiet = quietFrames(audio, frame)
        val reach = (REACH / FRAME).toInt()

        val parts = mutableListOf<FloatArray>()
        val moved = ArrayList<Timing>(timings.size)
        var cut = 0
        var shift = 0.0

        for (timing in timings) {
            moved += timing.copy(start = timing.start + shift, end = timing.end + shift)

            val target = wantedAfter(timing.phoneme, sentence, clause)
            if (target == 0.0) continue

            val at = (timing.end * sampleRate).toInt() / frame
            val run = runAround(quiet, at, reach)
            val runLength = run.last - run.first + 1
            val missing = target - runLength * FRAME

            // Splice inside the silence the model left; skip marks the model
            // ran straight through.
            if (runLength == 0 || missing <= 0.0) continue

            val middle = (run.start + runLength / 2) * frame
            parts += audio.copyOfRange(cut, middle)
            parts += FloatArray((missing * sampleRate).toInt())
            cut = middle
            shift += missing
        }

        if (parts.isEmpty()) return audio to timings
        parts += audio.copyOfRange(cut, audio.size)
        val total = parts.sumOf { it.size }
        val joined = FloatArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(joined, offset)
            offset += part.size
        }
        return joined to moved
    }

    private fun quietFrames(
        audio: FloatArray,
        frame: Int,
    ): BooleanArray {
        val usable = audio.size / frame * frame
        val frames = audio.size / frame
        val loudness = FloatArray(frames)
        var peak = 0.0f
        var i = 0
        while (i < usable) {
            var sum = 0.0
            val end = minOf(i + frame, usable)
            var j = i
            while (j < end) {
                val sample = audio[j]
                sum += sample.toDouble() * sample
                j++
            }
            val rms = kotlin.math.sqrt(sum / (end - i)).toFloat()
            loudness[i / frame] = rms
            if (rms > peak) peak = rms
            i = end
        }
        val threshold = peak * 10.0.pow(QUIET_DB / 20.0)
        return BooleanArray(frames) { loudness[it] <= threshold }
    }

    private fun runAround(
        quiet: BooleanArray,
        at: Int,
        reach: Int,
    ): IntRange {
        // The quiet run touching frame `at`, preferring frames closest to it
        // (stable tie-break: the smaller index wins, matching Python's sort).
        var inside = -1
        var bestDistance = Int.MAX_VALUE
        val from = maxOf(0, at - reach)
        val to = minOf(quiet.size - 1, at + reach)
        for (index in from..to) {
            if (!quiet[index]) continue
            val distance = abs(index - at)
            if (distance < bestDistance) {
                bestDistance = distance
                inside = index
            }
        }
        if (inside < 0) return at until at

        var start = inside
        while (start > 0 && quiet[start - 1]) start--
        var end = inside
        while (end + 1 < quiet.size && quiet[end + 1]) end++
        return start..end
    }
}
