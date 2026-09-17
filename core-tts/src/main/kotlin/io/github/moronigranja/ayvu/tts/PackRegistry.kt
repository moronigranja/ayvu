package io.github.moronigranja.ayvu.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The pack registry (T1): engine → pack → status, observable. This is the
 * surface the settings UI and engine wiring read — a missing language shows a
 * "download" action here, never a silent failure (hard-facts).
 *
 * - [packs] is the ordered status list (engine order, then declaration order).
 * - [download] starts (or resumes) one explicit download and coalesces
 *   concurrent requests for the same pack into a single transfer.
 *   Transient statuses ([PackStatus.Downloading], [PackStatus.Failed]) are
 *   emitted here; [refresh] recomputes statuses from disk truth.
 * - [isReady] is the engines' readiness gate (a `TTSEngine` synthesizes only
 *   when its required packs are ready).
 *
 * The registry is pure JVM; composition (which engines exist, where the cache
 * root is) happens at the app's Hilt wiring.
 */
class PackRegistry(
    private val cache: PackCache,
    private val downloader: PackDownloader,
    descriptors: List<EngineDescriptor>,
    /**
     * The dispatcher a transfer runs on. Injectable because a host test that
     * drives the registry from a virtual-time `TestDispatcher` cannot observe a
     * real `Dispatchers.IO` hop: the resumption lands in real time while the
     * test scheduler waits, which is how `SettingsViewModelTest` hung to its
     * 60 s timeout in CI (2026-09-17). Composition passes the app's
     * `@IoDispatcher`; the default keeps every other caller unchanged.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val descriptors: List<EngineDescriptor> = descriptors.toList()
    private val allPacks: List<TtsPack> =
        descriptors.flatMap { it.packs }.also {
            require(it.map(TtsPack::id).distinct().size == it.size) {
                "pack ids must be globally unique across engines"
            }
        }

    private val _packs = MutableStateFlow<List<PackState>>(emptyList())
    val packs: StateFlow<List<PackState>> = _packs.asStateFlow()

    /** Last attempt's failure per pack, held for the session so the UI can show it. */
    private val lastFailed = ConcurrentHashMap<String, DownloadFailureReason>()

    /**
     * Staging/staging-adjacent failures per pack ([PackInstaller] records them),
     * held for the session. A flow, not a plain map: a staging failure happens
     * after the pack is already `Ready`, so [packs] would not re-emit and the
     * row would never show the error. [refresh] never clears it.
     */
    private val installFailures = ConcurrentHashMap<String, String>()
    private val _installFailed = MutableStateFlow<Map<String, String>>(emptyMap())
    val installFailed: StateFlow<Map<String, String>> = _installFailed.asStateFlow()

    /**
     * Bumped after each staged pack. Surfaces that derive readiness from the
     * filesystem (espeak-ng lib+data, the tess-two data dir, the translate
     * bundle) need that re-evaluation, and staging changes no [packs] entry —
     * the pack is already `Ready` — so nothing else would re-emit.
     */
    private val _stagedTick = MutableStateFlow(0)
    val stagedTick: StateFlow<Int> = _stagedTick.asStateFlow()

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<DownloadOutcome>>()

    init {
        refresh()
    }

    fun engines(): List<EngineDescriptor> = descriptors

    fun packsFor(engineId: String): List<PackState> = _packs.value.filter { it.pack.engineId == engineId }

    /** Ready gate for engines: true iff the pack is verified on disk. */
    fun isReady(packId: String): Boolean = _packs.value.firstOrNull { it.pack.id == packId }?.status == PackStatus.Ready

    /** Records a staging failure for [packId] (the flow's only writer besides [clearInstallFailure]). */
    fun recordInstallFailure(
        packId: String,
        message: String,
    ) {
        installFailures[packId] = message
        _installFailed.value = installFailures.toMap()
    }

    /** Clears [packId]'s staging failure — a fresh install attempt is about to run. */
    fun clearInstallFailure(packId: String) {
        if (installFailures.remove(packId) != null) _installFailed.value = installFailures.toMap()
    }

    /** Records that a pack's staging step finished (see [stagedTick]). */
    fun recordStaged() {
        _stagedTick.value += 1
    }

    /** Recomputes statuses from disk truth, preserving the session's last failure. */
    fun refresh() {
        _packs.value = allPacks.map { PackState(it, statusOf(it)) }
    }

    private fun statusOf(pack: TtsPack): PackStatus =
        when {
            cache.isVerified(pack) -> PackStatus.Ready
            inFlight.containsKey(pack.id) -> PackStatus.Downloading(cache.downloadedBytes(pack), pack.sizeBytes)
            lastFailed.containsKey(pack.id) -> PackStatus.Failed(lastFailed.getValue(pack.id))
            else -> PackStatus.NotDownloaded
        }

    /**
     * Explicit, user-initiated download of [packId] (resumes a partial,
     * verifies, caches). Concurrent calls for the same pack join the running
     * transfer and share its outcome. Cancellation propagates and leaves the
     * partial on disk for the next attempt.
     */
    suspend fun download(
        packId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val pack =
            allPacks.firstOrNull { it.id == packId }
                ?: error("unknown pack: $packId")
        val deferred = CompletableDeferred<DownloadOutcome>()
        val existing = inFlight.putIfAbsent(packId, deferred)
        if (existing != null) return existing.await() // join the running transfer
        try {
            deferred.complete(performDownload(pack, onProgress))
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(packId, deferred)
            refresh() // disk truth: partials, new markers
        }
        return deferred.await()
    }

    private suspend fun performDownload(
        pack: TtsPack,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome {
        update(pack, PackStatus.Downloading(cache.downloadedBytes(pack), pack.sizeBytes))
        val outcome =
            withContext(ioDispatcher) {
                downloader.download(pack) { downloaded, total ->
                    onProgress(downloaded, total)
                    update(pack, PackStatus.Downloading(downloaded, total))
                }
            }
        when (outcome) {
            is DownloadOutcome.Failed -> lastFailed[pack.id] = outcome.reason
            else -> lastFailed.remove(pack.id)
        }
        update(
            pack,
            when (outcome) {
                is DownloadOutcome.Failed -> PackStatus.Failed(outcome.reason)
                else -> PackStatus.Ready
            },
        )
        return outcome
    }

    private fun update(
        pack: TtsPack,
        status: PackStatus,
    ) {
        _packs.value = _packs.value.map { if (it.pack.id == pack.id) PackState(it.pack, status) else it }
    }
}
