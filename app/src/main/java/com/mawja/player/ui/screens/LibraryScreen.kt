package com.mawja.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.R
import com.mawja.player.data.Track
import com.mawja.player.ui.HintText
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.LocalFooterBottomPadding
import com.mawja.player.ui.SectionTitle
import com.mawja.player.ui.TrackRow
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen() {
    val container = LocalContainer.current
    val library = container.library
    val player by container.player.state.collectAsStateWithLifecycle()
    val favorites by library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val stations by library.customStations.collectAsStateWithLifecycle(initialValue = emptyList())
    val recent by library.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val footerBottomPadding = LocalFooterBottomPadding.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    @Composable
    fun TrackItem(track: Track, queue: List<Track>, index: Int, removable: Boolean) {
        val isFavorite = favorites.any { it.id == track.id }
        TrackRow(
            track = track,
            current = player.track?.id == track.id,
            playing = player.isPlaying,
            actionIcon = when {
                removable -> Icons.Rounded.Close
                isFavorite -> Icons.Rounded.Favorite
                else -> Icons.Rounded.FavoriteBorder
            },
            actionActive = isFavorite && !removable,
            actionDescription = stringResource(if (removable) R.string.remove else R.string.favorite),
            onClick = { container.play(queue, index) },
            onAction = {
                scope.launch {
                    if (removable) library.removeCustomStation(track.id) else library.toggleFavorite(track)
                }
            },
        )
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = footerBottomPadding)) {
        item { SectionTitle(stringResource(R.string.favorites)) }
        if (favorites.isEmpty()) item { HintText(stringResource(R.string.favorites_empty)) }
        itemsIndexed(favorites, key = { _, t -> "fav:" + t.id }) { i, t -> TrackItem(t, favorites, i, false) }

        item {
            SectionTitle(stringResource(R.string.my_stations)) {
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_station))
                }
            }
        }
        if (stations.isEmpty()) item { HintText(stringResource(R.string.my_stations_empty)) }
        itemsIndexed(stations, key = { _, t -> "own:" + t.id }) { i, t -> TrackItem(t, stations, i, true) }

        item { SectionTitle(stringResource(R.string.recent)) }
        if (recent.isEmpty()) item { HintText(stringResource(R.string.recent_empty)) }
        itemsIndexed(recent, key = { _, t -> "rec:" + t.id }) { i, t -> TrackItem(t, recent, i, false) }
    }

    if (showAdd) {
        AddStationDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                scope.launch { library.addCustomStation(name, url) }
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val valid = url.trim().startsWith("https://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_station)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.station_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.station_url)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = valid) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
