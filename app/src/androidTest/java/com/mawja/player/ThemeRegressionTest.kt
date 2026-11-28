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
        // Both launcher paths and the old resource name must resolve to the new adaptive layers.
        val launcher = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(launcher is android.graphics.drawable.AdaptiveIconDrawable)
        assertTrue(context.getDrawable(R.mipmap.mawja_icon_clean) is android.graphics.drawable.AdaptiveIconDrawable)
        val component = android.content.ComponentName(context, MainActivity::class.java)
        assertTrue(context.packageManager.getActivityIcon(component) is android.graphics.drawable.AdaptiveIconDrawable)
        assertEquals(R.mipmap.ic_launcher_mawja, context.applicationInfo.icon)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertNotNull((launcher as android.graphics.drawable.AdaptiveIconDrawable).monochrome)
        }
        val source = requireNotNull(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.mawja_mark_silver_turquoise))
        assertTrue("Foreground must be genuinely transparent, not a framed tile", source.hasAlpha())
        for ((x, y) in listOf(0 to 0, source.width - 1 to 0, 0 to source.height - 1,
            source.width - 1 to source.height - 1)) assertEquals(0, android.graphics.Color.alpha(source.getPixel(x, y)))
        var visible = 0
        var gold = 0
        for (y in 0 until source.height step 4) for (x in 0 until source.width step 4) {
            val pixel = source.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) <= 32) continue
            visible++
            // 108dp layer with 20% inset: all visible logo pixels must fit the safe 66dp circle.
            val dx = ((x + .5) / source.width - .5) * 64.8
            val dy = ((y + .5) / source.height - .5) * 64.8
            assertTrue("Logo would clip in a circular mask", dx * dx + dy * dy <= 33.0 * 33.0)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            if (r > g * 1.12 && g > b * 1.2 && r - b > 45) gold++
        }
        assertTrue(visible > 1000)
        assertEquals("No old gold logo in the foreground", 0, gold)
        source.recycle()
        fun save(bitmap: android.graphics.Bitmap, name: String) {
            File(context.filesDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val preview = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        launcher.setBounds(0, 0, 512, 512)
        launcher.draw(android.graphics.Canvas(preview))
        save(preview, "launcher-adaptive.png")
        preview.recycle()
        val renders = mutableListOf<android.graphics.Bitmap>()
        for (name in listOf("circle", "rounded-square", "square")) {
            val bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val mask = android.graphics.Path()
            when (name) {
                "circle" -> mask.addCircle(256f, 256f, 256f, android.graphics.Path.Direction.CW)
                "rounded-square" -> mask.addRoundRect(android.graphics.RectF(0f, 0f, 512f, 512f), 112f, 112f, android.graphics.Path.Direction.CW)
                else -> mask.addRect(0f, 0f, 512f, 512f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(mask)
            // Draw the actual resources in Android's expanded 108:72 adaptive viewport.
            canvas.translate(-128f, -128f)
            for (id in listOf(R.drawable.mawja_icon_background, R.drawable.mawja_icon_foreground)) {
                val layer = requireNotNull(context.getDrawable(id))
                layer.setBounds(0, 0, 768, 768)
                layer.draw(canvas)
            }
            save(bitmap, "launcher-$name.png")
            renders.add(bitmap)
        }
        for (x in 128..384 step 32) for (y in 128..384 step 32) {
            assertEquals(renders[0].getPixel(x, y), renders[1].getPixel(x, y))
            assertEquals(renders[0].getPixel(x, y), renders[2].getPixel(x, y))
        }
        assertEquals(255, android.graphics.Color.alpha(renders[2].getPixel(1, 1)))
        renders.forEach { it.recycle() }

    }
}
