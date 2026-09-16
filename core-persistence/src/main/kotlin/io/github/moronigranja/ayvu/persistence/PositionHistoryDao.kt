package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PositionHistoryDao {
    @Insert
    suspend fun insert(entry: PositionHistoryEntity): Long

    /** Newest first (rowid order == insert order). */
    @Query("SELECT * FROM position_history WHERE bookId = :bookId ORDER BY id DESC")
    suspend fun all(bookId: String): List<PositionHistoryEntity>

    @Query("SELECT * FROM position_history WHERE bookId = :bookId ORDER BY id DESC LIMIT 1")
    suspend fun newest(bookId: String): PositionHistoryEntity?

    @Query("DELETE FROM position_history WHERE id = :id")
    suspend fun delete(id: Long)

    /** Book removal: the undo ring goes with the book (decisions #50 pass). */
    @Query("DELETE FROM position_history WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** One-shot read of every row — the backup snapshot source (E1). */
    @Query("SELECT * FROM position_history ORDER BY bookId, id")
    suspend fun all(): List<PositionHistoryEntity>

    /** Keeps the newest [keep] rows for the book — the cap (decisions #29). */
    @Query(
        """
        DELETE FROM position_history
        WHERE bookId = :bookId
          AND id NOT IN (
              SELECT id FROM position_history WHERE bookId = :bookId ORDER BY id DESC LIMIT :keep
          )
        """,
    )
    suspend fun prune(
        bookId: String,
        keep: Int,
    )
}
