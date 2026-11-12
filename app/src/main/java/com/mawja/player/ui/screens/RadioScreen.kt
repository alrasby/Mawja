package com.mawja.player.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mawja.player.R
import com.mawja.player.data.FileDisplayMode
import com.mawja.player.data.radio.RadioFilter
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.MessageState
import com.mawja.player.ui.RadioVm
import com.mawja.player.ui.SearchField
import com.mawja.player.ui.RadioCardGrid
import com.mawja.player.ui.VmFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
private fun RadioFilter.label(): String = labelRes?.let { stringResource(it) }
    ?: countryCodes.firstOrNull()?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.getDefault()) }
    ?: id

@Composable
fun RadioScreen() {
    val context = LocalContext.current
    val container = LocalContainer.current
    val vm: RadioVm = viewModel(factory = VmFactory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val localReady by vm.localReady.collectAsStateWithLifecycle()
    val worldCountries by vm.worldCountries.collectAsStateWithLifecycle()
    val worldCountry by vm.worldCountry.collectAsStateWithLifecycle()
    val displayMode by container.preferences.fileDisplayMode.collectAsStateWithLifecycle()
    val locationPermissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    var hasPermission by remember { mutableStateOf(locationPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    var locationPromptHandled by remember { mutableStateOf(container.preferences.locationPromptHandled) }
    var worldMenuOpen by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result.values.any { it }
        if (hasPermission) readLocation(context) { lat, lon -> vm.setLocalLocation(lat, lon) }
    }

    // Auto-load local stations when permission is already granted, without requiring a chip click
    LaunchedEffect(hasPermission, filter.id, localReady) {
        if (hasPermission && filter.id == RadioFilters.local.id && !localReady) {
            readLocation(context) { lat, lon -> vm.setLocalLocation(lat, lon) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(value = query, onValueChange = vm::onQuery, hint = stringResource(R.string.search_stations), onSubmit = vm::load)
        if (query.isBlank()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RadioFilters.all, key = { it.id }) { f ->
                    FilterChip(
                        selected = f.id == filter.id,
                        onClick = {
                            vm.select(f)
                            if (f.id == RadioFilters.local.id) {
                                if (hasPermission) readLocation(context) { lat, lon -> vm.setLocalLocation(lat, lon) }
                                else if (!locationPromptHandled) {
                                    container.preferences.markLocationPromptHandled()
                                    locationPromptHandled = true
                                    locationLauncher.launch(locationPermissions)
                                }
                            }
                        },
                        label = { Text(f.label()) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (filter.id == RadioFilters.world.id) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedButton(
                        onClick = { worldMenuOpen = true },
                        enabled = worldCountries.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Public, contentDescription = null)
                        Text(
                            worldCountries.firstOrNull { it.iso_3166_1 == worldCountry }?.name
                                ?: stringResource(R.string.choose_world_country),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = worldMenuOpen,
                        onDismissRequest = { worldMenuOpen = false },
                        modifier = Modifier.heightIn(max = 560.dp),
                    ) {
                        worldCountries.filter { it.name.isNotBlank() }.forEach { country ->
                            DropdownMenuItem(
                                text = { Text(country.name) },
                                onClick = { worldMenuOpen = false; vm.selectWorldCountry(country.iso_3166_1) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        when {
            filter.id == RadioFilters.local.id && !localReady && !locationPromptHandled -> MessageState(stringResource(R.string.local_radio_location_needed), stringResource(R.string.allow_location)) {
                if (hasPermission) readLocation(context) { lat, lon -> vm.setLocalLocation(lat, lon) } else {
                    container.preferences.markLocationPromptHandled()
                    locationPromptHandled = true
                    locationLauncher.launch(locationPermissions)
                }
            }
            filter.id == RadioFilters.local.id && !localReady -> RadioCardGrid(
                state = state,
                displayMode = displayMode,
                emptyText = stringResource(R.string.empty_stations),
                onRetry = vm::load,
                modifier = Modifier.weight(1f),
            )
            filter.id == RadioFilters.world.id && worldCountry == null -> MessageState(stringResource(R.string.choose_world_country))
            else -> RadioCardGrid(
                state = state,
                displayMode = displayMode,
                emptyText = stringResource(R.string.empty_stations),
                onRetry = vm::load,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun readLocation(context: Context, onLocation: (Double, Double) -> Unit) {
    val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!coarseGranted && !fineGranted) return
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    runCatching {
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) onLocation(last.latitude, last.longitude)
        else if (Build.VERSION.SDK_INT >= 30) {
            val delivered = AtomicBoolean(false)
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
                .forEach { provider ->
                    manager.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) { location ->
                        if (location != null && delivered.compareAndSet(false, true)) onLocation(location.latitude, location.longitude)
                    }
                }
        }
    }
}
