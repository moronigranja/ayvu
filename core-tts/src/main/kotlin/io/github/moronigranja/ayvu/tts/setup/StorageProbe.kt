package io.github.moronigranja.ayvu.tts.setup

/**
 * Free-bytes probe for the setup storage check (C1.2): the interface stays
 * pure JVM; the Android impl (`StatFs` over `context.filesDir`) lives in app
 * and is bound by the app's pack wiring. No equivalent exists elsewhere in
 * the codebase (scout: no StatFs/usableSpace uses).
 */
interface StorageProbe {
    /** Free bytes on the filesystem hosting the app files, immediately. */
    fun availableBytes(): Long
}
