package com.mawja.player.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "mawja_library")

/** Favorites, recently played and user-added stations, stored as JSON in DataStore. */
class LibraryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Track.serializer())

    private val favKey = stringPreferencesKey("favorites")
    private val recentKey = stringPreferencesKey("recent")
    private val customKey = stringPreferencesKey("custom_stations")

    private fun decode(raw: String?): List<Track> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private fun Flow<Preferences>.asTracks(key: Preferences.Key<String>): Flow<List<Track>> =
        map { decode(it[key]) }

    val favorites: Flow<List<Track>> = context.dataStore.data.asTracks(favKey)
    val recent: Flow<List<Track>> = context.dataStore.data.asTracks(recentKey)
    val customStations: Flow<List<Track>> = context.dataStore.data.asTracks(customKey)

    private suspend fun edit(key: Preferences.Key<String>, transform: (List<Track>) -> List<Track>) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(serializer, transform(decode(prefs[key])))
        }
    }

    suspend fun toggleFavorite(track: Track) = edit(favKey) { list ->
        if (list.any { it.id == track.id }) list.filterNot { it.id == track.id } else listOf(track) + list
    }

    suspend fun addRecent(track: Track) = edit(recentKey) { list ->
        (listOf(track) + list.filterNot { it.id == track.id }).take(40)
    }

    suspend fun addCustomStation(name: String, url: String) {
        val clean = url.trim()
        val host = runCatching { java.net.URI(clean).host }.getOrNull().orEmpty()
        val track = Track(
            id = "custom:" + UUID.randomUUID(),
            source = SourceType.RADIO,
            title = name.trim().ifBlank { host.ifBlank { clean } },
            subtitle = host,
            streamUrl = clean,
            isLive = true,
        )
        edit(customKey) { listOf(track) + it }
    }

    suspend fun removeCustomStation(id: String) = edit(customKey) { list -> list.filterNot { it.id == id } }
}
