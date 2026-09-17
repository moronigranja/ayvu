package io.github.moronigranja.ayvu.featuresettings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.backup.BackupCodec
import io.github.moronigranja.ayvu.backup.BackupReadError
import io.github.moronigranja.ayvu.backup.BackupReadResult
import io.github.moronigranja.ayvu.locate.IndexLock
import io.github.moronigranja.ayvu.locate.IndexRebuilder
import io.github.moronigranja.ayvu.model.LibraryStore
import io.github.moronigranja.ayvu.persistence.AppSettings
import io.github.moronigranja.ayvu.persistence.BackupMergeResult
import io.github.moronigranja.ayvu.persistence.BackupStore
import io.github.moronigranja.ayvu.player.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface BackupUiState {
    data object Idle : BackupUiState

    data object Exporting : BackupUiState

    data object Restoring : BackupUiState

    data class Finished(
        val message: String,
        val isError: Boolean,
    ) : BackupUiState
}

/**
 * E1: one-shot SAF backup export/import (decisions #109). [export] snapshots
 * the Room state (+ optional book files) and writes the codec zip to the
 * user-picked [Uri]; [restore] reads the picked zip, merges it, then reloads
 * the settings mirror and resyncs the search index so restored books are
 * searchable without a relaunch. Any failure lands as a typed
 * [BackupUiState.Finished] error — never a partial merge.
 */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val backupStore: BackupStore,
        private val appSettings: AppSettings,
        private val libraryStore: LibraryStore,
        private val indexRebuilder: IndexRebuilder,
        private val indexLock: IndexLock,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
        val state: StateFlow<BackupUiState> = _state.asStateFlow()

        /** Export success toast ("N books" = the number in the archive).
         * The archive has no user-facing filename — the SAF picker provides it. */
        fun export(
            includeBooks: Boolean,
            destination: Uri,
        ) {
            _state.value = BackupUiState.Exporting
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        attempt {
                            val snapshot = backupStore.snapshot(includeBooks)
                            val bytes = BackupCodec.write(snapshot)
                            context.contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                                ?: error("cannot open output stream")
                            snapshot
                        }
                    }
                _state.value =
                    result.fold(
                        onSuccess = { snapshot ->
                            BackupUiState.Finished(
                                "Backup written \u2014 ${snapshot.library.size} books",
                                isError = false,
                            )
                        },
                        onFailure = { BackupUiState.Finished("Export failed: ${it.message}", isError = true) },
                    )
            }
        }

        /** Restores [source] into the local database with merge precedence:
         * local progress wins, restored settings overwrite matching keys, and
         * bookmarks/history merge idempotently. The archive is streamed in under
         * [BackupCodec]'s ceiling (a stream length is not trusted — only the bytes
         * actually delivered), and a typed read failure is surfaced as such; an
         * `Error` from a bomb never escapes to the UI. After the merge the settings
         * mirror and the search index are resynced (no relaunch needed). */
        fun restore(source: Uri) {
            _state.value = BackupUiState.Restoring
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        attempt {
                            val r =
                                context.contentResolver.openInputStream(source)?.use { BackupCodec.read(it) }
                                    ?: error("cannot open input stream")
                            when (r) {
                                is BackupReadResult.Ok -> {
                                    val merged = backupStore.merge(r.snapshot)
                                    appSettings.reload()
                                    // Same resync the app runs at startup — restored
                                    // books are searchable without a relaunch.
                                    indexLock.withExclusiveIndex {
                                        indexRebuilder.rebuild(libraryStore.cachedBooks())
                                    }
                                    merged
                                }
                                is BackupReadResult.Error -> error(reasonMessage(r.reason))
                            }
                        }
                    }
                _state.value =
                    result.fold(
                        onSuccess = { summary(it) },
                        onFailure = { BackupUiState.Finished("Restore failed: ${it.message}", isError = true) },
                    )
            }
        }

        /** Finished → Idle (the result dialog's OK dismisses the state). */
        fun consumeResult() {
            _state.value = BackupUiState.Idle
        }

        /**
         * Runs [block] and turns EVERY failure into a `Result` failure, `Error` included:
         * [BackupCodec] reports a zip bomb's `OutOfMemoryError` as a typed failure, and one
         * raised anywhere else on this path must not take the app down either. Cancellation
         * is not a failure — it propagates untouched.
         */
        private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }

        private fun summary(merged: BackupMergeResult) =
            BackupUiState.Finished(
                "Restored ${merged.booksAdded} books, ${merged.bookmarksAdded} bookmarks, " +
                    "${merged.progressRestored} resume points",
                isError = false,
            )

        private fun reasonMessage(reason: BackupReadError): String =
            when (reason) {
                is BackupReadError.NotAZip -> "not a valid backup file"
                is BackupReadError.MissingSection -> "archive is missing sections: ${reason.sections.joinToString()}"
                is BackupReadError.MalformedSection -> "corrupt archive (${reason.section})"
                is BackupReadError.UnsupportedVersion -> "unsupported archive version ${reason.version}"
                is BackupReadError.ArchiveTooLarge -> "backup file is too large"
                is BackupReadError.TooManyEntries -> "archive has too many entries"
                is BackupReadError.EntryTooLarge -> "archive entry is too large (${reason.name})"
                is BackupReadError.ExpandedTooLarge -> "archive expands too far"
                is BackupReadError.ManifestTooLarge -> "corrupt archive (manifest)"
                is BackupReadError.DuplicateEntry -> "corrupt archive (duplicate entry ${reason.name})"
                is BackupReadError.UnsafeBookFile -> "archive has an unsafe book file name"
                is BackupReadError.OutOfMemory -> "backup file is too large to open"
            }
    }
