package com.mawja.player.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mawja.player.playback.PlaybackDiagnostics
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import com.mawja.player.data.youtube.RegionResolver
import com.mawja.player.data.youtube.YouTubeRegion
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Surface
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mawja.player.AppContainer
import com.mawja.player.R
import com.mawja.player.data.FileDisplayMode
import com.mawja.player.data.LanguageOption
import com.mawja.player.data.ThemeMode
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mawja.player.data.LocalMusicRepository.MediaFolder
import com.mawja.player.ui.GlassCard
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.LocalFooterBottomPadding
import com.mawja.player.ui.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val languages = listOf(
    LanguageOption("ar", "العربية"),
    LanguageOption("en", "English"),
    LanguageOption("fr", "Français"),
    LanguageOption("tr", "Türkçe"),
    LanguageOption("ur", "اردو"),
    LanguageOption("fa", "فارسی"),
    LanguageOption("es", "Español"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("it", "Italiano"),
    LanguageOption("nl", "Nederlands"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("id", "Bahasa Indonesia"),
)

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (section != null) section = null else onBack() }
    val container = LocalContainer.current
    val context = LocalContext.current
    val diagnosticScope = rememberCoroutineScope()
    var diagnosticBusy by remember { mutableStateOf(false) }
    val theme by container.preferences.theme.collectAsStateWithLifecycle()
    val language by container.preferences.language.collectAsStateWithLifecycle()
    val batterySaver by container.preferences.batterySaver.collectAsStateWithLifecycle()
    val equalizerPreset by container.preferences.equalizerPreset.collectAsStateWithLifecycle()
    val showMusicFolders by container.preferences.showMusicFolders.collectAsStateWithLifecycle()
    val hiddenFolders by container.preferences.hiddenFolders.collectAsStateWithLifecycle()
    val audiusEnabled by container.preferences.audiusEnabled.collectAsStateWithLifecycle()
    val youtubeEnabled by container.preferences.youtubeEnabled.collectAsStateWithLifecycle()
    val fileDisplayMode by container.preferences.fileDisplayMode.collectAsStateWithLifecycle()
    val youtubeLanguage by container.preferences.youtubeLanguage.collectAsStateWithLifecycle()
    val youtubeTopic by container.preferences.youtubeTopic.collectAsStateWithLifecycle()
    val youtubeCountry by container.preferences.youtubeCountry.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val footerBottomPadding = LocalFooterBottomPadding.current
    var rightsHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val rightsClearance = with(LocalDensity.current) { rightsHeightPx.toDp() } + 12.dp
    var showLanguages by remember { mutableStateOf(false) }
    var showYoutubeCountries by remember { mutableStateOf(false) }
    var showYoutubeLanguages by remember { mutableStateOf(false) }
    var showYoutubeTopics by remember { mutableStateOf(false) }
    var locationRefresh by remember { mutableStateOf(0) }
    var resolvedRegion by remember { mutableStateOf<YouTubeRegion?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationRefresh++ }
    val locale = context.resources.configuration.locales[0]
    val autoLabel = stringResource(R.string.youtube_auto_content)
    val youtubeCountries = listOf(LanguageOption("", autoLabel)) + remember(locale) {
        Locale.getISOCountries().map { code -> LanguageOption(code, Locale("", code).getDisplayCountry(locale)) }.sortedBy { it.label }
    }
    val youtubeLanguages = listOf(LanguageOption("", autoLabel)) + remember(locale) {
        Locale.getISOLanguages().map { code -> LanguageOption(code, Locale(code).getDisplayLanguage(locale)) }
            .filter { it.label.isNotBlank() }.sortedBy { it.label }
    }
    val youtubeTopics = listOf(
        LanguageOption("music", stringResource(R.string.music)),
        LanguageOption("quran", stringResource(R.string.filter_quran)),
        LanguageOption("podcasts", stringResource(R.string.youtube_podcasts)),
    )
    LaunchedEffect(section, youtubeCountry, youtubeLanguage, language, locationRefresh) {
        if (section != "online") return@LaunchedEffect
        resolvedRegion = RegionResolver.resolve(context)
    }

    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }

    LaunchedEffect(section) {
        if (section != "device") return@LaunchedEffect
        folders = withContext(Dispatchers.IO) {
            container.local.folders()
        }
    }

    val settingsScroll = rememberScrollState()
    LaunchedEffect(section) { settingsScroll.scrollTo(0) }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .padding(bottom = if (section == null) footerBottomPadding + rightsClearance else 0.dp)
            .verticalScroll(settingsScroll).padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = if (section == null) 0.dp else footerBottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (section == null) {
            SectionTitle(stringResource(R.string.settings))
            SettingCard(Icons.Rounded.SettingsSuggest, stringResource(R.string.settings_appearance), stringResource(R.string.refine_appearance_desc), { section = "appearance" })
            SettingCard(Icons.Rounded.Folder, stringResource(R.string.tab_device), stringResource(R.string.refine_device_desc), { section = "device" })
            SettingCard(Icons.Rounded.Language, stringResource(R.string.settings_online_sources), stringResource(R.string.refine_online_desc), { section = "online" })
            SettingCard(Icons.Rounded.BatterySaver, stringResource(R.string.settings_playback), stringResource(R.string.refine_playback_desc), { section = "playback" })
        } else {
            TextButton(onClick = { section = null }) { Text(stringResource(R.string.refine_back_settings)) }
            when (section) {
                "appearance" -> {
        SectionTitle(stringResource(R.string.settings_appearance))
        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.language),
            value = languages.firstOrNull { it.code == language }?.label ?: "English",
            onClick = { showLanguages = true },
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (theme) {
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                            ThemeMode.SYSTEM -> Icons.Rounded.SettingsSuggest
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (theme) {
                                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton(ThemeMode.SYSTEM, stringResource(R.string.theme_system), theme, container, Modifier.weight(1f))
                    ThemeButton(ThemeMode.LIGHT, stringResource(R.string.theme_light), theme, container, Modifier.weight(1f))
                    ThemeButton(ThemeMode.DARK, stringResource(R.string.theme_dark), theme, container, Modifier.weight(1f))
                }
            }
        }

                }
                "device" -> {
                    SectionTitle(stringResource(R.string.tab_device))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(stringResource(R.string.file_display), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.file_display_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fileDisplayMode == FileDisplayMode.GRID,
                        onClick = { container.preferences.setFileDisplayMode(FileDisplayMode.GRID) },
                        label = { Text(stringResource(R.string.display_grid)) },
                        leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = fileDisplayMode == FileDisplayMode.LIST,
                        onClick = { container.preferences.setFileDisplayMode(FileDisplayMode.LIST) },
                        label = { Text(stringResource(R.string.display_list)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ViewList, contentDescription = null) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LeftSwitchRow(showMusicFolders, container.preferences::setShowMusicFolders) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                        Text(stringResource(R.string.show_music_folders), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.show_music_folders_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.folders_in_device), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (folders.isEmpty()) {
                    Text(stringResource(R.string.no_folders_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    folders.forEach { folder ->
                        FolderVisibilityRow(
                            folder = folder,
                            visible = folder.name !in hiddenFolders,
                            onVisibleChange = { container.preferences.setFolderVisible(folder.name, it) },
                        )
                    }
                }
            }
        }


                }
                "online" -> {
        SectionTitle(stringResource(R.string.settings_online_sources))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_online_sources_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OnlineSourceRow(stringResource(R.string.enable_audius), audiusEnabled, container.preferences::setAudiusEnabled)
                OnlineSourceRow(stringResource(R.string.enable_youtube), youtubeEnabled, container.preferences::setYoutubeEnabled)
            }
        }

        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.youtube_country),
            value = youtubeCountries.firstOrNull { it.code == youtubeCountry }?.label
                ?: stringResource(R.string.youtube_country_auto),
            onClick = { showYoutubeCountries = true },
        )
        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.youtube_content_language),
            value = youtubeLanguages.firstOrNull { it.code == youtubeLanguage }?.label ?: autoLabel,
            onClick = { showYoutubeLanguages = true },
        )
        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.youtube_interest),
            value = youtubeTopics.firstOrNull { it.code == youtubeTopic }?.label.orEmpty(),
            onClick = { showYoutubeTopics = true },
        )
        resolvedRegion?.let { region ->
            val countryName = region.countryCode.takeIf { it.isNotEmpty() }?.let { Locale("", it).getDisplayCountry(locale) }
                ?: stringResource(R.string.youtube_country_unknown)
            Text(
                stringResource(R.string.youtube_effective_region, countryName, Locale.forLanguageTag(region.language).getDisplayLanguage(locale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (youtubeCountry.isEmpty()) {
            TextButton(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                Text(stringResource(R.string.radio_use_location))
            }
        }

                }
                "playback" -> {
        SectionTitle(stringResource(R.string.settings_playback))
        GlassCard(Modifier.fillMaxWidth()) {
            LeftSwitchRow(
                batterySaver,
                container.preferences::setBatterySaver,
                Modifier.padding(16.dp),
            ) {
                Icon(Icons.Rounded.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text(stringResource(R.string.battery_saver), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.battery_saver_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.volume), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    Text("${(player.volume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = player.volume, onValueChange = container.player::setVolume, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.equalizer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    item { EqualizerChip(container, R.string.eq_flat, "flat", equalizerPreset) }
                    item { EqualizerChip(container, R.string.eq_bass, "bass", equalizerPreset) }
                    item { EqualizerChip(container, R.string.eq_vocal, "vocal", equalizerPreset) }
                    item { EqualizerChip(container, R.string.eq_treble, "treble", equalizerPreset) }
                }
            }
        }
        TextButton(enabled = !diagnosticBusy, onClick = {
            diagnosticScope.launch {
                diagnosticBusy = true
                try {
                    val report = withContext(Dispatchers.IO) { PlaybackDiagnostics.report(context.applicationContext) }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Mawja playback diagnostics")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, context.getString(R.string.playback_diagnostics)))
                } finally { diagnosticBusy = false }
            }
        }) { Text(stringResource(R.string.playback_diagnostics)) }


                }
            }
        }
    }

    if (section == null) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = footerBottomPadding),
            contentAlignment = Alignment.Center,
        ) {
            SettingsRights(
                Modifier.fillMaxWidth(.78f).onSizeChanged { rightsHeightPx = it.height },
            )
        }
    }
    }

    if (showLanguages) {
        ChoiceDialog(
            title = stringResource(R.string.language),
            options = languages,
            selected = language,
            onSelect = { option ->
                container.preferences.setLanguage(option.code)
                showLanguages = false
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguages = false },
        )
    }
    if (showYoutubeCountries) {
        ChoiceDialog(
            title = stringResource(R.string.youtube_country),
            options = youtubeCountries,
            selected = youtubeCountry,
            onSelect = { option ->
                container.preferences.setYoutubeCountry(option.code)
                showYoutubeCountries = false
            },
            onDismiss = { showYoutubeCountries = false },
        )
    }
    if (showYoutubeLanguages) {
        ChoiceDialog(stringResource(R.string.youtube_content_language), youtubeLanguages, youtubeLanguage,
            { container.preferences.setYoutubeLanguage(it.code); showYoutubeLanguages = false },
            { showYoutubeLanguages = false })
    }
    if (showYoutubeTopics) {
        ChoiceDialog(stringResource(R.string.youtube_interest), youtubeTopics, youtubeTopic,
            { container.preferences.setYoutubeTopic(it.code); showYoutubeTopics = false },
            { showYoutubeTopics = false })
    }

}

@Composable
private fun LeftSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }
}

@Composable
private fun FolderVisibilityRow(folder: MediaFolder, visible: Boolean, onVisibleChange: (Boolean) -> Unit) {
    LeftSwitchRow(visible, onVisibleChange) {
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
            val music = stringResource(R.string.music)
            val videos = stringResource(R.string.videos)
            val categories = listOfNotNull(music.takeIf { folder.hasAudio }, videos.takeIf { folder.hasVideo })
            Text(categories.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (visible) R.string.folder_visible else R.string.folder_hidden),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun OnlineSourceRow(label: String, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    LeftSwitchRow(enabled, onChanged) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.online_source_enabled_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeButton(mode: ThemeMode, label: String, selected: ThemeMode, container: AppContainer, modifier: Modifier) {
    FilterChip(selected = selected == mode, onClick = { container.preferences.setTheme(mode) }, label = { Text(label) }, modifier = modifier)
}

@Composable
private fun SettingCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    GlassCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EqualizerChip(container: AppContainer, label: Int, preset: String, selectedPreset: String) {
    FilterChip(
        selected = selectedPreset == preset,
        onClick = { container.preferences.setEqualizerPreset(preset) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<LanguageOption>, selected: String, onSelect: (LanguageOption) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(search, { search = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 340.dp).selectableGroup()) {
                    items(options.filter { it.label.contains(search, true) || it.code.contains(search, true) }, key = { it.code }) { option ->
                        val checked = option.code == selected
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .selectable(selected = checked, role = Role.RadioButton, onClick = { onSelect(option) })
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            RadioButton(selected = checked, onClick = null)
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

/** Fixed above the measured glass footer; opaque so text stays readable over content. */
@Composable
private fun SettingsRights(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { uriHandler.openUri("https://nabdhserv.net") },
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.primary.copy(alpha = .08f).compositeOver(colors.surface),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = .45f)),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(
                    buildAnnotatedString {
                        append("Made with ")
                        withStyle(SpanStyle(color = colors.error)) { append("♥") }
                        append(" By NabdhServ.Net")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
