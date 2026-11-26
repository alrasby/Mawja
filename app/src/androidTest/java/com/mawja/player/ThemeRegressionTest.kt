package com.mawja.player

import androidx.activity.compose.setContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mawja.player.data.ThemeMode
import com.mawja.player.ui.screens.RadioOptionDialog
import com.mawja.player.ui.GlassDialog
import com.mawja.player.ui.MawjaTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeRegressionTest {
    @Test fun defaultAndDialogContentFollowBothThemesImmediately() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                val expected = AtomicReference<Color>()
                val inherited = AtomicReference<Color>()
                val dialog = AtomicReference<Color>()
                scenario.onActivity { activity ->
                    activity.setContent {
                        MawjaTheme(mode) {
                            val foreground = MaterialTheme.colorScheme.onSurface
                            val current = LocalContentColor.current
                            SideEffect { expected.set(foreground); inherited.set(current) }
                            GlassDialog(onDismissRequest = {}, title = { Text("Theme check") }, text = {
                                val dialogColor = LocalContentColor.current
                                SideEffect { dialog.set(dialogColor) }
                                Text("Readable dialog content")
                            }, confirmButton = {})
                        }
                    }
                }
                // setContent schedules composition on a frame; main-looper idle alone
                // can happen before Compose applies its SideEffects/dialog content.
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while ((expected.get() == null || inherited.get() == null || dialog.get() == null) &&
                    android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(30)
                }
                scenario.onActivity { activity ->
                    val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertEquals(mode != ThemeMode.DARK, controller.isAppearanceLightStatusBars)
                    assertEquals(mode != ThemeMode.DARK, controller.isAppearanceLightNavigationBars)
                }
                assertNotNull(expected.get())
                assertEquals(expected.get(), inherited.get())
                assertEquals(expected.get(), dialog.get())
                if (mode == ThemeMode.DARK) assertTrue(inherited.get().red > .8f)
                else assertTrue(inherited.get().red < .3f)
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.filesDir, "theme-${mode.name.lowercase()}.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            scenario.onActivity { activity ->
                activity.setContent {
                    MawjaTheme(ThemeMode.LIGHT) {
                        RadioOptionDialog(title = "Station language", allLabel = "All languages",
                            choices = listOf("" to "", "arabic" to "Arabic · العربية"),
                            selected = "", onSelect = {}, onDismiss = {})
                    }
                }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var labels = emptyList<String>()
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
                    if (node == null) return emptyList()
                    return listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { collect(node.getChild(it)) }
                }
                labels = collect(root)
                if (labels.any { it.contains("Arabic") } && labels.any { it == "All languages" }) break
                Thread.sleep(30)
            }
            assertTrue(labels.any { it == "All languages" })
            assertTrue(labels.any { it.contains("Arabic · العربية") })
        }
    }
}
