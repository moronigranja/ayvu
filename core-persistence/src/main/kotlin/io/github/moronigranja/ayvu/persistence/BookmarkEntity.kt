package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user bookmark on a passage (v2, decisions #29): long-press add, reader
 * menu. The position snapshot comes from the player state machine, so a
 * bookmark is always consistent with the position it was taken at (#33).
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val offsetSeconds: Double,
    val label: String?,
    val createdAtEpochMillis: Long,
)
