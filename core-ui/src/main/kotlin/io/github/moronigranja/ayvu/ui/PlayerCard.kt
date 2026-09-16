package io.github.moronigranja.ayvu.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.moronigranja.ayvu.player.PlaybackUiState
import io.github.moronigranja.ayvu.player.PlayerCommands
import io.github.moronigranja.ayvu.player.PlayerPhase
import java.io.File

/**
 * The app-wide docked player card (decisions #53, #94): cover thumb, title,
 * subtitle (authors · chapter · passage, or "Generating…" while the engine
 * loads), torrent-style book coverage with elapsed / % / remaining-at-speed,
 * and the transport row — −30s · play/pause (spinner while synthesizing) ·
 * +30s, all three at a uniform 48.dp height (M3 minimum touch target).
 *
 * Bar color legend ([CoverageProgress]): teal `primary` = generated audio
 * (on disk for the current voice+speed), `surfaceVariant` track = not
 * generated, `outlineVariant` ticks = chapter starts, `onSurface` marker =
 * the playhead. The amber pregen-cushion segment is gone — playback-time
 * generation feedback is the system notification; the card keeps the spinner
 * while the engine loads. State comes from the
 * service-published [PlaybackUiState], commands go through [PlayerCommands];
 * [topRight]/[badge] let the library add its row actions + offline usage;
 * [onOpen] makes the cover/title area open the book.
 */
@Composable
fun PlayerCard(
    state: PlaybackUiState,
    commands: PlayerCommands,
    modifier: Modifier = Modifier,
    /** Library-only surface (decisions #56): overflow actions (pre-gen, delete,
     * remove) drawn at the title row's right, and the offline disk badge drawn
     * under the progress bar. The reader passes nothing. */
    topRight: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    /** Opens the book (library card: the replaced row was tappable). */
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var cover by remember(state.bookId) { mutableStateOf(decodeCover(context, state.bookId)) }
    val loading = state.phase == PlayerPhase.LOADING
    val playing = state.phase == PlayerPhase.PLAYING || loading
    val subtitle =
        when {
            loading -> "Generating…"
            state.authors.isNotEmpty() ->
                state.authors.joinToString(", ") + " · Ch " + (state.chapterIndex + 1) + " · P " + (state.passageIndex + 1)
            else -> "Ch " + (state.chapterIndex + 1) + " · P " + (state.passageIndex + 1)
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = AyvuElevation.Card),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(AyvuSpacing.SM)
                    .then(if (onOpen != null) Modifier.clickable(onClick = onOpen!!) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                bitmap = cover,
                fallbackInitial = state.bookTitle,
                contentDescription = state.bookTitle,
                modifier = Modifier.size(width = 48.dp, height = 64.dp),
            )
            Spacer(Modifier.width(AyvuSpacing.SM))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (topRight != null) topRight()
                }
                if (state.degraded) {
                    // C1.5 (decisions #102): static "Device voice" pill — no
                    // animation, reduced-motion safe by construction.
                    Text(
                        text = "Device voice",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(AyvuSpacing.XS))
                CoverageProgress(
                    coverageSpans = state.coverageSpans,
                    chapterMarks = state.chapterMarks,
                    playheadFraction = state.playheadFraction,
                )
                Spacer(Modifier.height(2.dp))
                if (badge != null) badge()
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatClock(state.elapsedSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatPercent(state.readFraction),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatClock(state.timeLeftSeconds) + " left",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AyvuSpacing.SM),
            horizontalArrangement = Arrangement.spacedBy(AyvuSpacing.SM, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                "−30s",
                onClick = commands::seekBackward,
                enabled = state.bookId != null,
                modifier = Modifier.height(48.dp),
            )
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { if (playing) commands.pause() else commands.resume() },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    // Reduced motion (decisions #98): static ring, same
                    // size/stroke/tint — the pause control stays tappable and
                    // the "Generating…" subtitle stays visible.
                    if (LocalReducedMotion.current) {
                        StaticRing(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    }
                } else {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            PillButton(
                "+30s",
                onClick = commands::seekForward,
                enabled = state.bookId != null,
                modifier = Modifier.height(48.dp),
            )
        }
    }
}

/** The book's cover from the sidecar store (`files/covers/<bookId>`), or null. */
private fun decodeCover(
    context: Context,
    bookId: String?,
): androidx.compose.ui.graphics.ImageBitmap? {
    if (bookId == null) return null
    val file = File(context.filesDir, "covers/$bookId")
    if (!file.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()
}

/** "M:SS" under an hour, "H:MM:SS" at or above (mockup's 12:15 / 1:03:45). */
private fun formatClock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
