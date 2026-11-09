package com.mawja.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mawja.player.data.PreferencesStore
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.YouTubeFallbackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap


data class PlayerUiState(
    val track: Track? = null,
    val liveText: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepRemainingMs: Long = 0L,
    val error: String? = null,
)

/** UI-side handle on the playback service. */
class PlayerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: PreferencesStore,
) {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private val known = object : LinkedHashMap<String, Track>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > 512
    }
    private var sleepJob: Job? = null
    private var resolveJob: Job? = null
    private var pollJob: Job? = null
    @Volatile private var closed = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _controllerState = MutableStateFlow<MediaController?>(null)
    val controllerState: StateFlow<MediaController?> = _controllerState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!closed) sync()
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull()
                if (c != null && !closed) {
                    controller = c
                    _controllerState.value = c
                    c.addListener(listener)
                    pending.forEach { it(c) }
                    pending.clear()
                    sync()
                } else c?.release()
            },
            ContextCompat.getMainExecutor(context),
        )
        pollJob = scope.launch {
            while (isActive) {
                val c = controller
                val active = c?.currentMediaItem != null
                if (active && (c?.isPlaying == true || c?.playbackState == Player.STATE_BUFFERING)) sync()
                delay(
                    when {
                        preferences.batterySaver.value -> 2_000L
                        active -> 1_000L
                    else -> 15_000L
                    },
                )
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        if (closed) return
        val c = controller
        if (c != null) block(c) else if (pending.size < 64) pending += block
    }

    private fun sync() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val track = item?.let { known[it.mediaId] ?: it.toFallbackTrack() }
        val liveText = if (track?.isLive == true) c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() && it != track.title } else null
        val duration = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        _state.update {
            it.copy(
                track = track,
                liveText = liveText,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                volume = c.volume,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                error = c.playerError?.let { e -> e.message ?: e.errorCodeName },
            )
        }
    }

    fun play(tracks: List<Track>, index: Int) {
        val target = tracks.getOrNull(index) ?: return
        if (target.source == SourceType.YOUTUBE) {
            val videoId = target.id.removePrefix("yt:").takeIf { it.length == 11 } ?: return
            context.startActivity(
                Intent(context, YouTubeFallbackActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(YouTubeFallbackActivity.EXTRA_VIDEO_ID, videoId),
            )
            return
        }
        val playable = tracks.filter { !it.playbackUri.isNullOrBlank() }
        if (playable.isEmpty()) return
        playable.forEach { known[it.id] = it }
        resolveJob?.cancel()
        resolveJob = scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                playable.mapNotNull { track ->
                    val uri = track.playbackUri ?: return@mapNotNull null
                    // Keep YouTube watch URLs intact. PlaybackService resolves them
                    // just before opening the stream and can then attach the matching
                    // User-Agent/Referer headers for the short-lived URL.
                    track to uri
                }
            }
            val targetIndex = resolved.indexOfFirst { it.first.id == target.id }
            if (targetIndex < 0) {
                _state.update { it.copy(error = "YouTube audio could not be resolved") }
                return@launch
            }
            withController { c ->
                c.setMediaItems(resolved.map { (track, uri) -> track.toMediaItem(uri) }, targetIndex, 0L)
                if (target.isLive) c.repeatMode = Player.REPEAT_MODE_OFF
                c.prepare()
                c.play()
            }
        }
    }

    fun toggle() = withController { c ->
        when {
            c.isPlaying -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE || c.playerError != null) c.prepare()
                c.play()
            }
        }
    }

    fun stop() = withController { c ->
        resolveJob?.cancel()
        c.stop()
        c.clearMediaItems()
        _state.value = PlayerUiState()
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun setVolume(value: Float) = withController { it.volume = value.coerceIn(0f, 1f); sync() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepRemainingMs = 0L) }
        if (minutes <= 0) return
        val end = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                _state.update { it.copy(sleepRemainingMs = left) }
                delay(1000)
            }
            controller?.pause()
            _state.update { it.copy(sleepRemainingMs = 0L) }
        }
    }

    /** Releases controller/listeners when the application container is explicitly shut down. */
    fun close() {
        if (closed) return
        closed = true
        pollJob?.cancel()
        sleepJob?.cancel()
        resolveJob?.cancel()
        controllerFuture?.cancel(true)
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _controllerState.value = null
        controllerFuture = null
        pending.clear()
        known.clear()
    }
}

fun Track.toMediaItem(uriOverride: String? = null): MediaItem {
    val uri = Uri.parse(uriOverride ?: requireNotNull(playbackUri))
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(subtitle.ifBlank { null })
        .setAlbumTitle(album.ifBlank { null })
        .setArtworkUri(artworkUrl?.takeUnless { isVideo }?.let { Uri.parse(it) })
        .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(metadata)
        .build()
}

private fun MediaItem.toFallbackTrack() = Track(
    id = mediaId,
    source = SourceType.LOCAL,
    title = mediaMetadata.title?.toString().orEmpty(),
    subtitle = mediaMetadata.artist?.toString().orEmpty(),
    artworkUrl = mediaMetadata.artworkUri?.toString(),
    streamUrl = localConfiguration?.uri?.toString(),
)
