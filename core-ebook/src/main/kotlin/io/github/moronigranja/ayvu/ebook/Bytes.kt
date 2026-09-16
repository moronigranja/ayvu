package io.github.moronigranja.ayvu.ebook

import java.security.MessageDigest

/** Big-endian (Palm/mobi/zip) integer reads + hashing. */
internal object Bytes {
    fun u8(
        bytes: ByteArray,
        off: Int,
    ): Int = bytes[off].toInt() and 0xFF

    fun u16(
        bytes: ByteArray,
        off: Int,
    ): Int = ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

    fun u32(
        bytes: ByteArray,
        off: Int,
    ): Long =
        ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)

    /** 64-bit big-endian read; [off] must leave at least 8 readable bytes (caller pads). */
    fun be64(
        bytes: ByteArray,
        off: Int,
    ): Long = (u32(bytes, off) shl 32) or u32(bytes, off + 4)

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hasText(
        bytes: ByteArray,
        off: Int,
        text: String,
    ): Boolean = off + text.length <= bytes.size && String(bytes, off, text.length, Charsets.US_ASCII) == text
}
