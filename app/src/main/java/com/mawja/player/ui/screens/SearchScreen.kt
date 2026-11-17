package com.mawja.player.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.R
import com.mawja.player.data.ListState
import com.mawja.player.data.Track
import com.mawja.player.data.SourceType
import com.mawja.player.data.youtube.YouTubeSource
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.MessageState
import com.mawja.player.ui.SearchField
import com.mawja.player.ui.TrackList
import com.mawja.player.ui.fadeTopEdge
import com.mawja.player.ui.glassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun SearchScreen(tab: Int) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val audiusEnabled by container.preferences.audiusEnabled.collectAsStateWithLifecycle()
    val youtubeEnabled by container.preferences.youtubeEnabled.collectAsStateWithLifecycle()
    val sources = buildList {
        add(SourceType.LOCAL to R.string.tab_device)
        add(SourceType.RADIO to R.string.tab_radio)
        if (youtubeEnabled) add(SourceType.YOUTUBE to R.string.source_youtube)
        if (audiusEnabled) add(SourceType.AUDIUS to R.string.source_audius)
    }

    var chosen by rememberSaveable { mutableStateOf(if (tab == 1) SourceType.RADIO else SourceType.LOCAL) }
    val source = chosen.takeIf { selection -> sources.any { it.first == selection } } ?: SourceType.LOCAL
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    var retry by remember { mutableIntStateOf(0) }
    var granted by remember {
        mutableStateOf(mediaPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = mediaPermissions().any { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    var localTracks by remember(granted) { mutableStateOf<List<Track>?>(null) }
    var result by remember(source, term, granted, retry) { mutableStateOf(ListState(loading = term.isNotEmpty())) }
    LaunchedEffect(source, term, granted, retry) {
        if (term.isEmpty() || (source == SourceType.LOCAL && !granted)) return@LaunchedEffect
        delay(350)
        result = try {
            val tracks = when (source) {
                SourceType.LOCAL -> (localTracks ?: container.local.load().also { localTracks = it }).filter { track ->
                    listOf(track.title, track.artist, track.album, track.folder).any { it.contains(term, ignoreCase = true) }
                }
                SourceType.RADIO -> container.radio.search(term)
                SourceType.AUDIUS -> container.audius.search(term)
                SourceType.YOUTUBE -> if (container.youtubeData.isConfigured) container.youtubeData.search(term)
                    else YouTubeSource.search(term)
            }
            ListState(items = tracks)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ListState(error = "search")
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, stringResource(R.string.search), onSubmit = { retry++ })
        LazyRow(
            Modifier.glassSurface(RoundedCornerShape(22.dp)),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sources, key = { it.first.name }) { (option, label) ->
                FilterChip(selected = option == source, onClick = { chosen = option }, label = { Text(stringResource(label)) })
            }
        }
        when {
            source == SourceType.LOCAL && !granted -> MessageState(
                stringResource(R.string.permission_needed),
                stringResource(R.string.grant_access),
            ) { permissionLauncher.launch(mediaPermissions()) }
            term.isNotEmpty() -> TrackList(
                state = result,
                emptyText = stringResource(if (source == SourceType.RADIO) R.string.empty_stations else R.string.empty_music),
                onRetry = {
                    if (source == SourceType.LOCAL) localTracks = null
                    retry++
                },
                modifier = Modifier.weight(1f).fadeTopEdge(),
            )
        }
    }
}
