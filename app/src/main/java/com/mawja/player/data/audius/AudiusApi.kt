package com.mawja.player.data.audius

import com.mawja.player.BuildConfig
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

@Serializable
private data class AudiusResponse(val data: List<AudiusTrack> = emptyList())

@Serializable
private data class AudiusUserResponse(val data: List<JsonObject> = emptyList())

@Serializable
private data class AudiusTrack(
    val id: String = "",
    val title: String = "",
    val duration: Long = 0,
    val user: JsonObject? = null,
    val artwork: JsonObject? = null,
    val genre: String? = null,
    val is_streamable: Boolean? = true,
)

private data class AudiusUser(
    val id: String,
    val name: String,
    val handle: String,
    val trackCount: Int,
)

/** Audius public read API with artist-aware search and fresh stream URLs. */
class AudiusApi(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val base = "https://api.audius.co/v1"
    private val appName = "Mawja"

    suspend fun trending(): List<Track> = getTracks("/tracks/trending", limit = 40)

    /**
     * Searches the public track index and the user index. When a matching artist
     * profile exists, its own tracks are fetched as well, so an artist query is
     * not limited to the small subset returned by generic full-text search.
     */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(120)
        if (clean.isBlank()) return@withContext emptyList()

        val trackResults = runCatching {
            getTracks("/tracks/search", mapOf("query" to clean), limit = 100, offset = 0)
        }.getOrDefault(emptyList())
        val users = runCatching { getUsers(clean) }.getOrDefault(emptyList())
        val matchedUsers = users
            .map { it to userRelevance(it, clean) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }

        val ownedTracks = coroutineScope {
            matchedUsers.flatMap { user ->
                val offsets = listOf(0, 100).filter { offset -> offset == 0 || user.trackCount >= 100 }
                offsets.map { offset ->
                    async {
                        runCatching {
                            getTracks("/users/${user.id}/tracks", limit = 100, offset = offset)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }

        val relevantGeneric = trackResults.filter { track -> matchesQuery(track, clean) }
        val fallbackGeneric = if (relevantGeneric.size < 12) trackResults.filterNot { it in relevantGeneric } else emptyList()
        (ownedTracks + relevantGeneric + fallbackGeneric)
            .distinctBy { it.id }
            .take(120)
    }

    private suspend fun getUsers(query: String): List<AudiusUser> = withContext(Dispatchers.IO) {
        val url = buildUrl("/users/search", mapOf("query" to query, "limit" to "20", "offset" to "0"))
        val body = execute(url)
        json.decodeFromString<AudiusUserResponse>(body).data.mapNotNull { item ->
            val id = item.value("id").ifBlank { item.value("user_id") }
            if (id.isBlank()) return@mapNotNull null
            AudiusUser(
                id = id,
                name = item.value("name"),
                handle = item.value("handle"),
                trackCount = item.value("track_count").toIntOrNull() ?: 0,
            )
        }
    }

    private suspend fun getTracks(
        path: String,
        extra: Map<String, String> = emptyMap(),
        limit: Int,
        offset: Int = 0,
    ): List<Track> = withContext(Dispatchers.IO) {
        val params = extra + mapOf("limit" to limit.toString(), "offset" to offset.toString())
        val body = execute(buildUrl(path, params))
        json.decodeFromString<AudiusResponse>(body).data
            .filter { it.id.isNotBlank() && it.is_streamable != false }
            .map { it.toTrack() }
    }

    private fun buildUrl(path: String, params: Map<String, String>): String =
        (base + path).toHttpUrl().newBuilder()
            .addQueryParameter("app_name", appName)
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
            .toString()

    private fun execute(url: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mawja/1.0 (Android)")
        if (BuildConfig.AUDIUS_API_KEY.isNotBlank()) {
            builder.header("Authorization", "Bearer ${BuildConfig.AUDIUS_API_KEY}")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Audius HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun userRelevance(user: AudiusUser, query: String): Int {
        val q = query.trim().lowercase(Locale.ROOT)
        val name = user.name.lowercase(Locale.ROOT)
        val handle = user.handle.lowercase(Locale.ROOT)
        return when {
            name == q || handle == q -> 160
            name.contains(q) || handle.contains(q) -> 110
            q.split(Regex("\\s+")).any { it.length >= 2 && (name.contains(it) || handle.contains(it)) } -> 60
            else -> 0
        }
    }

    private fun matchesQuery(track: Track, query: String): Boolean {
        val q = query.lowercase(Locale.ROOT)
        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        return track.title.contains(q, true) || track.artist.contains(q, true) || track.subtitle.contains(q, true) ||
            tokens.any { token -> track.title.contains(token, true) || track.artist.contains(token, true) }
    }

    private fun AudiusTrack.toTrack(): Track {
        val owner = user ?: JsonObject(emptyMap())
        val artist = owner.value("name").ifBlank { owner.value("handle") }
        val cover = artwork?.value("480x480")
            ?: artwork?.value("1000x1000")
            ?: artwork?.value("150x150")
        return Track(
            id = "audius:$id",
            source = SourceType.AUDIUS,
            title = title.ifBlank { "—" },
            subtitle = artist.ifBlank { genre.orEmpty() },
            artist = artist,
            artworkUrl = cover,
            // The stream endpoint is resolved through Audius on every play.
            streamUrl = "$base/tracks/$id/stream?app_name=$appName",
            durationMs = duration * 1000,
        )
    }

    private fun JsonObject.value(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
