package io.github.moronigranja.ayvu.model

/**
 * A book in the user's library, as produced by the import pipeline:
 * a parsed [Book] plus when it was imported. The basis for the library store
 * (Room) and the list [`feature-library`] UI.
 */
data class LibraryEntry(
    val book: Book,
    val importedAtEpochMillis: Long,
)
