package io.github.moronigranja.ayvu.tts

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hermetic transport for tests: serves an in-memory [source] with controllable
 * range support, error modes and truncation. Records every open call so tests
 * can assert range behavior and coalescing.
 */
class FakeTransport : DownloadTransport {
    enum class Mode { RANGE_SUPPORTED, IGNORES_RANGE, HTTP_404, HTTP_500 }

    var source: ByteArray = ByteArray(0)
    var mode: Mode = Mode.RANGE_SUPPORTED

    /** When set, throws on every open (connection-level failure). */
    var failOpenWith: IOException? = null

    /** When set, the body stream ends at this absolute byte offset of [source]. */
    var truncateAt: Long? = null

    val calls = ConcurrentLinkedQueue<Pair<String, Long?>>()

    override suspend fun open(
        url: String,
        rangeFrom: Long?,
    ): OpenResult {
        calls += url to rangeFrom
        failOpenWith?.let { throw it }
        if (mode == Mode.HTTP_404) return OpenResult.HttpError(404)
        if (mode == Mode.HTTP_500) return OpenResult.HttpError(500)

        // A range-aware server: full source for a fresh request, the suffix for
        // a range request, empty when the range starts past the end.
        val servedFrom =
            if (mode == Mode.IGNORES_RANGE) {
                null // serves the full source regardless of the requested range
            } else {
                rangeFrom
            }
        val bytes =
            when {
                servedFrom == null -> source
                servedFrom >= source.size -> ByteArray(0)
                else -> source.copyOfRange(servedFrom.toInt(), source.size)
            }
        var served = bytes
        truncateAt?.let { end ->
            val fromIndex = servedFrom ?: 0L
            val available = (end - fromIndex).coerceAtLeast(0L)
            if (served.size > available) served = served.copyOfRange(0, available.toInt())
        }
        // IGNORES_RANGE answers 200 with the whole body; a range-aware server
        // answers 206 to a satisfiable range request.
        val status =
            when {
                mode == Mode.IGNORES_RANGE -> 200
                rangeFrom != null && rangeFrom > 0 && rangeFrom < source.size -> 206
                else -> 200
            }
        return OpenResult.Body(HttpBody(status, served.size.toLong(), ByteArrayInputStream(served)))
    }
}
