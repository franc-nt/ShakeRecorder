package com.shakerecorder

import android.Manifest
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], qualifiers = "w412dp-h915dp-xxhdpi")
class MainActivityCompatibilityTest {
    private var controller: ActivityController<MainActivity>? = null

    private fun launch(fontScale: Float = 1f): MainActivity {
        RuntimeEnvironment.setFontScale(fontScale)
        return Robolectric.buildActivity(MainActivity::class.java).also {
            controller = it
        }.setup().visible().get()
    }

    private fun settleDrawer(drawer: DrawerLayout) {
        // Robolectric does not continuously draw frames like a real display.
        repeat(80) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            drawer.computeScroll()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    @Test
    fun systemBarsAndCutoutAreAppliedToBothContentRoots() {
        val activity = launch()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 90, 0, 72))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12, 90, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.drawerLayout), insets)
        for (id in listOf(R.id.mainContent, R.id.drawerContent)) {
            val view = activity.findViewById<View>(id)
            assertEquals(90, view.paddingTop)
            assertEquals(72, view.paddingBottom)
            assertEquals(12, view.paddingLeft)
        }
        // Repeat dispatch: padding must not accumulate.
        ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.drawerLayout), insets)
        assertEquals(90, activity.findViewById<View>(R.id.drawerContent).paddingTop)
    }

    @Test
    fun keyboardUsesMaximumInsetRatherThanAddingNavigationBar() {
        val activity = launch()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 90, 0, 72))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.drawerLayout), insets)
        assertEquals(900, activity.findViewById<View>(R.id.drawerContent).paddingBottom)
    }

    @Test
    fun saveButtonRemainsAboveKeyboardInPortrait() {
        val activity = launch()
        val drawer = activity.findViewById<DrawerLayout>(R.id.drawerLayout)
        drawer.openDrawer(GravityCompat.END, false)
        activity.findViewById<View>(R.id.envioHeader).performClick()
        ViewCompat.dispatchApplyWindowInsets(drawer, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 90, 0, 72))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
            .build())
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val visible = Rect()
        assertTrue(activity.findViewById<View>(R.id.saveButton).getGlobalVisibleRect(visible))
        assertTrue(visible.bottom <= root.height - 900)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xxhdpi")
    fun largerFontsKeepStatusAndRecordButtonSeparate() {
        val activity = launch(fontScale = 1.5f)
        val status = activity.findViewById<View>(R.id.statusText)
        val button = activity.findViewById<View>(R.id.mainButton)
        assertTrue(status.bottom <= button.top)
        assertTrue(button.height > 0)
    }

    @Test
    fun menuSectionsAndBackRemainUsable() {
        val activity = launch()
        val drawer = activity.findViewById<DrawerLayout>(R.id.drawerLayout)
        activity.findViewById<View>(R.id.menuButton).performClick()
        settleDrawer(drawer)
        assertTrue(drawer.isDrawerOpen(GravityCompat.END))
        for ((header, content) in listOf(
            R.id.envioHeader to R.id.envioContent,
            R.id.gatilhosHeader to R.id.gatilhosContent,
            R.id.opcoesHeader to R.id.opcoesContent,
            R.id.logsHeader to R.id.logsContent
        )) {
            activity.findViewById<View>(header).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(content).visibility)
            activity.findViewById<View>(header).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(content).visibility)
        }
        activity.onBackPressedDispatcher.onBackPressed()
        settleDrawer(drawer)
        assertFalse(drawer.isDrawerOpen(GravityCompat.END))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun backClosesDrawerRestoredAfterRecreation() {
        val activity = launch()
        activity.findViewById<DrawerLayout>(R.id.drawerLayout).openDrawer(GravityCompat.END, false)
        controller!!.recreate()
        val recreated = controller!!.get()
        val drawer = recreated.findViewById<DrawerLayout>(R.id.drawerLayout)
        assertTrue(drawer.isDrawerOpen(GravityCompat.END))
        recreated.onBackPressedDispatcher.onBackPressed()
        settleDrawer(drawer)
        assertFalse(drawer.isDrawerOpen(GravityCompat.END))
        assertFalse(recreated.isFinishing)
    }

    @Test
    fun microphonePermissionAloneCanStartRecording() {
        val activity = launch()
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.findViewById<View>(R.id.mainButton).performClick()
        val permissionResult = activity.javaClass.getDeclaredMethod("hasRequiredPermissions").apply {
            isAccessible = true
        }.invoke(activity) as Boolean
        assertTrue(permissionResult)
        // Simulate the runtime result: microphone allowed, notifications refused.
        activity.onRequestPermissionsResult(
            shadowOf(activity).lastRequestedPermission.requestCode,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)
        )
        val started = shadowOf(activity.application).nextStartedService
        assertNotNull(started)
        assertEquals(RecorderService.ACTION_START_RECORDING, started.action)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun darkModeHasReadableMenuTextAndNoExtraActionBar() {
        val activity = launch()
        assertNull(activity.supportActionBar)
        val drawer = activity.findViewById<View>(R.id.drawerContent)
        val background = (drawer.background as ColorDrawable).color
        assertNotEquals(Color.WHITE, background)
        val label = activity.findViewById<TextView>(R.id.versionText)
        assertTrue(ColorUtils.calculateContrast(label.currentTextColor, background) >= 4.5)
        val save = activity.findViewById<TextView>(R.id.saveButton)
        val primary = com.google.android.material.color.MaterialColors.getColor(save, com.google.android.material.R.attr.colorPrimary)
        assertTrue(ColorUtils.calculateContrast(save.currentTextColor, primary) >= 4.5)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xxhdpi")
    fun landscapeKeepsControlsInsideScrollableContent() {
        val activity = launch()
        val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.mainContent)
        val button = activity.findViewById<View>(R.id.mainButton)
        assertTrue(button.height > 0)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertTrue(activity.findViewById<View>(R.id.menuButton).isShown)
    }
}
