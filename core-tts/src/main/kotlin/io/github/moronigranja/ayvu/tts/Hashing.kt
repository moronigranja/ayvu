package io.github.moronigranja.ayvu.tts

import java.io.File
import java.security.MessageDigest

/** Streaming SHA-256 of a file, lowercase hex. Used for pack verification and by tests to pin descriptors. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

/** SHA-256 of a byte array, lowercase hex (test helper for descriptor pinning). */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private const val DEFAULT_BUFFER_SIZE = 64 * 1024
