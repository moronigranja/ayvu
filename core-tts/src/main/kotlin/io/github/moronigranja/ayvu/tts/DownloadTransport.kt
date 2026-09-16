package io.github.moronigranja.ayvu.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The downloader's network seam. `core-tts` is pure JVM; concrete transports
 * live behind it so the on-device implementation can use the platform's HTTP
 * stack while tests stay hermetic (a fake, no sockets). This is the app's
 * single sanctioned socket use (hard-facts offline-first: "any download is a
 * single, explicit, user-consented, resumable operation; justify every socket
 * use" — this interface and its implementations ARE that justification: there
 * is no other network path).
 *
 * Implementations MUST follow redirects and honor [rangeFrom] by sending
 * `Range: bytes=<rangeFrom>-`.
 */
interface DownloadTransport {
    /**
     * Opens [url]; a non-null [rangeFrom] requests that byte range (resume).
     * Returns [OpenResult.Body] on a 2xx response, [OpenResult.HttpError]
     * otherwise. May throw [IOException] on connection/read failures.
     */
    suspend fun open(
        url: String,
        rangeFrom: Long? = null,
    ): OpenResult
}

sealed interface OpenResult {
    data class Body(
        val body: HttpBody,
    ) : OpenResult

    data class HttpError(
        val status: Int,
    ) : OpenResult
}

/** A 2xx response with a cancellable byte stream. */
class HttpBody(
    val statusCode: Int,
    val contentLength: Long?,
    val bytes: InputStream,
) : AutoCloseable {
    override fun close() = bytes.close()
}

/**
 * JVM implementation (tests, desktop tooling) over `java.net.http`.
 * JDK-only: `java.net.http.HttpClient` is not available on Android 12-
 * (minSdk 26), so the on-device transport is an Android adapter over the
 * platform/OkHttp stack in a later slice — behind the same [DownloadTransport].
 *
 * Cancellation aborts the in-flight exchange (`future.cancel(true)`); a
 * mid-body abort surfaces as [IOException] on the caller's read, which the
 * [PackDownloader] maps back to cancellation when the job is no longer active.
 */
class JdkHttpTransport(
    private val connectTimeout: Duration = Duration.ofSeconds(15),
    private val requestTimeout: Duration = Duration.ofMinutes(2),
) : DownloadTransport {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override suspend fun open(
        url: String,
        rangeFrom: Long?,
    ): OpenResult =
        suspendCancellableCoroutine { continuation ->
            val request =
                HttpRequest
                    .newBuilder(URI(url))
                    .timeout(requestTimeout)
                    .apply { if (rangeFrom != null) header("Range", "bytes=$rangeFrom-") }
                    .GET()
                    .build()
            val future = client.sendAsync(request, BodyHandlers.ofInputStream())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (error != null) {
                    continuation.resumeWithException(IOException("GET $url failed", error))
                } else if (response.statusCode() !in 200..299) {
                    response.body().close()
                    continuation.resume(OpenResult.HttpError(response.statusCode()))
                } else {
                    continuation.resume(
                        OpenResult.Body(
                            HttpBody(
                                statusCode = response.statusCode(),
                                contentLength =
                                    response
                                        .headers()
                                        .firstValue("Content-Length")
                                        .orElse(null)
                                        ?.toLongOrNull(),
                                bytes = response.body(),
                            ),
                        ),
                    )
                }
            }
        }
}
