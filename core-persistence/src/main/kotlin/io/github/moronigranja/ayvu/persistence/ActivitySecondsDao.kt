package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The activity_seconds store (Phase H, decisions #109). [accumulate] is a
 * query upsert that ADDS seconds on conflict — Room's `@Upsert` would
 * replace, losing earlier sessions. Day keys are local "yyyy-MM-dd" and
 * compare chronologically as strings.
 */
@Dao
interface ActivitySecondsDao {
    @Query(
        """
        INSERT INTO activity_seconds (dayKey, bookId, kind, seconds)
        VALUES (:dayKey, :bookId, :kind, :seconds)
        ON CONFLICT(dayKey, bookId, kind) DO UPDATE SET seconds = seconds + :seconds
        """,
    )
    suspend fun accumulate(
        dayKey: String,
        bookId: String,
        kind: String,
        seconds: Long,
    )

    /** All rows on/after [sinceDayKey] (inclusive) — the TODAY card's window. */
    @Query("SELECT * FROM activity_seconds WHERE dayKey >= :sinceDayKey")
    fun observeSince(sinceDayKey: String): Flow<List<ActivitySecondsEntity>>

    /** Days with at least one minute of combined activity — the 60 s minimum
     * the streak ([io.github.moronigranja.ayvu.player.Streak]) counts
     * over, applied here so the walk itself stays pure. */
    @Query(
        """
        SELECT dayKey FROM activity_seconds
        GROUP BY dayKey
        HAVING SUM(seconds) >= 60
        """,
    )
    fun observeActiveDays(): Flow<List<String>>

    /** Book removal drops its rows (mirrors the #50 housekeeping in
     * [RoomLibraryStore.delete]). */
    @Query("DELETE FROM activity_seconds WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
