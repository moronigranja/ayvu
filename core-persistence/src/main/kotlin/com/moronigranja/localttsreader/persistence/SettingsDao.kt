package com.moronigranja.localttsreader.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    /** Inserts or replaces the setting row. */
    @Upsert
    suspend fun put(setting: SettingEntity)

    /** Removes one row — the per-book override clear and the book-delete
     * drop (decisions #144); absent keys are unaffected. */
    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)

    /** One-shot read of every row, key-sorted — the backup snapshot source (E1). */
    @Query("SELECT * FROM settings ORDER BY key")
    suspend fun all(): List<SettingEntity>

    /** Bulk upsert — the backup restore apply (E1); absent keys keep their local rows. */
    @Upsert
    suspend fun putAll(settings: List<SettingEntity>)

    /** Key-list delete — the book-removal drop of the per-book settings rows
     * (voice/translate/display); absent keys are unaffected. */
    @Query("DELETE FROM settings WHERE key IN (:keys)")
    suspend fun deleteAll(keys: List<String>)
}
