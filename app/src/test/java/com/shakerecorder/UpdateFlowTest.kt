package com.shakerecorder

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.LooperMode
import java.io.File

/** UI-only fake bypasses APK verification; real validation is covered by AppUpdaterTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateFlowTest {
    companion object { private lateinit var fake: FakeUpdater }
    class TestActivity : UpdateActivity() {
        internal override fun createUpdater(): AppUpdater = fake
    }
    private class FakeUpdater(context: Context) : AppUpdater(context) {
        val manifest = UpdateManifest(1000, "10.0", 3, "0".repeat(64))
        val update = AvailableUpdate(manifest,
            "https://github.com/${AppUpdater.REPOSITORY}/releases/download/v10.0/${AppUpdater.APK_NAME}")
        var cached: UpdateManifest? = null
        var checkGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var checks = 0
        var downloads = 0
        var verifications = 0
        var downloadCanceled = false
        override suspend fun check(): AvailableUpdate? { checks++; checkGate?.await(); return update }
        override suspend fun downloadedUpdate(): UpdateManifest? = cached
        override suspend fun verifyDownloaded(manifest: UpdateManifest) { verifications++ }
        override suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File {
            downloads++
            try { downloadGate?.await() }
            catch (cancel: CancellationException) { downloadCanceled = true; throw cancel }
            apkFile.writeText("fake APK; never passed to a real installer")
            cached = manifest
            return apkFile
        }
    }
    private lateinit var application: Application
    private var controller: ActivityController<TestActivity>? = null
    private var serviceController: ServiceController<RecorderService>? = null
    private val activity get() = controller!!.get()
    private val button get() = activity.findViewById<Button>(R.id.updateButton)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click() { button.performClick(); idle() }
    private fun launch(cached: Boolean = false) {
        if (cached) { fake.cached = fake.manifest; fake.apkFile.writeText("fake APK") }
        controller = Robolectric.buildActivity(TestActivity::class.java).setup().visible()
        idle()
    }
    private fun dialog() = UpdateActivity::class.java.getDeclaredField("permissionDialog")
        .apply { isAccessible = true }.get(activity) as AlertDialog
    private fun startService(recording: Boolean = false) {
        serviceController = Robolectric.buildService(RecorderService::class.java).create()
        val service = serviceController!!.get()
        service.onStartCommand(Intent(service, RecorderService::class.java).setAction(
            if (recording) RecorderService.ACTION_START_RECORDING else RecorderService.ACTION_APPLY_SETTINGS), 0, 1)
        idle()
    }
    private fun detectorActive(): Boolean = (RecorderService::class.java.getDeclaredField("volumeButtonDetector")
        .apply { isAccessible = true }.get(serviceController!!.get()) as? VolumeButtonDetector)?.isActive() ?: false

    @Before fun setup() {
        application = ApplicationProvider.getApplicationContext()
        fake = FakeUpdater(application)
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        SettingsManager(application).apply {
            webhookUrl = ""; isTelegramEnabled = false; telegramBotToken = ""; telegramChatId = ""
            isSoundEnabled = false; isServiceEnabled = true; isShakeEnabled = false
            isVolumeHoldEnabled = false; isVolumeTriplePressEnabled = true
        }
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        serviceController?.destroy()
        idle()
        fake.clearDownload()
    }

    @Test fun repeatedCheckClicksStartOnlyOneOperation() {
        fake.checkGate = CompletableDeferred()
        launch()
        click(); click(); click()
        assertEquals(1, fake.checks)
        assertFalse(button.isEnabled)
        fake.checkGate!!.complete(Unit)
        idle()
        assertTrue(button.isEnabled)
        assertEquals("Baixar e atualizar", button.text.toString())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun decliningPermissionDialogNeverOpensSettingsOrInstaller() {
        launch(cached = true)
        click()
        assertTrue(dialog().isShowing)
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog().isShowing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(fake.apkFile.exists())
        assertTrue(button.isEnabled)
    }

    @Test fun permissionDeniedReturnDoesNotInstall() {
        launch(cached = true)
        click()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.intent.action)
        assertEquals("package:com.shakerecorder", request.intent.data.toString())
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        idle()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(activity.findViewById<TextView>(R.id.updateStatus).text.contains("Permissão não concedida"))
        assertTrue(fake.apkFile.exists())
    }

    @Test fun activeRecordingBlocksInstallerAndVerification() {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
        startService(recording = true)
        launch(cached = true)
        click()
        assertTrue(RecorderService.isRecording)
        assertEquals(0, fake.verifications)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun installerCancellationRestoresMonitoringAndKeepsDownload() {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
        startService()
        assertTrue(detectorActive())
        launch(cached = true)
        click()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_VIEW, request.intent.action)
        assertEquals("application/vnd.android.package-archive", request.intent.type)
        assertEquals("content", request.intent.data!!.scheme)
        assertTrue(request.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(detectorActive())
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        idle()
        assertTrue(detectorActive())
        assertTrue(SettingsManager(application).isServiceEnabled)
        assertTrue(fake.apkFile.exists())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun rotationCancelsPendingDownloadWithoutLaunchingInstaller() {
        fake.downloadGate = CompletableDeferred()
        launch(); click(); click()
        assertEquals(1, fake.downloads)
        controller!!.recreate()
        idle()
        assertTrue(fake.downloadCanceled)
        assertEquals(1, fake.downloads)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(button.isEnabled)
    }

    @Test fun backAndDestructionCancelPendingDownload() {
        fake.downloadGate = CompletableDeferred()
        launch(); click(); click()
        val oldActivity = activity
        oldActivity.findViewById<Button>(R.id.backButton).performClick()
        assertTrue(oldActivity.isFinishing)
        controller!!.pause().stop().destroy()
        controller = null
        idle()
        assertTrue(fake.downloadCanceled)
        assertNull(shadowOf(oldActivity).nextStartedActivityForResult)
    }
}
