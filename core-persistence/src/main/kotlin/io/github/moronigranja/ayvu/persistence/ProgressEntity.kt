package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book's resume point (P1, extended in v2): one row per book, pointing at
 * the last read/listened passage plus the in-passage offset in book-time
 * seconds (speed-independent, decisions #33) and the per-book speed (preset
 * restore, decisions #29). Written by the player state machine — the single
 * transactional write point — and read by resume wiring (S3).
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    /** Book-time seconds into the passage at 1.0× — never moves with speed. */
    val offsetSeconds: Double = 0.0,
    /** Per-book playback speed (1.0 default; presets, decisions #29). */
    val speed: Double = 1.0,
    val updatedAtEpochMillis: Long,
)
