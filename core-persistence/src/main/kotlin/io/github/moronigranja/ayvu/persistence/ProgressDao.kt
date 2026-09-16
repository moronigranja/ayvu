package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    /** Every resume row — the library read-progress source (D4). */
    @Query("SELECT * FROM progress ORDER BY bookId")
    fun observeAll(): Flow<List<ProgressEntity>>

    /** Book removal: the resume row goes with the book (decisions #50 pass). */
    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    /** Inserts or replaces the book's resume point. */
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    /** One-shot read of every resume row, book-sorted — the backup snapshot source (E1). */
    @Query("SELECT * FROM progress ORDER BY bookId")
    suspend fun all(): List<ProgressEntity>
}
