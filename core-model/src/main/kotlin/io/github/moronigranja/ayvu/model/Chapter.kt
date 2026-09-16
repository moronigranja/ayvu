package io.github.moronigranja.ayvu.model

/**
 * One chapter (spine item) of a book, holding its ordered text passages.
 *
 * [index] is the 0-based chapter ordinal assigned by the parser (spine order).
 * [title] may be null when neither the TOC nor a heading provides one.
 */
data class Chapter(
    val index: Int,
    val title: String?,
    val passages: List<TextPassage> = emptyList(),
)
