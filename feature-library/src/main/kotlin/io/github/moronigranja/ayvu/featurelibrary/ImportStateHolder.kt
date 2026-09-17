package io.github.moronigranja.ayvu.featurelibrary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE import state every surface renders (the library overlay and the setup
 * wizard included): a singleton, not per-View-Model state, so an in-flight
 * import started from either surface is the same import — and the operation
 * body never captures a ViewModel.
 */
@Singleton
class ImportStateHolder
    @Inject
    constructor() {
        private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)

        val state: StateFlow<ImportUiState> = _state.asStateFlow()

        fun set(state: ImportUiState) {
            _state.value = state
        }
    }
