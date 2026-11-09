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
) {
    val arabicGulf: Boolean
        get() = countryCode in setOf("OM", "SA", "AE", "KW", "QA", "BH")
}

/** Uses a fresh network/GPS fix first, then carrier/SIM/locale fallbacks. */
object RegionResolver {
    @Volatile private var cached: Pair<Long, YouTubeRegion>? = null
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    suspend fun resolve(context: Context): YouTubeRegion = withContext(Dispatchers.IO) {
        val manualCountry = context.getSharedPreferences(PreferencesStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PreferencesStore.KEY_YOUTUBE_COUNTRY, "")
            .orEmpty()
            .uppercase(Locale.ROOT)
        if (manualCountry in ARABIC_COUNTRIES) {
            return@withContext YouTubeRegion(manualCountry, "ar")
        }
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.first < CACHE_TTL_MS }?.second?.let { return@withContext it }
        val location = currentLocation(context)
        val address = location?.let { reverseGeocode(context, it) }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val carrierCountry = telephony?.networkCountryIso?.uppercase(Locale.ROOT).orEmpty()
        val simCountry = telephony?.simCountryIso?.uppercase(Locale.ROOT).orEmpty()
        val localeCountry = Locale.getDefault().country.uppercase(Locale.ROOT)
        val country = sequenceOf(address?.countryCode, carrierCountry, simCountry, localeCountry, "OM")
            .mapNotNull { it?.trim()?.takeIf { value -> value.length == 2 && value.all(Char::isLetter) } }
            .firstOrNull() ?: "OM"
        val language = if (country in setOf("OM", "SA", "AE", "KW", "QA", "BH")) {
            "ar"
        } else {
            Locale.getDefault().language.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "ar"
        }
        val resolved = YouTubeRegion(country, language, location?.latitude, location?.longitude, address?.locality)
        cached = now to resolved
        resolved
    }

    private suspend fun currentLocation(context: Context): Location? {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 6 * 60 * 60 * 1000L }
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

    private val ARABIC_COUNTRIES = setOf(
        "OM", "SA", "AE", "KW", "QA", "BH", "YE", "JO", "LB", "SY", "IQ", "PS",
        "EG", "SD", "LY", "TN", "DZ", "MA", "MR", "SO", "DJ", "KM",
    )
}
