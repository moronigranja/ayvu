package io.github.moronigranja.ayvu.featurelibrary

import kotlin.math.roundToLong

/**
 * Parses the pre-generation dialog's free-form listening-time entry
 * (Phase K item 3 — the backend accepts any listening-minute budget
 * ([io.github.moronigranja.ayvu.player.PregenBudget.maxSeconds]); this is
 * the UI-only front door).
 *
 * Plain numbers are minutes ("90"); a unit makes hours and minutes combine
 * ("1h", "1h30", "1h30m", "90m", "1.5h"; comma decimals accepted). Returns
 * whole minutes, or null when nothing sensible parses (empty, unitless
 * fractions, zero/negative).
 */
fun parseListeningMinutes(input: String): Long? {
    val plain = input.trim().toLongOrNull()
    if (plain != null) return plain.takeIf { it > 0 }
    val match =
        Regex(
            """^\s*(?:(\d+(?:[.,]\d+)?)\s*(?:hours?|hrs?|hr|h))?\s*(?:(\d+(?:[.,]\d+)?)\s*((?:minutes?|mins?|min|m))?)?\s*$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(input) ?: return null
    val hoursText = match.groupValues[1]
    val minutesText = match.groupValues[2]
    val minutesUnit = match.groupValues[3]
    if (hoursText.isEmpty() && minutesText.isEmpty()) return null
    val hours = hoursText.replace(',', '.').toDoubleOrNull()?.times(60.0) ?: 0.0
    val minutes =
        when {
            // "1h" — no minute part at all
            minutesText.isEmpty() -> 0.0
            // unitless number ("90", "1.5"): integer minutes only — a fraction
            // without a unit is ambiguous
            minutesUnit.isEmpty() && hoursText.isEmpty() ->
                minutesText.toLongOrNull()?.toDouble() ?: return null
            else -> minutesText.replace(',', '.').toDoubleOrNull() ?: return null
        }
    return (hours + minutes).roundToLong().takeIf { it > 0 }
}
