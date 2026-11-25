package com.mawja.player.playback

import android.annotation.SuppressLint
import android.Manifest
import android.app.SearchManager
import android.content.pm.PackageManager
import android.media.audiofx.Equalizer
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import androidx.media3.common.PlaybackException
import android.net.Uri
import android.os.Bundle
import android.location.LocationManager
import android.telephony.TelephonyManager
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
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mawja.player.AppContainer
import com.mawja.player.MawjaApp
import com.mawja.player.R
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.data.forCarAudio
import com.mawja.player.data.CAR_LOCAL_VIDEO_AUDIO_PREFIX
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.data.radio.RadioFilter
import com.mawja.player.data.radio.CarRadioBrowse
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
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Owns ExoPlayer, MediaLibrarySession, fresh source URLs, and the system Equalizer effect. */
@SuppressLint("UnsafeOptInUsageError")
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    // All AudioEffect binder/native calls, including release, belong to this serial worker.
    private var equalizer: Equalizer? = null
    private val effectDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MawjaAudioEffects").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val effectScope = CoroutineScope(SupervisorJob() + effectDispatcher)
    private val audioSessionIds = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var installedAudioSessionId = 0
    @Volatile private var effectsClosing = false
    private var retriedYoutubeId: String? = null
    private lateinit var redirectClient: OkHttpClient
    private val autoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoTracks = Collections.synchronizedMap(
        object : LinkedHashMap<String, Track>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean =
                size > 2048
        },
    )
    private val searchGate = Mutex()
    private val rewindCommand = SessionCommand("com.mawja.player.REWIND_LOCAL", Bundle.EMPTY)
    private val forwardCommand = SessionCommand("com.mawja.player.FORWARD_LOCAL", Bundle.EMPTY)
    private var showLocalSeekButtons = false
    private val appContainer: AppContainer
        get() = (application as MawjaApp).container

    override fun onCreate() {
        super.onCreate()
        val preferences = appContainer.preferences
        val saver = preferences.batterySaver.value
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
        val mediaSources = DefaultMediaSourceFactory(resolving)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(object : MediaSource.Factory by mediaSources {
                override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                    val source = mediaSources.createMediaSource(mediaItem)
                    return if (mediaItem.mediaId.startsWith(VIDEO_AUDIO_PREFIX) ||
                        (mediaItem.mediaId.startsWith("yt:") && mediaItem.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC)) {
                        FilteringMediaSource(source, C.TRACK_TYPE_AUDIO)
                    } else source
                }
            })
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
            override fun onEvents(player: Player, events: Player.Events) {
                val seekable = canSeekLocal(player)
                if (seekable == showLocalSeekButtons) return
                showLocalSeekButtons = seekable
                session?.setMediaButtonPreferences(if (seekable) localSeekButtons() else emptyList())
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (item?.mediaId != retriedYoutubeId) retriedYoutubeId = null
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = player.currentMediaItem
                val httpError = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                PlaybackDiagnostics.record("player source=${item?.mediaId?.substringBefore(':')} code=${error.errorCodeName} http=${httpError?.responseCode}")
                if (item?.mediaId?.startsWith("yt:") == true && retriedYoutubeId != item.mediaId && httpError?.responseCode in listOf(401, 403, 410)) {
                    retriedYoutubeId = item.mediaId
                    YouTubeSource.invalidateStream(item.mediaId.removePrefix("yt:"))
                    player.prepare()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                audioSessionIds.value = audioSessionId
            }
        })
        audioSessionIds.value = player.audioSessionId
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        autoScope.launch(Dispatchers.Main.immediate) {
            preferences.batterySaver.collect { enabled ->
                player.setWakeMode(if (enabled) C.WAKE_MODE_NONE else C.WAKE_MODE_NETWORK)
            }
        }
        effectScope.launch {
            combine(audioSessionIds, preferences.equalizerPreset) { id, preset -> id to preset }
                .conflate().collect { (id, preset) ->
                    if (!effectsClosing) {
                        if (id != installedAudioSessionId) installEqualizer(id)
                        applyEqualizerPreset(preset)
                    }
                }
        }
    }

    private fun installEqualizer(audioSessionId: Int) {
        check(Thread.currentThread().name == "MawjaAudioEffects")
        runCatching { equalizer?.release() }
        equalizer = null
        installedAudioSessionId = audioSessionId
        if (audioSessionId <= 0) return
        runCatching { equalizer = Equalizer(0, audioSessionId) }
            .onFailure { PlaybackDiagnostics.record("equalizer unavailable: ${it.javaClass.simpleName}") }
    }

    private fun applyEqualizerPreset(preset: String) {
        check(Thread.currentThread().name == "MawjaAudioEffects")
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

    private fun canSeekLocal(player: Player): Boolean {
        val id = player.currentMediaItem?.mediaId ?: return false
        return (id.startsWith("local:") || id.startsWith("external:") || id.startsWith(VIDEO_AUDIO_PREFIX)) &&
            player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    private fun localSeekButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName(getString(R.string.auto_rewind))
            .setSessionCommand(rewindCommand)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName(getString(R.string.auto_forward))
            .setSessionCommand(forwardCommand)
            .build(),
    )

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val isAndroidAuto = controller.packageName == ANDROID_AUTO_PACKAGE &&
                packageManager.getPackagesForUid(controller.uid)?.contains(ANDROID_AUTO_PACKAGE) == true
            if (controller.uid != applicationInfo.uid && !controller.isTrusted && !isAndroidAuto) {
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(rewindCommand)
                        .add(forwardCommand)
                        .build(),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val delta = when (customCommand.customAction) {
                rewindCommand.customAction -> -10_000L
                forwardCommand.customAction -> 10_000L
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val player = session.player
            if (!canSeekLocal(player)) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val target = (player.currentPosition + delta).coerceAtLeast(0L)
            val duration = player.duration
            player.seekTo(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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
            val all = runCatching { libraryChildren(parentId) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Library browse failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            LibraryResult.ofItemList(
                if (session.isAutoCompanionController(browser) || session.isAutomotiveController(browser)) all
                else pageItems(all, page, pageSize),
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
            val playable = mediaItems.mapNotNull { item ->
                if (controller.uid == applicationInfo.uid) {
                    val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                    uri?.let { item.buildUpon().setUri(it).build() }
                } else {
                    autoTracks[item.mediaId]?.forCarAudio()?.takeIf { !it.isVideo && it.playbackUri != null }?.toMediaItem()
                }
            }.toMutableList()
            return Futures.immediateFuture(playable)
        }
    }

    private suspend fun libraryChildren(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browseItem(RADIO, "Radio", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS),
            browseItem(ONLINE, "Online", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem("podcasts", getString(R.string.podcasts), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(DEVICE, "Device", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )
        parentId == "podcasts" -> {
            appContainer.podcasts.restore()
            appContainer.podcasts.subscriptions.value.map { feed ->
                browseItem("podcast-feed:" + com.mawja.player.data.digest(feed),
                    appContainer.podcasts.episodes.value.firstOrNull { it.folder == feed }?.album?.ifBlank { feed } ?: feed,
                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            }
        }
        parentId.startsWith("podcast-feed:") -> {
            appContainer.podcasts.restore()
            appContainer.podcasts.episodes.value.filter {
                "podcast-feed:" + com.mawja.player.data.digest(it.folder) == parentId
            }.take(200).map(::playableItem)
        }
        parentId == RADIO -> RadioFilters.all.map { filter ->
            radioCategoryItem(filter)
        }
        parentId == RADIO_LOCAL -> localRadioItems()
        parentId == ONLINE -> listOf(
            browseItem(ONLINE_AUDIO, "Audius", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browseItem(ONLINE_YOUTUBE, "YouTube", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        parentId == DEVICE -> listOf(
            browseItem(DEVICE_AUDIO, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browseItem(DEVICE_VIDEO, getString(R.string.auto_video_audio), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
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
        parentId.startsWith("radio:") -> CarRadioBrowse.category(parentId)?.let { filter ->
            carRadioStations(filter).map(::playableItem)
        }.orEmpty()
        parentId == ONLINE_AUDIO -> appContainer.audius.trending().map(::playableItem)
        parentId == ONLINE_YOUTUBE -> if (appContainer.youtubeData.isConfigured) {
            appContainer.youtubeData.search("new music", localDiscovery = true).map(::playableItem)
        } else {
            YouTubeSource.search("new music", localDiscovery = true).map(::playableItem)
        }
        parentId.startsWith("world:") -> {
            val country = parentId.removePrefix("world:").uppercase(Locale.ROOT)
            val stations = carRadioStations(RadioFilters.world, countryOverride = country)
            Log.d(TAG, "Android Auto radio country=$country stations=${stations.size}")
            stations.map(::playableItem)
        }
        else -> emptyList()
    }

    private fun radioCategoryItem(filter: RadioFilter): MediaItem = browseItem(
        CarRadioBrowse.id(filter),
        getString(requireNotNull(filter.labelRes)),
        if (filter.id == RadioFilters.news.id) MediaMetadata.MEDIA_TYPE_FOLDER_NEWS
        else MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
    )

    private suspend fun carRadioStations(filter: RadioFilter, countryOverride: String? = null): List<Track> {
        val preferences = appContainer.preferences
        val (country, language) = CarRadioBrowse.selection(filter, preferences.language.value, preferences::radioOption)
        return appContainer.radio.stations(filter, countryCode = countryOverride ?: country, language = language)
    }

    private suspend fun localRadioItems(): List<MediaItem> {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(LocationManager::class.java)
        val location = if (hasLocation && manager != null) {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time in 0L..21_600_000L }
                .maxByOrNull { it.time }
        } else null
        val locationCountry = location?.let {
            appContainer.radio.countryCodeFor(it.latitude, it.longitude)
        }
        val networkCountry = runCatching {
            getSystemService(TelephonyManager::class.java)?.networkCountryIso
        }.getOrNull()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        val country = locationCountry ?: networkCountry
        val stations = if (country == null) emptyList() else appContainer.radio.stations(
            RadioFilters.local,
            location?.latitude?.takeIf { locationCountry != null },
            location?.longitude?.takeIf { locationCountry != null },
            country,
        )
        return if (stations.isEmpty()) {
            listOf(browseItem(RADIO_WORLD, "World", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS))
        } else stations.map(::playableItem)
    }

    private suspend fun deviceCategoryItems(video: Boolean): List<MediaItem> = listOf(
        browseItem(deviceGroupPrefix(video, "folder"), "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browseItem(deviceGroupPrefix(video, "album"), "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseItem(deviceGroupPrefix(video, "artist"), "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
    )

    private suspend fun deviceGroupTracks(parentId: String, video: Boolean): List<MediaItem> {
        val prefix = if (video) DEVICE_VIDEO_GROUP else DEVICE_AUDIO_GROUP
        val remainder = parentId.removePrefix(prefix)
        val grouping = remainder.substringBefore(':')
        if (grouping !in setOf("folder", "album", "artist")) return emptyList()
        if (':' !in remainder) return deviceGroupItems(video, grouping)

        val name = Uri.decode(remainder.substringAfter(':'))
        return appContainer.local.load()
            .filter { it.isVideo == video && groupName(it, grouping, video).trim() == name }
            .map(::playableItem)
    }

    private suspend fun deviceGroupItems(video: Boolean, grouping: String): List<MediaItem> {
        val groups = appContainer.local.load()
            .filter { it.isVideo == video }
            .groupBy { groupName(it, grouping, video).trim() }
        val prefix = if (video) DEVICE_VIDEO_GROUP else DEVICE_AUDIO_GROUP
        return groups.toList().sortedBy { it.first.lowercase() }.map { (name, tracks) ->
            val safeName = Uri.encode(name.ifBlank { "Unknown" })
            browseItem(
                "$prefix$grouping:$safeName",
                "$name (${tracks.size})",
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

    private fun playableItem(original: Track): MediaItem {
        val audio = original.forCarAudio()
        val track = if (original.isVideo && original.source == SourceType.LOCAL) {
            audio.copy(subtitle = getString(R.string.auto_video_audio))
        } else audio
        autoTracks[track.id] = track
        return runCatching {
            val item = track.toMediaItem()
            if (!track.isVideo) item
            else item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build())
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "Failed to create media item: ${e.javaClass.simpleName}")
            // Minimal fallback item that Android Auto can still display
            MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title.ifBlank { "Unknown" })
                        .setIsBrowsable(false)
                        .setIsPlayable(!track.isVideo)
                        .setMediaType(if (track.isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
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
        RADIO_LOCAL -> radioCategoryItem(RadioFilters.local)
        RADIO_WORLD -> radioCategoryItem(RadioFilters.world)
        RADIO_NEWS -> radioCategoryItem(RadioFilters.news)
        ONLINE -> browseItem(ONLINE, "Online", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        ONLINE_AUDIO -> browseItem(ONLINE_AUDIO, "Audius", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        ONLINE_YOUTUBE -> browseItem(ONLINE_YOUTUBE, "YouTube", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        DEVICE -> browseItem(DEVICE, "Device", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        DEVICE_AUDIO -> browseItem(DEVICE_AUDIO, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        DEVICE_VIDEO -> browseItem(DEVICE_VIDEO, getString(R.string.auto_video_audio), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        LIBRARY -> browseItem(LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        else -> null
    }

    private fun dynamicBrowseItem(id: String): MediaItem? {
        CarRadioBrowse.category(id)?.let { return radioCategoryItem(it) }
        if (id.startsWith("podcast-feed:")) {
            val feed = appContainer.podcasts.subscriptions.value.firstOrNull {
                "podcast-feed:" + com.mawja.player.data.digest(it) == id
            } ?: return null
            return browseItem(id, appContainer.podcasts.episodes.value.firstOrNull { it.folder == feed }?.album?.ifBlank { feed } ?: feed,
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        }
        if (id.startsWith("world:")) {
            return browseItem(
                id,
                id.removePrefix("world:").uppercase(java.util.Locale.ROOT),
                MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            )
        }
        val audioPrefix = DEVICE_AUDIO_GROUP
        val videoPrefix = DEVICE_VIDEO_GROUP
        val prefix = when {
            id.startsWith(audioPrefix) -> audioPrefix
            id.startsWith(videoPrefix) -> videoPrefix
            else -> return null
        }
        val remainder = id.removePrefix(prefix)
        if (remainder.isBlank()) return null
        val grouping = remainder.substringBefore(':')
        val type = if (prefix == audioPrefix) {
            when (grouping) {
                "album" -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                "artist" -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            }
        } else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        val title = remainder.substringAfter(':', grouping).let(Uri::decode)
        return browseItem(id, title, type)
    }

    private fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        // Android Auto commonly uses an unset/zero page size for an unpaged
        // browse request. Treating page as an offset in that case turned valid
        // folders into "No items".
        if (pageSize <= 0 || page < 0) return items
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(from + pageSize, items.size.toLong()).toInt())
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = autoScope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) result.cancel(false)
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, Runnable::run)
        return result
    }

    // Legacy browsers bind with a placeholder identity before onConnect supplies the real caller.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        autoScope.cancel()
        autoTracks.clear()
        effectsClosing = true
        effectScope.launch {
            try { runCatching { equalizer?.release() }; equalizer = null }
            finally { effectScope.cancel(); effectDispatcher.close() }
        }
        session?.run {
            player.release()
            release()
        }
        session = null
        runCatching {
            redirectClient.dispatcher.cancelAll()
            redirectClient.dispatcher.executorService.shutdown()
            redirectClient.connectionPool.evictAll()
            redirectClient.cache?.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val VIDEO_AUDIO_PREFIX = CAR_LOCAL_VIDEO_AUDIO_PREFIX
        private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
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
    }
}
