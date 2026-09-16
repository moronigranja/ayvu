package com.moronigranja.localttsreader.persistence

import androidx.room.Entity
import androidx.room.Index

/**
 * One stored passage translation (v4, read-in-language display): the reader
 * shows the ORIGINAL passage plus this text, keyed by the natural
 * (bookId, chapterIndex, passageIndex, lang, translator) identity — the SAME
 * (lang, translator) pair the audio cache keys on ([PregenKey.target]), so
 * speech and display always name one stored artifact and a passage is
 * translated at most once. Nothing about a translation survives a process
 * restart today; this table is what makes it durable (the PCM cache alone
 * never yields text back).
 */
@Entity(
    tableName = "translations",
    indices = [Index(value = ["bookId"])],
    primaryKeys = ["bookId", "chapterIndex", "passageIndex", "lang", "translator"],
)
data class TranslationEntity(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val lang: String,
    val translator: String,
    val text: String,
    val createdAtEpochMillis: Long,
)