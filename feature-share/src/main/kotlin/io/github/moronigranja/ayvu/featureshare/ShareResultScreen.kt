package io.github.moronigranja.ayvu.featureshare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.moronigranja.ayvu.ui.AyvuSpacing
import io.github.moronigranja.ayvu.ui.LoadingState

/**
 * S2 result UX: "Found: book · chapter · passage" for a hit; a clear
 * not-found card (with the closest candidate dimmed as a hint); typed
 * failures. Close dismisses the share surface.
 */
@Composable
fun ShareResultScreen(
    onClose: () -> Unit,
    onListen: (ShareResolution.Found) -> Unit = {},
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(AyvuSpacing.XL),
            verticalArrangement = Arrangement.spacedBy(AyvuSpacing.LG),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is ShareUiState.Idle, is ShareUiState.Resolving -> {
                    LoadingState(
                        if (s is ShareUiState.Resolving) "Reading the library…" else "Preparing…",
                        Modifier.fillMaxWidth(),
                    )
                }
                is ShareUiState.Verdict -> VerdictContent(s.resolution, onClose, onListen)
            }
        }
    }
}

@Composable
private fun VerdictContent(
    resolution: ShareResolution,
    onClose: () -> Unit,
    onListen: (ShareResolution.Found) -> Unit,
) {
    when (resolution) {
        is ShareResolution.Found -> FoundCard(resolution, onListen)
        is ShareResolution.NotFound -> NotFoundCard(resolution)
        is ShareResolution.Failed ->
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(
                    Modifier.padding(AyvuSpacing.LG),
                    verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM),
                ) {
                    Text(
                        "Could not match the share.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        resolution.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("Close")
    }
}

@Composable
private fun FoundCard(
    found: ShareResolution.Found,
    onListen: (ShareResolution.Found) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(AyvuSpacing.LG), verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM)) {
            Text("Found in your library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(found.bookTitle, style = MaterialTheme.typography.titleLarge)
            Text(
                "${found.chapterTitle ?: "Chapter ${found.chapterIndex + 1}"} · Passage ${found.passageIndex + 1}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Match ${(found.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            // S3: open the book at this passage and start listening there.
            Button(onClick = { onListen(found) }, modifier = Modifier.fillMaxWidth()) {
                Text("Listen here")
            }
        }
    }
}

@Composable
private fun NotFoundCard(notFound: ShareResolution.NotFound) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(AyvuSpacing.LG), verticalArrangement = Arrangement.spacedBy(AyvuSpacing.SM)) {
            Text("No passage matched", style = MaterialTheme.typography.titleLarge)
            Text(
                when (notFound.reason) {
                    ShareResolution.Reason.BLANK -> "The shared text was empty. Share a quote from a book in your library."
                    ShareResolution.Reason.NO_READABLE_TEXT -> "No readable text came out of that image. Try a sharper screenshot."
                    ShareResolution.Reason.OCR_UNAVAILABLE -> "OCR languages aren't installed yet — add one in Settings, then share again."
                    ShareResolution.Reason.NO_MATCH -> "Nothing in your library reached the match threshold. Try a longer quote."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (notFound.snippet.isNotBlank()) {
                Text(
                    "\u201C${notFound.snippet.take(120)}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            notFound.closest?.let { closest ->
                Text(
                    "Closest: ${closest.bookTitle} (${(closest.confidence * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
