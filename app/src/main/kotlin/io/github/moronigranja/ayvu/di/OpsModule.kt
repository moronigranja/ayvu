package io.github.moronigranja.ayvu.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.ops.AndroidOperationRunner
import io.github.moronigranja.ayvu.ops.OperationRunner
import javax.inject.Singleton

/**
 * A6 composition root: binds the long-operation contract (core-ops) to the
 * Android implementation. Features depend only on [OperationRunner]; the
 * foreground service and notification plumbing stay in `app`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OpsModule {
    @Binds
    @Singleton
    abstract fun bindOperationRunner(impl: AndroidOperationRunner): OperationRunner
}
