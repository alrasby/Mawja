package com.mawja.player.playback

import android.annotation.SuppressLint
import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mawja.player.AppContainer
import com.mawja.player.MawjaApp
import com.mawja.player.data.Track
import com.mawja.player.data.PreferencesStore
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.data.youtube.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Owns ExoPlayer, MediaLibrarySession, fresh source URLs, and the system Equalizer effect. */
@SuppressLint("UnsafeOptInUsageError")
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var equalizer: Equalizer? = null
    private lateinit var redirectClient: OkHttpClient
    private val autoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoTracks = ConcurrentHashMap<String, Track>()
    private val searchGate = Mutex()
    private val appContainer: AppContainer
        get() = (application as MawjaApp).container

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PreferencesStore.PREFS_NAME, MODE_PRIVATE)
        val saver = prefs.getBoolean(PreferencesStore.KEY_BATTERY_SAVER, false)
        redirectClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mawja/1.4 (Android)",
                    "Accept" to "*/*",
                ),
            )
        val upstream = DefaultDataSource.Factory(this, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 1_000, 5_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(if (saver) C.WAKE_MODE_NONE else C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                installEqualizer(audioSessionId)
            }
        })
        installEqualizer(player.audioSessionId)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    private fun installEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        runCatching {
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
            applyEqualizerPreset(
                getSharedPreferences(PreferencesStore.PREFS_NAME, MODE_PRIVATE)
                    .getString(PreferencesStore.KEY_EQ_PRESET, "flat").orEmpty(),
            )
        }
    }

    private fun applyEqualizerPreset(preset: String) {
        val eq = equalizer ?: return
        runCatching {
            if (!eq.enabled) eq.enabled = true
            val range = eq.bandLevelRange
            val low = range[0].toInt()
            val high = range[1].toInt()
            for (index in 0 until eq.numberOfBands.toInt()) {
                val hz = eq.getCenterFreq(index.toShort()) / 1000
                val amount = when (preset) {
                    "bass" -> when { hz < 250 -> .86f; hz < 1_000 -> .66f; else -> .50f }
                    "vocal" -> when { hz < 250 -> .40f; hz < 4_000 -> .73f; else -> .58f }
                    "treble" -> when { hz < 1_000 -> .42f; hz < 4_000 -> .61f; else -> .84f }
                    else -> .50f
                }
                eq.setBandLevel(index.toShort(), (low + ((high - low) * amount)).toInt().toShort())
            }
        }
    }

    private fun resolve(spec: DataSpec): DataSpec {
        val uri = spec.uri
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        if (host == "api.audius.co" && path.contains("/tracks/") && path.endsWith("/stream")) {
            val fresh = resolveAudiusRedirects(uri.toString())
            return spec.buildUpon()
                .setUri(Uri.parse(fresh))
                .setHttpRequestHeaders(mapOf("User-Agent" to "Mawja/1.4 (Android)", "Accept" to "audio/mpeg"))
                .build()
        }

        val youtubeHost = host == "www.youtube.com" || host == "youtube.com" || host == "music.youtube.com"
        if (youtubeHost && path == "/watch") {
            val wantsVideo = uri.getQueryParameter("mawja_video") == "1"
            val direct = try {
                YouTubeSource.resolveStreamUrl(uri.toString(), preferVideo = wantsVideo)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
            return spec.buildUpon()
                .setUri(Uri.parse(direct))
                .setHttpRequestHeaders(
                    mapOf(
                        "User-Agent" to YouTubeSource.userAgentFor(direct),
                        "Accept" to "*/*",
                        "Referer" to "https://www.youtube.com/",
                    ),
                )
                .build()
        }
        return spec
    }

    private fun resolveAudiusRedirects(start: String): String {
        var current = start
        repeat(5) {
            redirectClient.newCall(
                Request.Builder()
                    .url(current)
                    .get()
                    .header("User-Agent", "Mawja/1.4 (Android)")
                    .header("Accept", "audio/mpeg")
                    .build(),
            ).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: throw IOException("Audius redirect without location")
                    if (!location.startsWith("https://", ignoreCase = true)) {
                        throw IOException("Rejected non-HTTPS Audius redirect")
                    }
                    current = response.request.url.resolve(location)?.toString() ?: location
                } else if (response.isSuccessful) {
                    return current
                } else {
                    throw IOException("Audius stream HTTP ${response.code}")
                }
            }
        }
        throw IOException("Audius redirect chain is too long")
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseItem(ROOT, "Mawja", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> = future {
            val all = libraryChildren(parentId)
            LibraryResult.ofItemList(
                com.google.common.collect.ImmutableList.copyOf(pageItems(all, page, pageSize)),
                params,
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            autoTracks[mediaId]?.let { track ->
                return Futures.immediateFuture(LibraryResult.ofItem(playableItem(track), null))
            }
            return future {
                val item = staticItem(mediaId)
                    ?: dynamicBrowseItem(mediaId)
                    ?: libraryChildren(ROOT).firstOrNull { it.mediaId == mediaId }
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid(params))

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> = future {
            val tracks = searchAllSources(query)
            LibraryResult.ofItemList(pageItems(tracks.map(::playableItem), page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems.map { item ->
                item.buildUpon().setUri(item.requestMetadata.mediaUri ?: item.localConfiguration?.uri).build()
            }.toMutableList()
            return Futures.immediateFuture(playable)
        }
    }

    private suspend fun libraryChildren(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browseItem(RADIO, "Radio", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            browseItem(ONLINE, "Online", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(DEVICE, "Device", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )
        parentId == RADIO -> listOf(
            browseItem(RADIO_LOCAL, "Local", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            browseItem(RADIO_WORLD, "World", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            browseItem(RADIO_NEWS, "News", MediaMetadata.MEDIA_TYPE_FOLDER_NEWS),
        )
        parentId == RADIO_LOCAL -> localRadioItems()
        parentId == ONLINE -> listOf(
            browseItem(ONLINE_AUDIO, "Audius", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(ONLINE_YOUTUBE, "YouTube", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        parentId == DEVICE -> listOf(
            browseItem(DEVICE_AUDIO, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browseItem(DEVICE_VIDEO, "Videos", MediaMetadata.MEDIA_TYPE_FOLDER_MOVIES),
        )
        parentId == DEVICE_AUDIO -> deviceCategoryItems(video = false)
        parentId == DEVICE_VIDEO -> deviceCategoryItems(video = true)
        parentId.startsWith(DEVICE_AUDIO_GROUP) -> deviceGroupTracks(parentId, video = false)
        parentId.startsWith(DEVICE_VIDEO_GROUP) -> deviceGroupTracks(parentId, video = true)
        parentId == LIBRARY -> coroutineScope {
            val favorites = async { appContainer.library.favorites.first() }
            val recent = async { appContainer.library.recent.first() }
            val custom = async { appContainer.library.customStations.first() }
            (favorites.await() + recent.await() + custom.await()).distinctBy { it.id }.map(::playableItem)
        }
        parentId == RADIO_WORLD -> appContainer.radio.countries().map {
            browseItem("world:${it.iso_3166_1}", "${it.name} (${it.stationcount})", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        }
        parentId == RADIO_NEWS -> appContainer.radio.stations(RadioFilters.news).map(::playableItem)
        parentId == ONLINE_AUDIO -> appContainer.audius.trending().map(::playableItem)
        parentId == ONLINE_YOUTUBE -> if (appContainer.youtubeData.isConfigured) {
            appContainer.youtubeData.search("new music", localDiscovery = true).map(::playableItem)
        } else {
            YouTubeSource.search("new music", localDiscovery = true).map(::playableItem)
        }
        parentId.startsWith("world:") -> {
            val country = parentId.removePrefix("world:").uppercase(Locale.ROOT)
            val stations = appContainer.radio.stations(RadioFilters.world, countryCode = country)
            Log.d(TAG, "Android Auto radio country=$country stations=${stations.size}")
            stations.map(::playableItem)
        }
        else -> emptyList()
    }

    private suspend fun localRadioItems(): List<MediaItem> {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return emptyList()
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return emptyList()
        val country = appContainer.radio.countryCodeFor(location.latitude, location.longitude) ?: return emptyList()
        return appContainer.radio.stations(RadioFilters.local, location.latitude, location.longitude, country).map(::playableItem)
    }

    private suspend fun deviceCategoryItems(video: Boolean): List<MediaItem> = listOf(
        browseItem(deviceGroupPrefix(video, "folder"), "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browseItem(deviceGroupPrefix(video, "album"), "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseItem(deviceGroupPrefix(video, "artist"), "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
    )

    private suspend fun deviceGroupTracks(parentId: String, video: Boolean): List<MediaItem> {
        val prefix = if (video) DEVICE_VIDEO_GROUP else DEVICE_AUDIO_GROUP
        val remainder = parentId.removePrefix(prefix)
        val separator = remainder.indexOf(':')
        if (separator <= 0) return deviceGroupItems(video, remainder)

        val grouping = remainder.substring(0, separator)
        val encodedName = remainder.substring(separator + 1)
        val name = Uri.decode(encodedName)

        val tracks = appContainer.local.load()
            .filter { it.isVideo == video && groupName(it, grouping, video) == name }

        if (tracks.isEmpty()) {
            val fallback = appContainer.local.load()
                .filter { it.isVideo == video && groupName(it, grouping, video) == encodedName }
            return fallback.map(::playableItem)
        }
        return tracks.map(::playableItem)
    }

    private suspend fun deviceGroupItems(video: Boolean, grouping: String): List<MediaItem> {
        val groups = appContainer.local.load()
            .filter { it.isVideo == video }
            .groupBy { groupName(it, grouping, video) }
        val prefix = if (video) DEVICE_VIDEO_GROUP else DEVICE_AUDIO_GROUP
        return groups.toList().sortedBy { it.first.lowercase() }.map { (name, tracks) ->
            browseItem(
                "$prefix$grouping:${Uri.encode(name)}",
                name,
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            )
        }
    }

    private fun groupName(track: Track, grouping: String, video: Boolean): String = when (grouping) {
        "folder" -> track.folder.ifBlank { "Unknown folder" }
        "album" -> track.album.ifBlank { track.folder.ifBlank { if (video) "Videos" else "Unknown album" } }
        "artist" -> track.artist.ifBlank { track.folder.ifBlank { if (video) "Videos" else "Unknown artist" } }
        else -> "Unknown"
    }

    private fun deviceGroupPrefix(video: Boolean, grouping: String): String =
        (if (video) DEVICE_VIDEO_GROUP else DEVICE_AUDIO_GROUP) + grouping

    private suspend fun searchAllSources(rawQuery: String): List<Track> = searchGate.withLock {
        val query = rawQuery.trim().take(120)
        if (query.isBlank()) return@withLock emptyList()
        withTimeoutOrNull(20_000L) {
            coroutineScope {
                val sources = listOf(
                    async { runCatching { appContainer.local.load().filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) || it.folder.contains(query, true) }.take(40) }.getOrDefault(emptyList()) },
                    async { runCatching { appContainer.radio.search(query).take(40) }.getOrDefault(emptyList()) },
                    async { runCatching { appContainer.audius.search(query).take(40) }.getOrDefault(emptyList()) },
                    async { runCatching { YouTubeSource.search(query).take(40) }.getOrDefault(emptyList()) },
                    async { runCatching { appContainer.library.favorites.first().filter { it.title.contains(query, true) || it.subtitle.contains(query, true) }.take(40) }.getOrDefault(emptyList()) },
                )
                sources.awaitAll().flatten().distinctBy { it.id }.take(120)
            }
        }.orEmpty()
    }

    private fun playableItem(track: Track): MediaItem {
        if (autoTracks.size > 512) autoTracks.clear()
        autoTracks[track.id] = track
        val item = track.toMediaItem()
        if (!track.isVideo) return item
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build())
            .build()
    }

    private fun browseItem(id: String, title: String, mediaType: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build(),
            )
            .build()

    private fun staticItem(id: String): MediaItem? = when (id) {
        ROOT -> browseItem(ROOT, "Mawja", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        RADIO -> browseItem(RADIO, "Radio", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        RADIO_LOCAL -> browseItem(RADIO_LOCAL, "Local", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        RADIO_WORLD -> browseItem(RADIO_WORLD, "World", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        RADIO_NEWS -> browseItem(RADIO_NEWS, "News", MediaMetadata.MEDIA_TYPE_FOLDER_NEWS)
        ONLINE -> browseItem(ONLINE, "Online", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        ONLINE_AUDIO -> browseItem(ONLINE_AUDIO, "Audius", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        ONLINE_YOUTUBE -> browseItem(ONLINE_YOUTUBE, "YouTube", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        DEVICE -> browseItem(DEVICE, "Device", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        DEVICE_AUDIO -> browseItem(DEVICE_AUDIO, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        DEVICE_VIDEO -> browseItem(DEVICE_VIDEO, "Videos", MediaMetadata.MEDIA_TYPE_FOLDER_MOVIES)
        LIBRARY -> browseItem(LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        else -> null
    }

    private fun dynamicBrowseItem(id: String): MediaItem? {
        if (id.startsWith("world:")) {
            return browseItem(id, id.removePrefix("world:"), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
        }
        return null
    }

    private fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0) return items
        val from = page * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from, minOf(from + pageSize, items.size))
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        autoScope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        autoScope.cancel()
        autoTracks.clear()
        equalizer?.release()
        equalizer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        runCatching {
            redirectClient.dispatcher.executorService.shutdown()
            redirectClient.connectionPool.evictAll()
            redirectClient.cache?.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MawjaAuto"
        private const val ROOT = "root"
        private const val RADIO = "radio"
        private const val ONLINE = "online"
        private const val DEVICE = "device"
        private const val LIBRARY = "library"
        private const val RADIO_LOCAL = "radio:local"
        private const val RADIO_WORLD = "radio:world"
        private const val RADIO_NEWS = "radio:news"
        private const val ONLINE_AUDIO = "online:audius"
        private const val ONLINE_YOUTUBE = "online:youtube"
        private const val DEVICE_AUDIO = "device:audio"
        private const val DEVICE_VIDEO = "device:video"
        private const val DEVICE_AUDIO_GROUP = "device:audio:group:"
        private const val DEVICE_VIDEO_GROUP = "device:video:group:"
        const val ACTION_SET_EQUALIZER = "com.mawja.player.SET_EQUALIZER"
        const val ACTION_SET_BATTERY_SAVER = "com.mawja.player.SET_BATTERY_SAVER"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ENABLED = "enabled"

        fun setEqualizerPreset(context: Context, preset: String) {
            val app = context.applicationContext
            app.getSharedPreferences(PreferencesStore.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PreferencesStore.KEY_EQ_PRESET, preset).apply()
            runCatching { app.startService(Intent(app, PlaybackService::class.java).setAction(ACTION_SET_EQUALIZER).putExtra(EXTRA_PRESET, preset)) }
        }

        fun setBatterySaver(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            app.getSharedPreferences(PreferencesStore.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PreferencesStore.KEY_BATTERY_SAVER, enabled).apply()
            runCatching { app.startService(Intent(app, PlaybackService::class.java).setAction(ACTION_SET_BATTERY_SAVER).putExtra(EXTRA_ENABLED, enabled)) }
        }
    }
}
