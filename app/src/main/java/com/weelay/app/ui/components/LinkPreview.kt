package com.weelay.app.ui.components

import com.weelay.app.ui.theme.Palette
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class LinkPreviewData(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?
)

private val previewCache = ConcurrentHashMap<String, LinkPreviewData>()
private val failedUrls = ConcurrentHashMap.newKeySet<String>()

// Matches <meta property="og:title" content="..."> and variations
private val metaTagPattern = Regex(
    """<meta\s+([^>]+?)\/?>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private val attrPattern = Regex(
    """(property|name|content)\s*=\s*["']([^"']*?)["']""",
    RegexOption.IGNORE_CASE
)

private val titlePattern = Regex(
    """<title[^>]*>([^<]+)</title>""",
    RegexOption.IGNORE_CASE
)

private val youtubePattern = Regex(
    """(?:youtube\.com/watch\?.*v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})"""
)

private fun extractYoutubeVideoId(url: String): String? {
    return youtubePattern.find(url)?.groupValues?.get(1)
}

private suspend fun fetchYoutubePreview(url: String, videoId: String): LinkPreviewData? {
    return try {
        val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val connection = URL(oembedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        // Simple JSON parsing for title and author — no library needed
        val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        val author = Regex(""""author_name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")

        val imageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

        LinkPreviewData(
            url = url,
            title = title,
            description = author?.let { "YouTube · $it" },
            imageUrl = imageUrl
        )
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchLinkPreview(url: String): LinkPreviewData? = withContext(Dispatchers.IO) {
    previewCache[url]?.let { return@withContext it }
    if (failedUrls.contains(url)) return@withContext null

    try {
        // YouTube special handling — their HTML doesn't have OG tags without JS
        val youtubeId = extractYoutubeVideoId(url)
        if (youtubeId != null) {
            val result = fetchYoutubePreview(url, youtubeId)
            if (result != null) {
                previewCache[url] = result
                return@withContext result
            }
            failedUrls.add(url)
            return@withContext null
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.instanceFollowRedirects = true

        if (connection.responseCode != 200) {
            failedUrls.add(url)
            return@withContext null
        }

        val contentType = connection.contentType ?: ""
        if (!contentType.contains("text/html", ignoreCase = true) &&
            !contentType.contains("application/xhtml", ignoreCase = true)) {
            failedUrls.add(url)
            return@withContext null
        }

        // Read up to 64KB to find meta tags
        val html = connection.inputStream.bufferedReader().use { reader ->
            val buf = CharArray(65536)
            val sb = StringBuilder()
            var totalRead = 0
            while (totalRead < buf.size) {
                val read = reader.read(buf, 0, minOf(buf.size - totalRead, buf.size))
                if (read == -1) break
                sb.append(buf, 0, read)
                totalRead += read
                // Stop early if we've passed </head>
                if (sb.contains("</head>", ignoreCase = true)) break
            }
            sb.toString()
        }
        connection.disconnect()

        val meta = mutableMapOf<String, String>()

        // Parse all <meta> tags and extract property/name + content pairs
        metaTagPattern.findAll(html).forEach { metaMatch ->
            val attrs = metaMatch.groupValues[1]
            val pairs = mutableMapOf<String, String>()
            attrPattern.findAll(attrs).forEach { attrMatch ->
                pairs[attrMatch.groupValues[1].lowercase()] = attrMatch.groupValues[2]
            }
            val key = pairs["property"] ?: pairs["name"] ?: return@forEach
            val content = pairs["content"] ?: return@forEach
            meta.putIfAbsent(key.lowercase(), content)
        }

        // OG tags take priority, fall back to twitter cards, then <title>
        val title = meta["og:title"]
            ?: meta["twitter:title"]
            ?: titlePattern.find(html)?.groupValues?.get(1)?.trim()
        val description = meta["og:description"]
            ?: meta["twitter:description"]
            ?: meta["description"]
        var imageUrl = meta["og:image"]
            ?: meta["twitter:image"]
            ?: meta["twitter:image:src"]

        // Resolve relative image URLs
        if (imageUrl != null && !imageUrl.startsWith("http")) {
            try {
                val baseUri = URI(url)
                imageUrl = baseUri.resolve(imageUrl).toString()
            } catch (_: Exception) {}
        }

        if (title == null && description == null && imageUrl == null) {
            failedUrls.add(url)
            return@withContext null
        }

        val result = LinkPreviewData(
            url = url,
            title = title?.let { decodeHtmlEntities(it) },
            description = description?.let { decodeHtmlEntities(it) },
            imageUrl = imageUrl
        )
        previewCache[url] = result
        result
    } catch (e: Exception) {
        failedUrls.add(url)
        null
    }
}

private fun decodeHtmlEntities(text: String): String {
    return text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x27;", "'")
        .replace("&#x2F;", "/")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
}

@Composable
fun LinkPreviewCard(
    url: String,
    isOwnMessage: Boolean
) {
    var preview by remember(url) { mutableStateOf<LinkPreviewData?>(null) }
    var loaded by remember(url) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(url) {
        preview = fetchLinkPreview(url)
        loaded = true
    }

    if (!loaded || preview == null) return

    val data = preview!!
    val cardColor = if (isOwnMessage) Palette.ownPreviewCard else Palette.previewCard

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cardColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable { uriHandler.openUri(url) }
    ) {
        Column {
            data.imageUrl?.let { imgUrl ->
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.91f)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
            }

            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                // Domain
                Text(
                    text = try {
                        URL(url).host.removePrefix("www.")
                    } catch (e: Exception) { url },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                data.title?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Palette.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                data.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Palette.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
