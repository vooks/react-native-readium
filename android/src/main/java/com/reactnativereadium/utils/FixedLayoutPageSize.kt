package com.reactnativereadium.utils

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layoutOf
import org.readium.r2.shared.publication.presentation.presentation

data class PageSize(val width: Double, val height: Double)

private const val SAMPLED_PAGES = 3
private const val HEAD_BYTES = 16384L

private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
private val VIEWPORT_NAME = Regex("name\\s*=\\s*[\"']viewport[\"']", RegexOption.IGNORE_CASE)
private val CONTENT_ATTR = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
private val WIDTH_VALUE = Regex("(?:^|[,;\\s])width\\s*=\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
private val HEIGHT_VALUE = Regex("(?:^|[,;\\s])height\\s*=\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)

internal fun parseViewportPageSize(html: String): PageSize? {
  for (tag in META_TAG.findAll(html)) {
    if (!VIEWPORT_NAME.containsMatchIn(tag.value)) continue
    val content = CONTENT_ATTR.find(tag.value)?.groupValues?.get(1) ?: continue
    val width = WIDTH_VALUE.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
    val height = HEIGHT_VALUE.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
    if (width != null && height != null && width > 0 && height > 0) {
      return PageSize(width, height)
    }
  }
  return null
}

private suspend fun pageSizeOf(publication: Publication, link: Link): PageSize? {
  val width = link.width
  val height = link.height
  if (width != null && height != null && width > 0 && height > 0) {
    return PageSize(width.toDouble(), height.toDouble())
  }
  val resource = publication.get(link) ?: return null
  return try {
    resource.read(0L until HEAD_BYTES).getOrNull()?.let { parseViewportPageSize(String(it, Charsets.UTF_8)) }
  } catch (e: Exception) {
    null
  } finally {
    resource.close()
  }
}

internal suspend fun fixedLayoutPageSize(publication: Publication): PageSize? {
  val presentation = publication.metadata.presentation
  val fixedPages = publication.readingOrder
    .filter { presentation.layoutOf(it) == EpubLayout.FIXED }
    .take(SAMPLED_PAGES)
  if (fixedPages.isEmpty()) return null
  val sizes = fixedPages.mapNotNull { pageSizeOf(publication, it) }
  if (sizes.isEmpty()) return null
  return sizes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
