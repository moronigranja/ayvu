package io.github.moronigranja.ayvu.featureplayer.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.player.pregen.PcmPassageCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one disk-tier cache instance (decisions #35, #42): shared by the
 * playback fast path ([PlaybackService]) and the offline pre-generation
 * worker ([PregenWorker]) under `files/pregen/` — the [PregenKey] path
 * layout, so a book's pre-gen'ed audio is one `bookId` subtree.
 */
@Singleton
class PregenCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val cache: PcmPassageCache = PcmPassageCache(File(context.filesDir, "pregen"))
    }
