package io.github.moronigranja.ayvu.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.featureplayer.playback.AudioTrackPassageOutput
import io.github.moronigranja.ayvu.featureplayer.playback.PassageOutput
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.VoiceAudition
import io.github.moronigranja.ayvu.tts.audition.VoiceAuditionCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * C2 composition root: the ephemeral [PassageOutput] the audition coordinator
 * plays through (a standalone track — the book's service-private output is
 * never shared), and the [VoiceAudition] contract behind the coordinator so
 * Setup/Settings/Reader observe one audition state. The coordinator's engine
 * access goes through [VoiceAuditionCoordinator]'s [EngineSelector] seam; the
 * narration pause/resume is [PlayerCommands] (A5 single-writer).
 */
@Module
@InstallIn(SingletonComponent::class)
object AuditionModule {
    @Provides
    @Singleton
    fun provideAuditionOutput(): PassageOutput = AudioTrackPassageOutput()

    @Provides
    @Singleton
    fun provideVoiceAudition(
        selector: io.github.moronigranja.ayvu.featureplayer.playback.EngineSelector,
        output: PassageOutput,
        commands: PlayerCommands,
        appScope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        settings: AppSettings,
    ): VoiceAudition = VoiceAuditionCoordinator(selector, output, commands, appScope, ioDispatcher, settings)
}
