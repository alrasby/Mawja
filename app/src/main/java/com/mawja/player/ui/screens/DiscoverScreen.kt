package com.mawja.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.R
import com.mawja.player.data.Track
import com.mawja.player.data.episodeDurationLabel
import com.mawja.player.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(onBack: () -> Unit, onRadio: () -> Unit, onOnline: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = LocalContainer.current
    val feeds by container.podcasts.subscriptions.collectAsStateWithLifecycle()
    val episodes by container.podcasts.episodes.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var selectedFeed by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleEpisodes = remember(episodes, selectedFeed) { episodes.filter { selectedFeed == null || it.folder == selectedFeed } }
    val footer = LocalFooterBottomPadding.current
    fun refresh(force: Boolean) {
        scope.launch {
            busy = true
            try { failed = container.podcasts.refresh(force) > 0 }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        container.podcasts.restore()
        busy = true
        try { failed = container.podcasts.refresh() > 0 } finally { busy = false }
    }
    LaunchedEffect(feeds) { if (selectedFeed !in feeds) selectedFeed = null }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = footer), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.discover_back)) }
            SectionTitle(stringResource(R.string.refine_discover_title))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AssistChip(onClick = onRadio, label = { Text(stringResource(R.string.discover_radio)) }) }
                item { AssistChip(onClick = onOnline, label = { Text(stringResource(R.string.discover_online)) }) }
            }
            SectionTitle(stringResource(R.string.podcasts)) {
                Row {
                    IconButton(enabled = !busy, onClick = { refresh(true) }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.podcast_refresh))
                    }
                    IconButton(enabled = !busy, onClick = { showAdd = true }) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.podcast_subscribe))
                    }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (failed) item { MessageState(stringResource(R.string.podcast_error), stringResource(R.string.retry)) { refresh(true) } }
        if (feeds.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedFeed == null, onClick = { selectedFeed = null }, label = { Text(stringResource(R.string.all_sources)) }) }
                    items(feeds, key = { it }) { feed ->
                        val title = episodes.firstOrNull { it.folder == feed }?.album?.ifBlank { feed } ?: feed
                        FilterChip(selected = selectedFeed == feed, onClick = { selectedFeed = feed },
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) })
                    }
                }
            }
            if (selectedFeed != null) item {
                TextButton(enabled = !busy, onClick = {
                    val feed = selectedFeed ?: return@TextButton
                    scope.launch { container.podcasts.remove(feed); selectedFeed = null }
                }) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Text(stringResource(R.string.refine_remove_subscription))
                }
            }
        }
        if (visibleEpisodes.isEmpty() && !busy && !failed) item {
            MessageState(stringResource(R.string.podcast_empty), stringResource(R.string.podcast_subscribe)) { showAdd = true }
        }
        items(visibleEpisodes, key = { it.id }) { track ->
            val current = player.track?.id == track.id
            EpisodeRow(track, current, current && player.isPlaying, if (current) player.positionMs else 0L,
                if (current && player.durationMs > 0) player.durationMs else track.durationMs) {
                if (current) container.player.toggle()
                else container.play(visibleEpisodes, visibleEpisodes.indexOf(track))
            }
        }
    }

    if (showAdd) GlassDialog(
        onDismissRequest = { if (!busy) showAdd = false },
        title = { Text(stringResource(R.string.podcast_subscribe)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.podcast_help))
                OutlinedTextField(value = url, onValueChange = { url = it.take(2048) },
                    label = { Text("RSS · HTTPS") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.podcast_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && url.trim().startsWith("https://"), onClick = {
                scope.launch {
                    busy = true; failed = false
                    try { container.podcasts.subscribe(url.trim()); url = ""; showAdd = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.podcast_subscribe)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EpisodeRow(track: Track, current: Boolean, playing: Boolean, position: Long, duration: Long, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).clickable(onClick = onPlay).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, track.source, Modifier.size(48.dp), corner = 12.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                    color = if (current) colors.primary else colors.onSurface)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val durationText = if (duration > 0) episodeDurationLabel(duration) else ""
                if (durationText.isNotEmpty()) Text(
                    if (current) episodeDurationLabel(position) + " / " + durationText else durationText,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.pause else R.string.play), tint = colors.primary)
            }
        }
        if (current && duration > 0) LinearProgressIndicator(
            progress = (position.toFloat() / duration).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
