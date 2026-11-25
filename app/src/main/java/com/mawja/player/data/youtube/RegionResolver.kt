package com.mawja.player.data.youtube

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.mawja.player.data.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class YouTubeRegion(
    val countryCode: String,
    val language: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locality: String? = null,
)
/** Resolve the country independently from the user's preferred content language. */
object RegionResolver {
    private data class CachedRegion(val key: String, val time: Long, val value: YouTubeRegion)
    @Volatile private var cached: CachedRegion? = null

    suspend fun resolve(context: Context): YouTubeRegion = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PreferencesStore.PREFS_NAME, Context.MODE_PRIVATE)
        val manual = normalizeYouTubeCountry(prefs.getString(PreferencesStore.KEY_YOUTUBE_COUNTRY, "").orEmpty())
        val language = contentLanguage(
            prefs.getString(PreferencesStore.KEY_YOUTUBE_LANGUAGE, "").orEmpty(),
            prefs.getString(PreferencesStore.KEY_LANGUAGE, "").orEmpty(),
            Locale.getDefault().toLanguageTag(),
        )
        if (manual.isNotEmpty()) return@withContext YouTubeRegion(manual, language)
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val network = runCatching { telephony?.networkCountryIso }.getOrNull().orEmpty()
        val sim = runCatching { telephony?.simCountryIso }.getOrNull().orEmpty()
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val key = listOf(language, network, sim, Locale.getDefault().toLanguageTag(), permission).joinToString("|")
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.takeIf { it.key == key && now - it.time < 60_000L }?.let { return@withContext it.value }
        val location = currentLocation(context)
        val address = location?.let { reverseGeocode(context, it) }
        val country = sequenceOf(address?.countryCode.orEmpty(), network, sim, Locale.getDefault().country)
            .map(::normalizeYouTubeCountry).firstOrNull { it.isNotEmpty() }.orEmpty()
        val resolved = YouTubeRegion(country, language, location?.latitude, location?.longitude, address?.locality)
        cached = CachedRegion(key, now, resolved)
        resolved
    }

    private suspend fun currentLocation(context: Context): Location? {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time in 0L..21_600_000L }
            .maxByOrNull { it.time }
        if (known != null) return known
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val network = withTimeoutOrNull(2_500L) { requestCurrent(manager, LocationManager.NETWORK_PROVIDER, context) }
            if (network != null) return network
        }
        return null
    }

    @SuppressLint("MissingPermission", "NewApi")
    private suspend fun requestCurrent(manager: LocationManager, provider: String, context: Context): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }

    private fun reverseGeocode(context: Context, location: Location): AddressParts? {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        return runCatching {
            Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { AddressParts(it.countryCode?.uppercase(Locale.ROOT), it.locality ?: it.subAdminArea ?: it.adminArea) }
        }.getOrNull()
    }

    private data class AddressParts(val countryCode: String?, val locality: String?)

}
