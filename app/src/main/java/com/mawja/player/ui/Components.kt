package com.mawja.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.mawja.player.AppContainer
import com.mawja.player.R
import com.mawja.player.data.FileDisplayMode
import com.mawja.player.data.ListState
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.data.youtubeThumbnailCandidates
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

enum class MediaGroupKind { TRACK, FOLDER, ALBUM, ARTIST, VIDEO }

fun sourceIcon(source: SourceType): ImageVector = when (source) {
    SourceType.RADIO -> Icons.Rounded.Radio
    SourceType.LOCAL -> Icons.Rounded.MusicNote
    SourceType.AUDIUS -> Icons.Rounded.GraphicEq
    SourceType.YOUTUBE -> Icons.Rounded.PlayCircle
}

/** Thumbnail-first artwork. If Coil cannot load the URI, the creative fallback remains visible. */
@Composable
fun Artwork(url: String?, source: SourceType, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    ThumbnailOrCreative(url, source, MediaGroupKind.TRACK, modifier, corner)
}

@Composable
fun MediaGroupArtwork(track: Track?, kind: MediaGroupKind, modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    ThumbnailOrCreative(track?.artworkUrl, track?.source, kind, modifier, corner)
}

@Composable
private fun ThumbnailOrCreative(url: String?, source: SourceType?, kind: MediaGroupKind, modifier: Modifier, corner: Dp) {
    val context = LocalContext.current
    var youtubeAttempt by remember(url, source) { mutableIntStateOf(0) }
    val youtubeUrls = remember(url, source) {
        if (source == SourceType.YOUTUBE) {
            Regex("/vi/([^/]+)/").find(url.orEmpty())?.groupValues?.getOrNull(1)
                ?.let(::youtubeThumbnailCandidates).orEmpty()
        } else emptyList()
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val imageUrl = youtubeUrls.getOrNull(youtubeAttempt) ?: url
        if (imageUrl.isNullOrBlank()) {
            CreativeFallback(kind, source, Modifier.fillMaxSize())
        } else {
            val request = remember(imageUrl, source) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(180)
                    .memoryCacheKey("mawja-artwork:$imageUrl")
                    .diskCacheKey("mawja-artwork:$imageUrl")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(false)
                    .apply {
                        if (source == SourceType.LOCAL && imageUrl.startsWith("content://")) videoFrameMillis(1_000)
                    }
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (source == SourceType.YOUTUBE && youtubeAttempt + 1 < youtubeUrls.size) youtubeAttempt++
                },
                loading = { CreativeFallback(kind, source, Modifier.fillMaxSize()) },
                error = { CreativeFallback(kind, source, Modifier.fillMaxSize()) },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}

@Composable
private fun CreativeFallback(kind: MediaGroupKind, source: SourceType?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val palette = when (kind) {
        MediaGroupKind.FOLDER -> listOf(colors.primary, colors.secondary)
        MediaGroupKind.ARTIST -> listOf(colors.secondary, colors.tertiary)
        MediaGroupKind.VIDEO -> listOf(colors.tertiary, colors.primary)
        MediaGroupKind.ALBUM -> listOf(colors.primary, colors.tertiary)
        MediaGroupKind.TRACK -> when (source) {
            SourceType.AUDIUS -> listOf(colors.primary, colors.secondary)
            SourceType.YOUTUBE -> listOf(colors.secondary, colors.primary)
            SourceType.RADIO -> listOf(colors.tertiary, colors.primary)
            else -> listOf(colors.primary, colors.tertiary)
        }
    }
    Box(modifier.background(Brush.linearGradient(palette))) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val w = size.width
            val h = size.height
            val bright = Color.White.copy(alpha = .9f)
            val soft = Color.White.copy(alpha = .28f)
            when (kind) {
                MediaGroupKind.FOLDER -> {
                    drawRoundRect(bright.copy(alpha = .88f), Offset(w * .10f, h * .30f), Size(w * .80f, h * .48f), CornerRadius(w * .08f))
                    drawRoundRect(bright.copy(alpha = .97f), Offset(w * .18f, h * .20f), Size(w * .34f, h * .17f), CornerRadius(w * .07f))
                    drawWave(soft, w * .055f, w, h)
                }
                MediaGroupKind.ARTIST -> {
                    drawCircle(bright.copy(alpha = .92f), radius = w * .14f, center = Offset(w * .5f, h * .32f))
                    drawArc(bright.copy(alpha = .92f), 205f, 130f, false, Offset(w * .24f, h * .42f), Size(w * .52f, h * .38f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * .09f, cap = StrokeCap.Round))
                    drawArc(soft, 25f, 130f, false, Offset(w * .08f, h * .10f), Size(w * .84f, h * .78f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * .035f, cap = StrokeCap.Round))
                }
                MediaGroupKind.VIDEO -> {
                    drawRoundRect(bright.copy(alpha = .9f), Offset(w * .16f, h * .20f), Size(w * .68f, h * .60f), CornerRadius(w * .10f))
                    val play = Path().apply { moveTo(w * .45f, h * .37f); lineTo(w * .45f, h * .63f); lineTo(w * .68f, h * .50f); close() }
                    drawPath(play, palette.first())
                    drawWave(palette[1].copy(alpha = .45f), w * .035f, w, h)
                }
                MediaGroupKind.ALBUM, MediaGroupKind.TRACK -> {
                    drawCircle(bright.copy(alpha = .86f), radius = w * .34f, center = Offset(w * .5f, h * .5f))
                    drawCircle(palette.first().copy(alpha = .85f), radius = w * .12f, center = Offset(w * .5f, h * .5f))
                    drawArc(palette.first().copy(alpha = .62f), -35f, 250f, false, Offset(w * .16f, h * .16f), Size(w * .68f, h * .68f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * .045f, cap = StrokeCap.Round))
                    drawWave(palette[1].copy(alpha = .48f), w * .035f, w, h)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(color: Color, stroke: Float, w: Float, h: Float) {
    val wave = Path().apply {
        moveTo(w * .16f, h * .58f)
        cubicTo(w * .30f, h * .34f, w * .38f, h * .80f, w * .52f, h * .52f)
        cubicTo(w * .65f, h * .27f, w * .72f, h * .70f, w * .86f, h * .43f)
    }
    drawPath(wave, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
}

@Composable
fun TrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier, fixedWidth: Boolean = true) {
    Column(
        modifier.then(if (fixedWidth) Modifier.width(164.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Artwork(track.artworkUrl, track.source, Modifier.fillMaxWidth().height(144.dp), corner = 15.dp)
        Spacer(Modifier.height(9.dp))
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(track.subtitle.ifBlank { track.album }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrackRow(
    track: Track,
    current: Boolean,
    playing: Boolean,
    actionIcon: ImageVector,
    actionActive: Boolean,
    actionDescription: String,
    onClick: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUrl, track.source, Modifier.size(54.dp), corner = 14.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = if (current) colors.primary else colors.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.isLive) { Box(Modifier.size(6.dp).clip(CircleShape).background(colors.secondary)); Spacer(Modifier.width(6.dp)) }
                val detail = track.subtitle.ifBlank { track.album }
                if (detail.isNotBlank()) Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (current && playing) Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.primary)
        IconButton(onClick = onAction) { Icon(actionIcon, contentDescription = actionDescription, tint = if (actionActive) colors.secondary else colors.onSurfaceVariant) }
    }
}

@Composable
fun RadioCardGrid(
    state: ListState,
    displayMode: FileDisplayMode,
    emptyText: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    val player by container.player.state.collectAsStateWithLifecycle()
    val footerBottomPadding = LocalFooterBottomPadding.current
    val items = remember(state.items) { state.items.distinctBy { it.id } }
    when {
        state.loading && items.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && items.isEmpty() -> Box(modifier.fillMaxSize()) { MessageState(stringResource(R.string.error_generic), stringResource(R.string.retry), onRetry) }
        items.isEmpty() -> Box(modifier.fillMaxSize()) { MessageState(emptyText) }
        else -> if (displayMode == FileDisplayMode.GRID) {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp + footerBottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items.chunked(2), key = { row -> row.joinToString("|") { it.id } }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { track ->
                            RadioCard(track, player.track?.id == track.id, player.isPlaying, { container.play(items, items.indexOf(track)) }, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp + footerBottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { track ->
                    RadioListRow(
                        track = track,
                        current = player.track?.id == track.id,
                        playing = player.isPlaying,
                        onClick = { container.play(items, items.indexOf(track)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioListRow(track: Track, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artworkUrl, track.source, Modifier.size(68.dp), corner = 16.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(track.subtitle.ifBlank { stringResource(R.string.live) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (current && playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle,
                contentDescription = null,
                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun RadioCard(track: Track, current: Boolean, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .76f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box {
                Artwork(track.artworkUrl, track.source, Modifier.fillMaxWidth().height(126.dp), corner = 17.dp)
                if (current && playing) {
                    Box(Modifier.padding(8.dp).size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
            Text(track.subtitle.ifBlank { stringResource(R.string.live) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String, onSubmit: () -> Unit = {}) {
    OutlinedTextField(value = value, onValueChange = onValueChange, singleLine = true, placeholder = { Text(hint) }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, trailingIcon = if (value.isNotEmpty()) { { IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear)) } } } else null, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun HintText(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

@Composable
fun MessageState(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) { Spacer(Modifier.height(16.dp)); FilledTonalButton(onClick = onAction) { Text(actionLabel) } }
    }
}

@Composable
fun TrackList(state: ListState, emptyText: String, onRetry: () -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    val container = LocalContainer.current
    val player by container.player.state.collectAsStateWithLifecycle()
    val favorites by container.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val footerBottomPadding = LocalFooterBottomPadding.current
    val items = remember(state.items) { state.items.distinctBy { it.id } }
    val scope = rememberCoroutineScope()
    when {
        state.loading && items.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && items.isEmpty() -> Box(modifier.fillMaxSize()) { MessageState(stringResource(R.string.error_generic), stringResource(R.string.retry), onRetry) }
        items.isEmpty() -> Box(modifier.fillMaxSize()) { MessageState(emptyText) }
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = footerBottomPadding)) {
            if (header != null) item { header() }
            itemsIndexed(items, key = { _, t -> t.id }) { index, track ->
                val isFavorite = track.id in favoriteIds
                TrackRow(track, player.track?.id == track.id, player.isPlaying, if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, isFavorite, stringResource(R.string.favorite), { container.play(items, index) }, { scope.launch { container.library.toggleFavorite(track) } })
            }
        }
    }
}
