package io.github.moronigranja.ayvu.ui

/** [0..1] position → "%" (sub-1% keeps a decimal so early listening shows motion). */
fun formatPercent(fraction: Float): String {
    val percent = fraction * 100
    return if (percent < 1f) String.format("%.1f%%", percent) else "${percent.toInt()}%"
}

/** Always two decimals (operation progress: pre-generation, export). */
fun formatProgressPercent(fraction: Float): String = String.format("%.2f%%", fraction * 100f)
