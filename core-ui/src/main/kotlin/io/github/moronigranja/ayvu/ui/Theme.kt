package io.github.moronigranja.ayvu.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Ayvu brand palette, light — TEAL-LED (decisions #95, owner call during the
 * B4 device pass): deep-teal primary, amber demoted to secondary/accents, on
 * warm cream surfaces. The M3-default `surfaceContainer*` ramp (lavender
 * #E6E0E9 — owner rejected it as "light blue" on the cards) is fully
 * overridden with a cream ramp. Error/error-container intentionally keep the
 * M3 defaults (no brand error tone was contrast-verified). Verified contrast:
 * ink/paper 13.6, white-on-#0B5F72 7.3, #0B5F72-on-paper 6.3,
 * white-on-#7A5200 6.9, #7A5200-on-paper 6.0.
 */
val AyvuLightColors =
    lightColorScheme(
        primary = Color(0xFF0B5F72),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCDEFF6),
        onPrimaryContainer = Color(0xFF042E39),
        secondary = Color(0xFF7A5200),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFBE0B8),
        onSecondaryContainer = Color(0xFF2A1A00),
        tertiary = Color(0xFF0B5F72),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFCDEFF6),
        onTertiaryContainer = Color(0xFF042E39),
        background = Color(0xFFF5EFE0),
        onBackground = Color(0xFF1B2430),
        surface = Color(0xFFF5EFE0),
        onSurface = Color(0xFF1B2430),
        surfaceVariant = Color(0xFFE7DEC9),
        onSurfaceVariant = Color(0xFF56503F),
        outline = Color(0xFF5F5745),
        outlineVariant = Color(0xFFCFC6AE),
        surfaceDim = Color(0xFFD8CFBA),
        surfaceBright = Color(0xFFFBF5E6),
        surfaceContainerLowest = Color(0xFFFFFEFA),
        surfaceContainerLow = Color(0xFFF4EEDF),
        surfaceContainer = Color(0xFFF0E8D7),
        surfaceContainerHigh = Color(0xFFE9DEC6),
        surfaceContainerHighest = Color(0xFFE0D2B6),
    )

/** Ayvu brand palette, dark: bright amber / light teal on ink. The
 * `surfaceContainer*` ramp is overridden with ink-family shades (decisions
 * #95) — M3's purple-gray defaults clash with the ink background. */
val AyvuDarkColors =
    darkColorScheme(
        primary = Color(0xFFE8A33D),
        onPrimary = Color(0xFF1B2430),
        primaryContainer = Color(0xFF5A3C00),
        onPrimaryContainer = Color(0xFFFBE0B8),
        secondary = Color(0xFF66C8E1),
        onSecondary = Color(0xFF042E39),
        secondaryContainer = Color(0xFF104D5C),
        onSecondaryContainer = Color(0xFFCDEFF6),
        tertiary = Color(0xFF66C8E1),
        onTertiary = Color(0xFF042E39),
        tertiaryContainer = Color(0xFF104D5C),
        onTertiaryContainer = Color(0xFFCDEFF6),
        background = Color(0xFF1B2430),
        onBackground = Color(0xFFF5EFE0),
        surface = Color(0xFF1B2430),
        onSurface = Color(0xFFF5EFE0),
        surfaceVariant = Color(0xFF2B3542),
        onSurfaceVariant = Color(0xFFCBC2AE),
        outline = Color(0xFF8A94A1),
        outlineVariant = Color(0xFF424B57),
        surfaceDim = Color(0xFF12171F),
        surfaceBright = Color(0xFF3D4856),
        surfaceContainerLowest = Color(0xFF0E1218),
        surfaceContainerLow = Color(0xFF232C38),
        surfaceContainer = Color(0xFF29323F),
        surfaceContainerHigh = Color(0xFF2F3947),
        surfaceContainerHighest = Color(0xFF354050),
    )

/** M3 default type scale; the single future override point for a branded face (B4 decision). */
val AyvuTypography: Typography = Typography()

val AyvuShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

object AyvuSpacing {
    val XS = 4.dp
    val SM = 8.dp
    val MD = 12.dp
    val LG = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

object AyvuMotion {
    const val STANDARD_MS = 300
}

/** Named surface elevations (B1 token list). The PlayerCard's 8.dp is the only consumer. */
object AyvuElevation {
    val Card = 8.dp
}

/** Brand theme wrapper. Dumb about [darkTheme] (hosts resolve their own
 * ThemeMode setting) but the single provider of [LocalReducedMotion] — the
 * system animator-duration-scale read happens here so no call site repeats it
 * (decisions #98). */
@Composable
fun AyvuTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(
            colorScheme = if (darkTheme) AyvuDarkColors else AyvuLightColors,
            typography = AyvuTypography,
            shapes = AyvuShapes,
            content = content,
        )
    }
}
