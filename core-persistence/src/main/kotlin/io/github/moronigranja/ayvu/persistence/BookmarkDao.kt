package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    /** Book removal: the book's bookmarks go with it (decisions #50 pass). */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun all(bookId: String): List<BookmarkEntity>

    /** One-shot read of every row — the backup snapshot source (E1). */
    @Query("SELECT * FROM bookmarks ORDER BY bookId, createdAtEpochMillis, id")
    suspend fun all(): List<BookmarkEntity>
}
