package com.moronigranja.localttsreader.model

/**
 * One atomic chunk of chapter text (typically a paragraph). The passage grain is the
 * unit of matching (share-and-identify) and of resume, so it must stay small and
 * stable across re-parses.
 */
data class TextPassage(
    val text: String,
)
