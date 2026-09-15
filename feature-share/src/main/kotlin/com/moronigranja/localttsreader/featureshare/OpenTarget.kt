package com.moronigranja.localttsreader.featureshare

/**
 * What "Listen from here" needs to open (S3): a book and a passage to start
 * playback at. The extras contract is owned HERE (the originating feature);
 * the app's MainActivity consumes it and the app's [ShareOpenHandler]
 * implementation launches the navigation.
 */
data class OpenTarget(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
) {
    companion object {
        const val EXTRA_BOOK_ID = "org.moronigranja.localttsreader.open.bookId"
        const val EXTRA_CHAPTER = "org.moronigranja.localttsreader.open.chapter"
        const val EXTRA_PASSAGE = "org.moronigranja.localttsreader.open.passage"
        const val DEFAULT_CHAPTER = -1 // absent = start wherever resume would

        /** Pure parse for tests and the app's intent consumption. */
        fun fromExtras(
            bookId: String?,
            chapterIndex: Int,
            passageIndex: Int,
        ): OpenTarget? {
            if (bookId.isNullOrBlank()) return null
            return OpenTarget(bookId, chapterIndex.coerceAtLeast(0), passageIndex.coerceAtLeast(0))
        }
    }
}

/** App-owned navigation seam: the share gate never names MainActivity. */
fun interface ShareOpenHandler {
    fun open(target: OpenTarget)
}
