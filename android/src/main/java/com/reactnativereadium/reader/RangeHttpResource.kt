package com.reactnativereadium.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.ReadTry
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.fetch
import org.readium.r2.shared.util.http.head
import org.readium.r2.shared.util.resource.Resource

/**
 * Remote publication resource read with one bounded range request per read.
 *
 * Readium's own HttpResource keeps a single open-ended stream and no locking;
 * the WebView requests a page's markup, styles and image concurrently, so the
 * readers close each other's stream and a streamed page can stay blank. Here
 * every read is independent, and a mutex-guarded chunk cache turns the ZIP
 * reader's many small sequential reads into a few large requests.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class RangeHttpResource(
  override val sourceUrl: AbsoluteUrl,
  private val client: HttpClient,
  private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : Resource {
  private companion object {
    const val DEFAULT_CHUNK_SIZE = 512L * 1024
  }

  private val lock = Mutex()
  private var headResponse: HttpResponse? = null
  private var chunkStart = -1L
  private var chunk: ByteArray = ByteArray(0)

  override suspend fun properties(): ReadTry<Resource.Properties> =
    Try.success(Resource.Properties())

  override suspend fun length(): ReadTry<Long> = lock.withLock {
    head().flatMap { response ->
      response.contentLength
        ?.let { Try.success(it) }
        ?: Try.failure(ReadError.UnsupportedOperation(DebugError("No Content-Length for $sourceUrl")))
    }
  }

  override suspend fun read(range: LongRange?): ReadTry<ByteArray> = lock.withLock {
    if (range == null) {
      return@withLock fetch(null)
    }
    if (range.isEmpty()) {
      return@withLock Try.success(ByteArray(0))
    }
    val wanted = range.last - range.first + 1
    if (wanted > chunkSize) {
      return@withLock fetch(range)
    }
    if (!isCached(range)) {
      val fetched = fetch(range.first until range.first + chunkSize)
      if (fetched.isFailure) {
        return@withLock fetched
      }
      chunkStart = range.first
      chunk = fetched.getOrThrow()
      if (!isCached(range)) {
        // Short final chunk: the range runs past the end of the archive.
        return@withLock Try.success(chunk.copyOfRange(0, minOf(wanted, chunk.size.toLong()).toInt()))
      }
    }
    val offset = (range.first - chunkStart).toInt()
    Try.success(chunk.copyOfRange(offset, offset + wanted.toInt()))
  }

  override fun close() {}

  private fun isCached(range: LongRange): Boolean =
    chunkStart >= 0 && range.first >= chunkStart && range.last < chunkStart + chunk.size

  private suspend fun head(): ReadTry<HttpResponse> {
    headResponse?.let { return Try.success(it) }
    return client.head(HttpRequest(sourceUrl))
      .map { response -> response.also { headResponse = it } }
      .mapFailure { ReadError.Access(it) }
  }

  private suspend fun fetch(range: LongRange?): ReadTry<ByteArray> {
    val headers = range
      ?.let { mapOf("Range" to listOf("bytes=${it.first}-${it.last}")) }
      ?: emptyMap()
    return client.fetch(HttpRequest(sourceUrl, headers = headers))
      .map { it.body }
      .mapFailure { ReadError.Access(it) }
  }
}
