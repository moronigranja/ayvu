package io.github.moronigranja.ayvu.tts

import io.github.moronigranja.ayvu.ops.OperationChannel
import io.github.moronigranja.ayvu.ops.OperationReporter
import io.github.moronigranja.ayvu.ops.OperationRunner
import io.github.moronigranja.ayvu.ops.OperationSpec
import kotlinx.coroutines.CancellationException

/** Stages whatever a verified pack unlocks (the concrete stagers live in
 *  core-player / core-ocr / core-translate; the app wires them). */
fun interface PackStager {
    suspend fun onPackReady(packId: String)
}

/** The batch finished with at least one failed pack (transfer or staging); the
 *  per-pack text lives in [PackRegistry.installFailed]. */
class OperationFailed(
    message: String,
) : Exception(message)

/**
 * The download entry point every surface shares (settings, setup, reader,
 * library): download each id in order, report progress through the running
 * operation's notification, then stage what the verified pack unlocks.
 *
 * A staging failure happens *after* the pack is `Ready`, so it is recorded in
 * [PackRegistry.installFailed] (a flow the rows observe) instead of being
 * swallowed or misread as a download failure.
 */
class PackInstaller(
    private val registry: PackRegistry,
    private val stager: PackStager,
) {
    /** The registry row's display name for [packId], for the operation title. */
    fun displayName(packId: String): String? =
        registry.packs.value
            .firstOrNull { it.pack.id == packId }
            ?.pack
            ?.displayName

    /** Downloads + stages each id in order, reporting progress. Throws
     *  [OperationFailed] after the batch when any pack failed. */
    suspend fun install(
        packIds: List<String>,
        reporter: OperationReporter,
    ) {
        var failures = 0
        for (packId in packIds) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: continue
            registry.clearInstallFailure(packId)
            reporter.report(pack.displayName, 0)
            val outcome =
                registry.download(packId) { done, total ->
                    val percent = percentOf(done, total)
                    reporter.report(
                        if (percent == null) "${pack.displayName} — downloading…" else "${pack.displayName} — $percent%",
                        percent,
                    )
                }
            when (outcome) {
                is DownloadOutcome.Failed -> {
                    registry.recordInstallFailure(packId, outcome.reason.shortMessage())
                    failures++
                }
                DownloadOutcome.Ready,
                DownloadOutcome.AlreadyCached,
                -> {
                    reporter.report("${pack.displayName} — unpacking…", null)
                    try {
                        stager.onPackReady(packId)
                        registry.recordStaged()
                    } catch (e: CancellationException) {
                        // Stop is not a staging failure: it must end the
                        // operation quietly, not post a failure notification
                        // (device-observed 2026-09-17: `runCatching` swallowed
                        // the cancellation and reported "1 pack(s) failed").
                        throw e
                    } catch (t: Throwable) {
                        registry.recordInstallFailure(packId, "unpacking failed: ${t.message}")
                        failures++
                    }
                }
            }
        }
        if (failures > 0) throw OperationFailed("$failures pack(s) failed — see Settings")
    }

    private fun percentOf(
        done: Long,
        total: Long,
    ): Int? = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
}

/**
 * The one call site all four download entry points (settings, setup, reader,
 * library) share: run [packIds] as a single observable, cancellable operation.
 * The spec id is the cancel address — `cancel("pack-download:<first id>")`.
 */
fun OperationRunner.installPacks(
    installer: PackInstaller,
    packIds: List<String>,
) {
    val first = packIds.firstOrNull() ?: return
    run(
        OperationSpec(
            id = "pack-download:$first",
            channel = OperationChannel.DOWNLOADS,
            title = installer.displayName(first)?.let { "Ayvu — $it" } ?: "Ayvu — download",
        ),
    ) { reporter -> installer.install(packIds, reporter) }
}
