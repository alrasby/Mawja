package com.mawja.player.data

import android.content.Context
import org.json.JSONArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class FileDisplayMode { GRID, LIST }

data class LanguageOption(val code: String, val label: String)

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "en").orEmpty())
    val language: StateFlow<String> = _language.asStateFlow()
    private val _batterySaver = MutableStateFlow(prefs.getBoolean(KEY_BATTERY_SAVER, false))
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()
    private val _equalizerPreset = MutableStateFlow(prefs.getString(KEY_EQ_PRESET, "flat").orEmpty().ifBlank { "flat" })
    val equalizerPreset: StateFlow<String> = _equalizerPreset.asStateFlow()
    private val _showMusicFolders = MutableStateFlow(prefs.getBoolean(KEY_SHOW_MUSIC_FOLDERS, true))
    val showMusicFolders: StateFlow<Boolean> = _showMusicFolders.asStateFlow()
    private val _hiddenFolders = MutableStateFlow(readHiddenFolders())
    val hiddenFolders: StateFlow<Set<String>> = _hiddenFolders.asStateFlow()
    private val _audiusEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUDIUS_ENABLED, true))
    val audiusEnabled: StateFlow<Boolean> = _audiusEnabled.asStateFlow()
    private val _youtubeEnabled = MutableStateFlow(prefs.getBoolean(KEY_YOUTUBE_ENABLED, true))
    val youtubeEnabled: StateFlow<Boolean> = _youtubeEnabled.asStateFlow()
    private val _fileDisplayMode = MutableStateFlow(readFileDisplayMode())
    val fileDisplayMode: StateFlow<FileDisplayMode> = _fileDisplayMode.asStateFlow()
    private val _youtubeCountry = MutableStateFlow(prefs.getString(KEY_YOUTUBE_COUNTRY, "").orEmpty())
    val youtubeCountry: StateFlow<String> = _youtubeCountry.asStateFlow()
    val locationPromptHandled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_PROMPT_HANDLED, false)

    fun setTheme(value: ThemeMode) { prefs.edit().putString(KEY_THEME, value.name).apply(); _theme.value = value }
    fun setLanguage(value: String) { prefs.edit().putString(KEY_LANGUAGE, value).apply(); _language.value = value }
    fun setBatterySaver(value: Boolean) { prefs.edit().putBoolean(KEY_BATTERY_SAVER, value).apply(); _batterySaver.value = value }
    fun setEqualizerPreset(value: String) { val preset = value.ifBlank { "flat" }; prefs.edit().putString(KEY_EQ_PRESET, preset).apply(); _equalizerPreset.value = preset }
    fun setShowMusicFolders(value: Boolean) { prefs.edit().putBoolean(KEY_SHOW_MUSIC_FOLDERS, value).apply(); _showMusicFolders.value = value }
    fun setAudiusEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_AUDIUS_ENABLED, value).apply(); _audiusEnabled.value = value }
    fun setYoutubeEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, value).apply(); _youtubeEnabled.value = value }
    fun setFileDisplayMode(value: FileDisplayMode) { prefs.edit().putString(KEY_FILE_DISPLAY_MODE, value.name).apply(); _fileDisplayMode.value = value }
    fun setYoutubeCountry(value: String) { prefs.edit().putString(KEY_YOUTUBE_COUNTRY, value).apply(); _youtubeCountry.value = value }
    fun markLocationPromptHandled() { prefs.edit().putBoolean(KEY_LOCATION_PROMPT_HANDLED, true).apply() }

    fun setFolderVisible(folder: String, visible: Boolean) {
        val clean = folder.trim()
        if (clean.isBlank()) return
        val next = _hiddenFolders.value.toMutableSet().apply { if (visible) remove(clean) else add(clean) }.toSet()
        val json = JSONArray().apply { next.sorted().forEach(::put) }.toString()
        prefs.edit().putString(KEY_HIDDEN_FOLDERS, json).apply()
        _hiddenFolders.value = next
    }

    private fun readTheme(): ThemeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty()) }.getOrDefault(ThemeMode.SYSTEM)
    private fun readFileDisplayMode(): FileDisplayMode = runCatching {
        FileDisplayMode.valueOf(prefs.getString(KEY_FILE_DISPLAY_MODE, FileDisplayMode.GRID.name).orEmpty())
    }.getOrDefault(FileDisplayMode.GRID)
    private fun readHiddenFolders(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_HIDDEN_FOLDERS, "[]"))
        buildSet { for (index in 0 until array.length()) array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add) }
    }.getOrDefault(emptySet())

    companion object {
        const val PREFS_NAME = "mawja_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_BATTERY_SAVER = "battery_saver"
        const val KEY_EQ_PRESET = "equalizer_preset"
        const val KEY_SHOW_MUSIC_FOLDERS = "show_music_folders"
        const val KEY_HIDDEN_FOLDERS = "hidden_music_folders"
        const val KEY_AUDIUS_ENABLED = "audius_enabled"
        const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
        const val KEY_FILE_DISPLAY_MODE = "file_display_mode"
        const val KEY_YOUTUBE_COUNTRY = "youtube_country"
        const val KEY_LOCATION_PROMPT_HANDLED = "location_prompt_handled"
        private const val KEY_THEME = "theme"
    }
}
