package com.mawja.player

import android.os.Bundle
import android.net.Uri
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Official YouTube player host for videos that cannot be resolved to a direct stream.
 * The hosted HTTPS page supplies YouTube with a real origin and referrer context.
 */
class YouTubeFallbackActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)?.trim().orEmpty()
        if (videoId.length != 11) {
            finish()
            return
        }
        val playerBase = BuildConfig.YOUTUBE_PLAYER_BASE_URL.trimEnd('/')
        val playerUrl = "$playerBase/youtube.html?id=$videoId"
        val playerUri = Uri.parse(playerUrl)
        if (playerUri.scheme != "https" || playerUri.host != "mawjaplay-bumwjcee.manus.space") {
            finish()
            return
        }
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty().lowercase()
                    return host !in setOf(
                        "mawjaplay-bumwjcee.manus.space",
                        "www.youtube.com",
                        "youtube.com",
                        "www.youtube-nocookie.com",
                        "youtube-nocookie.com",
                    )
                }
            }
            webChromeClient = WebChromeClient()
            // The hosted page embeds the official YouTube IFrame from a real
            // HTTPS origin. This avoids the synthetic WebView context that
            // causes Error 152-4 in direct/local embeds.
            loadUrl(playerUri.toString())
        }
        val playerView = requireNotNull(webView)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
        // Request after attaching the view; requesting before setContentView
        // is why the previous build still allowed the navigation bar to cover it.
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view?.canGoBack() == true) view.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
