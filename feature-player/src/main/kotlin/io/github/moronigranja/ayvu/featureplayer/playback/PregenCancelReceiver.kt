package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * D1: the pre-generation notification's Stop action (id 43). A foreground
 * notification cannot be swiped away, so this broadcast is the run's cancel
 * path — it ends every tagged manual run and leaves the already-cached audio on
 * disk.
 */
@AndroidEntryPoint
class PregenCancelReceiver : BroadcastReceiver() {
    @Inject lateinit var pregenManager: PregenManager

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == PregenWorker.ACTION_CANCEL_PREGEN) pregenManager.cancelAllRunning()
    }
}
