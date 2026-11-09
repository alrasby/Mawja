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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        buildList {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }.toTypedArray()
    } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(): Boolean =
        permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var granted by remember { mutableStateOf(hasPermission()) }

    // Re-check permission whenever the screen resumes (after exit/entry or settings change)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it } || hasPermission()
    }

    if (granted) LocalContent() else MessageState(stringResource(R.string.permission_needed), stringResource(R.string.grant_access)) {
        launcher.launch(permissions)
    }
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
        if (activeGroup == null) {
            SectionTitle(groupingTitle)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = footerBottomPadding)) {
                items(groups.keys.toList(), key = { it }) { name ->
                    val tracks = groups.getValue(name)
                    MediaGroupArtwork(
                        kind = groupKind,
                        title = name,
                        count = tracks.size,
                        onClick = { openedGroup = name },
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openedGroup = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
                Text(activeGroup, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.item_count, activeTracks.size, activeTracks.size), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = footerBottomPadding)) {
                items(activeTracks, key = { it.id }) { track ->
                    val isFav = favorites.any { it.id == track.id }
                    TrackRow(
                        track = track,
                        isPlaying = player.track?.id == track.id,
                        isFavorite = isFav,
                        onPlay = { scope.launch { container.player.play(track, activeTracks) } },
                        onFavorite = { scope.launch { container.library.toggleFavorite(track) } },
                    )
                }
            }
        }
    }
}
