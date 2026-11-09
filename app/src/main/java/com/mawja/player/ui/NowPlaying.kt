package com.mawja.player.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mawja.player.R
import com.mawja.player.YouTubeFallbackActivity
import com.mawja.player.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

/** Picks a dark tone from the artwork so the player screen takes on the cover's colour. */
@Composable
private fun rememberArtworkColor(url: String?, fallback: Color): Color {
    val context = LocalContext.current
    var target by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
            ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        target = Color(palette.getDarkMutedColor(palette.getDominantColor(fallback.toArgb())))
    }
    return animateColorAsState(target, tween(600), label = "artworkColor").value
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val container = LocalContainer.current
    val state by container.player.state.collectAsStateWithLifecycle()
    val track = state.track ?: return
    val colors = MaterialTheme.colorScheme

    Surface(
        color = colors.surface.copy(alpha = .88f),
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
    ) {
        Column {
            if (!track.isLive && state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.artworkUrl, track.source, Modifier.size(44.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        state.liveText ?: track.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = container.player::toggle) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                            tint = colors.onSurface,
                        )
                    }
                }
                IconButton(onClick = container.player::stop) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.stop), tint = colors.onSurface)
                }
                IconButton(onClick = container.player::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next), tint = colors.onSurface)
                }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val container = LocalContainer.current
    val player = container.player
    val context = LocalContext.current
    val state by player.state.collectAsStateWithLifecycle()
    val mediaController by player.controllerState.collectAsStateWithLifecycle()
    val favorites by container.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val track = state.track

    LaunchedEffect(track == null) { if (track == null) onClose() }
    if (track == null) return

    val tint = rememberArtworkColor(track.artworkUrl, colors.surfaceVariant)
    val isFavorite = favorites.any { it.id == track.id }
    var showSleep by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, colors.background))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.close), tint = colors.onBackground)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Rounded.Timer,
                        contentDescription = stringResource(R.string.sleep_timer),
                        tint = if (state.sleepRemainingMs > 0) colors.primary else colors.onBackground,
                    )
                }
                IconButton(onClick = { scope.launch { container.library.toggleFavorite(track) } }) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(R.string.favorite),
                        tint = if (isFavorite) colors.primary else colors.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (track.isVideo && mediaController != null) {
                VideoPlayerSurface(
                    mediaController = mediaController,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Artwork(
                    track.artworkUrl,
                    track.source,
                    Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f),
                    corner = 24.dp,
                )
            }
            Spacer(Modifier.height(28.dp))

            Text(
                track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            val secondLine = state.liveText ?: track.subtitle
            if (secondLine.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    secondLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            if (track.isLive) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.live), color = colors.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                val duration = state.durationMs.coerceAtLeast(1L).toFloat()
                Slider(
                    value = if (dragging) dragValue else state.positionMs.toFloat().coerceIn(0f, duration),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        player.seekTo(dragValue.toLong())
                        dragging = false
                    },
                    valueRange = 0f..duration,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (dragging) dragValue.toLong() else state.positionMs), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(state.durationMs), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!track.isLive) {
                    IconButton(onClick = player::toggleShuffle) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.shuffle),
                            tint = if (state.shuffle) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous), tint = colors.onBackground, modifier = Modifier.size(36.dp))
                }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(76.dp)) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = colors.onPrimary, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next), tint = colors.onBackground, modifier = Modifier.size(36.dp))
                }
                if (!track.isLive) {
                    IconButton(onClick = player::cycleRepeat) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.repeat),
                            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = player::stop) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.stop), tint = colors.onSurfaceVariant)
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.playback_error),
                    color = colors.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (track.source == SourceType.YOUTUBE) {
                    TextButton(
                        onClick = {
                            val videoId = track.id.removePrefix("yt:")
                            context.startActivity(
                                Intent(context, YouTubeFallbackActivity::class.java)
                                    .putExtra(YouTubeFallbackActivity.EXTRA_VIDEO_ID, videoId),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.open_youtube_official))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSleep) {
        SleepTimerDialog(
            remainingMs = state.sleepRemainingMs,
            onPick = { minutes ->
                player.startSleepTimer(minutes)
                showSleep = false
            },
            onDismiss = { showSleep = false },
        )
    }
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
private fun VideoPlayerSurface(
    mediaController: androidx.media3.session.MediaController?,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { view -> view.player = mediaController },
    )
}

@Composable
private fun SleepTimerDialog(remainingMs: Long, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                if (remainingMs > 0) {
                    Text(
                        stringResource(R.string.sleep_left, formatTime(remainingMs)),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sleep_minutes, minutes))
                    }
                }
                if (remainingMs > 0) {
                    TextButton(onClick = { onPick(0) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sleep_off))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
