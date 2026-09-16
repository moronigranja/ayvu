package io.github.moronigranja.ayvu.player

/**
 * Chapter-menu label for the reader chrome (decisions #52/#53): the dense
 * 1-based index prefix for chapters whose title carries no ordinal of its
 * own. A spine/navPoint title that is already numbered — "1. Millie: …",
 * "IV. Storm", "Chapter 3", "PART II" — is shown unchanged, so the menu
 * never renders "1. 1. Millie: …".
 */
private val LEADING_ORDINAL =
    Regex(
        """^\s*(?:\d+[.)]|[ivxlcdm]+[.)]|(?:chapter|part)\s+(?:\d+|[ivxlcdm]+))""",
        RegexOption.IGNORE_CASE,
    )

fun chapterMenuLabel(
    index: Int,
    title: String,
): String = if (LEADING_ORDINAL.containsMatchIn(title)) title else "${index + 1}. $title"
