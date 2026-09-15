package com.moronigranja.localttsreader.featureplayer.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * One passage's audio output — the seam the service plays through and tests
 * fake. [play] is non-blocking; the CALLER detects completion either via an
 * exact end marker armed with [setCompletionMarker] (verified reliable on the
 * S22, decisions #81) or by polling [positionSamples] against the frame count
 * as a fallback (the historical "static markers unreliable on some devices"
 * concern — kept because the marker path is device-dependent); [stop] cancels
 * playback, after which [positionSamples] reads 0.
 *
 * [speed] is applied via [AudioTrack.setPlaybackRate] — the audio system's
 * rate converter (no manual resampling, no interpolation artifacts). The
 * buffer holds book-time frames, so [positionSamples] counts book-time
 * frames regardless of [speed] (a 2× track reaches the same frame count in
 * half the physical time — decisions #52).
 */
interface PassageOutput {
    fun play(
        pcm: ByteArray,
        sampleRate: Int,
        speed: Double,
    )

    fun stop()

    val positionSamples: Int

    /** Linear output gain for every subsequent [play] (and re-applied when a
     * passage rebuilds the underlying track): 1.0 = unity, > 1.0 amplifies.
     * The platform clamps to `AudioTrack.getMaxVolume()`. */
    fun setVolume(multiplier: Float)

    /** One-shot end-of-buffer marker (decisions #81): [onReached] fires when
     * playback passes [frames]. Where MODE_STATIC markers are unreliable the
     * platform never fires it and the caller's [positionSamples] polling is
     * the fallback. Default no-op so test fakes compile unchanged. */
    fun setCompletionMarker(
        frames: Int,
        onReached: () -> Unit,
    ) = Unit

    /** Pre-builds the NEXT passage's track (decisions #84): the caller arms
     * the upcoming passage's size/rate while the current one plays, so the
     * boundary [play] swaps to a staged track instead of rebuilding on the
     * critical path (the measured 29-55 ms rebuild). The staging track is
     * used only when it matches the [play] arguments; a mismatch falls back
     * to a build. Default no-op so test fakes compile unchanged. */
    fun prearm(
        pcmBytes: Int,
        sampleRate: Int,
    ) = Unit
}

/** Real output: one MODE_STATIC [AudioTrack] retained across passages
 * (S4) — the track is re-fed in place when the new passage matches its
 * format AND static capacity, and rebuilt fresh on any mismatch. */
class AudioTrackPassageOutput : PassageOutput {
    private var track: AudioTrack? = null

    /** The pre-armed next track (decisions #84): built by [prearm] during the
     * current passage's playback so the boundary [play] swaps instead of
     * rebuilding on the critical path. Used only when it matches the [play]
     * arguments (rate + capacity); otherwise the standard build path runs. */
    private var staged: AudioTrack? = null
    private var markerCallback: (() -> Unit)? = null
    private var markerListenerAttached = false

    /** Last gain from [setVolume] — persisted so a rebuilt track (and every
     * new passage) re-applies it instead of resetting to unity. */
    private var volume = 1f

    private val markerListener =
        object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                // One-shot per passage: captured and cleared after firing.
                markerCallback?.let { cb ->
                    markerCallback = null
                    cb()
                }
            }

            override fun onPeriodicNotification(track: AudioTrack) = Unit
        }

    override fun prearm(
        pcmBytes: Int,
        sampleRate: Int,
    ) {
        val existing = staged
        // Rebuild only when the staged track doesn't already match the
        // upcoming passage — pre-arming twice for the same passage must be a
        // no-op (the loop re-arms after every play).
        if (existing == null || existing.sampleRate != sampleRate || existing.bufferSizeInFrames * 2 != pcmBytes) {
            staged?.let { it.release() }
            staged = buildTrack(pcmBytes, sampleRate)
        }
    }

    override fun play(
        pcm: ByteArray,
        sampleRate: Int,
        speed: Double,
    ) {
        val staged = staged
        // The staged track wins when it exactly matches this passage (rate +
        // static capacity) — the fast boundary handoff, no build here.
        if (staged != null && staged.sampleRate == sampleRate && staged.bufferSizeInFrames * 2 == pcm.size) {
            track?.let {
                it.pause()
                it.flush()
                it.release()
            }
            this.staged = null
            track = staged
        } else {
            // A mismatched staging is discarded (released) — the current
            // track stays and the standard reuse/build path runs.
            this.staged?.let { it.release() }
            this.staged = null
            val retained = track
            if (retained == null || !shouldReuse(retained, sampleRate, pcm.size)) {
                track?.let {
                    it.pause()
                    it.flush()
                    it.release()
                }
                track = buildTrack(pcm.size, sampleRate)
                markerListenerAttached = false
            } else {
                retained.stop()
                retained.flush()
            }
        }
        val active = track!!
        if (!markerListenerAttached) {
            runCatching { active.setPlaybackPositionUpdateListener(markerListener, Handler(Looper.getMainLooper())) }
            markerListenerAttached = true
        }
        active.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        runCatching { active.setPlaybackRate((sampleRate * speed.coerceAtLeast(0.1)).toInt().coerceIn(4_000, 192_000)) }
        active.setVolume(volume)
        active.play()
    }

    /** MODE_STATIC track sized to exactly one passage's PCM. */
    private fun buildTrack(
        pcmBytes: Int,
        sampleRate: Int,
    ): AudioTrack {
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcmBytes, 1))
            .build()
    }

    override fun stop() {
        track?.let { current ->
            current.pause()
            current.flush()
            current.release()
        }
        staged?.let { it.release() }
        staged = null
        track = null
        markerListenerAttached = false
        markerCallback = null
    }

    /** Monotonic played samples; 0 when idle. Safe for UI polling. */
    override val positionSamples: Int
        get() = track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.playbackHeadPosition else 0 } ?: 0

    override fun setVolume(multiplier: Float) {
        volume = multiplier.coerceAtLeast(0f)
        track?.setVolume(volume)
    }

    override fun setCompletionMarker(
        frames: Int,
        onReached: () -> Unit,
    ) {
        markerCallback = onReached
        val t = track ?: return
        if (!markerListenerAttached) {
            runCatching { t.setPlaybackPositionUpdateListener(markerListener, Handler(Looper.getMainLooper())) }
            markerListenerAttached = true
        }
        runCatching { t.setNotificationMarkerPosition(frames.coerceAtLeast(1)) }
    }
}

/**
 * Reuse decision for a retained [AudioTrack] (S4): keep the track only when
 * the new passage is identical in every property that defines a MODE_STATIC
 * track — sample rate, channel config, and the static buffer capacity. A
 * static track's frame count is fixed at construction and the audio server
 * plays to it, so a smaller re-write would replay stale tail audio and a
 * larger one cannot be written at all; only an exact-capacity re-feed is
 * faithful. [speed] is deliberately NOT part of the identity: the rate is
 * applied per play via [AudioTrack.setPlaybackRate], so one track serves
 * every speed of a matching passage (decisions #52).
 */
internal fun shouldReuse(
    current: AudioTrack,
    sampleRate: Int,
    pcmBytes: Int,
): Boolean =
    current.sampleRate == sampleRate &&
        current.channelConfiguration == AudioFormat.CHANNEL_OUT_MONO &&
        pcmBytes == current.bufferSizeInFrames * 2 // mono 16-bit: 2 bytes/frame
