package io.github.moronigranja.ayvu.player

/**
 * C2: the explicit voice-pack download action the shared selector's rows show
 * while the required packs are missing. Implemented at the composition root
 * (app) over the [io.github.moronigranja.ayvu.tts.PackRegistry] — the
 * reader surface (feature-player) depends on this core contract, never on the
 * download machinery, so A6's feature-boundary rule holds.
 */
interface VoicePackDownloader {
    /** Starts the named voice's required downloads under the active engine. */
    fun requestDownload(voice: String)
}
