package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Centered loading indicator + label (16.dp gap, matching the share screen).
 * Under reduced motion the animated spinner degrades to a static ring
 * (decisions #98) — the label keeps the state visible. */
@Composable
fun LoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AyvuSpacing.LG),
    ) {
        if (LocalReducedMotion.current) {
            StaticRing(Modifier.size(48.dp))
        } else {
            CircularProgressIndicator(Modifier.size(48.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
