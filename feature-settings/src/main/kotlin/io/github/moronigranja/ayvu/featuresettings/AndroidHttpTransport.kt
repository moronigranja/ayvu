package io.github.moronigranja.ayvu.featuresettings

import io.github.moronigranja.ayvu.tts.DownloadTransport
import io.github.moronigranja.ayvu.tts.HttpBody
import io.github.moronigranja.ayvu.tts.OpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-device download transport (V1): the Android adapter behind
 * [DownloadTransport] — HttpURLConnection on the IO dispatcher, following
 * redirects (platform default for GET; GitHub release URLs redirect to the
 * CDN), honoring `Range: bytes=<from>-` for resumable pack downloads.
 *
 * This is the app's sanctioned network path for the settings download UI
 * (hard-facts offline-first: an explicit, user-consented download — same
 * seam the JVM tooling uses; no other socket use).
 */
class AndroidHttpTransport : DownloadTransport {
    override suspend fun open(
        url: String,
        rangeFrom: Long?,
    ): OpenResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (rangeFrom != null) connection.setRequestProperty("Range", "bytes=$rangeFrom-")
                val status = connection.responseCode
                if (status in 200..299) {
                    OpenResult.Body(
                        HttpBody(
                            statusCode = status,
                            contentLength = connection.contentLengthLong.takeIf { it >= 0 },
                            bytes = connection.inputStream,
                        ),
                    )
                } else {
                    connection.errorStream?.close()
                    OpenResult.HttpError(status)
                }
            } catch (e: IOException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Android surfaces DNS failures as an unchecked GaiException (e.g.
                // airplane mode — getaddrinfo EPERM/EAI_NODATA). The downloader's
                // contract is typed failures via IOException; anything else would
                // escape the download coroutine and crash the process (observed
                // 2026-08-27 on the S22 while offline).
                throw IOException("network error: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "ayvu/0.1"
    }
}
