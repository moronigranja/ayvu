package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The shared portrait cover tile (decisions #94): clipped `shapes.small` box
 * on `surfaceVariant`, the bitmap cropped to fill, or the book's initial in
 * `headlineMedium` when no cover decoded. Sizing comes from the caller's
 * [modifier] — default 56×80 matches the library rows; the player card passes
 * 48×64. Cover *decoding* stays at the callsites (the two decode paths differ).
 */
@Composable
fun BookCover(
    bitmap: ImageBitmap?,
    fallbackInitial: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(width = 56.dp, height = 80.dp),
) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = fallbackInitial?.take(1)?.uppercase().orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
