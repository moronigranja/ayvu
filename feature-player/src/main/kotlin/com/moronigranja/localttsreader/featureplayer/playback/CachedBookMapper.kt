package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage

/**
 * Rebuilds the reader/player layout from the cached parse rows (P2). The
 * player binds its [com.moronigranja.localttsreader.player.BookLayout] and
 * passage texts to the reconstructed [Book], never to a source file.
 */
fun CachedBook.toBook(): Book {
    val chapters =
        passages
            .groupBy { it.chapterIndex }
            .map { (index, rows) ->
                Chapter(
                    index = index,
                    title = rows.firstNotNullOfOrNull { it.chapterTitle },
                    passages = rows.sortedBy { it.passageIndex }.map { TextPassage(it.text) },
                )
            }.sortedBy { it.index }
    return Book(id = id, title = title, authors = authors, chapters = chapters)
}
