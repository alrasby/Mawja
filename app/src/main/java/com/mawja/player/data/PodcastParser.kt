package com.mawja.player.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory
import java.io.ByteArrayInputStream
import java.net.URI

/** Streaming RSS 2 / Atom parser: no HTML execution, DTDs or external entities. */
object PodcastParser {
    fun parse(bytes: ByteArray, feedUrl: String): List<Track> {
        require(bytes.size <= 2_097_152)
        // Reject declarations before parsing, including UTF-16 feeds.
        val scan = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        require(!scan.contains("<!DOCTYPE", true) && !scan.contains("<!ENTITY", true))
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val result = mutableListOf<Track>()
        val handler = object : DefaultHandler() {
            var item = false
            var title = ""; var show = ""; var url = ""; var image = ""; var feedImage = ""; var duration = 0L
            val text = StringBuilder()
            var depth = 0
            fun safe(value: String?): String = try {
                val uri = URI(feedUrl).resolve(value.orEmpty())
                if (uri.scheme == "https" && uri.host != null && uri.userInfo == null) uri.toString() else ""
            } catch (_: Exception) { "" }
            override fun resolveEntity(publicId: String?, systemId: String?) = org.xml.sax.InputSource(java.io.StringReader(""))
            override fun startElement(uri: String?, local: String, q: String, attrs: Attributes) {
                require(++depth <= 64)
                text.setLength(0)
                if (local == "item" || local == "entry") { item = true; title = ""; url = ""; image = ""; duration = 0 }
                if (local == "enclosure" || (local == "link" && attrs.getValue("rel") == "enclosure")) {
                    val type = attrs.getValue("type").orEmpty()
                    if (type.isEmpty() || type.startsWith("audio/")) url = safe(attrs.getValue("url") ?: attrs.getValue("href"))
                }
                if (local == "image" || local == "thumbnail") {
                    val value = safe(attrs.getValue("href") ?: attrs.getValue("url"))
                    if (item) image = value else feedImage = value
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                require(text.length + length <= 262144)
                text.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String, q: String) {
                val value = text.toString().trim()
                if (local == "title") { if (item) title = value else if (show.isEmpty()) show = value }
                if (item && local == "duration") duration = value.split(':').fold(0L) { acc, part -> (acc * 60 + (part.toLongOrNull() ?: 0)).coerceAtMost(604800) } * 1000
                if (local == "item" || local == "entry") {
                    if (url.isNotEmpty() && result.size < 200) result += Track(
                        id = "podcast:" + digest(feedUrl + "\n" + url), source = SourceType.PODCAST,
                        title = title.ifBlank { show }, subtitle = show, artist = show, album = show,
                        streamUrl = url, artworkUrl = image.ifBlank { feedImage }.ifBlank { null },
                        durationMs = duration, folder = feedUrl,
                    )
                    item = false
                }
                depth--
                text.setLength(0)
            }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        return result.distinctBy { it.id }
    }
}
