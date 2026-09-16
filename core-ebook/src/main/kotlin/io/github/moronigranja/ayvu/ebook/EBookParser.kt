package io.github.moronigranja.ayvu.ebook

import io.github.moronigranja.ayvu.model.Book
import java.io.InputStream

/**
 * Failure to parse an ebook container into a [Book]. Thrown for missing/broken
 * containers, malformed XML, empty spines — never caught as a crash: the import
 * flow maps it to a user-visible "could not import" state with [message].
 * Open so a specific refusal can preserve its own message as a subtype
 * ([EBookLimitExceededException] for a breached import ceiling).
 */
open class EBookParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Where a format parser reads a book from. The [open] stream is owned by the caller
 * of the factory (import flow); parsers read it fully through [readCapped], which
 * closes it and enforces the container ceiling.
 */
data class EBookSource(
    val fileName: String,
    val open: () -> InputStream,
)

/**
 * Adapts a format parser to the common domain model ([Book]). One implementation per
 * container format; [EBookFormats.parserFor] picks the implementation by file name.
 */
interface EBookParser {
    fun parse(source: EBookSource): Book

    /** Cover artwork bytes (EPUB cover image); null when the format/container has none. */
    fun coverOf(bytes: ByteArray): ByteArray? = null
}
