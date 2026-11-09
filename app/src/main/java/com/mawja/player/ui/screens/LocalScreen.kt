package com.mawja.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mawja.player.R
import com.mawja.player.data.FileDisplayMode
import com.mawja.player.data.Track
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.LocalFooterBottomPadding
import com.mawja.player.ui.LocalVm
import com.mawja.player.ui.MediaGroupArtwork
import com.mawja.player.ui.MediaGroupKind
import com.mawja.player.ui.MessageState
import com.mawja.player.ui.SearchField
import com.mawja.player.ui.SectionTitle
import com.mawja.player.ui.TrackRow
import com.mawja.player.ui.VmFactory
import kotlinx.coroutines.launch

private enum class DeviceMedia { MUSIC, VIDEO }
private enum class DeviceGrouping { FOLDER, ALBUM, ARTIST }

@Composable
fun LocalScreen() {
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        buildList {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }.toTypedArray()
    } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    var granted by remember { mutableStateOf(permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
    }
    if (granted) LocalContent() else MessageState(stringResource(R.string.permission_needed), stringResource(R.string.grant_access)) { launcher.launch(permissions) }
}

@Composable
private fun LocalContent() {
    val container = LocalContainer.current
    val vm: LocalVm = viewModel(factory = VmFactory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val showMusicFolders by container.preferences.showMusicFolders.collectAsStateWithLifecycle()
    val hiddenFolders by container.preferences.hiddenFolders.collectAsStateWithLifecycle()
    val fileDisplayMode by container.preferences.fileDisplayMode.collectAsStateWithLifecycle()
    val footerBottomPadding = LocalFooterBottomPadding.current
    val favorites by container.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var media by rememberSaveable { mutableStateOf(DeviceMedia.MUSIC) }
    var grouping by rememberSaveable { mutableStateOf(DeviceGrouping.FOLDER) }
    var openedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    val visibleItems = remember(state.items, hiddenFolders) {
        state.items.filter { it.folder.isBlank() || it.folder !in hiddenFolders }
    }
    val mediaItems = remember(visibleItems, media) {
        visibleItems.filter { if (media == DeviceMedia.VIDEO) it.isVideo else !it.isVideo }
    }
    val unknownFolder = stringResource(R.string.unknown_folder)
    val unknownAlbum = stringResource(R.string.unknown_album)
    val unknownArtist = stringResource(R.string.unknown_artist)
    val videoLabel = stringResource(R.string.videos)

    val groups = remember(mediaItems, grouping, unknownFolder, unknownAlbum, unknownArtist, videoLabel) {
        mediaItems.groupBy { track ->
            when (grouping) {
                DeviceGrouping.FOLDER -> track.folder.ifBlank { unknownFolder }
                DeviceGrouping.ALBUM -> track.album.ifBlank { track.folder.ifBlank { if (media == DeviceMedia.VIDEO) videoLabel else unknownAlbum } }
                DeviceGrouping.ARTIST -> track.artist.ifBlank { track.folder.ifBlank { if (media == DeviceMedia.VIDEO) videoLabel else unknownArtist } }
            }
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val activeGroup = openedGroup?.takeIf { it in groups }
    val activeTracks = activeGroup?.let { groups.getValue(it) }.orEmpty()
    val groupKind = when (grouping) {
        DeviceGrouping.FOLDER -> MediaGroupKind.FOLDER
        DeviceGrouping.ALBUM -> MediaGroupKind.ALBUM
        DeviceGrouping.ARTIST -> MediaGroupKind.ARTIST
    }
    val groupingTitle = when (grouping) {
        DeviceGrouping.FOLDER -> stringResource(R.string.folders)
        DeviceGrouping.ALBUM -> stringResource(R.string.albums)
        DeviceGrouping.ARTIST -> stringResource(R.string.artists)
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(value = query, onValueChange = vm::onQuery, hint = stringResource(R.string.search_device))
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = media == DeviceMedia.MUSIC,
                    onClick = { media = DeviceMedia.MUSIC; openedGroup = null },
                    label = { Text(stringResource(R.string.music)) },
                )
            }
            item {
                FilterChip(
                    selected = media == DeviceMedia.VIDEO,
                    onClick = { media = DeviceMedia.VIDEO; openedGroup = null },
                    label = { Text(stringResource(R.string.videos)) },
                )
            }
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showMusicFolders) item {
                FilterChip(
                    selected = grouping == DeviceGrouping.FOLDER,
                    onClick = { grouping = DeviceGrouping.FOLDER; openedGroup = null },
                    label = { Text(stringResource(R.string.folders)) },
                )
            }
            item {
                FilterChip(
                    selected = grouping == DeviceGrouping.ALBUM,
                    onClick = { grouping = DeviceGrouping.ALBUM; openedGroup = null },
                    label = { Text(stringResource(R.string.albums)) },
                )
            }
            item {
                FilterChip(
                    selected = grouping == DeviceGrouping.ARTIST,
                    onClick = { grouping = DeviceGrouping.ARTIST; openedGroup = null },
                    label = { Text(stringResource(R.string.artists)) },
                )
            }
        }

        when {
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            mediaItems.isEmpty() -> MessageState(if (media == DeviceMedia.VIDEO) stringResource(R.string.empty_videos) else stringResource(R.string.empty_music), stringResource(R.string.retry), vm::refresh)
            activeGroup == null -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = footerBottomPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { SectionTitle(groupingTitle) }
                    if (fileDisplayMode == FileDisplayMode.GRID) {
                        val groupRows = groups.keys.toList().chunked(2)
                        items(groupRows, key = { it.joinToString("|") }) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { name ->
                                    val groupTracks = groups.getValue(name)
                                    val sample = groupTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() } ?: groupTracks.firstOrNull()
                                    Card(
                                        modifier = Modifier.weight(1f).clickable { openedGroup = name },
                                        shape = RoundedCornerShape(22.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)),
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            MediaGroupArtwork(sample, groupKind, Modifier.fillMaxWidth().aspectRatio(1.12f), corner = 17.dp)
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
                                            Text(pluralStringResource(R.plurals.item_count, groupTracks.size, groupTracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp))
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        items(groups.keys.toList(), key = { it }) { name ->
                            val groupTracks = groups.getValue(name)
                            val sample = groupTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() } ?: groupTracks.firstOrNull()
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).clickable { openedGroup = name },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MediaGroupArtwork(sample, groupKind, Modifier.size(88.dp), corner = 16.dp)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                        Text(pluralStringResource(R.plurals.item_count, groupTracks.size, groupTracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = footerBottomPadding)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { openedGroup = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.close)) }
                            Text(activeGroup, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { container.play(activeTracks, 0) }, enabled = activeTracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, contentDescription = null); Text(stringResource(R.string.play_all)) }
                            OutlinedButton(onClick = { container.play(activeTracks.shuffled(), 0) }, enabled = activeTracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, contentDescription = null); Text(stringResource(R.string.shuffle)) }
                        }
                    }
                    items(activeTracks, key = { it.id }) { track ->
                        val isFavorite = favorites.any { it.id == track.id }
                        TrackRow(track, player.track?.id == track.id, player.isPlaying, if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, isFavorite, stringResource(R.string.favorite), { container.play(activeTracks, activeTracks.indexOf(track)) }, { scope.launch { container.library.toggleFavorite(track) } })
                    }
                }
            }
        }
    }
}
