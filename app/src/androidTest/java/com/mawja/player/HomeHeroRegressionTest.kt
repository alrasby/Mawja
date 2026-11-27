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
import com.mawja.player.data.PreferencesStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
                val all = localized.getString(R.string.tab_device)
                val preferences = PreferencesStore(context).apply {
                    setHomeDeviceEnabled(true); setAudiusEnabled(true); setYoutubeEnabled(true)
                }
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
                                    val device by preferences.homeDeviceEnabled.collectAsStateWithLifecycle()
                                    val audius by preferences.audiusEnabled.collectAsStateWithLifecycle()
                                    val youtube by preferences.youtubeEnabled.collectAsStateWithLifecycle()
                                    HomeHero(device, audius, youtube,
                                        { preferences.setHomeDeviceEnabled(!preferences.homeDeviceEnabled.value); clicks.incrementAndGet() },
                                        { preferences.setAudiusEnabled(!preferences.audiusEnabled.value); clicks.incrementAndGet() },
                                        { preferences.setYoutubeEnabled(!preferences.youtubeEnabled.value); clicks.incrementAndGet() },
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
                fun tap(label: String) {
                    val b = bounds(label)
                    val expected = clicks.get()
                    instrumentation.uiAutomation.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}").close()
                    val deadline = android.os.SystemClock.uptimeMillis() + 2000
                    while (clicks.get() == expected && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
                    assertEquals(expected + 1, clicks.get())
                    Thread.sleep(100)
                    instrumentation.waitForIdleSync()
                }
                val labels = listOf(all, localized.getString(R.string.source_audius), localized.getString(R.string.source_youtube))
                fun checked(label: String): Boolean {
                    var node = find(label)
                    while (!node.isCheckable && node.parent != null) node = node.parent
                    assertTrue("Independent switch semantics", node.isCheckable)
                    return node.isChecked
                }
                assertTrue(labels.all(::checked))
                labels.forEachIndexed { index, label ->
                    tap(label)
                    labels.forEachIndexed { other, name -> assertEquals(other > index, checked(name)) }
                }
                val restored = PreferencesStore(context)
                assertFalse(restored.homeDeviceEnabled.value)
                assertFalse(restored.audiusEnabled.value)
                assertFalse(restored.youtubeEnabled.value)
                labels.forEach(::tap)
                assertTrue(labels.all(::checked))
                for (label in listOf(search, settings, localized.getString(R.string.discover_more))) tap(label)
                assertEquals("Every control must respond: $language/$mode", 9, clicks.get())
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
