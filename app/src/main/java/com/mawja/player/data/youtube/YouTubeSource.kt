package com.mawja.player.data.youtube

import android.net.Uri
import android.content.Context
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.data.youtubeThumbnailUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** YouTube search plus fresh audio/video stream resolution through Innertube and NewPipe. */
object YouTubeSource {
    private const val WEB_UA = "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36"
    private const val ANDROID_VR_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"
    private const val IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val IOS_BROWSER_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 Version/18.1 Mobile/15E148 Safari/604.1"
    private const val URL_TTL_MS = 45_000L
    private const val MAX_SEARCH_RESULTS = 100
    private const val SEARCH_PAGES = 2
    private const val INNERTUBE_SEARCH_PAGES = 3
    private const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val INNERTUBE_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    /** Used only after direct Innertube and NewPipe resolution fail. */
    private val pipedInstances = listOf(
        "https://pipedapi.ducks.party",
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.leptons.xyz",
        "https://api.piped.yt",
        "https://pipedapi.nosebs.ru",
        "https://piped-api.privacy.com.de",
        "https://pipedapi.owo.si",
        "https://piped-api.codespace.cz",
        "https://pipedapi.darkness.services",
        "https://pipedapi.orangenet.cc",
    )

    private data class InnertubeClient(val name: String, val version: String, val userAgent: String)
    private data class SearchSpec(val filter: String, val video: Boolean)

    private val clients = listOf(
        InnertubeClient("IOS", "20.10.4", IOS_UA),
        InnertubeClient("ANDROID_VR", "1.60.19", ANDROID_VR_UA),
        InnertubeClient("IOS", "20.10.4", IOS_BROWSER_UA),
    )
    private val searchSpecs = listOf(
        SearchSpec(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, video = false),
        SearchSpec(YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS, video = true),
        SearchSpec(YoutubeSearchQueryHandlerFactory.VIDEOS, video = true),
    )
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val userAgentCache = ConcurrentHashMap<String, String>()
    private val idRegex = Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private lateinit var client: OkHttpClient
    private lateinit var pipedClient: OkHttpClient
    private lateinit var appContext: Context
    @Volatile private var activeRegion = YouTubeRegion("OM", "ar")

    fun init(httpClient: OkHttpClient, context: Context) {
        client = httpClient
        appContext = context.applicationContext
        pipedClient = httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        NewPipe.init(OkHttpDownloader(httpClient), Localization("ar", "OM"), ContentCountry("OM"))
    }

    /**
     * Searches songs and music videos, walks two result pages for each filter,
     * removes duplicates, then ranks title/uploader matches before returning them.
     */
    suspend fun search(query: String, localDiscovery: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(120)
        if (clean.isBlank()) return@withContext emptyList()
        val region = RegionResolver.resolve(appContext)
        activeRegion = region
        NewPipe.init(OkHttpDownloader(client), Localization(region.language, region.countryCode), ContentCountry(region.countryCode))
        val effectiveQuery = if (localDiscovery) {
            if (region.arabicGulf) {
                listOf("\u0623\u063a\u0627\u0646\u064a \u062e\u0644\u064a\u062c\u064a\u0629 \u0639\u0631\u0628\u064a\u0629 \u0639\u0645\u0627\u0646\u064a\u0629", region.locality).filterNotNull().joinToString(" ")
            } else {
                listOf("local music", region.locality, region.countryCode).filterNotNull().joinToString(" ")
            }
        } else clean
        val newPipeResults = searchSpecs.flatMap { spec ->
            runCatching { searchFilter(effectiveQuery, spec, region) }.getOrDefault(emptyList())
        }
        val innertubeResults = runCatching { searchViaInnertube(effectiveQuery, region) }.getOrDefault(emptyList())
        (newPipeResults + innertubeResults)
            .distinctBy { it.id }
            .filter { !localDiscovery || !region.arabicGulf || hasArabicOrGulfText(it) }
            .sortedWith(
                compareByDescending<Track> { regionalScore(it, region) }
                    .thenByDescending { relevanceScore(it, effectiveQuery) }
                    .thenByDescending { it.durationMs },
            )
            .take(MAX_SEARCH_RESULTS)
    }

    private fun regionalScore(track: Track, region: YouTubeRegion): Int {
        val text = "${track.title} ${track.subtitle}"
        val arabic = text.count { it in '\u0600'..'\u06FF' }
        val gulf = listOf("\u062e\u0644\u064a\u062c", "\u0639\u0645\u0627\u0646", "\u0639\u0645\u0627\u0646\u064a", "\u0627\u0644\u0633\u0639\u0648\u062f\u064a\u0629", "\u0627\u0644\u0625\u0645\u0627\u0631\u0627\u062a", "\u0642\u0637\u0631", "\u0627\u0644\u0643\u0648\u064a\u062a", "\u0627\u0644\u0628\u062d\u0631\u064a\u0646")
            .count { text.contains(it, ignoreCase = true) }
        return if (region.arabicGulf) arabic * 3 + gulf * 20 else arabic
    }

    private fun hasArabicOrGulfText(track: Track): Boolean {
        val text = "${track.title} ${track.subtitle}"
        return text.any { it in '\u0600'..'\u06FF' } ||
            listOf("Gulf", "Oman", "Saudi", "Emirati", "Qatar", "Kuwait", "Bahrain")
                .any { text.contains(it, ignoreCase = true) }
    }

    /** YouTube's JSON search endpoint is more resilient than HTML extraction when its layout changes. */
    private fun searchViaInnertube(query: String, region: YouTubeRegion): List<Track> {
        var continuation: String? = null
        val result = ArrayList<Track>()
        repeat(INNERTUBE_SEARCH_PAGES) {
            val body = if (continuation == null) {
                buildJsonObject {
                    put("context", searchContext(region))
                    put("query", query)
                }
            } else {
                buildJsonObject {
                    put("context", searchContext(region))
                    put("continuation", continuation!!)
                }
            }
            val request = Request.Builder()
                .url(INNERTUBE_SEARCH_URL)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", WEB_UA)
                .header("Accept-Language", "${region.language}-${region.countryCode},${region.language};q=0.9")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()
            val page = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("YouTube search HTTP ${response.code}")
                parseSearchPage(json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject)
            }
            result += page.tracks
            continuation = page.continuation ?: return@repeat
        }
        return result.distinctBy { it.id }
    }

    private fun searchContext(region: YouTubeRegion): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", "WEB")
            put("clientVersion", "2.20251001.01.00")
            put("hl", region.language)
            put("gl", region.countryCode)
        })
    }

    private data class SearchPage(val tracks: List<Track>, val continuation: String?)

    private fun parseSearchPage(root: JsonObject): SearchPage {
        val tracks = ArrayList<Track>()
        var next: String? = null

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val videoId = element["videoId"]?.jsonPrimitive?.contentOrNull
                    if (videoId != null && videoId.length == 11 && element["title"] != null) {
                        val title = textOf(element["title"])
                        val uploader = textOf(
                            element["ownerText"] ?: element["longBylineText"] ?: element["shortBylineText"],
                        )
                        val artwork = element["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
                            ?.mapNotNull { thumbnail -> thumbnail.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
                            ?.lastOrNull()
                        if (title.isNotBlank()) {
                            tracks += Track(
                                id = "yt:$videoId",
                                source = SourceType.YOUTUBE,
                                title = title,
                                subtitle = uploader,
                                artist = uploader,
                                artworkUrl = youtubeThumbnailUrl(videoId, artwork),
                                isVideo = true,
                            )
                        }
                    }
                    if (next == null) {
                        next = element["continuationEndpoint"]?.jsonObject
                            ?.get("continuationCommand")?.jsonObject
                            ?.get("token")?.jsonPrimitive?.contentOrNull
                            ?: element["continuationCommand"]?.jsonObject
                                ?.get("token")?.jsonPrimitive?.contentOrNull
                    }
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return SearchPage(tracks.distinctBy { it.id }, next)
    }

    private fun textOf(element: JsonElement?): String {
        val objectValue = element as? JsonObject ?: return ""
        objectValue["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return objectValue["runs"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    }

    private fun searchFilter(query: String, spec: SearchSpec, region: YouTubeRegion): List<Track> {
        val handler = ServiceList.YouTube.getSearchQHFactory().fromQuery(query, listOf(spec.filter), region.countryCode)
        val extractor = ServiceList.YouTube.getSearchExtractor(handler)
        var page = extractor.initialPage
        var pageNumber = 0
        val result = ArrayList<Track>()
        while (pageNumber < SEARCH_PAGES) {
            result += page.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                val id = idRegex.find(item.url)?.groupValues?.get(1) ?: return@mapNotNull null
                Track(
                    id = "yt:$id",
                    source = SourceType.YOUTUBE,
                    title = item.name.orEmpty().ifBlank { "\u2014" },
                    subtitle = item.uploaderName.orEmpty(),
                    artist = item.uploaderName.orEmpty(),
                    artworkUrl = youtubeThumbnailUrl(id, item.thumbnails.maxByOrNull { thumbnail -> thumbnail.height }?.url),
                    durationMs = item.duration.coerceAtLeast(0) * 1000,
                    // YouTube search results are visual videos in Mawja. The player
                    // decides whether to use a progressive muxed stream or DASH.
                    isVideo = true,
                )
            }
            if (!page.hasNextPage()) break
            page = extractor.getPage(page.nextPage)
            pageNumber++
        }
        return result
    }

    private fun relevanceScore(track: Track, query: String): Int {
        val q = query.trim().lowercase(Locale.ROOT)
        val title = track.title.lowercase(Locale.ROOT)
        val artist = track.artist.lowercase(Locale.ROOT)
        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        var score = 0
        if (artist == q) score += 160
        if (artist.contains(q)) score += 110
        if (title == q) score += 120
        if (title.contains(q)) score += 80
        tokens.forEach { token ->
            if (artist.contains(token)) score += 30
            if (title.contains(token)) score += 20
        }
        if (track.isVideo) score += 2
        return score
    }

    fun resolveAudioUrl(watchUrl: String): String = resolveStreamUrl(watchUrl, preferVideo = false)

    /** Resolves either an audio-only stream or a progressive video-with-audio stream. */
    fun resolveStreamUrl(watchUrl: String, preferVideo: Boolean): String {
        val id = idRegex.find(watchUrl)?.groupValues?.get(1) ?: throw IOException("Bad YouTube URL")
        val key = "$id:${if (preferVideo) "video" else "audio"}"
        val now = System.currentTimeMillis()
        urlCache[key]?.let { (url, createdAt) -> if (now - createdAt < URL_TTL_MS) return url }
        val failures = mutableListOf<Throwable>()
        val resolvers = listOf<(String, Boolean) -> String>(
            { value, video -> resolveViaInnertube(value, video, activeRegion) },
            { value, video -> resolveViaNewPipe(value, video) },
            { value, video -> resolveViaPiped(value, video) },
        )
        for (resolver in resolvers) {
            try {
                val url = resolver(id, preferVideo)
                urlCache[key] = url to now
                return url
            } catch (error: Throwable) {
                failures += error
            }
        }
        urlCache.remove(key)
        val mode = if (preferVideo) "video" else "audio"
        throw IOException("YouTube $mode stream unavailable: ${failures.lastOrNull()?.message}", failures.lastOrNull())
    }

    private fun resolveViaInnertube(id: String, preferVideo: Boolean, region: YouTubeRegion): String {
        var lastError = "unknown"
        for (candidate in clients) {
            val body = if (candidate.name == "ANDROID_VR") {
                """{"context":{"client":{"clientName":"ANDROID_VR","clientVersion":"${candidate.version}","androidSdkVersion":34,"hl":"${region.language}","gl":"${region.countryCode}"}},"videoId":"$id"}"""
            } else {
                """{"context":{"client":{"clientName":"${candidate.name}","clientVersion":"${candidate.version}","hl":"${region.language}","gl":"${region.countryCode}"}},"videoId":"$id"}"""
            }
            val request = Request.Builder()
                .url(INNERTUBE_URL)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", candidate.userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                    val streaming = root["streamingData"]?.jsonObject
                    if (streaming == null) {
                        lastError = "${status ?: "unknown"}: no streaming data"
                        return@use
                    }
                    val progressive = streaming["formats"]?.jsonArray.orEmpty()
                    val adaptive = streaming["adaptiveFormats"]?.jsonArray.orEmpty()
                    val pool = if (preferVideo) progressive + adaptive else adaptive
                    val candidatesWithUrls = pool.mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val mime = item["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val url = item["url"]?.jsonPrimitive?.contentOrNull
                        if (url.isNullOrBlank()) return@mapNotNull null
                        val bitrate = item["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val width = item["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val height = item["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val hasAudio = mime.startsWith("audio/") || mime.contains("mp4a") || mime.contains("audio")
                        val hasVideo = mime.startsWith("video/") || mime.contains("avc1") || mime.contains("vp9")
                        if (preferVideo && !hasVideo) return@mapNotNull null
                        if (!preferVideo && !hasAudio) return@mapNotNull null
                        Triple(url, bitrate, width * height)
                    }
                    val best = if (preferVideo) {
                        candidatesWithUrls.maxByOrNull { it.third } ?: candidatesWithUrls.maxByOrNull { it.second }
                    } else {
                        candidatesWithUrls.maxByOrNull { it.second }
                    }
                    if (best != null) return best.first
                    lastError = "no suitable stream"
                }
            } catch (error: Throwable) {
                lastError = error.message ?: "exception"
            }
        }
        throw IOException("Innertube failed: $lastError")
    }

    private fun resolveViaNewPipe(id: String, preferVideo: Boolean): String {
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        return if (preferVideo) {
            info.videoStreams.maxByOrNull { it.resolution.length }?.url
                ?: info.videoOnlyStreams.maxByOrNull { it.resolution.length }?.url
                ?: throw IOException("No video stream from NewPipe")
        } else {
            info.audioStreams.maxByOrNull { it.bitrate }?.url
                ?: throw IOException("No audio stream from NewPipe")
        }
    }

    private fun resolveViaPiped(id: String, preferVideo: Boolean): String {
        for (instance in pipedInstances) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$id")
                    .header("User-Agent", WEB_UA)
                    .build()
                pipedClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val streams = if (preferVideo) root["videoStreams"]?.jsonArray else root["audioStreams"]?.jsonArray
                    val url = streams?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }?.firstOrNull()
                    if (!url.isNullOrBlank()) return url
                }
            } catch (_: Throwable) {
                // try next instance
            }
        }
        throw IOException("Piped instances failed")
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: NewPipeRequest): Response {
            val builder = Request.Builder().url(request.url())
            request.headers().forEach { (key, values) ->
                values.forEach { value -> builder.addHeader(key, value) }
            }
            if (request.httpMethod() == "POST") {
                builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody())
            }
            client.newCall(builder.build()).execute().use { response ->
                return Response(response.code, response.message, response.headers.toMultimap(), response.body?.bytes(), response.request.url.toString())
            }
        }
    }
}
