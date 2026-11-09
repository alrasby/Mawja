package com.mawja.player.data.radio

import android.content.Context
import android.location.Geocoder
import androidx.annotation.StringRes
import com.mawja.player.R
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** What the chips on the radio screen select. */
data class RadioFilter(
    val id: String,
    val countryCodes: List<String> = emptyList(),
    val language: String? = null,
    val tag: String? = null,
    @param:StringRes val labelRes: Int? = null,
)

@Serializable
data class RadioCountry(
    val name: String = "",
    val iso_3166_1: String = "",
    val stationcount: Int = 0,
)

object RadioFilters {
    val local = RadioFilter("local", labelRes = R.string.filter_local)
    val oman = RadioFilter("om", countryCodes = listOf("OM"), labelRes = R.string.filter_oman)
    val gulf = RadioFilter("gulf", countryCodes = listOf("OM", "SA", "AE", "KW", "QA", "BH"), labelRes = R.string.filter_gulf)
    val arab = RadioFilter("arab", language = "arabic", labelRes = R.string.filter_arab)
    val world = RadioFilter("world", labelRes = R.string.filter_world)
    val quran = RadioFilter("quran", tag = "quran", labelRes = R.string.filter_quran)
    val news = RadioFilter("news", tag = "news", language = "arabic", labelRes = R.string.filter_news)
    // Quran remains available to the media-session compatibility layer, but is
    // intentionally not exposed as a button in the visual Radio screen.
    val all: List<RadioFilter> = listOf(local, world, news)
}

object OmanSeeds {
    val ranking = listOf(
        "Oman Radio", "Radio Oman", "Oman General", "Oman Quran", "Quran Radio Oman", "Holy Quran",
        "Oman Youth", "Al Shabab", "Oman FM", "Hala", "Hi FM", "Merge", "Al Wisal", "Wisal",
        "Virgin Radio Oman", "Muscat FM", "Shabiba", "Sawt", "Dhofar", "Salalah",
    )
    val latinNames = listOf(
        "Oman Radio", "Radio Oman", "Oman General", "Oman Quran", "Quran Oman", "Oman Youth", "Oman FM",
        "Hala FM Oman", "Hi FM Oman", "Merge 104.8", "Al Wisal", "Virgin Radio Oman", "Muscat FM", "Shabiba",
        "T FM Oman", "Sawt Oman", "Dhofar", "Salalah", "Sohar", "Muscat", "Al Sumood", "Oman",
    )
    val arabicNames = listOf("عمان", "سلطنة عمان", "مسقط", "صلالة", "ظفار", "الوصال", "هلا", "الشبيبة")
    val tags = listOf("oman", "omani", "muscat", "salalah")
}

@Serializable
private data class RadioStation(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    val url_resolved: String = "",
    val favicon: String = "",
    val homepage: String = "",
    val tags: String = "",
    val countrycode: String = "",
    val country: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val clickcount: Int = 0,
    val lastcheckok: Int = 0,
    val ssl_error: Int = 0,
    val hls: Int = 0,
    val geo_lat: Double? = null,
    val geo_long: Double? = null,
)

/** Radio Browser directory with verified official Oman streams added as a deterministic fallback. */
class RadioRepository(private val context: Context, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val stationList = ListSerializer(RadioStation.serializer())
    private val trackList = ListSerializer(Track.serializer())
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    suspend fun stations(
        filter: RadioFilter,
        latitude: Double? = null,
        longitude: Double? = null,
        countryCode: String? = null,
    ): List<Track> = if (filter.id == RadioFilters.local.id) {
        // Local is deliberately strict: never use a cache or a popular/global
        // fallback, because a cached station may belong to a previous location.
        runCatching {
            withContext(Dispatchers.IO) { fetchFilter(filter, latitude, longitude, countryCode) }
        }.getOrDefault(emptyList())
    } else try {
        val key = cacheKey(filter, countryCode)
        val result = withContext(Dispatchers.IO) { fetchFilter(filter, latitude, longitude, countryCode) }
        if (result.isNotEmpty()) saveCache(key, result)
        result.ifEmpty { loadCache(key) ?: fallbackStations(filter) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        loadCache(cacheKey(filter, countryCode)) ?: fallbackStations(filter)
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        fetch(mapOf(
            "name" to query,
            "hidebroken" to "true",
            "lastcheckok" to "1",
            "order" to "clickcount",
            "reverse" to "true",
            "limit" to "100",
        )).mapNotNull { it.toTrack() }
    }

    suspend fun countries(): List<RadioCountry> = withContext(Dispatchers.IO) {
        for (base in servers) {
            runCatching {
                val response = request(base, "/json/countries", mapOf("order" to "name", "reverse" to "false", "limit" to "300"))
                json.decodeFromString(ListSerializer(RadioCountry.serializer()), response)
                    .filter { it.name.isNotBlank() && it.iso_3166_1.isNotBlank() && it.stationcount > 0 }
                    .distinctBy { it.iso_3166_1 }
                    .sortedBy { it.name.trim().lowercase() }
            }.getOrNull()?.let { return@withContext it }
        }
        emptyList()
    }

    suspend fun countClick(track: Track) {
        if (!track.id.startsWith("radio:") || track.id.startsWith("radio:official-")) return
        val uuid = track.id.removePrefix("radio:")
        withContext(Dispatchers.IO) { runCatching { fetchRaw("/json/url/$uuid", emptyMap()) } }
    }

    private suspend fun fetchFilter(f: RadioFilter, latitude: Double?, longitude: Double?, countryCode: String?): List<Track> = when {
        f.id == RadioFilters.local.id && latitude != null && longitude != null -> fetchNearby(latitude, longitude, countryCode)
        f.id == RadioFilters.local.id -> emptyList()
        f.id == RadioFilters.oman.id -> fetchOman()
        f.id == RadioFilters.world.id && !countryCode.isNullOrBlank() -> fetchCountry(countryCode)
        f.id == RadioFilters.world.id -> emptyList()
        else -> fetchGeneric(f)
    }

    private fun fetchCountry(countryCode: String): List<Track> = fetch(
        mapOf(
            "countrycode" to countryCode,
            "hidebroken" to "true",
            "lastcheckok" to "1",
            "order" to "name",
            "reverse" to "false",
            "limit" to "1000",
        ),
    ).distinctBy { it.stationuuid }.mapNotNull { it.toTrack() }

    private fun fetchNearby(latitude: Double, longitude: Double, countryCode: String?): List<Track> {
        // Local is country-scoped, not radius-scoped: the position determines
        // the country, then every verified station in that country is returned.
        // Stations that have coordinates are sorted nearest-first; directory
        // entries without coordinates remain at the end instead of disappearing.
        if (countryCode.isNullOrBlank()) return emptyList()
        val stations = fetch(
            mapOf(
            "countrycode" to countryCode,
            "hidebroken" to "true",
            "lastcheckok" to "1",
            "order" to "name",
            "reverse" to "false",
            "limit" to "1000",
            ),
        )
        return stations.asSequence()
            .filter { it.countrycode.equals(countryCode, ignoreCase = true) }
            .mapNotNull { station ->
                val distance = station.distanceMeters(latitude, longitude) ?: Double.MAX_VALUE
                station.toTrack()?.let { distance to it }
            }
            .sortedWith(compareBy<Pair<Double, Track>> { it.first }.thenBy { it.second.title.lowercase(Locale.ROOT) })
            .map { it.second }
            .distinctBy { it.streamUrl?.lowercase()?.trimEnd('/') }
            .toList()
    }

    @Suppress("DEPRECATION")
    suspend fun countryCodeFor(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()?.countryCode?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
        }.getOrNull()
    }

    private suspend fun fetchGeneric(f: RadioFilter): List<Track> = coroutineScope {
        val base = mutableMapOf(
            "hidebroken" to "true",
            "lastcheckok" to "1",
            "order" to "clickcount",
            "reverse" to "true",
        )
        f.language?.let { base["language"] = it; base["languageExact"] = "true" }
        f.tag?.let { base["tag"] = it; base["tagExact"] = "true" }
        val stations = if (f.countryCodes.isEmpty()) {
            fetch(base + ("limit" to "200"))
        } else {
            f.countryCodes.map { cc -> async { runCatching { fetch(base + mapOf("countrycode" to cc, "limit" to "60")) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }
        stations.distinctBy { it.stationuuid }
            .sortedByDescending { it.clickcount }
            .mapNotNull { it.toTrack() }
    }

    private suspend fun fetchOman(): List<Track> = coroutineScope {
        val base = mapOf("hidebroken" to "true", "lastcheckok" to "1", "order" to "clickcount", "reverse" to "true")
        val primary = async { runCatching { fetch(base + mapOf("countrycode" to "OM", "limit" to "300")) }.getOrDefault(emptyList()) }
        val queries = buildList<Map<String, String>> {
            add(mapOf("country" to "Oman", "countryExact" to "true", "limit" to "200"))
            OmanSeeds.tags.forEach { add(mapOf("tag" to it, "tagExact" to "true", "limit" to "100")) }
            (OmanSeeds.latinNames + OmanSeeds.arabicNames).forEach { add(mapOf("name" to it, "limit" to "40")) }
        }
        val found = ConcurrentLinkedQueue<RadioStation>()
        val gate = Semaphore(6)
        val wide = async {
            withTimeoutOrNull(12_000) {
                queries.map { q -> async { gate.withPermit { runCatching { fetch(base + q) }.getOrNull()?.let(found::addAll) } } }.awaitAll()
            }
        }
        val primaryItems = primary.await()
        wide.await()
        val directory = (primaryItems + found.toList().filter { it.isOmani() })
            .distinctBy { it.stationuuid }
            .distinctBy { it.url_resolved.ifBlank { it.url }.lowercase().trimEnd('/') }
            .sortedWith(compareBy<RadioStation> { it.seedRank() }.thenByDescending { it.clickcount })
            .mapNotNull { it.toTrack() }
        (officialOmanStations() + directory).distinctBy { it.streamUrl?.lowercase()?.trimEnd('/') }
    }

    /** These are the two official Oman Ministry/Ayn endpoints currently published in the directory. */
    private fun officialOmanStations(): List<Track> = listOf(
        Track(
            id = "radio:official-oman-english",
            source = SourceType.RADIO,
            title = "Oman English Radio",
            subtitle = "Official Oman • 90.4 FM",
            streamUrl = "https://partwota.cdn.mgmlcdn.com/omengrdoorg/omengrdo.stream_aac/chunklist.m3u8",
            isLive = true,
        ),
        Track(
            id = "radio:official-oman-quran",
            source = SourceType.RADIO,
            title = "Oman Quran Radio",
            subtitle = "Official Oman • القرآن الكريم",
            streamUrl = "https://partwota.cdn.mgmlcdn.com/quranrdoorg/quranrdo.stream_aac/chunklist.m3u8",
            isLive = true,
        ),
    )

    /** Small, verified fallback set so a temporary directory outage never blanks the radio screen. */
    private fun fallbackStations(filter: RadioFilter): List<Track> {
        val oman = officialOmanStations() + listOf(
            direct("radio:fallback-hala", "Hala FM", "Oman • Arabic", "https://listen-halafm.sharp-stream.com/halafmlow.mp3"),
            direct("radio:fallback-wisal", "Al Wisal", "Oman • Arabic", "http://uk7.internet-radio.com:8067/;"),
            direct("radio:fallback-hi", "Hi FM Oman", "Oman • English", "http://listen-hifmtemp.sharp-stream.com/hifmmid.mp3"),
            direct("radio:fallback-virgin-oman", "Virgin Radio Oman", "Oman • English", "http://uk5.internet-radio.com:8115/stream"),
        )
        val arabic = listOf(
            direct("radio:fallback-arabiya", "Al Arabiya FM", "Arabic • News", "https://fm.alarabiya.net/fm/myStream/playlist.m3u8"),
            direct("radio:fallback-mosaique", "Mosaique FM", "Tunisia • Arabic", "http://radio.mosaiquefm.net:8000/mosalive"),
        )
        val quran = listOf(
            direct("radio:fallback-maher", "Maher Al-Muaiqly Quran", "Quran", "https://backup.qurango.net/radio/maher"),
        )
        return when (filter.id) {
            RadioFilters.oman.id -> oman
            RadioFilters.gulf.id -> (oman + arabic).distinctBy { it.streamUrl }
            RadioFilters.arab.id -> arabic
            RadioFilters.quran.id -> quran + officialOmanStations().last()
            RadioFilters.news.id -> arabic.filter { it.title.contains("Arabiya", ignoreCase = true) }
            RadioFilters.world.id -> oman.filter { it.subtitle.contains("English", ignoreCase = true) }
            else -> emptyList()
        }
    }

    private fun direct(id: String, title: String, subtitle: String, stream: String) = Track(
        id = id,
        source = SourceType.RADIO,
        title = title,
        subtitle = subtitle,
        streamUrl = stream,
        isLive = true,
    )

    private val omanWord = Regex("\\boman(i)?\\b", RegexOption.IGNORE_CASE)

    private fun RadioStation.isOmani(): Boolean {
        if (countrycode.equals("OM", ignoreCase = true)) return true
        if (country.equals("Oman", ignoreCase = true) || country.equals("Sultanate of Oman", ignoreCase = true)) return true
        if (countrycode.isNotBlank()) return false
        val host = runCatching { URI(homepage).host }.getOrNull().orEmpty()
        return host.endsWith(".om") || omanWord.containsMatchIn(name) || name.contains("عمان") || tags.split(',').any { it.trim().equals("oman", ignoreCase = true) }
    }

    private fun RadioStation.seedRank(): Int = OmanSeeds.ranking.indexOfFirst { name.contains(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it }

    private fun RadioStation.distanceMeters(latitude: Double, longitude: Double): Double? {
        val stationLat = geo_lat ?: return null
        val stationLon = geo_long ?: return null
        val earth = 6_371_000.0
        val dLat = Math.toRadians(stationLat - latitude)
        val dLon = Math.toRadians(stationLon - longitude)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(latitude)) * kotlin.math.cos(Math.toRadians(stationLat)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * earth * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun RadioStation.toTrack(): Track? {
        val stream = url_resolved.ifBlank { url }.trim()
        if (stream.isBlank() || stationuuid.isBlank() || ssl_error != 0) return null
        val quality = codec.takeIf { it.isNotBlank() }?.uppercase()?.let { c -> if (bitrate > 0) "$c ${bitrate}k" else c }
        return Track(
            id = "radio:$stationuuid",
            source = SourceType.RADIO,
            title = name.trim().ifBlank { "Radio" },
            subtitle = listOfNotNull(country.ifBlank { countrycode }.ifBlank { null }, language.ifBlank { null }, quality).joinToString(" • "),
            artworkUrl = normalizeFavicon(favicon),
            streamUrl = stream,
            isLive = true,
        )
    }

    private fun normalizeFavicon(value: String): String? {
        val favicon = value.trim().takeIf { it.isNotBlank() } ?: return null
        return when {
            favicon.startsWith("https://", ignoreCase = true) -> favicon
            favicon.startsWith("http://", ignoreCase = true) -> "https://" + favicon.substring(7)
            favicon.startsWith("//") -> "https:$favicon"
            else -> null
        }
    }

    private fun fetch(params: Map<String, String>): List<RadioStation> {
        var last: IOException? = null
        for (base in servers) {
            try {
                val result = json.decodeFromString(stationList, request(base, "/json/stations/search", params))
                if (result.isNotEmpty()) return result
            } catch (e: IOException) {
                last = e
            } catch (e: Exception) {
                last = IOException(e.message ?: "Invalid radio response", e)
            }
        }
        if (last != null) throw last
        return emptyList()
    }

    private fun fetchRaw(path: String, params: Map<String, String>): String {
        var last: IOException? = null
        for (base in servers) {
            try { return request(base, path, params) } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("No radio server reachable")
    }

    private fun request(base: String, path: String, params: Map<String, String>): String {
        val url = (base + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mawja/1.1 (Android)")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun cacheKey(filter: RadioFilter, countryCode: String?): String =
        if (filter.id == RadioFilters.world.id && !countryCode.isNullOrBlank()) "world_$countryCode" else filter.id

    private fun cacheFile(id: String) = File(context.filesDir, "radio_$id.json")
    private fun saveCache(id: String, tracks: List<Track>) { runCatching { cacheFile(id).writeText(json.encodeToString(trackList, tracks)) } }
    private fun loadCache(id: String): List<Track>? = runCatching {
        val f = cacheFile(id)
        if (f.isFile) json.decodeFromString(trackList, f.readText()) else null
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
