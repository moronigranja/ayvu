package com.moronigranja.localttsreader.persistence

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedPassage
import com.moronigranja.localttsreader.model.LibraryEntry

/**
 * Mappings between the domain types (core-model) and Room rows. The author list
 * is encoded as a single column: U+001F-joined. U+001F cannot appear in a
 * realistic author name; if one ever does, the round-trip is lossy — switch to
 * a child table before supporting such data.
 */

private const val AUTHORS_SEPARATOR = "\u001F"

fun encodeAuthors(authors: List<String>): String = authors.joinToString(AUTHORS_SEPARATOR)

fun decodeAuthors(encoded: String): List<String> = if (encoded.isEmpty()) emptyList() else encoded.split(AUTHORS_SEPARATOR)

fun LibraryEntry.bookEntity(): BookEntity =
    BookEntity(
        id = book.id,
        title = book.title,
        authors = encodeAuthors(book.authors),
        importedAtEpochMillis = importedAtEpochMillis,
    )

/** List-view entry: chapters are cached per passage, not re-assembled here. */
fun BookEntity.toLibraryEntry(): LibraryEntry =
    LibraryEntry(
        book = Book(id = id, title = title, authors = decodeAuthors(authors)),
        importedAtEpochMillis = importedAtEpochMillis,
    )

/** Flattens one book's chapters into cache rows — the stored cached parse. */
fun Book.cachedPassages(): List<PassageEntity> =
    chapters.flatMap { chapter ->
        chapter.passages.mapIndexed { index, passage ->
            PassageEntity(
                bookId = id,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                passageIndex = index,
                text = passage.text,
            )
        }
    }

fun PassageEntity.toCachedPassage(): CachedPassage =
    CachedPassage(
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        passageIndex = passageIndex,
        text = text,
    )
