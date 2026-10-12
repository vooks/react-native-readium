package com.reactnativereadium.reader

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.util.RNLog
import com.reactnativereadium.utils.LinkOrLocator
import java.io.File
import java.util.Locale
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpResource
import org.readium.r2.shared.util.resource.buffered
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser


class ReaderService(
  private val reactContext: ReactApplicationContext
) {
  private companion object {
    const val REMOTE_READ_BUFFER_BYTES = 512 * 1024
  }

  private val httpClient = DefaultHttpClient()
  private val assetRetriever = AssetRetriever(
    reactContext.contentResolver,
    httpClient
  )
  private val publicationOpener = PublicationOpener(
    publicationParser = DefaultPublicationParser(
      context = reactContext,
      assetRetriever = assetRetriever,
      httpClient = httpClient,
      pdfFactory = null,
    )
  )

  fun locatorFromLinkOrLocator(
    location: LinkOrLocator?,
    publication: Publication,
  ): Locator? {

    if (location == null) return null

    when (location) {
      is LinkOrLocator.Link -> {
        return publication.locatorFromLink(location.link)
      }
      is LinkOrLocator.Locator -> {
        return location.locator
      }
    }

    return null
  }

  // An http(s) URL is handed to the AssetRetriever as-is: its ZipArchiveOpener
  // streams the archive over HTTP range requests, so only the resources needed
  // for the pages being read are fetched. Anything else must be a local file.
  private fun resolvePublicationUrl(fileName: String): AbsoluteUrl? {
    if (isHttpUrl(fileName)) {
      return AbsoluteUrl(fileName) ?: run {
        RNLog.e(reactContext, "Invalid publication URL: $fileName")
        null
      }
    }

    val publicationFile = File(fileName).absoluteFile
    if (!publicationFile.exists()) {
      RNLog.e(reactContext, "Failed to open publication: File does not exist: $fileName")
      return null
    }
    return runCatching { publicationFile.toUrl() }
      .onFailure {
        RNLog.e(reactContext, "Invalid publication path: $fileName - ${it.message}")
      }
      .getOrNull()
  }

  private fun isHttpUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true)

  // Format hint from the path's extension; a signed URL carries its query
  // string after the extension, so strip that first.
  private fun extensionHint(fileName: String): String? =
    fileName
      .substringBefore('?')
      .substringAfterLast('/')
      .substringAfterLast('.', "")
      .takeIf { it.isNotEmpty() }
      ?.lowercase(Locale.ROOT)

  suspend fun openPublication(
    fileName: String,
    initialLocation: LinkOrLocator?,
    callback: suspend (fragment: BaseReaderFragment) -> Unit
  ) {
    val publicationUrl = resolvePublicationUrl(fileName) ?: return
    val hints = FormatHints(fileExtension = extensionHint(fileName)?.let { FileExtension(it) })

    // A remote archive is read through a large buffer so the ZIP reader's many
    // small seeks become a handful of range requests instead of one each;
    // without it a single page image can take tens of seconds to arrive.
    val asset: Asset? = if (isHttpUrl(fileName)) {
      assetRetriever
        .retrieve(
          HttpResource(publicationUrl, httpClient).buffered(bufferSize = REMOTE_READ_BUFFER_BYTES),
          hints
        )
        .onFailure { RNLog.w(reactContext, "Unable to retrieve remote publication: ${it.message}") }
        .getOrNull()
    } else {
      assetRetriever
        .retrieve(publicationUrl, hints)
        .onFailure { RNLog.w(reactContext, "Unable to retrieve publication asset: ${it.message}") }
        .getOrNull()
    }
    if (asset == null) return

    publicationOpener
      .open(
        asset = asset,
        allowUserInteraction = false
      )
      .onSuccess {
        val locator = locatorFromLinkOrLocator(initialLocation, it)
        val readerFragment = EpubReaderFragment.newInstance()
        readerFragment.initFactory(it, locator)
        callback.invoke(readerFragment)
      }
      .onFailure {
        RNLog.w(
          reactContext,
          "Error executing ReaderService.openPublication: ${it.message}"
        )
        // TODO: implement failure event
      }
  }

  sealed class Event {

    class ImportPublicationFailed(val errorMessage: String?) : Event()

    object UnableToMovePublication : Event()

    object ImportPublicationSuccess : Event()

    object ImportDatabaseFailed : Event()

    class OpenBookError(val errorMessage: String?) : Event()
  }
}
