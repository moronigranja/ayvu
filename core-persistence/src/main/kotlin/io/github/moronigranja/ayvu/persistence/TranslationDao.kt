package io.github.moronigranja.ayvu.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {
    /** Inserts or replaces the row — the natural key IS the PK, so a restore
     * or a re-translate overwrites the previous text (REPLACE, same
     * precedence as the backup settings merge). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(translation: TranslationEntity): Long

    /** The passage's translation for one exact (lang, translator) target. */
    @Query(
        "SELECT text FROM translations WHERE bookId=:bookId AND chapterIndex=:chapter AND passageIndex=:passage AND lang=:lang AND translator=:translator",
    )
    suspend fun get(
        bookId: String,
        chapter: Int,
        passage: Int,
        lang: String,
        translator: String,
    ): String?

    /** Every translation of one chapter — the reader's seed + the backup archive. */
    @Query("SELECT * FROM translations WHERE bookId=:bookId AND chapterIndex=:chapter")
    suspend fun chapter(
        bookId: String,
        chapter: Int,
    ): List<TranslationEntity>

    /** Book removal: the book's translations go with it. */
    @Query("DELETE FROM translations WHERE bookId=:bookId")
    suspend fun deleteByBook(bookId: String)

    /** One-shot read of every row — the backup snapshot source (E1). */
    @Query("SELECT * FROM translations")
    suspend fun all(): List<TranslationEntity>

    /** One row per language with stored translations for [translator]. */
    @Query("SELECT lang, COUNT(*) AS count FROM translations WHERE bookId=:bookId AND translator=:translator GROUP BY lang")
    suspend fun countsByLanguage(
        bookId: String,
        translator: String,
    ): List<LanguageCount>
}

/** One row per language with stored translations for a translator. */
data class LanguageCount(
    val lang: String,
    val count: Int,
)
