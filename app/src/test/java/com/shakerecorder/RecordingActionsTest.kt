package com.shakerecorder

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/** Synthetic audio and a simulated MediaRecorder only; uploads are disabled before any service starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35, 36], qualifiers = "w412dp-h915dp-xxhdpi")
class RecordingActionsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private var recorder: ServiceController<RecorderService>? = null
    private val created = mutableListOf<File>()
    private val bytes = "synthetic UI fixture, not real recorded audio".toByteArray()

    @Before fun prepare() {
        RecordingProviderFixture.prepare(context)
        SettingsManager(context).apply {
            webhookUrl = ""
            isTelegramEnabled = false
            telegramBotToken = ""
            telegramChatId = ""
            isServiceEnabled = false
            isSoundEnabled = false
            isShakeEnabled = false
            isVolumeHoldEnabled = false
            isVolumeTriplePressEnabled = false
        }
    }

    @After fun cleanUp() {
        controller?.pause()?.stop()?.destroy()
        recorder?.destroy()
        created.asReversed().forEach { it.delete() }
        RecordingProviderFixture.clearCachedPaths(context)
    }

    private fun recording(name: String): File = File(RecordingIntents.recordingsDirectory(), name).also {
        it.parentFile!!.mkdirs()
        it.writeBytes(bytes)
        created += it
    }

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).also {
        controller = it
    }.setup().visible().get().also { settle(it) }

    private fun settle(activity: MainActivity) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Click the actual bound RecyclerView row, not a private Activity method. */
    private fun clickRecording(activity: MainActivity, file: File) {
        settle(activity)
        val list = activity.findViewById<RecyclerView>(R.id.recordingsList)
        val row = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull {
            it.findViewById<TextView>(R.id.recordingName)?.text?.toString() == file.name
        }
        assertNotNull("A displayed recording row must exist for ${file.name}", row)
        assertTrue(row!!.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun actions(): AlertDialog = (ShadowDialog.getLatestDialog() as AlertDialog).also {
        assertTrue("Recording actions must be visible", it.isShowing)
    }

    private fun choose(dialog: AlertDialog, position: Int) {
        val list = dialog.listView
        assertTrue(list.performItemClick(list.getChildAt(position), position, list.adapter.getItemId(position)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNoLaunch(activity: MainActivity) {
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(chooser: Intent): Uri {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        return send.getParcelableExtra(Intent.EXTRA_STREAM)!!
    }

    private fun textViews(view: View): List<TextView> {
        val own = if (view is TextView) listOf(view) else emptyList()
        return own + if (view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) } else emptyList()
    }

    @Test fun tappingRecordingShowsListenAndShareAndDoesNotLaunchImmediately() {
        val file = recording("synthetic-menu.m4a")
        val activity = launch()
        clickRecording(activity, file)
        val dialog = actions()
        assertEquals(2, dialog.listView.adapter.count)
        assertEquals(activity.getString(R.string.listen_recording), dialog.listView.adapter.getItem(0))
        assertEquals(activity.getString(R.string.share_recording), dialog.listView.adapter.getItem(1))
        assertTrue(textViews(dialog.window!!.decorView).any { it.text.toString() == file.name })
        assertEquals(activity.getString(R.string.cancel), dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
        assertNoLaunch(activity)
    }

    @Test fun cancelAndBackDismissActionsWithoutLaunchingAndAllowOpeningAgain() {
        val file = recording("synthetic-cancel.m4a")
        val activity = launch()
        clickRecording(activity, file)
        val first = actions()
        first.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(first.isShowing)
        assertNoLaunch(activity)
        clickRecording(activity, file)
        val second = actions()
        @Suppress("DEPRECATION")
        second.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(second.isShowing)
        assertNoLaunch(activity)
        clickRecording(activity, file)
        assertTrue(actions().isShowing)
    }

    @Test fun listeningStillOpensSelectedAudioWithReadOnlyContentUri() {
        val file = recording("synthetic-listen.m4a")
        val activity = launch()
        clickRecording(activity, file)
        choose(actions(), 0)
        val play = shadowOf(activity).nextStartedActivityForResult!!.intent
        assertEquals(Intent.ACTION_VIEW, play.action)
        assertEquals("audio/mp4", play.type)
        assertEquals("content", play.data!!.scheme)
        assertEquals(file.name, play.data!!.lastPathSegment)
        assertEquals(play.data, play.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, play.flags)
        assertNoLaunch(activity)
    }

    @Test fun sharingUsesAndroidChooserAndDoesNotClaimRecordingWasSent() {
        val file = recording("synthetic-share.m4a")
        val activity = launch()
        clickRecording(activity, file)
        choose(actions(), 1)
        val launch = shadowOf(activity).nextStartedActivityForResult!!
        assertEquals(file.name, sharedUri(launch.intent).lastPathSegment)
        assertTrue(launch.requestCode >= 0)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertNoLaunch(activity)
    }

    @Test fun repeatedRowClicksReplaceActionsAndShareOnlyLastChosenRecording() {
        val first = recording("synthetic-first.m4a")
        val second = recording("synthetic-second.m4a")
        val activity = launch()
        clickRecording(activity, first)
        val firstDialog = actions()
        clickRecording(activity, first)
        val repeated = actions()
        assertFalse(firstDialog.isShowing)
        assertNotSame(firstDialog, repeated)
        clickRecording(activity, second)
        val lastDialog = actions()
        assertFalse(repeated.isShowing)
        choose(lastDialog, 1)
        assertEquals(second.name, sharedUri(shadowOf(activity).nextStartedActivityForResult!!.intent).lastPathSegment)
        assertNoLaunch(activity)
        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes, second.readBytes())
    }

    @Test fun repeatedShareAndAnotherRowCannotLaunchTwiceWhileChooserIsOpen() {
        val first = recording("synthetic-double-tap.m4a")
        val second = recording("synthetic-blocked-row.m4a")
        val activity = launch()
        clickRecording(activity, first)
        val dialog = actions()
        choose(dialog, 1)
        assertEquals(first.name, sharedUri(shadowOf(activity).nextStartedActivityForResult!!.intent).lastPathSegment)
        // Exercise a stale queued click as well as a new row click.
        choose(dialog, 1)
        clickRecording(activity, second)
        assertFalse(dialog.isShowing)
        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertNoLaunch(activity)
    }

    @Test fun cancelledChooserAllowsSharingAnotherRecordingAndPreservesData() {
        val first = recording("synthetic-before-cancel.m4a")
        val second = recording("synthetic-after-cancel.m4a")
        val activity = launch()
        val preferences = context.getSharedPreferences("shake_recorder_prefs", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        clickRecording(activity, first)
        choose(actions(), 1)
        val launch = shadowOf(activity).nextStartedActivityForResult!!
        controller!!.pause()
        assertTrue(activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null))
        controller!!.resume()
        settle(activity)
        assertNoLaunch(activity)
        clickRecording(activity, second)
        choose(actions(), 1)
        assertEquals(second.name, sharedUri(shadowOf(activity).nextStartedActivityForResult!!.intent).lastPathSegment)
        assertEquals(before, preferences.all)
        assertArrayEquals(bytes, first.readBytes())
        assertArrayEquals(bytes, second.readBytes())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun recreationDoesNotResendAndRestoredChooserResultUnlocksSharing() {
        val file = recording("synthetic-recreate.m4a")
        val original = launch()
        clickRecording(original, file)
        choose(actions(), 1)
        val launch = shadowOf(original).nextStartedActivityForResult!!
        controller!!.recreate()
        val recreated = controller!!.get()
        settle(recreated)
        assertNoLaunch(recreated)
        clickRecording(recreated, file)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        assertNoLaunch(recreated)
        assertTrue(recreated.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null))
        clickRecording(recreated, file)
        choose(actions(), 1)
        assertEquals(file.name, sharedUri(shadowOf(recreated).nextStartedActivityForResult!!.intent).lastPathSegment)
        assertNoLaunch(recreated)
    }

    @Test fun recreationWithMenuOpenDismissesItWithoutPlaybackOrSharing() {
        val file = recording("synthetic-recreate-menu.m4a")
        val activity = launch()
        clickRecording(activity, file)
        val oldDialog = actions()
        controller!!.recreate()
        val recreated = controller!!.get()
        assertFalse(oldDialog.isShowing)
        assertNoLaunch(recreated)
        clickRecording(recreated, file)
        assertTrue(actions().isShowing)
    }

    @Test fun deletingFileAfterOpeningMenuShowsErrorAndRemovesStaleRow() {
        val file = recording("synthetic-deleted.m4a")
        val remaining = recording("synthetic-remaining.m4a")
        val activity = launch()
        clickRecording(activity, file)
        val dialog = actions()
        assertTrue(file.delete())
        choose(dialog, 1)
        assertEquals(activity.getString(R.string.recording_unavailable), ShadowToast.getTextOfLatestToast())
        assertNoLaunch(activity)
        assertFalse(activity.isFinishing)
        assertEquals(1, activity.findViewById<RecyclerView>(R.id.recordingsList).adapter!!.itemCount)
        clickRecording(activity, remaining)
        choose(actions(), 1)
        assertEquals(remaining.name, sharedUri(shadowOf(activity).nextStartedActivityForResult!!.intent).lastPathSegment)
    }

    @Test fun sharingActiveRecordingIsBlockedButPreviousAudioSharesWithoutStoppingRecorder() {
        val previous = recording("synthetic-previous.m4a")
        recorder = Robolectric.buildService(RecorderService::class.java).create()
        val service = recorder!!.get()
        service.onStartCommand(Intent(service, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START_RECORDING), 0, 1)
        assertTrue(RecorderService.isRecording)
        val active = File(RecorderService.activeRecordingPath!!).also {
            // Robolectric's MediaRecorder does not capture bytes. Supply a synthetic fixture.
            it.writeBytes(bytes)
            created += it
        }
        val activity = launch()
        val prefs = context.getSharedPreferences("shake_recorder_prefs", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        clickRecording(activity, active)
        choose(actions(), 1)
        assertNoLaunch(activity)
        assertEquals(activity.getString(R.string.finish_recording_first), ShadowToast.getTextOfLatestToast())
        assertTrue(RecorderService.isRecording)
        clickRecording(activity, active)
        choose(actions(), 0)
        assertNoLaunch(activity)
        assertEquals(activity.getString(R.string.finish_recording_first), ShadowToast.getTextOfLatestToast())
        clickRecording(activity, previous)
        choose(actions(), 1)
        assertEquals(previous.name, sharedUri(shadowOf(activity).nextStartedActivityForResult!!.intent).lastPathSegment)
        assertTrue(RecorderService.isRecording)
        assertTrue(RecorderService.isRunning)
        assertEquals(active.absolutePath, RecorderService.activeRecordingPath)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(activity.application).nextStartedService)
        assertEquals(before, prefs.all)
        assertArrayEquals(bytes, previous.readBytes())
        assertArrayEquals(bytes, active.readBytes())
    }

    /** Native Android canvas render, not an emulator or physical Samsung screenshot. */
    private fun renderActions(name: String) {
        val file = recording("recording_20261106_120000.m4a")
        val activity = launch()
        clickRecording(activity, file)
        val dialog = actions()
        val root = dialog.window!!.decorView
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val labels = textViews(root).filter { it.text.toString() in setOf("Ouvir", "Compartilhar", "Cancelar") }
            assertEquals(3, labels.size)
            val rootLocation = IntArray(2).also { root.getLocationOnScreen(it) }
            for (label in labels) {
                assertTrue(label.isShown)
                assertTrue(label.width > 0 && label.height > 0)
                assertEquals(1f, label.alpha, 0f)
                assertNotNull(label.layout)
                val location = IntArray(2).also { label.getLocationOnScreen(it) }
                val x = location[0] - rootLocation[0]
                val y = location[1] - rootLocation[1]
                val background = bitmap.getPixel(x + label.width / 2, y + 2)
                assertEquals(255, Color.alpha(background))
                // Material's light-theme text uses alpha 222. Check the actual
                // composed foreground, not an artificial requirement of alpha 255.
                val foreground = ColorUtils.compositeColors(label.currentTextColor, background)
                assertTrue("${label.text} needs readable contrast",
                    ColorUtils.calculateContrast(foreground, background) >= 4.5)
                var glyphPixels = 0
                for (py in y until y + label.height) for (px in x until x + label.width) {
                    val pixel = bitmap.getPixel(px, py)
                    if (Color.alpha(pixel) >= 250 &&
                        kotlin.math.abs(Color.red(pixel) - Color.red(foreground)) <= 12 &&
                        kotlin.math.abs(Color.green(pixel) - Color.green(foreground)) <= 12 &&
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(foreground)) <= 12) glyphPixels++
                }
                assertTrue("${label.text} must render visible glyphs", glyphPixels > 20)
            }
            val destination = File("build/reports/ui/$name.png")
            destination.parentFile!!.mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    @Config(sdk = [36])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderLightRecordingActions() = renderActions("android16-recording-actions-light")

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h915dp-night-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderDarkRecordingActions() = renderActions("android16-recording-actions-dark")
}
