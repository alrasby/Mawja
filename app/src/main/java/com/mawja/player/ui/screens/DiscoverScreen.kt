package com.mawja.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.R
import com.mawja.player.ui.*
import kotlinx.coroutines.launch

@Composable
fun DiscoverScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = LocalContainer.current
    val feeds by container.podcasts.subscriptions.collectAsStateWithLifecycle()
    val episodes by container.podcasts.episodes.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var category by remember { mutableIntStateOf(0) }
    val footer = LocalFooterBottomPadding.current
    LaunchedEffect(Unit) {
        busy = true
        try { failed = container.podcasts.refresh() > 0 } finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.discover_back)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.podcasts, R.string.discover_radio, R.string.discover_online).forEachIndexed { index, label ->
                FilterChip(selected = category == index, onClick = { category = index }, label = { Text(stringResource(label)) })
            }
        }
        if (category == 1) { RadioScreen(); return@Column }
        if (category == 2) { HomeScreen(allowDiscover = false); return@Column }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = footer)) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.podcasts), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.podcast_help))
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("RSS · HTTPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = !busy && url.startsWith("https://"), onClick = {
                            scope.launch {
                                busy = true; failed = false
                                try { container.podcasts.subscribe(url); url = "" }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { failed = true }
                                finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.podcast_subscribe)) }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try { failed = container.podcasts.refresh(true) > 0 } finally { busy = false }
                            }
                        }) { Text(stringResource(R.string.podcast_refresh)) }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.podcast_error), color = MaterialTheme.colorScheme.error)
                }
            }
            items(feeds, key = { "feed:$it" }) { feed ->
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(episodes.firstOrNull { it.folder == feed }?.album?.ifBlank { feed } ?: feed)
                    TextButton(enabled = !busy, onClick = { scope.launch { container.podcasts.remove(feed) } }) { Text(stringResource(R.string.remove)) }
                }
            }
            if (episodes.isEmpty() && !busy) item { HintText(stringResource(R.string.podcast_empty)) }
            items(episodes, key = { it.id }) { track ->
                TrackCard(track = track, onClick = { container.play(episodes, episodes.indexOf(track)) }, fixedWidth = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
