package com.mawja.player.data.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Validate a small GET range with playback headers, not a HEAD request or full download. */
internal class StreamProbe(private val client: OkHttpClient) {
    fun check(url: String, userAgent: String) {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-1023").header("Accept-Encoding", "identity")
            .header("User-Agent", userAgent).header("Referer", "https://www.youtube.com/").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Stream probe HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            if (type.isNotEmpty() && !type.startsWith("audio/") && !type.startsWith("video/") && type != "application/octet-stream") {
                throw IOException("Stream probe rejected non-media content")
            }
            val source = response.body?.source() ?: throw IOException("Empty media body")
            if (!source.request(1) || source.buffer[0L] == '<'.code.toByte()) throw IOException("Empty or document response")
        }
    }
}
