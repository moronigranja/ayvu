package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One library book. [id] is the content hash (decisions #11); [authors] is the
 * author list encoded with U+001F separators ([EntityMappers]) — empty string
 * encodes no authors. The book's parsed passages live in the `passages` table
 * (the cached parse the launch-time rebuild consumes — P2).
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val authors: String,
    val importedAtEpochMillis: Long,
)
