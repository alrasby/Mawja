package com.mawja.player.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.mawja.player.data.radio.radioLanguageOptions
import com.mawja.player.data.radio.radioLanguageLabel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mawja.player.R
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.ui.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

@Composable
fun RadioScreen() {
    val context = LocalContext.current
    val container = LocalContainer.current
    val vm: RadioVm = viewModel(factory = VmFactory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val isLocal = filter.id == RadioFilters.local.id
    val countries by vm.worldCountries.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val locationCountry by vm.locationCountry.collectAsStateWithLifecycle()
    val displayMode by container.preferences.fileDisplayMode.collectAsStateWithLifecycle()
    val permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasLocation() = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(hasLocation()) }
    var locationRequest by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasLocation() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocation()
        locationRequest++
    }
    LaunchedEffect(granted, locationRequest, isLocal) {
        if (granted && isLocal) readRadioLocation(context)?.let { vm.setLocalLocation(it.latitude, it.longitude) }
    }
    val locale = context.resources.configuration.locales[0]
    val countryCollator = remember(locale) { java.text.Collator.getInstance(locale) }
    val countryOptions = countries.map { item ->
        item.iso_3166_1 to (countryFlag(item.iso_3166_1) + " " +
            Locale("", item.iso_3166_1).getDisplayCountry(locale).ifBlank { item.name })
    }.sortedWith { first, second -> countryCollator.compare(first.second.substringAfter(' '), second.second.substringAfter(' ')) }
    val allCountries = stringResource(R.string.radio_all_countries)
    val countryLabel = countryOptions.firstOrNull { it.first == country }?.second
        ?: if (isLocal && locationCountry != null) Locale("", locationCountry!!).getDisplayCountry(locale)
        else allCountries

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RadioFilters.all, key = { it.id }) { item ->
                FilterChip(filter.id == item.id, onClick = { vm.select(item) },
                    label = { Text(item.labelRes?.let { stringResource(it) } ?: item.id) })
            }
        }
        if (isLocal) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    if (granted) locationRequest++ else launcher.launch(permissions)
                }) { Text(stringResource(R.string.radio_use_location)) }
                if (granted && locationCountry != null) Text(
                    Locale("", locationCountry!!).getDisplayCountry(locale),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker = "country" }, modifier = Modifier.weight(1f)) { Text(countryLabel, maxLines = 2) }
                OutlinedButton(onClick = { picker = "language" }, modifier = Modifier.weight(1f)) {
                    Text(language?.let { radioLanguageLabel(it, locale) } ?: stringResource(R.string.radio_all_languages), maxLines = 2)
                }
            }
        }
        when {
            isLocal && (!granted || locationCountry == null) ->
                MessageState(stringResource(R.string.radio_choose_location))
            else -> RadioCardGrid(
                state = state, emptyText = stringResource(R.string.empty_stations), onRetry = vm::load,
                modifier = Modifier.weight(1f).fadeTopEdge(), displayMode = displayMode,
            )
        }
    }
    if (picker != null && !isLocal) {
        val isCountryPicker = picker == "country"
        val choices = if (isCountryPicker) countryOptions else radioLanguageOptions(languages.map { it.name }, locale)
        RadioOptionDialog(
            title = stringResource(if (isCountryPicker) R.string.choose_world_country else R.string.radio_language),
            allLabel = if (isCountryPicker) allCountries else stringResource(R.string.radio_all_languages),
            choices = choices,
            selected = if (isCountryPicker) country else language,
            onSelect = { value ->
                if (isCountryPicker) vm.selectCountry(value) else vm.selectLanguage(value)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

@Composable
internal fun RadioOptionDialog(
    title: String, allLabel: String, choices: List<Pair<String, String>>,
    selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .85f
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(Modifier.fillMaxWidth().heightIn(max = maxHeight), opaque = true) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val validChoices = choices.filter { it.first.isNotBlank() && it.second.isNotBlank() }.distinctBy { it.first }
                val visibleChoices = validChoices.filter { it.first.contains(query.trim(), true) || it.second.contains(query.trim(), true) }
                val current = selected?.trim()?.takeIf { value -> validChoices.any { it.first == value } }
                LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                    // One explicit, labeled reset row replaces the old text button + blank option.
                    item(key = "all-options") {
                        RadioOptionRow(allLabel, current == null) { onSelect(null) }
                    }
                    items(visibleChoices, key = { it.first }) { (value, label) ->
                        RadioOptionRow(label, current == value) { onSelect(value) }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun RadioOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, modifier = Modifier.padding(start = 10.dp),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

private suspend fun readRadioLocation(context: Context): android.location.Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time in 0L..21_600_000L }.maxByOrNull { it.time }
    if (known != null) return known
    if (Build.VERSION.SDK_INT < 30) return null
    return withTimeoutOrNull(5_000L) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor) {
                    if (continuation.isActive) continuation.resume(it)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }
}


private fun countryFlag(code: String): String =
    if (code.length == 2 && code.all { it.uppercaseChar() in 'A'..'Z' }) {
        code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
    } else ""
