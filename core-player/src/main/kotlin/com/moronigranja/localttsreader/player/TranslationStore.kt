package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.player.pregen.TranslationTarget

/**
 * The translated-text persistence contract (read-in-language display): one
 * row per (bookId, chapter, passage, lang, translator) — the SAME identity
 * the audio cache keys on ([PregenKey.target]), so speech and display always
 * name one stored artifact (a passage is translated at most once). Implemented
 * by Room in core-persistence ([RoomTranslationStore]); the backup archive
 * rides it as an optional section, and the book-delete path drops it with the
 * book's other rows.
 */
interface TranslationStore {
    /** The passage's stored translation for exactly [target], or null. */
    suspend fun get(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String?

    /** Stores (or overwrites) the passage's translation for [target]. */
    suspend fun put(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
        text: String,
    )

    /** Every translation of one chapter (any translator) — the reader seed. */
    suspend fun chapter(
        bookId: String,
        chapter: Int,
    ): List<StoredTranslation>

    /** Book removal: the book's translations go with it. */
    suspend fun deleteByBook(bookId: String)
}

/** The stored form of one passage translation — the chapter seed and the
 * backup archive row (mirrors the Room entity, kept copy-free). */
data class StoredTranslation(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val lang: String,
    val translator: String,
    val text: String,
    val createdAtEpochMillis: Long,
)
