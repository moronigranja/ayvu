package io.github.moronigranja.ayvu.ebook

/**
 * Picks an [EBookParser] by file extension. Unsupported containers return null so the
 * import flow can tell the user "format not supported" instead of crashing.
 */
object EBookFormats {
    fun parserFor(fileName: String): EBookParser? =
        when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "epub" -> EpubParser
            "txt", "markdown", "md" -> TextParser
            "azw3", "kf8", "mobi", "azw" -> MobiParser
            else -> null
        }
}
