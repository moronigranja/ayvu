package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * D1: the pre-generation notification's Stop action ends every manual run. The
 * requests must carry [PregenWorker.TAG_PREGEN] — the tag the cancel addresses
 * (`PregenManager.cancelAllRunning`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PregenTagCancelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var manager: PregenManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        manager = PregenManager(context)
    }

    @Test
    fun `a manual run carries the pregen tag the Stop action cancels by`() {
        manager.pregenerate("book-a")

        assertTrue(
            "every manual run carries the pregen tag: ${WorkManager.getInstance(context).workInfos("book-a").tags}",
            PregenWorker.TAG_PREGEN in WorkManager.getInstance(context).workInfos("book-a").tags,
        )
    }

    @Test
    fun `the Stop action's cancel covers every tagged run`() {
        // Parked work (a day of initial delay) so the tag-based cancel is
        // observable: WorkManager's test scheduler runs unconstrained work
        // immediately, and the worker cannot be constructed without its Hilt deps.
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                PregenWorker.workName("book-a"),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PregenWorker>()
                    .addTag(PregenWorker.TAG_PREGEN)
                    .setInitialDelay(1, TimeUnit.DAYS)
                    .build(),
            )
        assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(context).workInfos("book-a").state)

        manager.cancelAllRunning() // the Stop-action path (PregenCancelReceiver)

        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).workInfos("book-a").state)
    }

    private fun WorkManager.workInfos(bookId: String): WorkInfo = getWorkInfosForUniqueWork(PregenWorker.workName(bookId)).get().single()
}
