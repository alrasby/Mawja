package com.mawja.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mawja.player.data.PreferencesStore
import com.mawja.player.data.SourceType
import com.mawja.player.data.Track
import com.mawja.player.ui.LocalContainer
import com.mawja.player.ui.MawjaRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var incomingMediaUri by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingMediaUri = if (savedInstanceState == null) mediaUriFromIntent(intent) else null
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
                MawjaRoot()
                val uri = incomingMediaUri
                if (uri != null) {
                    LaunchedEffect(uri) {
                        val isVideo = intent.type.orEmpty().startsWith("video/", true)
                        val track = trackFromUri(uri, isVideo)
                        if (incomingMediaUri == uri) {
                            incomingMediaUri = null
                            container.play(listOf(track), 0)
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
        val uri = intent.data?.takeIf { it.scheme == "content" } ?: return null
        val type = intent.type.orEmpty()
        return uri.takeIf { type.startsWith("audio/", true) || type.startsWith("video/", true) || type.isBlank() }
    }

    private suspend fun trackFromUri(uri: Uri, isVideo: Boolean): Track = withContext(Dispatchers.IO) {
        val displayName = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        val name = displayName.ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "Media file" } }
        val title = name.substringBeforeLast('.').ifBlank { name }
        Track(
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
