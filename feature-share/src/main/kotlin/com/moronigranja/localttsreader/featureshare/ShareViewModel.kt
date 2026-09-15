package com.moronigranja.localttsreader.featureshare

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The share screen's fence: nothing resolved yet, resolving, or a verdict. */
sealed interface ShareUiState {
    data object Idle : ShareUiState

    data object Resolving : ShareUiState

    data class Verdict(
        val resolution: ShareResolution,
    ) : ShareUiState
}

/**
 * S2: turns the incoming SEND intent into a [ShareUiState.Verdict]. Reads the
 * intent exactly once (a config change re-delivers the state, never re-runs
 * the pipeline); the image branch goes through [ImageDecoder] then the pure
 * [ShareSnippetResolver]. Process the verdict off-main — OCR is seconds.
 */
@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        application: Application,
        private val resolver: ShareSnippetResolver,
    ) : AndroidViewModel(application) {
        private val _state = MutableStateFlow<ShareUiState>(ShareUiState.Idle)
        val state: StateFlow<ShareUiState> = _state.asStateFlow()

        /** Resolves [extraText]/[extraStream]; safe to call once per intent. */
        fun resolve(
            extraText: String?,
            extraStream: Uri?,
            mimeType: String?,
        ) {
            if (_state.value !is ShareUiState.Idle) return
            val input =
                when {
                    mimeType?.startsWith("image/") == true && extraStream != null -> {
                        _state.value = ShareUiState.Resolving
                        viewModelScope.launch {
                            val image = ImageDecoder.decode(extraStream, getApplication<Application>().contentResolver)
                            val input =
                                if (image != null) {
                                    ShareInput.Image(image)
                                } else {
                                    _state.value =
                                        ShareUiState.Verdict(
                                            ShareResolution.Failed(
                                                message = "Could not read the shared image.",
                                                snippet = "",
                                            ),
                                        )
                                    null
                                }
                            if (input != null) verdict(resolver.resolve(input))
                        }
                        return
                    }
                    mimeType == null || mimeType.startsWith("text/") -> ShareInput.Text(extraText.orEmpty())
                    else -> return // not ours (some apps send weird types); stay quiet
                }
            _state.value = ShareUiState.Resolving
            viewModelScope.launch {
                verdict(resolver.resolve(input))
            }
        }

        private fun verdict(resolution: ShareResolution) {
            _state.value = ShareUiState.Verdict(resolution)
        }
    }
