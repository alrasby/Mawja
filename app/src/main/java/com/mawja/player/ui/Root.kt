package com.mawja.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.R
import com.mawja.player.ui.screens.HomeScreen
import com.mawja.player.ui.screens.LibraryScreen
import com.mawja.player.ui.screens.LocalScreen
import com.mawja.player.ui.screens.RadioScreen
import com.mawja.player.ui.screens.SettingsScreen

private data class TabItem(val icon: ImageVector, val label: Int)

private val tabs = listOf(
    TabItem(Icons.Rounded.Home, R.string.tab_home),
    TabItem(Icons.Rounded.Radio, R.string.tab_radio),
    TabItem(Icons.Rounded.PhoneAndroid, R.string.tab_device),
    TabItem(Icons.Rounded.LibraryMusic, R.string.tab_library),
)

/** Inset consumed inside scrollable content, never by the screen canvas. */
val LocalFooterBottomPadding = staticCompositionLocalOf<Dp> { 0.dp }

@Composable
fun MawjaRoot() {
    val container = LocalContainer.current
    val themeMode by container.preferences.theme.collectAsStateWithLifecycle()
    MawjaTheme(themeMode) {
        // Do not restore transient navigation state when a new Activity session starts.
        var selectedTab by remember { mutableIntStateOf(0) }
        var contentTab by remember { mutableIntStateOf(0) }
        var showPlayer by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var footerCompact by rememberSaveable { mutableStateOf(false) }
        var footerOccupiedPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current

        // Paint the selected tab first. The screen swap is scheduled for the
        // next frame so a heavy screen cannot delay the footer's pressed state.
        LaunchedEffect(selectedTab) {
            androidx.compose.runtime.withFrameNanos { }
            contentTab = selectedTab
        }

        val footerScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y != 0f) footerCompact = true
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    footerCompact = false
                    return Velocity.Zero
                }
            }
        }
        val contentBottomSpace = if (footerOccupiedPx == 0) {
            116.dp
        } else {
            with(density) { footerOccupiedPx.toDp() } + 10.dp
        }

        // One app canvas. The footer is a transparent overlay with one glass
        // surface only. The measured inset is consumed inside scrollable lists
        // so the canvas remains fully transparent behind the floating capsule.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            CompositionLocalProvider(LocalFooterBottomPadding provides contentBottomSpace) {
                Column(Modifier.fillMaxSize()) {
                    MawjaHeader(
                        onSearch = { selectedTab = 0; showSettings = false; showSearch = !showSearch },
                        onSettings = { showSettings = true; showSearch = false },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(footerScrollConnection),
                    ) {
                        when {
                            showSettings -> SettingsScreen()
                            contentTab == 0 -> HomeScreen(showSearch)
                            contentTab == 1 -> RadioScreen()
                            contentTab == 2 -> LocalScreen()
                            else -> LibraryScreen()
                        }
                    }
                }
            }

            // The outer column is transparent and measured only to reserve the
            // exact occupied space of MiniPlayer + the animated glass bar.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .onGloballyPositioned { footerOccupiedPx = it.size.height },
            ) {
                MiniPlayer(onOpen = { showPlayer = true })
                Box(Modifier.fillMaxWidth()) {
                    MawjaFloatingBar(
                        modifier = Modifier.align(Alignment.Center),
                        compact = footerCompact,
                        selected = selectedTab,
                        onSelect = { index ->
                            if (showSettings && index == 0) {
                                showSettings = false
                                showSearch = false
                                selectedTab = 0
                                contentTab = 0
                            } else if (selectedTab != index) {
                                selectedTab = index
                                footerCompact = false
                                showSettings = false
                                showSearch = false
                            }
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = showPlayer,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NowPlayingScreen(onClose = { showPlayer = false })
            }
            BackHandler(enabled = showPlayer) { showPlayer = false }
            BackHandler(enabled = showSettings && !showPlayer) {
                showSettings = false
                showSearch = false
            }
        }
    }
}

@Composable
private fun MawjaFloatingBar(modifier: Modifier = Modifier, compact: Boolean, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glassShape = RoundedCornerShape(38.dp)
    val expandedAlpha = 1.30f
    fun glassColor(color: Color, alpha: Float): Color = Color(
        red = color.red * .84f,
        green = color.green * .84f,
        blue = color.blue * .84f,
        alpha = (alpha * expandedAlpha).coerceAtMost(.94f),
    )
    // A further 15% opacity increase over the previous glass values. The RGB
    // tint is also slightly shaded, while alpha remains below 1 so content can
    // still be seen through the single glass surface.
    val glassBrush = Brush.linearGradient(
        colors = listOf(
            glassColor(colors.primaryContainer, .78f),
            glassColor(colors.surfaceVariant, .66f),
            glassColor(colors.secondary, .40f),
        ),
    )
    val glassBorder = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = .72f),
            colors.primary.copy(alpha = .38f),
            colors.onSurface.copy(alpha = .18f),
        ),
    )
    val expansion by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (compact) 0f else 1f,
        animationSpec = tween(220),
        label = "footerExpansion",
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (compact) 48.dp else 68.dp,
        animationSpec = tween(220),
        label = "footerHeight",
    )
    val widthFraction = .62f + (.38f * expansion)

    Row(
        modifier
            .fillMaxWidth(widthFraction)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .height(animatedHeight)
            .clip(glassShape)
            .background(glassBrush, glassShape)
            .border(1.dp, glassBorder, glassShape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = if (compact) {
            Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.spacedBy(0.dp)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, item ->
            val selectedColor by animateColorAsState(
                targetValue = if (selected == index) colors.primaryContainer.copy(alpha = .92f) else Color.Transparent,
                animationSpec = tween(180),
                label = "footerSelection",
            )
            val itemShape = if (compact) CircleShape else RoundedCornerShape(30.dp)
            val itemModifier = if (compact) {
                Modifier.size(33.dp)
            } else {
                Modifier.weight(1f).fillMaxHeight()
            }
            Column(
                itemModifier
                    .clip(itemShape)
                    .background(selectedColor)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = stringResource(item.label),
                    tint = if (selected == index) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                AnimatedVisibility(
                    visible = !compact,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(80)),
                ) {
                    Text(
                        stringResource(item.label),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected == index) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MawjaHeader(onSearch: () -> Unit, onSettings: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
            ) {
                IconButton(onClick = onSearch, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                }
                Text(
                    "Mawja",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        }
    }
}
