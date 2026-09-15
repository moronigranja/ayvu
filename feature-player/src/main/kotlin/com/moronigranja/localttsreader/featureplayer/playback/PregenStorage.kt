package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.player.OfflineStorage
import com.moronigranja.localttsreader.player.pregen.PcmPassageCache
import com.moronigranja.localttsreader.player.pregen.PregenSpaceEstimate
import com.moronigranja.localttsreader.player.pregen.PregenSpaceEstimator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage transparency façade (decisions #44): sizes and reclaims the
 * pre-generated audio tier the UI can show. Disk reads are cheap (file
 * stats); the room-left estimates are pure math over the cached parses.
 *
 * [deleteBook] honours the fast-path invariant: it cancels the book's queued
 * WorkManager work FIRST (a running worker would re-write passages right
 * after the delete), then removes the subtree. Active playback is unaffected
 * — a disk miss just falls back to synthesis.
 */
@Singleton
class PregenStorage
    @Inject
    constructor(
        private val pregenCache: PregenCache,
        private val manager: PregenManager,
        private val libraryStore: LibraryStore,
        private val settings: AppSettings,
    ) : OfflineStorage {
        private val estimator = PregenSpaceEstimator(pregenCache.cache)

        /** The shared disk tier (queries, book deletes). */
        val cache: PcmPassageCache get() = pregenCache.cache

        /** Bytes on disk per book (pcm + sidecar files); books without audio are absent. */
        override fun usageByBook(): Map<String, Long> = pregenCache.cache.usageByBook()

        /** Estimates for every cached book in one pass (one cachedBooks() query). */
        override suspend fun estimateAll(): Map<String, PregenSpaceEstimate> {
            val voice = settings.state.value.voice
            return libraryStore.cachedBooks().associate { it.id to estimator.estimate(it.toBook(), voice, 1.0) }
        }

        /** Reclaims one book's pre-generated audio: cancel queued work, then delete. */
        override fun deleteBook(bookId: String) {
            manager.cancel(bookId)
            pregenCache.cache.deleteBook(bookId)
        }
    }
