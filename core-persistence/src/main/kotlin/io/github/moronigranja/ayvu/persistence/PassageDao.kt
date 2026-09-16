package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PassageDao {
    /** Replaces a book's full cached parse (content hash ⇒ identical rows). */
    @Upsert
    suspend fun upsertAll(passages: List<PassageEntity>)

    @Query("SELECT * FROM passages WHERE bookId = :bookId ORDER BY chapterIndex, passageIndex")
    suspend fun forBook(bookId: String): List<PassageEntity>

    /** Every cached passage, ordered per book — the rebuild's flat input (P2). */
    @Query("SELECT * FROM passages ORDER BY bookId, chapterIndex, passageIndex")
    suspend fun all(): List<PassageEntity>

    /** Per-(book, chapter) passage counts — the read-progress denominator (D4). */
    @Query("SELECT bookId, chapterIndex, COUNT(*) AS passageCount FROM passages GROUP BY bookId, chapterIndex")
    fun chapterCounts(): Flow<List<ChapterCount>>

    @Query("DELETE FROM passages WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
