package com.shakerecorder

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Uses only Robolectric sandbox files and simulated recorder; no actual audio or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class UpdateDataSafetyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun settings() = SettingsManager(context).apply {
        webhookUrl = ""; isTelegramEnabled = false; telegramBotToken = ""; telegramChatId = ""
        isSoundEnabled = false; isServiceEnabled = true; shakeCount = 5
        isShakeEnabled = false; isVolumeHoldEnabled = false; isVolumeTriplePressEnabled = false
    }

    @Test fun checksFailuresAndCacheCleanupPreservePreferencesAndRecording() = runBlocking {
        settings()
        val prefs = context.getSharedPreferences("shake_recorder_prefs", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "ShakeRecorder/synthetic-update-safety.m4a")
        file.parentFile!!.mkdirs()
        val bytes = "synthetic test bytes; not audio".toByteArray()
        file.writeBytes(bytes)
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(404).message("fixture").body("".toResponseBody()).build()
            }.build()
            val updater = AppUpdater(context, client)
            assertNull(updater.check())
            try {
                updater.download(AvailableUpdate(UpdateManifest(1000, "10.0", 3, "0".repeat(64)),
                    "https://evil.test/app.apk")) {}
                fail("Untrusted download must be blocked")
            } catch (_: IllegalArgumentException) { }
            updater.apkFile.writeText("broken")
            File(updater.apkFile.parentFile, "update.json").writeText("invalid json")
            assertNull(updater.downloadedUpdate())
            updater.clearDownload()
            assertEquals(before, prefs.all)
            assertEquals(5, SettingsManager(context).shakeCount)
            assertTrue(SettingsManager(context).isServiceEnabled)
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun preparePausesIdleServiceWithoutDisablingSavedPreference() {
        settings().isVolumeTriplePressEnabled = true
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(service, RecorderService::class.java).setAction(RecorderService.ACTION_APPLY_SETTINGS), 0, 1)
            val detectorField = RecorderService::class.java.getDeclaredField("volumeButtonDetector").apply { isAccessible = true }
            assertTrue((detectorField.get(service) as VolumeButtonDetector).isActive())
            assertTrue(RecorderService.prepareForUpdate())
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(RecorderService.isRunning)
            assertFalse((detectorField.get(service) as? VolumeButtonDetector)?.isActive() ?: false)
            assertTrue(SettingsManager(context).isServiceEnabled)
            service.onStartCommand(Intent(service, RecorderService::class.java).setAction(RecorderService.ACTION_START_RECORDING), 0, 2)
            assertFalse(RecorderService.isRecording)
            RecorderService.resumeAfterUpdate()
            assertTrue((detectorField.get(service) as VolumeButtonDetector).isActive())
            service.onStartCommand(Intent(service, RecorderService::class.java).setAction(RecorderService.ACTION_START_RECORDING), 0, 3)
            assertTrue(RecorderService.isRecording)
        } finally { controller.destroy() }
        assertTrue(SettingsManager(context).isServiceEnabled)
    }

    @Test fun prepareNeverInterruptsRecording() {
        settings()
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(service, RecorderService::class.java).setAction(RecorderService.ACTION_START_RECORDING), 0, 1)
            assertTrue(RecorderService.isRecording)
            assertFalse(RecorderService.prepareForUpdate())
            assertTrue(RecorderService.isRecording)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(SettingsManager(context).isServiceEnabled)
        } finally { controller.destroy() }
    }
    @Test fun returningFromInstallerRespectsExplicitStop() {
        settings().isVolumeTriplePressEnabled = true
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(service, RecorderService::class.java), 0, 1)
            assertTrue(RecorderService.prepareForUpdate())
            service.onStartCommand(Intent(service, RecorderService::class.java).setAction(RecorderService.ACTION_STOP), 0, 2)
            RecorderService.resumeAfterUpdate()
            val detector = RecorderService::class.java.getDeclaredField("volumeButtonDetector")
                .apply { isAccessible = true }.get(service) as VolumeButtonDetector
            assertFalse(detector.isActive())
            assertFalse(SettingsManager(context).isServiceEnabled)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

}
