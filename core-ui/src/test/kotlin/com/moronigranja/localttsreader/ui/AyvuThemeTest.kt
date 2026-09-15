package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Locks the load-bearing B1 palette/token values (guards hex typos and regressions). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AyvuThemeTest {
    @Test
    fun lightThemePrimaryIsBrandTeal() {
        assertEquals(Color(0xFF0B5F72), AyvuLightColors.primary)
    }

    @Test
    fun lightThemeSecondaryIsBrandAmber() {
        assertEquals(Color(0xFF7A5200), AyvuLightColors.secondary)
    }

    @Test
    fun lightThemeCardContainerIsCreamNotM3Lavender() {
        // The owner rejected the M3-default lavender card surface (#E6E0E9)
        // during the B4 pass — lock the cream ramp (decisions #95); the card
        // tone was deepened after on-device feedback that #E6DCC6 lacked
        // separation from the #F5EFE0 background.
        assertEquals(Color(0xFFE0D2B6), AyvuLightColors.surfaceContainerHighest)
    }

    @Test
    fun lightThemeOnBackgroundIsInk() {
        assertEquals(Color(0xFF1B2430), AyvuLightColors.onBackground)
    }

    @Test
    fun darkThemePrimaryIsBrightAmber() {
        assertEquals(Color(0xFFE8A33D), AyvuDarkColors.primary)
    }

    @Test
    fun darkThemeBackgroundIsInk() {
        assertEquals(Color(0xFF1B2430), AyvuDarkColors.background)
    }

    @Test
    fun darkThemeOnBackgroundIsCream() {
        assertEquals(Color(0xFFF5EFE0), AyvuDarkColors.onBackground)
    }

    @Test
    fun largeShapeIs16Dp() {
        assertEquals(RoundedCornerShape(16.dp), AyvuShapes.large)
    }

    @Test
    fun spacingLGIs16Dp() {
        assertEquals(16.dp, AyvuSpacing.LG)
    }

    @Test
    fun motionStandardIs300Ms() {
        assertEquals(300, AyvuMotion.STANDARD_MS)
    }

    @Test
    fun elevationCardIs8Dp() {
        assertEquals(8.dp, AyvuElevation.Card)
    }

    @Test
    fun formatPercentKeepsDecimalUnderOnePercent() {
        assertEquals("0.5%", formatPercent(0.005f))
    }

    @Test
    fun formatPercentDropsDecimalAtOrAboveOnePercent() {
        assertEquals("42%", formatPercent(0.42f))
    }
}
