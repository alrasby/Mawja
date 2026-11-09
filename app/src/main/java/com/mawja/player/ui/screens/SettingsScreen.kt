package com.mawja.player.ui.screens

import android.app.Activity
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mawja.player.playback.PlaybackService
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

private val youtubeCountries = listOf(
    LanguageOption("", "Auto • GPS / Network / SIM"),
    LanguageOption("OM", "Oman • عُمان"),
    LanguageOption("SA", "Saudi Arabia • السعودية"),
    LanguageOption("AE", "United Arab Emirates • الإمارات"),
    LanguageOption("KW", "Kuwait • الكويت"),
    LanguageOption("QA", "Qatar • قطر"),
    LanguageOption("BH", "Bahrain • البحرين"),
    LanguageOption("YE", "Yemen • اليمن"),
    LanguageOption("EG", "Egypt • مصر"),
    LanguageOption("JO", "Jordan • الأردن"),
    LanguageOption("IQ", "Iraq • العراق"),
    LanguageOption("LB", "Lebanon • لبنان"),
    LanguageOption("PS", "Palestine • فلسطين"),
    LanguageOption("SY", "Syria • سوريا"),
    LanguageOption("MA", "Morocco • المغرب"),
    LanguageOption("DZ", "Algeria • الجزائر"),
    LanguageOption("TN", "Tunisia • تونس"),
    LanguageOption("LY", "Libya • ليبيا"),
    LanguageOption("SD", "Sudan • السودان"),
)

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val theme by container.preferences.theme.collectAsStateWithLifecycle()
    val language by container.preferences.language.collectAsStateWithLifecycle()
    val batterySaver by container.preferences.batterySaver.collectAsStateWithLifecycle()
    val equalizerPreset by container.preferences.equalizerPreset.collectAsStateWithLifecycle()
    val showMusicFolders by container.preferences.showMusicFolders.collectAsStateWithLifecycle()
    val hiddenFolders by container.preferences.hiddenFolders.collectAsStateWithLifecycle()
    val audiusEnabled by container.preferences.audiusEnabled.collectAsStateWithLifecycle()
    val youtubeEnabled by container.preferences.youtubeEnabled.collectAsStateWithLifecycle()
    val fileDisplayMode by container.preferences.fileDisplayMode.collectAsStateWithLifecycle()
    val youtubeCountry by container.preferences.youtubeCountry.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val footerBottomPadding = LocalFooterBottomPadding.current
    var showLanguages by remember { mutableStateOf(false) }
    var showYoutubeCountries by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) {
            container.local.folders()
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_appearance))
        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.language),
            value = languages.firstOrNull { it.code == language }?.label ?: "English",
            onClick = { showLanguages = true },
        )
        SettingCard(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.youtube_country),
            value = youtubeCountries.firstOrNull { it.code == youtubeCountry }?.label
                ?: stringResource(R.string.youtube_country_auto),
            onClick = { showYoutubeCountries = true },
        )
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                        Text(stringResource(R.string.show_music_folders), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.show_music_folders_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = showMusicFolders, onCheckedChange = container.preferences::setShowMusicFolders)
                }
                Text(stringResource(R.string.folders_in_device), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (folders.isEmpty()) {
                    Text(stringResource(R.string.no_folders_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    folders.forEach { folder ->
                        FolderVisibilityRow(
                            folder = folder,
                            visible = folder !in hiddenFolders,
                            onVisibleChange = { container.preferences.setFolderVisible(folder, it) },
                        )
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_online_sources))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_online_sources_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OnlineSourceRow(stringResource(R.string.enable_audius), audiusEnabled, container.preferences::setAudiusEnabled)
                OnlineSourceRow(stringResource(R.string.enable_youtube), youtubeEnabled, container.preferences::setYoutubeEnabled)
            }
        }

        SectionTitle(stringResource(R.string.settings_playback))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text(stringResource(R.string.battery_saver), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.battery_saver_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = batterySaver,
                    onCheckedChange = { enabled ->
                        container.preferences.setBatterySaver(enabled)
                        PlaybackService.setBatterySaver(context, enabled)
                    },
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.volume), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp))
                    Text("${(player.volume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                Slider(value = player.volume, onValueChange = container.player::setVolume, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.equalizer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    item { EqualizerChip(container, context, R.string.eq_flat, "flat", equalizerPreset) }
                    item { EqualizerChip(container, context, R.string.eq_bass, "bass", equalizerPreset) }
                    item { EqualizerChip(container, context, R.string.eq_vocal, "vocal", equalizerPreset) }
                    item { EqualizerChip(container, context, R.string.eq_treble, "treble", equalizerPreset) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Made with ", style = MaterialTheme.typography.labelMedium)
                    Text("♥", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    Text(" by NabdhServ.net", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(20.dp + footerBottomPadding))
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
}

@Composable
private fun FolderVisibilityRow(folder: String, visible: Boolean, onVisibleChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(folder, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (visible) R.string.folder_visible else R.string.folder_hidden),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = visible, onCheckedChange = onVisibleChange)
    }
}

@Composable
private fun OnlineSourceRow(label: String, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.online_source_enabled_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = onChanged)
    }
}

@Composable
private fun ThemeButton(mode: ThemeMode, label: String, selected: ThemeMode, container: AppContainer, modifier: Modifier) {
    FilterChip(selected = selected == mode, onClick = { container.preferences.setTheme(mode) }, label = { Text(label) }, modifier = modifier)
}

@Composable
private fun SettingCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
private fun EqualizerChip(container: AppContainer, context: Context, label: Int, preset: String, selectedPreset: String) {
    FilterChip(
        selected = selectedPreset == preset,
        onClick = {
            container.preferences.setEqualizerPreset(preset)
            PlaybackService.setEqualizerPreset(context, preset)
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<LanguageOption>, selected: String, onSelect: (LanguageOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(options, key = { it.code }) { option ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option.code == selected, onClick = { onSelect(option) })
                        Text(option.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
