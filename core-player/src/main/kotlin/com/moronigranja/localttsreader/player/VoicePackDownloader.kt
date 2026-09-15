package com.moronigranja.localttsreader.player

/**
 * C2: the explicit voice-pack download action the shared selector's rows show
 * while the Kokoro packs are missing. Implemented at the composition root
 * (app) over the [com.moronigranja.localttsreader.tts.PackRegistry] — the
 * reader surface (feature-player) depends on this core contract, never on the
 * download machinery, so A6's feature-boundary rule holds.
 */
interface VoicePackDownloader {
    /** Starts the required Kokoro downloads (model + voices + espeak-ng). */
    fun requestDownload()
}
