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
                instrumentation.waitForIdleSync()
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
        }
    }
}
