package com.mawja.player.ui.screens

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mawja.player.R
import com.mawja.player.data.ListState
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.ui.Artwork
import com.mawja.player.ui.HomeState
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.LocalFooterBottomPadding
import com.mawja.player.ui.LocalVm
import com.mawja.player.ui.MediaGroupArtwork
import com.mawja.player.ui.MediaGroupKind
import com.mawja.player.ui.MessageState
import com.mawja.player.ui.OnlineVm
import com.mawja.player.ui.SearchField
import com.mawja.player.ui.TrackCard
import com.mawja.player.ui.VmFactory

private fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    buildList {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.READ_MEDIA_VIDEO)
    }.toTypedArray()
} else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
fun HomeScreen(showSearch: Boolean) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val vm: OnlineVm = viewModel(factory = VmFactory(container))
    val localVm: LocalVm = viewModel(factory = VmFactory(container))
    val selected by vm.selectedSource.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val localState by localVm.state.collectAsStateWithLifecycle()
    val audiusEnabled by container.preferences.audiusEnabled.collectAsStateWithLifecycle()
    val youtubeEnabled by container.preferences.youtubeEnabled.collectAsStateWithLifecycle()
    val youtubeCountry by container.preferences.youtubeCountry.collectAsStateWithLifecycle()
    var mediaGranted by remember { mutableStateOf(mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        mediaGranted = result.values.any { it }
        if (mediaGranted) localVm.refresh()
    }

    LaunchedEffect(audiusEnabled, youtubeEnabled, youtubeCountry) { vm.load() }
    LaunchedEffect(selected, audiusEnabled, youtubeEnabled) {
        if ((selected == SourceType.AUDIUS && !audiusEnabled) || (selected == SourceType.YOUTUBE && !youtubeEnabled)) vm.setSource(null)
    }

    Column(Modifier.fillMaxSize()) {
        if (showSearch) SearchField(value = query, onValueChange = vm::onQuery, hint = stringResource(R.string.search_music), onSubmit = vm::submitQuery)
        else HomeHero()
        SourceFilterBar(selected, audiusEnabled, youtubeEnabled, vm::setSource)
        HomeContent(
            state = state,
            query = query,
            localState = localState,
            mediaGranted = mediaGranted,
            onRequestMedia = { permissionLauncher.launch(mediaPermissions()) },
            onRetry = { vm.load(); localVm.refresh() },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeHero() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
            }
        }
    }
}

@Composable
private fun SourceFilterBar(selected: SourceType?, audiusEnabled: Boolean, youtubeEnabled: Boolean, onSelect: (SourceType?) -> Unit) {
    if (!audiusEnabled && !youtubeEnabled) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SourceChip(stringResource(R.string.all_sources), selected == null) { onSelect(null) } }
        if (audiusEnabled) item { SourceChip(stringResource(R.string.source_audius), selected == SourceType.AUDIUS) { onSelect(SourceType.AUDIUS) } }
        if (youtubeEnabled) item { SourceChip(stringResource(R.string.source_youtube), selected == SourceType.YOUTUBE) { onSelect(SourceType.YOUTUBE) } }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(selected = selected, onClick = onClick, label = { Text(label) })

@Composable
private fun HomeContent(
    state: HomeState,
    query: String,
    localState: ListState,
    mediaGranted: Boolean,
    onRequestMedia: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val container = LocalContainer.current
    val footerBottomPadding = LocalFooterBottomPadding.current
    val hiddenFolders by container.preferences.hiddenFolders.collectAsStateWithLifecycle()
    // During an online query, do not rebuild the complete local library
    // grouping on the UI thread. This also keeps the search screen focused on
    // the selected online source instead of mixing it with device folders.
    val localItems = remember(localState.items, query, hiddenFolders) {
        if (query.isNotBlank()) emptyList()
        else localState.items.filter { it.folder.isBlank() || it.folder !in hiddenFolders }
    }
    val videosLabel = stringResource(R.string.videos)
    val unknownFolderLabel = stringResource(R.string.unknown_folder)
    val albumGroups = localItems.filter { it.album.isNotBlank() || it.isVideo }.groupBy { if (it.album.isNotBlank()) it.album else videosLabel }.toList().sortedByDescending { it.second.size }.take(12)
    val artistGroups = localItems.filter { it.artist.isNotBlank() || it.isVideo }.groupBy { if (it.artist.isNotBlank()) it.artist else videosLabel }.toList().sortedByDescending { it.second.size }.take(12)
    val folderGroups = localItems.groupBy { it.folder.ifBlank { unknownFolderLabel } }.toList().sortedBy { it.first.lowercase() }.take(12)
    val hasLocal = localItems.isNotEmpty()

    when {
        state.loading && localState.loading && state.sections.isEmpty() && !hasLocal -> Box(modifier, Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp + footerBottomPadding)) {
            if (!mediaGranted) item { PermissionCard(onRequestMedia) }
            state.sections.forEachIndexed { sectionIndex, section ->
                item(key = "online-title:$sectionIndex:${section.titleRes}") { SectionHeader(stringResource(section.titleRes)) }
                section.items.chunked(2).forEachIndexed { rowIndex, row ->
                    item(key = "online-grid:$sectionIndex:$rowIndex:${section.titleRes}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { track ->
                                TrackCard(
                                    track = track,
                                    onClick = { container.play(section.items, section.items.indexOf(track)) },
                                    modifier = Modifier.weight(1f),
                                    fixedWidth = false,
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (hasLocal) {
                item { SectionHeader(stringResource(R.string.local_library)) }
                if (albumGroups.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.albums)) }
                    item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(albumGroups, key = { it.first }) { (name, tracks) -> LocalGroupCard(name, tracks, MediaGroupKind.ALBUM) { container.play(tracks, 0) } } } }
                }
                if (artistGroups.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.artists)) }
                    item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(artistGroups, key = { it.first }) { (name, tracks) -> LocalGroupCard(name, tracks, MediaGroupKind.ARTIST) { container.play(tracks, 0) } } } }
                }
                if (folderGroups.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.folders)) }
                    item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(folderGroups, key = { it.first }) { (name, tracks) -> LocalGroupCard(name, tracks, MediaGroupKind.FOLDER) { container.play(tracks, 0) } } } }
                }
            }
            if (state.sections.isEmpty() && !hasLocal && mediaGranted && !state.loading) {
                item { MessageState(if (query.isBlank()) stringResource(R.string.empty_home) else stringResource(R.string.empty_music), stringResource(R.string.retry), onRetry) }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.permission_needed), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.permission_home_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onRequest) { Text(stringResource(R.string.grant_access)) }
        }
    }
}

@Composable
private fun LocalGroupCard(name: String, tracks: List<Track>, kind: MediaGroupKind, onClick: () -> Unit) {
    val sample = tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() } ?: tracks.firstOrNull()
    Card(Modifier.width(170.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .68f))) {
        Column(Modifier.padding(12.dp)) {
            MediaGroupArtwork(sample, kind, Modifier.fillMaxWidth().height(108.dp), corner = 15.dp)
            Spacer(Modifier.height(9.dp))
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(pluralStringResource(R.plurals.item_count, tracks.size, tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
    }
}
