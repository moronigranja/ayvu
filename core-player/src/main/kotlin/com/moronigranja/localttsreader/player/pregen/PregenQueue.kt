package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * T5 pre-generation (decisions #35/#review): synthesizes the passages ahead of
 * the playhead while the current one plays, so a passage change is a [take]
 * fast path instead of a synthesize-then-play gap. In-memory, bounded by both
 * a passage count ([lookahead]) and a time target ([lookaheadSeconds]),
 * backed by [PcmPassageCache] as the post-v1 disk tier.
 *
 * The time target is what keeps playback gap-free: Kokoro synthesizes faster
 * than real-time (RTF 0.66-0.76 on the S22, hard-facts), so an always-running
 * generator fills `lookaheadSeconds` of audio ahead of the playhead and, once
 * filled, keeps up with 1x playback (each second of listening frees ~0.3 s of
 * synthesis slack). A jump forward prunes stale audio and refills from the new
 * position.
 *
 * Contract:
 * - [ensure] walks the [BookLayout] after [PlayerPosition] and synthesizes
 *   the missing passages up to both bounds. Single-flight: concurrent callers
 *   serialize on the internal lock; [take] runs lock-free against the map so
 *   the play loop never waits on synthesis.
 * - Every freshly synthesized passage is handed to [onSynthesized] so the owner
 *   can persist it to the disk tier (the service wires it to `PcmPassageCache`).
 * - Entries at/before the playhead are pruned on [ensure]; a jump forward
 *   prunes the now-stale look-ahead and refills.
 * - A failed synthesis stops the look-ahead: nothing is queued past the gap,
 *   and the player's synchronous synthesize path is the typed-failure
 *   fallback — never a placeholder.
 */
class PregenQueue(
    private val book: Book,
    private val voice: String,
    private val speed: Double,
    private val synthesize: suspend (text: String) -> SynthesisOutcome,
    private val lookahead: Int = 5,
    /** Buffered audio target in seconds ahead of the playhead — the D1
     * horizon (decisions #155, "approximately 30-second audio horizon");
     * the service passes its horizon constant explicitly. */
    private val lookaheadSeconds: Double = 30.0,
    /** Handed every freshly synthesized [PregenAudio] so the owner can persist
     * it to the disk tier. The default no-ops for host tests. */
    private val onSynthesized: suspend (key: PregenKey, audio: PregenAudio) -> Unit = { _, _ -> },
    /** Engine whose voice/speed the queue synthesizes — part of the [PregenKey] cache path. */
    private val engine: String = PregenKey.DEFAULT_ENGINE,
) {
    private val planner = PregenPlanner(book, voice, speed, engine)
    private val lock = Object()
    private val entries = LinkedHashMap<PregenKey, PregenAudio>()
    private val inFlight = mutableSetOf<PregenKey>()

    /** Synthesizes the passages after [from] that fit within both bounds;
     *  concurrent callers share in-flight work instead of duplicating synthesis:
     *  the plan is a CONTIGUOUS prefix — the walk stops at the first passage
     *  another coroutine is already synthesizing, so a second caller never
     *  queues far-ahead audio past an unsynthesized near gap (a hole at the
     *  playhead makes the ahead-seconds target lie and stalls playback).
     *
     *  D1 (survive-seek): [rearm] lets the long-lived fill re-check the live
     *  playhead BETWEEN passages. When the playhead has jumped forward past
     *  the next planned passage, the plan yields instead of synthesizing the
     *  stale remainder — the next ensure re-prunes and re-plans from the new
     *  position, so a seek costs at most one in-flight passage instead of a
     *  cancelled fill + full restart. A backward (or unchanged) playhead keeps
     *  the plan: every planned key is still strictly after it. */
    suspend fun ensure(from: PlayerPosition, rearm: (() -> PlayerPosition?)? = null) {
        val plan = synchronized(lock) {
            entries.keys.removeAll { key -> !isAfter(key, from) }
            shrinkToBound()
            val missing = mutableListOf<PregenKey>()
            planner.plan(
                from = from.chapterIndex to from.passageIndex,
                // Contiguous-prefix + bounds, exactly the old loop's decisions:
                // stop at the first passage another coroutine is synthesizing
                // (never plan past an unsynthesized near gap) or at the
                // look-ahead count/time limits.
                stop = { _, _, key ->
                    key in inFlight ||
                        entries.size + missing.size >= lookahead ||
                        queuedSecondsLocked(from) >= lookaheadSeconds
                },
                shouldVisit = { _, _, key -> !entries.containsKey(key) && key !in missing },
                onCandidate = { _, _, key -> missing += key },
            )
            inFlight.addAll(missing)
            missing
        }
        try {
            for (key in plan) {
                // A cancelled fill must stop synthesizing: cancellation is
                // cooperative and synthesize blocks in the engine, so without
                // this check a cancelled caller finishes its whole plan.
                currentCoroutineContext().ensureActive()
                // D1: yield the stale remainder when the playhead overtook the
                // next planned passage mid-ensure (seek while synthesizing).
                val current = rearm?.invoke()
                if (current != null && !isAfter(key, current)) break
                val text = book.passageText(key.chapterIndex, key.passageIndex) ?: break
                val audio = convert(synthesize(text)) ?: break
                onSynthesized(key, audio)
                synchronized(lock) {
                    if (!entries.containsKey(key)) {
                        entries[key] = audio
                        shrinkToBound()
                    }
                }
                // Stop early once the time buffer is filled so callers (post-stop
                // fill, buffer-before-start) get enough audio promptly instead of
                // synthesizing the whole lookahead (long passages run minutes).
                if (aheadSeconds(from) >= lookaheadSeconds) break
            }
        } finally {
            synchronized(lock) { plan.forEach { inFlight.remove(it) } }
        }
    }

    /** Seconds of audio currently queued strictly after [from]. */
    fun aheadSeconds(from: PlayerPosition): Double =
        synchronized(lock) { queuedSecondsLocked(from) }

    /** Seconds of audio currently queued strictly after [from]. */
    private fun queuedSecondsLocked(from: PlayerPosition): Double =
        entries.entries
            .filter { isAfter(it.key, from) }
            .sumOf { it.value.pcm.size / 2.0 / it.value.sampleRateHz }

    /** The queued audio for the passage, consumed; null when not pre-generated. */
    fun take(chapterIndex: Int, passageIndex: Int): PregenAudio? =
        synchronized(lock) { entries.remove(PregenKey(book.id, chapterIndex, passageIndex, voice, speed, engine)) }

    /** The queued audio for the passage, NON-consuming — the boundary pre-arm
     * peeks the next passage's size/rate without dequeuing it. */
    fun peek(chapterIndex: Int, passageIndex: Int): PregenAudio? =
        synchronized(lock) { entries[PregenKey(book.id, chapterIndex, passageIndex, voice, speed, engine)] }

    val size: Int get() = synchronized(lock) { entries.size }

    private fun shrinkToBound() {
        while (entries.size > lookahead) entries.remove(entries.keys.last())
    }

    private fun isAfter(key: PregenKey, from: PlayerPosition): Boolean =
        key.chapterIndex > from.chapterIndex ||
            (key.chapterIndex == from.chapterIndex && key.passageIndex > from.passageIndex)

    private fun convert(outcome: SynthesisOutcome): PregenAudio? =
        (outcome as? SynthesisOutcome.Audio)?.let { PregenAudio(it.pcm, it.sampleRateHz, it.segments) }
}
