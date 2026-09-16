package io.github.moronigranja.ayvu.featureplayer.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.player.pregen.TranslationService

/**
 * Binds the read-in-language text service to its Room-backed implementation.
 * feature-player's singletons are @Inject-constructor-bound (EngineSelector,
 * TranslateRuntime, …); only a service whose contract is an INTERFACE needs a
 * module binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranslationServiceModule {
    @Binds
    abstract fun bindTranslationService(impl: TranslationServiceImpl): TranslationService
}
