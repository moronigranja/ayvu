package io.github.moronigranja.ayvu.ui

import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The B4 reduced-motion signal (decisions #98): Android's "Remove animations"
 * accessibility toggle zeroes the global ANIMATOR_DURATION_SCALE, but Compose
 * animations run on their own clock and ignore that scale — so motion-heavy
 * UI gates on this explicitly. [AyvuTheme] provides it app-wide.
 *
 * The degradation contract (roadmap B4): motion degrades WITHOUT hiding state
 * or controls — spinners become static rings (same size/stroke/tint), entrance
 * transitions snap, the "Generating…" copy stays.
 */
val LocalReducedMotion = compositionLocalOf { false }

/** Reads the system animator duration scale; true exactly when it is zeroed. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** The static loading ring reduced motion swaps an animated
 * [androidx.compose.material3.CircularProgressIndicator] for — same size
 * (via [modifier]), stroke and tint, minus the infinite rotation that
 * repaints e-ink panels forever. */
@Composable
fun StaticRing(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    Box(modifier.border(strokeWidth, color, CircleShape))
}
