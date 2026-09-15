package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager surface for offline pre-generation (decisions #42) — the UI
 * never touches [PregenWorker] internals:
 *
 * - [pregenerate] starts a manual, unique run for one book; the optional
 *   [budgetMinutes] bounds the run to that much listening time AND anchors it
 *   to the book's current reading position (the next N minutes, decisions
 *   #132; null = whole book from the spine start, the pre-overlay default).
 *   KEEP: a tap while one is already queued does nothing.
 * - [workInfo] observes a book's manual job for the library-row progress.
 *
 * The overnight arm is gone (S1b): only [cancelOvernight] remains, to
 * neutralize a periodic job left behind by a pre-removal install.
 */
@Singleton
class PregenManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        // Lazy: the Hilt-provided Configuration.Provider (LocalTtsReaderApp) reads
        // the injected worker factory, so no WorkManager touch may happen DURING
        // injection — first use is after onCreate completes.
        private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

        /** Starts a manual run for one book; [budgetMinutes] bounds the run to that
         * much listening time of NEW audio (null = whole book, the pre-overlay
         * default). Unique KEEP: no-op while a run is already queued. */
        fun pregenerate(
            bookId: String,
            budgetMinutes: Long? = null,
        ) {
            val input =
                workDataOf(
                    PregenWorker.KEY_MODE to PregenWorker.MODE_MANUAL,
                    PregenWorker.KEY_BOOK_IDS to arrayOf(bookId),
                )
            val withBudget =
                budgetMinutes?.let { minutes ->
                    Data
                        .Builder()
                        .putAll(input)
                        .putLong(PregenWorker.KEY_BUDGET_MINUTES, minutes)
                        .build()
                } ?: input
            workManager.enqueueUniqueWork(
                PregenWorker.workName(bookId),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PregenWorker>()
                    .setInputData(withBudget)
                    .build(),
            )
        }

        fun cancel(bookId: String) {
            workManager.cancelUniqueWork(PregenWorker.workName(bookId))
        }

        /**
         * App-start neutralization (QW5d): the overnight scheduling arm is gone
         * (S1b), but a previously-enqueued overnight PeriodicWorkRequest survives
         * in WorkManager's DB and can still fire once after an upgrade — cancel
         * it deterministically at startup (LocalTtsReaderApp.onCreate). A fresh
         * install with nothing enqueued is a harmless no-op.
         */
        fun cancelOvernight() {
            workManager.cancelUniqueWork(PregenWorker.OVERNIGHT_NAME)
        }

        fun workInfo(bookId: String): LiveData<List<WorkInfo>> =
            workManager.getWorkInfosForUniqueWorkLiveData(PregenWorker.workName(bookId))

        /** Flow form of [workInfo] (WorkManager 2.10 Flow API) — the service's
         * coverage-refresh trigger for manual runs. */
        fun workInfoFlow(bookId: String): kotlinx.coroutines.flow.Flow<List<WorkInfo>> =
            workManager.getWorkInfosForUniqueWorkFlow(PregenWorker.workName(bookId))
    }
