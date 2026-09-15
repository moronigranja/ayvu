package com.moronigranja.localttsreader.di

import com.moronigranja.localttsreader.PlaybackCommandSender
import com.moronigranja.localttsreader.WorkManagerPregenScheduler
import com.moronigranja.localttsreader.featureplayer.playback.PregenStorage
import com.moronigranja.localttsreader.player.OfflineStorage
import com.moronigranja.localttsreader.player.PlayerCommands
import com.moronigranja.localttsreader.player.PregenScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
