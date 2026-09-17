package io.github.moronigranja.ayvu.featuresettings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.moronigranja.ayvu.player.IoDispatcher
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.SectionHeader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The Licences subpane: reading the bundled assets is a background IO read,
 * so the pane says so while it runs. */
sealed interface LicencesUiState {
    data object Loading : LicencesUiState

    /** One entry per bundled document, in [LicenceDocuments.all] order. */
    data class Ready(
        val documents: List<LicenceText>,
    ) : LicencesUiState
}

/**
 * Release 0.1.1 (audit B): reads the licence documents the APK carries
 * (`:app`'s `copyLicenceAssets` → `assets/LICENSE` + `assets/NOTICE.md`) off
 * the main thread, once per screen instance.
 */
@HiltViewModel
class LicencesViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow<LicencesUiState>(LicencesUiState.Loading)
        val state: StateFlow<LicencesUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val reader = LicenceReader { name -> context.assets.open(name) }
                _state.value = withContext(ioDispatcher) { LicencesUiState.Ready(reader.readAll()) }
            }
        }
    }

/**
 * The About → Licences surface: the GPL-3.0 text and the third-party notices
 * exactly as this build ships them, readable offline. It adds to the About
 * group — the outbound source/notices links stay for the online reader.
 *
 * Each document is one heading plus its own lines, lazily composed so a
 * 34 KB licence is not laid out in one pass. A document that could not be read
 * renders its typed failure in the error color; the screen never shows a blank
 * page where the licence should be.
 */
@Composable
internal fun LicencesPane(
    padding: PaddingValues,
    viewModel: LicencesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(AyvuSpacing.LG),
    ) {
        when (val ui = state) {
            LicencesUiState.Loading ->
                item {
                    Text(
                        "Reading the bundled licences…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
                    )
                }

            is LicencesUiState.Ready ->
                ui.documents.forEach { licence ->
                    item(key = "licence:${licence.document.assetName}") {
                        SectionHeader(
                            licence.document.title,
                            Modifier.padding(top = AyvuSpacing.LG, bottom = AyvuSpacing.XS),
                        )
                    }
                    when (licence) {
                        is LicenceText.Loaded ->
                            items(licence.text.lines()) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
                                )
                            }

                        is LicenceText.Failed ->
                            item(key = "licence-failure:${licence.document.assetName}") {
                                Text(
                                    "Not readable in this build: ${licence.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = AyvuSpacing.XS),
                                )
                            }
                    }
                }
        }
    }
}
