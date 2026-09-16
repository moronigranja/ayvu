package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The per-book position ring (v2, decisions #29/#33): one entry per
 * user-directed move away from a position (skip/seek/accidental play),
 * capped per book, newest first. Pop = one-shot undo. Written atomically
 * with the progress row by the player state machine, so the ring can never
 * drift from the resume point (T4 carry-over note 3).
 */
@Entity(
    tableName = "position_history",
    indices = [Index(value = ["bookId"])],
)
data class PositionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val createdAtEpochMillis: Long,
)
