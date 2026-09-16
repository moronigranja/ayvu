package io.github.moronigranja.ayvu.featureshare

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.moronigranja.ayvu.ocr.OcrImage

/**
 * Decodes a shared image URI into a full-resolution [OcrImage] (S2). The
 * decode stays codec-side (Android BitmapFactory); OCR sizing policy belongs
 * to the resolver's downscaler. Screenshots come in at display resolution
 * (~17.7 MP on the S22) — too big to decode directly into an ARGB int
 * buffer without risking API-guideline pressure, so the bounds are read
 * first and [BitmapFactory.Options.inSampleSize] halves the long side down
 * toward [DECODE_MAX_LONG_SIDE] before pixel extraction.
 */
object ImageDecoder {
    const val DECODE_MAX_LONG_SIDE = 2400

    fun decode(
        uri: Uri,
        resolver: ContentResolver,
    ): OcrImage? {
        // Bounds pass: decodeStream returns null BY DESIGN with
        // inJustDecodeBounds=true — the options receive the dimensions, so
        // the null is expected and ignored (the stream is reopened below).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX_LONG_SIDE) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap =
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = OcrImage(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        return image
    }
}
