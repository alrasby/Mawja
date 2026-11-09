package com.mawja.player.data.youtube

import android.content.Context
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.data.youtubeThumbnailUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/** Official YouTube Data API v3 client. It retrieves metadata only; playback remains separate. */
class YouTubeDataApi(
    private val context: Context,
    private val http: OkHttpClient,
    private val apiKey: String,
) {
    private companion object {
        // Required by Google APIs when an API key is restricted to Android apps.
        private const val ANDROID_PACKAGE = "com.mawja.player"
        private const val ANDROID_CERT_SHA1 = "CE35D0586631662EEBC52DFC46D3338D93CB9A76"
    }

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    suspend fun search(query: String, localDiscovery: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        if (!isConfigured || query.isBlank()) return@withContext emptyList()
        val region = RegionResolver.resolve(context)
        val effectiveQuery = if (localDiscovery) {
            if (region.arabicGulf) {
                listOf("أغاني خليجية عربية عمانية", region.locality).filterNotNull().joinToString(" ")
            } else {
                listOf("local music", region.locality, region.countryCode).filterNotNull().joinToString(" ")
            }
        } else query.trim().take(120)
        val builder = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("q", effectiveQuery)
            .addQueryParameter("videoCategoryId", "10")
            .addQueryParameter("maxResults", "50")
            .addQueryParameter("videoEmbeddable", "true")
            .addQueryParameter("videoSyndicated", "true")
            .addQueryParameter("safeSearch", "moderate")
            .addQueryParameter("regionCode", region.countryCode)
            .addQueryParameter("relevanceLanguage", region.language)
        if (localDiscovery && region.latitude != null && region.longitude != null) {
            builder.addQueryParameter("location", "${region.latitude},${region.longitude}")
                .addQueryParameter("locationRadius", "100km")
        }
        val searchRoot = request(
            builder.addQueryParameter("key", apiKey).build(),
        )
        val rawIdsAndTracks = searchRoot["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val objectItem = item.jsonObject
            val videoId = objectItem["id"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val snippet = objectItem["snippet"]?.jsonObject ?: return@mapNotNull null
            val title = snippet.string("title")
            if (title.isBlank()) return@mapNotNull null
            val thumbnails = snippet["thumbnails"]?.jsonObject
            val artwork = listOf("maxres", "standard", "high", "medium", "default")
                .asSequence()
                .mapNotNull { key -> thumbnails?.get(key)?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull }
                .firstOrNull()
            videoId to Track(
                id = "yt:$videoId",
                source = SourceType.YOUTUBE,
                title = title,
                subtitle = snippet.string("channelTitle"),
                artist = snippet.string("channelTitle"),
                artworkUrl = youtubeThumbnailUrl(videoId, artwork),
                isVideo = true,
            )
        }
        val idsAndTracks = rawIdsAndTracks.filter { (_, track) ->
            !localDiscovery || !region.arabicGulf || hasArabicOrGulfText(track)
        }
        if (idsAndTracks.isEmpty()) return@withContext emptyList()

        val details = runCatching {
            request(
                "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
                    .addQueryParameter("part", "contentDetails,status")
                    .addQueryParameter("id", idsAndTracks.joinToString(",") { it.first })
                    .addQueryParameter("key", apiKey)
                    .build(),
            )
        }.getOrNull()
        val durations = details?.get("items")?.jsonArray.orEmpty().associate { item ->
            val objectItem = item.jsonObject
            val id = objectItem["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val duration = objectItem["contentDetails"]?.jsonObject?.get("duration")?.jsonPrimitive?.contentOrNull.orEmpty()
            id to parseDurationMs(duration)
        }
        idsAndTracks
            .sortedWith(compareByDescending<Pair<String, Track>> { regionalScore(it.second, region) })
            .map { (id, track) -> track.copy(durationMs = durations[id] ?: 0L) }
    }

    private fun regionalScore(track: Track, region: YouTubeRegion): Int {
        val text = "${track.title} ${track.subtitle}"
        val arabic = text.count { it in '\u0600'..'\u06FF' }
        val gulf = listOf("خليج", "عمان", "عماني", "السعودية", "الإمارات", "قطر", "الكويت", "البحرين")
            .count { text.contains(it, ignoreCase = true) }
        return if (region.arabicGulf) arabic * 3 + gulf * 20 else arabic
    }

    private fun hasArabicOrGulfText(track: Track): Boolean {
        val text = "${track.title} ${track.subtitle}"
        return text.any { it in '\u0600'..'\u06FF' } ||
            listOf("Gulf", "Oman", "Saudi", "Emirati", "Qatar", "Kuwait", "Bahrain")
                .any { text.contains(it, ignoreCase = true) }
    }

    private fun request(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("X-Android-Package", ANDROID_PACKAGE)
            .header("X-Android-Cert", ANDROID_CERT_SHA1)
            .header("Accept", "application/json")
            .build()
        return http.newCall(request)
            .execute()
            .use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("YouTube Data API HTTP ${response.code}")
                json.parseToJsonElement(body).jsonObject
            }
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun parseDurationMs(value: String): Long {
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(value) ?: return 0L
        val hours = match.groups[1]?.value?.toLongOrNull() ?: 0L
        val minutes = match.groups[2]?.value?.toLongOrNull() ?: 0L
        val seconds = match.groups[3]?.value?.toLongOrNull() ?: 0L
        return (hours * 3600 + minutes * 60 + seconds) * 1000L
    }
}
