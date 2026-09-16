package io.github.moronigranja.ayvu.model

/**
 * A parsed book: metadata plus its ordered chapters. The canonical domain type shared
 * by every module (parsers produce it, the text index consumes it, the library store
 * persists it).
 *
 * [id] is a stable content-derived identifier assigned by the importing parser
 * (e.g. SHA-256 of the container bytes) — deterministic across imports, no cloud.
 */
data class Book(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)
