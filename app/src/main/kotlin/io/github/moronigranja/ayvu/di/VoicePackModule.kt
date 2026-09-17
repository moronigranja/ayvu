package io.github.moronigranja.ayvu.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.player.VoicePackDownloader
import io.github.moronigranja.ayvu.tts.PackInstaller
import io.github.moronigranja.ayvu.tts.installPacks
import io.github.moronigranja.ayvu.tts.setup.SetupEnginePacks
import javax.inject.Singleton

/**
 * C2 composition root: the explicit voice-pack download the reader's voice
 * sheet surfaces while the ACTIVE engine's packs are missing — over the same
 * [PackRegistry] Setup and Settings use (one disk truth). Engine- and
 * voice-aware through the shared required-pack table (K2, decisions
 * #144/#156): the resolved Piper voice's model + config plus the shared
 * espeak bundle under piper-v1, Kokoro's three otherwise — the sheet's
 * download action must never fetch the wrong engine's packs.
 *
 * K3: the request runs as an observable, cancellable foreground operation
 * through the shared installer (progress + Stop), not a bare app-scope launch.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoicePackModule {
    @Provides
    @Singleton
    fun provideVoicePackDownloader(
        installer: PackInstaller,
        operations: OperationRunner,
        settings: AppSettings,
    ): VoicePackDownloader =
        object : VoicePackDownloader {
            override fun requestDownload(voice: String) {
                val prefs = settings.state.value
                operations.installPacks(installer, SetupEnginePacks.requiredIds(prefs.ttsEngine, voice))
            }
        }
}
