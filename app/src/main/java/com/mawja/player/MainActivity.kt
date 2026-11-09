package com.mawja.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mawja.player.data.PreferencesStore
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.MawjaRoot
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var incomingMediaUri by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingMediaUri = mediaUriFromIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val container = (application as MawjaApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                NotificationPermissionEffect()
                MawjaRoot()
                val uri = incomingMediaUri
                if (uri != null) {
                    LaunchedEffect(uri) {
                        val track = trackFromUri(uri)
                        if (track != null) {
                            container.play(listOf(track), 0)
                            incomingMediaUri = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingMediaUri = mediaUriFromIntent(intent)
    }

    private fun mediaUriFromIntent(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val type = intent.type.orEmpty()
        return uri.takeIf { type.startsWith("audio/", true) || type.startsWith("video/", true) || type.isBlank() }
    }

    private fun trackFromUri(uri: Uri): Track? {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "Media file" } }
        val isVideo = intent.type.orEmpty().startsWith("video/", true)
        val title = name.substringBeforeLast('.').ifBlank { name }
        return Track(
            id = "external:${uri}",
            source = SourceType.LOCAL,
            title = title,
            subtitle = if (isVideo) "Video" else "",
            streamUrl = uri.toString(),
            isVideo = isVideo,
        )
    }
}

private fun localizedContext(base: Context): Context {
    val language = base.getSharedPreferences(PreferencesStore.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PreferencesStore.KEY_LANGUAGE, "en")
        .orEmpty()
        .ifBlank { "en" }
    val locale = Locale.forLanguageTag(language)
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return base.createConfigurationContext(configuration)
}

/** Android 13+ needs a runtime permission for the playback notification. */
@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
