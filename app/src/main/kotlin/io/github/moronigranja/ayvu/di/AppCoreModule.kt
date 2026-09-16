package io.github.moronigranja.ayvu.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.PlaybackCommandSender
import io.github.moronigranja.ayvu.WorkManagerPregenScheduler
import io.github.moronigranja.ayvu.featureplayer.playback.PregenStorage
import io.github.moronigranja.ayvu.player.OfflineStorage
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PregenScheduler
import javax.inject.Singleton

/**
 * A6 composition root: binds the player core contracts to their
 * implementations — the intent sender, the WorkManager pre-generation
 * scheduler, and the disk-tier storage façade. Features depend only on the
 * core contracts; only this module knows the implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppCoreModule {
    @Binds
    @Singleton
    abstract fun bindPlayerCommands(sender: PlaybackCommandSender): PlayerCommands

    @Binds
    @Singleton
    abstract fun bindPregenScheduler(scheduler: WorkManagerPregenScheduler): PregenScheduler

    @Binds
    @Singleton
    abstract fun bindOfflineStorage(storage: PregenStorage): OfflineStorage
}
