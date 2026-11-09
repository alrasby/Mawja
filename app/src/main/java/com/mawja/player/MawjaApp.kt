package com.mawja.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import coil.disk.DiskCache
import com.mawja.player.data.LibraryStore
import com.mawja.player.data.LocalMusicRepository
import com.mawja.player.data.PreferencesStore
import com.mawja.player.data.Track
import com.mawja.player.data.audius.AudiusApi
import com.mawja.player.data.radio.RadioRepository
import com.mawja.player.data.youtube.YouTubeSource
import com.mawja.player.data.youtube.YouTubeDataApi
import com.mawja.player.playback.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MawjaApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("mawja_image_cache")).maxSizePercent(0.03).build() }
        .respectCacheHeaders(false)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    override fun onTerminate() {
        if (::container.isInitialized) container.close()
        super.onTerminate()
    }
}

class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val library = LibraryStore(app)
    val preferences = PreferencesStore(app)
    val radio = RadioRepository(app, http)
    val audius = AudiusApi(http)
    val youtubeData = YouTubeDataApi(app, http, BuildConfig.YOUTUBE_DATA_API_KEY)
    val local = LocalMusicRepository(app)
    val player = PlayerManager(app, appScope, preferences)

    init {
        YouTubeSource.init(http, app)
    }

    fun play(tracks: List<Track>, index: Int) {
        val track = tracks.getOrNull(index) ?: return
        player.play(tracks, index)
        appScope.launch {
            library.addRecent(track)
            radio.countClick(track)
        }
    }

    fun close() {
        player.close()
        appScope.cancel()
    }
}
