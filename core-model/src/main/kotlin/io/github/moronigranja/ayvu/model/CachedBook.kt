package io.github.moronigranja.ayvu.model

/**
 * Flat, storage-ready form of a book's cached parse (P2): exactly what passes
 * through the persistence layer and what the launch-time index rebuild consumes.
 *
 * Rebuilding from these rows is the "never re-parse on launch" contract — a
 * `CachedBook` is derived from the segmented passages a `BookImporter` produced,
 * never from a source file.
 */
data class CachedBook(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val passages: List<CachedPassage> = emptyList(),
)

/** One cached passage row; [chapterTitle] is denormalized per row (may be null). */
data class CachedPassage(
    val chapterIndex: Int,
    val chapterTitle: String?,
    val passageIndex: Int,
    val text: String,
)

/**
 * The book as its storage-ready cached form (P2): one [CachedPassage] per
 * passage, in spine order. This is THE flattening — the persistence layer
 * stores exactly these rows, so a parse has one storage shape everywhere.
 */
fun Book.toCachedBook(): CachedBook =
    CachedBook(
        id = id,
        title = title,
        authors = authors,
        passages =
            chapters.flatMap { chapter ->
                chapter.passages.mapIndexed { index, passage ->
                    CachedPassage(
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title,
                        passageIndex = index,
                        text = passage.text,
                    )
                }
            },
    )
