package io.github.moronigranja.ayvu.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.moronigranja.ayvu.ebook.BookImporter
import io.github.moronigranja.ayvu.ebook.ImportCoordinator
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.locate.TextIndex
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.player.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * A6 composition root: the app-wide import core providers (formerly
 * feature-library's ImportModule) — [TextIndex], the parse-only
 * [BookImporter], the [ImportCoordinator] (A3: durable-commit-then-index
 * under [IndexLock]), the launch-time [IndexRebuilder], the IO dispatcher
 * and the process-lifetime [CoroutineScope].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppCoreProvidesModule {
    @Provides
    @Singleton
    fun provideTextIndex(): TextIndex = TextIndex()

    @Provides
    @Singleton
    fun provideBookImporter(): BookImporter = BookImporter()

    /** CR-3/A3: serializes every index mutation (publish/remove/rebuild). */
    @Provides
    @Singleton
    fun provideIndexLock(): IndexLock = IndexLock()

    /** CR-3/A3: the one import orchestration boundary (parse → durable → index). */
    @Provides
    @Singleton
    fun provideImportCoordinator(
        importer: BookImporter,
        store: LibraryStore,
        index: TextIndex,
        indexLock: IndexLock,
    ): ImportCoordinator = ImportCoordinator(importer, store, index, indexLock)

    @Provides
    @Singleton
    fun provideIndexRebuilder(index: TextIndex): IndexRebuilder = IndexRebuilder(index)

    /** Process-lifetime scope — launch-time index rebuild, long-lived stores. */
    @Provides
    @Singleton
    fun provideAppScope(
        @IoDispatcher io: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + io)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
