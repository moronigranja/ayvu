package io.github.moronigranja.ayvu.persistence

import io.github.moronigranja.ayvu.player.StoredTranslation
import io.github.moronigranja.ayvu.player.TranslationStore
import io.github.moronigranja.ayvu.player.pregen.TranslationTarget

/**
 * Room-backed [TranslationStore] (v4, read-in-language display): thin
 * entity/DAO mapping — the natural key IS the table PK, so [put] replaces
 * and the backup merge's restored-rows-overwrite-local precedence falls out
 * of [TranslationDao.put] alone.
 */
class RoomTranslationStore(
    private val database: LibraryDatabase,
) : TranslationStore {
    override suspend fun get(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
    ): String? = database.translationDao().get(bookId, chapter, passage, target.lang, target.translator)

    override suspend fun put(
        bookId: String,
        chapter: Int,
        passage: Int,
        target: TranslationTarget,
        text: String,
    ) {
        database.translationDao().put(
            TranslationEntity(
                bookId = bookId,
                chapterIndex = chapter,
                passageIndex = passage,
                lang = target.lang,
                translator = target.translator,
                text = text,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun chapter(
        bookId: String,
        chapter: Int,
    ): List<StoredTranslation> = database.translationDao().chapter(bookId, chapter).map { it.toStored() }

    override suspend fun deleteByBook(bookId: String) = database.translationDao().deleteByBook(bookId)

    private fun TranslationEntity.toStored() =
        StoredTranslation(
            bookId = bookId,
            chapterIndex = chapterIndex,
            passageIndex = passageIndex,
            lang = lang,
            translator = translator,
            text = text,
            createdAtEpochMillis = createdAtEpochMillis,
        )
}
