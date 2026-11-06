package com.shakerecorder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], qualifiers = "w412dp-h915dp-xxhdpi")
class UpdateActivityTest {
    private var main: ActivityController<MainActivity>? = null
    private var update: ActivityController<UpdateActivity>? = null
    @After fun finish() {
        update?.pause()?.stop()?.destroy()
        main?.pause()?.stop()?.destroy()
    }

    @Test fun menuOpensDedicatedUpdaterWithoutStartingInstallerOrDeletingPreferences() {
        main = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = main!!.get()
        val settings = SettingsManager(activity)
        settings.volumePressCount = 4
        activity.findViewById<View>(R.id.checkUpdateButton).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(UpdateActivity::class.java.name, intent.component!!.className)
        assertNotEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals(4, settings.volumePressCount)
    }

    @Test fun updaterShowsVersionAndBackReturnsWithoutInstaller() {
        update = Robolectric.buildActivity(UpdateActivity::class.java).setup().visible()
        val activity = update!!.get()
        assertTrue(activity.findViewById<TextView>(R.id.currentVersion).text.contains(BuildConfig.VERSION_NAME))
        assertTrue(activity.findViewById<View>(R.id.updateButton).isShown)
        assertNull(shadowOf(activity).nextStartedActivity)
        activity.findViewById<View>(R.id.backButton).performClick()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun recreationDoesNotAutomaticallyDownloadOrLaunchInstaller() {
        update = Robolectric.buildActivity(UpdateActivity::class.java).setup().visible()
        update!!.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = update!!.get()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.findViewById<TextView>(R.id.currentVersion).text.contains(BuildConfig.VERSION_NAME))
    }
    private fun renderUpdater(name: String) {
        update = Robolectric.buildActivity(UpdateActivity::class.java).setup().visible()
        val activity = update!!.get()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        val root = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        assertButtonLabelVisible(activity.findViewById(R.id.backButton), bitmap, root)
        assertButtonLabelVisible(activity.findViewById(R.id.updateButton), bitmap, root)
        val file = java.io.File("build/reports/ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(activity.findViewById<View>(R.id.updateButton).isShown)
    }

    /** Check both resolved contrast and real glyph pixels, not just a nonempty label. */
    private fun assertButtonLabelVisible(button: Button, screenshot: Bitmap, root: View) {
        assertTrue("Button must be visible", button.isShown)
        assertTrue("Button label must not be empty", button.text.isNotBlank())
        assertTrue("Button label must be opaque", Color.alpha(button.currentTextColor) == 255)
        assertEquals("Button must not be faded", 1f, button.alpha, 0f)
        assertTrue("Button must have a laid-out text line", button.layout != null && button.layout.lineCount > 0)
        val buttonLocation = IntArray(2).also { button.getLocationOnScreen(it) }
        val rootLocation = IntArray(2).also { root.getLocationOnScreen(it) }
        val pixels = Bitmap.createBitmap(screenshot, buttonLocation[0] - rootLocation[0],
            buttonLocation[1] - rootLocation[1], button.width, button.height)
        try {
            // This point is inside the solid fill, above the vertically centered label.
            val background = pixels.getPixel(button.width / 2, button.height / 4)
            assertEquals("Button fill must be opaque", 255, Color.alpha(background))
            assertTrue("Button label must have at least 4.5:1 contrast",
                ColorUtils.calculateContrast(button.currentTextColor, background) >= 4.5)
            val foreground = button.currentTextColor
            var glyphPixels = 0
            for (y in button.height / 4 until 3 * button.height / 4) {
                for (x in 0 until button.width) {
                    val pixel = pixels.getPixel(x, y)
                    if (Color.alpha(pixel) >= 250 &&
                        kotlin.math.abs(Color.red(pixel) - Color.red(foreground)) <= 12 &&
                        kotlin.math.abs(Color.green(pixel) - Color.green(foreground)) <= 12 &&
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(foreground)) <= 12) glyphPixels++
                }
            }
            assertTrue("Button must actually render visible text pixels", glyphPixels > 20)
        } finally { pixels.recycle() }
    }

    @Test
    @Config(sdk = [36])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun renderLightUpdater() = renderUpdater("android16-updater-light")

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h915dp-night-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun renderDarkUpdater() = renderUpdater("android16-updater-dark")

}
