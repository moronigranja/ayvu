package io.github.moronigranja.ayvu

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.moronigranja.ayvu.featurelibrary.BookExportOperations
import io.github.moronigranja.ayvu.featurelibrary.ExportStateHolder
import io.github.moronigranja.ayvu.featureplayer.playback.PregenManager
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.persistence.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level Hilt container; the DI root for feature modules (C5/C6).
 *
 * [onCreate] warms the search index from the cached parses (P2): the rebuild
 * reads the passages stored by the [LibraryStore] and **never re-parses** a
 * source file. It runs on the app [appScope] (IO, process-lifetime) and is
 * idempotent — a relaunch, an empty library, or a rebuild racing an import
 * all settle to "index mirrors the persisted library".
 */
@HiltAndroidApp
class AyvuApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var libraryStore: LibraryStore

    // C1.1: the settings mirror starts as all-defaults until reload(); setup
    // and process-start theme/voice/engine reads need the persisted values, so
    // reload alongside the index rebuild (order-independent).
    @Inject lateinit var appSettings: AppSettings

    @Inject lateinit var indexRebuilder: IndexRebuilder

    @Inject lateinit var indexLock: IndexLock

    @Inject lateinit var pregenManager: PregenManager

    // The device E2E drives the export operation through the same graph the
    // library row uses (the orchestrator + its state holder).
    @Inject lateinit var bookExports: BookExportOperations

    @Inject lateinit var exportStateHolder: ExportStateHolder

    @Inject lateinit var appScope: CoroutineScope

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // QW5d: the overnight pregen scheduling hook is disabled, but a
        // previously-enqueued overnight job survives in WorkManager's DB and
        // can still fire once after an upgrade — cancel it deterministically
        // (PregenManager.cancelOvernight; the manager's WorkManager is lazy,
        // so this does not touch the provider during injection).
        pregenManager.cancelOvernight()
        appScope.launch {
            appSettings.reload()
            // CR-3/A3: the rebuild reconciles UNDER the index lock — the fresh
            // Room snapshot is read inside the critical section, so a
            // concurrent import can neither be purged by a stale snapshot
            // nor published mid-reconciliation.
            indexLock.withExclusiveIndex {
                indexRebuilder.rebuild(libraryStore.cachedBooks())
            }
        }
    }
}
