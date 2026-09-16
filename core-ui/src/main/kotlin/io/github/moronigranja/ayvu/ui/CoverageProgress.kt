package io.github.moronigranja.ayvu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.github.moronigranja.ayvu.player.CoverageSpan

/**
 * Torrent-download-manager-style book coverage bar (coverage-bar redo,
 * decisions #98 pattern): each span of the book is colored by whether its
 * audio is generated on disk, with chapter-start ticks and a static playhead
 * marker.
 *
 * Legend (referenced by PlayerCard's doc too): teal `primary` = generated
 * audio (on the disk tier for the current voice+speed), `surfaceVariant`
 * track = not generated, `outlineVariant` ticks = chapter starts, and the
 * `onSurface` marker = the playhead. The marker is the ONLY listened-position
 * indicator — the binary generated/not coloring never carries position
 * (the user's torrent analogy; the amber pregen-cushion segment is gone —
 * playback-time generation feedback moved to the system notification).
 *
 * Static by construction (no animation) — reduced-motion safe without a
 * guard, and cheap at the 1 Hz publication rate: one Canvas, no layout
 * passes per span.
 */
@Composable
fun CoverageProgress(
    coverageSpans: List<CoverageSpan>,
    chapterMarks: List<Float>,
    playheadFraction: Float,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val generated = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
    ) {
        // (1) Track — the not-generated color underneath everything.
        drawRect(track)
        // (2) Generated spans, each from its cumulative start to endFraction.
        var start = 0f
        for (span in coverageSpans) {
            val end = span.endFraction.coerceIn(0f, 1f)
            if (span.generated && end > start) {
                drawRect(
                    color = generated,
                    topLeft = Offset(size.width * start, 0f),
                    size = Size(size.width * (end - start), size.height),
                )
            }
            start = end
        }
        // (3) Chapter ticks, full height.
        for (mark in chapterMarks) {
            val x = size.width * mark.coerceIn(0f, 1f)
            drawLine(
                color = tick,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        // (4) Playhead marker, on top of everything.
        val x = size.width * playheadFraction.coerceIn(0f, 1f)
        drawLine(
            color = marker,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
