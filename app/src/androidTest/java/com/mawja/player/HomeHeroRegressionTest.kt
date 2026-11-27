package com.mawja.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mawja.player.data.ThemeMode
import com.mawja.player.ui.MawjaTheme
import com.mawja.player.ui.screens.HomeHero
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeHeroRegressionTest {
    @Test fun compactHeroKeepsControlsAccessibleInBothLanguagesAndThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (language in listOf("en", "ar")) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val clicks = AtomicInteger()
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    setLayoutDirection(Locale.forLanguageTag(language))
                }
                val localized = context.createConfigurationContext(configuration)
                val search = localized.getString(R.string.search)
                val settings = localized.getString(R.string.settings)
                val all = localized.getString(R.string.all_sources)
                scenario.onActivity { activity ->
                    activity.setContent {
                        MawjaTheme(mode) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalContext provides localized,
                                androidx.compose.ui.platform.LocalConfiguration provides configuration,
                                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                                LocalDensity provides Density(density.density, 1.3f),
                            ) {
                                Box(Modifier.width(320.dp)) {
                                    HomeHero(null, true, true, { clicks.incrementAndGet() },
                                        { clicks.incrementAndGet() }, { clicks.incrementAndGet() }, { clicks.incrementAndGet() })
                                }
                            }
                        }
                    }
                }
                fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
                    if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
                fun find(label: String): AccessibilityNodeInfo {
                    val deadline = android.os.SystemClock.uptimeMillis() + 5000
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        instrumentation.waitForIdleSync()
                        descendants(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                            it.text?.toString() == label || it.contentDescription?.toString() == label
                        }?.let { return it }
                        Thread.sleep(50)
                    }
                    throw AssertionError("Missing hero control: $label")
                }
                fun bounds(label: String) = Rect().also { find(label).getBoundsInScreen(it) }
                val searchBounds = bounds(search)
                val settingsBounds = bounds(settings)
                val allBounds = bounds(all)
                val audiusBounds = bounds(localized.getString(R.string.source_audius))
                val youtubeBounds = bounds(localized.getString(R.string.source_youtube))
                assertTrue(searchBounds.centerX() < settingsBounds.centerX())
                assertEquals(searchBounds.centerY(), settingsBounds.centerY())
                assertTrue(settingsBounds.bottom <= allBounds.top)
                assertEquals(allBounds.centerY(), audiusBounds.centerY())
                assertEquals(audiusBounds.centerY(), youtubeBounds.centerY())
                assertTrue(allBounds.right <= audiusBounds.left)
                assertTrue(audiusBounds.right <= youtubeBounds.left)
                assertTrue(descendants(instrumentation.uiAutomation.rootInActiveWindow).none {
                    it.text?.toString() == localized.getString(R.string.discover_more)
                })
                assertTrue(youtubeBounds.right <= context.resources.displayMetrics.widthPixels)
                for (label in listOf(search, settings, all, localized.getString(R.string.source_audius),
                    localized.getString(R.string.source_youtube), localized.getString(R.string.discover_more))) {
                    val b = bounds(label)
                    val expected = clicks.get()
                    instrumentation.uiAutomation.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}").close()
                    val deadline = android.os.SystemClock.uptimeMillis() + 2000
                    while (clicks.get() == expected && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
                }
                assertEquals("Every control must respond: $language/$mode", 6, clicks.get())
                Thread.sleep(350) // Capture after the last touch ripple settles.
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.filesDir, "home-hero-$language-${mode.name.lowercase()}.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }
}
