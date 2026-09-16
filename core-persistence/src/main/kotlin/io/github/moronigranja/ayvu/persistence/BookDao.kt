package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** Library rows in import order — the list UI's source. */
    @Query("SELECT * FROM books ORDER BY importedAtEpochMillis")
    fun observeAll(): Flow<List<BookEntity>>

    /** One-shot read of every book, in import order — the rebuild's book set (P2). */
    @Query("SELECT * FROM books ORDER BY importedAtEpochMillis")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun byId(id: String): BookEntity?

    /** Inserts or replaces by id; a duplicate id never duplicates rows. */
    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}
