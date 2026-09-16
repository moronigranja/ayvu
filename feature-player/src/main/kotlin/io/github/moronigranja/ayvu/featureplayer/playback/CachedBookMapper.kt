package io.github.moronigranja.ayvu.featureplayer.playback

import io.github.moronigranja.ayvu.model.Book
import io.github.moronigranja.ayvu.model.CachedBook
import io.github.moronigranja.ayvu.model.Chapter
import io.github.moronigranja.ayvu.model.TextPassage

/**
 * Rebuilds the reader/player layout from the cached parse rows (P2). The
 * player binds its [io.github.moronigranja.ayvu.player.BookLayout] and
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
