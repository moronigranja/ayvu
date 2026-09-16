package io.github.moronigranja.ayvu.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One cached passage row (declaration 13: passage = unit of matching + resume).
 * The rows of a book are its cached parse: the launch-time index rebuild reads
 * these and **never re-parses** the source file (P2). Deleting a book cascades
 * to its rows ([ForeignKey.CASCADE]).
 *
 * [chapterTitle] is denormalized per row to keep the rebuild single-table;
 * untitled chapters store null.
 */
@Entity(
    tableName = "passages",
    primaryKeys = ["bookId", "chapterIndex", "passageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class PassageEntity(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val passageIndex: Int,
    val text: String,
)
