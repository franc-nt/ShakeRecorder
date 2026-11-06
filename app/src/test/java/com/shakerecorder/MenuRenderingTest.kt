package com.shakerecorder

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Android canvas renders for visual inspection, not screenshots from a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuRenderingTest {
    private fun render(name: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val drawer = activity.findViewById<DrawerLayout>(R.id.drawerLayout)
            drawer.openDrawer(GravityCompat.END, false)
            activity.findViewById<View>(R.id.envioHeader).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val root = activity.window.decorView
            root.measure(
                View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val save = activity.findViewById<android.widget.TextView>(R.id.saveButton)
            org.junit.Assert.assertEquals("Salvar", save.text.toString())
            println("$name: save=${save.text}, color=${save.currentTextColor}, alpha=${save.alpha}, layout=${save.layout?.text}, bounds=${save.width}x${save.height}")
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val destination = File("build/reports/ui/$name.png")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun lightMenu() = render("android16-menu-light")

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun darkMenu() = render("android16-menu-dark")
}
