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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    val news = RadioFilter("news", tag = "news", labelRes = R.string.filter_news)
    // Shared phone/car taxonomy; add new categories here so both surfaces stay aligned.
    val music = RadioFilter("music", tag = "music", labelRes = R.string.music)
    val sports = RadioFilter("sports", tag = "sports", labelRes = R.string.radio_sports)
    val talk = RadioFilter("talk", tag = "talk", labelRes = R.string.radio_talk)
    val culture = RadioFilter("culture", tag = "culture", labelRes = R.string.radio_culture)
    val all: List<RadioFilter> = listOf(local, world, news, music, sports, talk, culture, quran)
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
    private val directoryClient = client.newBuilder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
    private val stationList = ListSerializer(RadioStation.serializer())
    private val trackList = ListSerializer(Track.serializer())
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    @Serializable
    data class RadioLanguage(val name: String = "", val stationcount: Int = 0)

    suspend fun languages(): List<RadioLanguage> = radioLanguages.map { RadioLanguage(it.name, 1) }

    private val reviewed by lazy {
        context.assets.open("radio/reviewed-stations.json").bufferedReader().use {
            json.decodeFromString(ListSerializer(ReviewedRadioStation.serializer()), it.readText())
        }
    }

    private val unavailableStreams by lazy {
        context.assets.open("radio/unavailable-streams.json").bufferedReader().use {
            json.decodeFromString(ListSerializer(String.serializer()), it.readText()).toSet()
        }
    }

    suspend fun stations(
        filter: RadioFilter, latitude: Double? = null, longitude: Double? = null,
        countryCode: String? = null, language: String? = null,
    ): List<Track> = withContext(Dispatchers.IO) {
        val country = countryCode?.uppercase(Locale.ROOT)
        if (filter.id == RadioFilters.local.id && country == null) return@withContext emptyList()
        // Curated entries participate in every matching country/category, even
        // when the directory is offline or lacks language/category metadata.
        val curated = reviewed.filter { it.matches(filter, country, language) }.map { it.track() }
        val directory = directoryStations(filter, latitude, longitude, country, language)
        (curated + directory).filterNot { isRetiredRadioEntry(it.title, it.streamUrl.orEmpty()) || it.streamUrl in unavailableStreams }
            .distinctBy { it.streamUrl?.trimEnd('/') }
    }

    private suspend fun directoryStations(
        filter: RadioFilter,
        latitude: Double? = null,
        longitude: Double? = null,
        countryCode: String? = null,
        language: String? = null,
    ): List<Track> = withContext(Dispatchers.IO) { if (filter.id == RadioFilters.local.id) {
        if (countryCode.isNullOrBlank()) emptyList()
        else {
            val key = "local_v2_$countryCode"
            val fallback = { loadCache(key) ?: if (countryCode == "OM") fallbackStations(RadioFilters.oman) else emptyList() }
            try {
                val result = withContext(Dispatchers.IO) {
                    if (latitude != null && longitude != null) fetchNearby(latitude, longitude, countryCode)
                    else fetchCountry(countryCode)
                }
                if (result.isNotEmpty()) saveCache(key, result)
                result.ifEmpty(fallback)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fallback()
            }
        }
    } else try {
        val key = cacheKey(filter, countryCode, language)
        val result = withContext(Dispatchers.IO) { fetchFilter(filter.copy(language = language ?: filter.language), latitude, longitude, countryCode) }
        if (result.isNotEmpty()) saveCache(key, result)
        result.ifEmpty { loadCache(key) ?: if (language == null) fallbackStations(filter, countryCode) else emptyList() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        loadCache(cacheKey(filter, countryCode, language)) ?: if (language == null) fallbackStations(filter, countryCode) else emptyList()
    }

    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val curated = reviewed.filter { entry ->
            (listOf(entry.name) + entry.aliases).any { it.contains(query.trim(), ignoreCase = true) }
        }.map { it.track() }
        val directory = runCatching { fetch(mapOf(
            "name" to query,
            "hidebroken" to "true",
            "lastcheckok" to "1",
            "order" to "clickcount",
            "reverse" to "true",
            "limit" to "100",
        )).mapNotNull { it.toTrack() } }.getOrElse { e ->
            if (e is CancellationException) throw e
            emptyList()
        }
        (curated + directory).distinctBy { it.streamUrl }
    }

    suspend fun countries(): List<RadioCountry> = withContext(Dispatchers.IO) {
        for (base in servers.take(2)) {
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
        f.id == RadioFilters.world.id -> fetchGeneric(f.copy(countryCodes = listOfNotNull(countryCode)))
        else -> fetchGeneric(f.copy(countryCodes = listOfNotNull(countryCode).ifEmpty { f.countryCodes }))
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
    ).filter { it.countrycode.equals(countryCode, ignoreCase = true) }
        .distinctBy { it.stationuuid }.mapNotNull { it.toTrack() }
        .distinctBy { it.streamUrl }

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
            .distinctBy { it.streamUrl }
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
        // Country queries must include unlabeled stations. The previous exact
        // language predicate and 60-result cap hid most regional broadcasters.
        if (f.countryCodes.isEmpty()) {
            f.language?.let { base["language"] = normalizeRadioLanguage(it) }
            f.tag?.let { base["tag"] = it }
        }
        val stations = if (f.countryCodes.isEmpty()) {
            val languages = if (normalizeRadioLanguage(f.language.orEmpty()) == "arabic") listOf("arabic", "ar", "العربية") else listOf(f.language)
            languages.map { language -> async {
                runCatching { fetch(base + mapOf("limit" to "1000") + (language?.let { mapOf("language" to it) } ?: emptyMap())) }
                    .getOrDefault(emptyList())
            } }.awaitAll().flatten()
        } else {
            f.countryCodes.map { cc -> async { runCatching { fetch(base + mapOf("countrycode" to cc, "limit" to "1000")) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }
        stations.filter { station ->
            (f.countryCodes.isEmpty() || station.countrycode.uppercase(Locale.ROOT) in f.countryCodes) &&
                matchesRadioLanguage(station.reviewedEntry()?.language ?: station.language.ifBlank {
                    station.tags.split(',').firstOrNull { normalizeRadioLanguage(it) == normalizeRadioLanguage(f.language.orEmpty()) }.orEmpty()
                }, f.language) &&
                (station.reviewedEntry()?.let { f.tag == null || f.id in it.categories }
                    ?: matchesRadioCategory(f, station.name, station.tags))
        }
            .distinctBy { it.stationuuid }
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
        (officialOmanStations() + directory).distinctBy { it.streamUrl }
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
    private fun fallbackStations(filter: RadioFilter, countryCode: String? = null): List<Track> {
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
            RadioFilters.news.id -> emptyList()
            RadioFilters.world.id -> if (countryCode == "OM") oman else emptyList()
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

    private fun RadioStation.reviewedEntry(): ReviewedRadioStation? = reviewed.firstOrNull { entry ->
        entry.streamUrl.trimEnd('/') == url_resolved.ifBlank { url }.trimEnd('/') ||
            ((countrycode.isBlank() || countrycode.equals(entry.countryCode, true)) && (listOf(entry.name) + entry.aliases).any { it.equals(name.trim(), true) })
    }

    private fun RadioStation.toTrack(): Track? {
        if (isRetiredRadioEntry(name, url_resolved.ifBlank { url })) return null
        reviewedEntry()?.let { return it.track() }
        val stream = url_resolved.ifBlank { url }.trim()
        if (stream in unavailableStreams || stationuuid.isBlank() || lastcheckok != 1 || ssl_error != 0 || !isPlayableRadioUrl(stream)) return null
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
        for (base in servers.take(2)) {
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
        for (base in servers.take(2)) {
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
        directoryClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun cacheKey(filter: RadioFilter, countryCode: String?, language: String?): String {
        val languageKey = (language ?: filter.language ?: "all").map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "global_v2_${filter.id}_${countryCode ?: "all"}_$languageKey"
    }

    private val cacheLock = Any()
    private val cacheDirectory = File(context.cacheDir, "radio_metadata").apply { mkdirs() }
    private fun cacheFile(id: String) = File(cacheDirectory, com.mawja.player.data.digest(id) + ".json")
    private fun saveCache(id: String, tracks: List<Track>) = synchronized(cacheLock) {
        runCatching {
            val atomic = android.util.AtomicFile(cacheFile(id))
            val output = atomic.startWrite()
            try {
                output.write(json.encodeToString(trackList, tracks.take(1000)).toByteArray())
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            var size = 0L
            cacheDirectory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                size += file.length()
                if (index >= 64 || size > 16L * 1024 * 1024) file.delete()
            }
            // Previous releases stored expendable metadata in persistent files without limits.
            context.filesDir.listFiles()?.filter { it.name.startsWith("radio_") && it.name.endsWith(".json") }?.forEach { it.delete() }
        }
        Unit
    }
    private fun loadCache(id: String): List<Track>? = synchronized(cacheLock) {
        runCatching {
            val f = cacheFile(id)
            if (f.isFile && f.length() <= 4L * 1024 * 1024 &&
                System.currentTimeMillis() - f.lastModified() in 0..7L * 24 * 60 * 60 * 1000) {
                json.decodeFromString(trackList, android.util.AtomicFile(f).readFully().decodeToString())
                    .filter { isPlayableRadioUrl(it.streamUrl.orEmpty()) }.take(1000)
            } else null
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

}

/** Conservative news-only classification. Mixed-format stations remain in country listings. */
internal fun isNewsStation(@Suppress("UNUSED_PARAMETER") name: String, rawTags: String): Boolean {
    val tags = rawTags.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
    val newsTags = setOf("news", "news talk", "news/talk", "world news", "international news", "أخبار", "اخبار")
    val excluded = setOf("music", "pop", "rock", "dance", "quran", "coran", "religion", "religious", "variety", "general", "entertainment", "top40", "top 40", "sports", "sport", "قرآن", "قران", "القرآن الكريم", "دين", "ديني", "موسيقى", "أغاني", "اغاني", "رياضة", "رياضي", "jazz", "classical", "oldies", "electronic", "hip hop", "hip-hop", "soul", "r&b")
    return tags.any { it in newsTags } &&
        tags.none { tag -> tag in excluded || tag.endsWith(" music") || tag.startsWith("music ") }

}

internal fun isPlayableRadioUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
    return url.isHttps || listOf("internet-radio.com", "sharp-stream.com", "mosaiquefm.net")
        .any { url.host == it || url.host.endsWith(".$it") }
}
