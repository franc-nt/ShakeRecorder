package com.shakerecorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.CompoundButton
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
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Integration regression tests. MediaRecorder is simulated, never a real microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveTriggerSettingsTest {
    private lateinit var application: Application
    private lateinit var settings: SettingsManager
    private var serviceController: ServiceController<RecorderService>? = null
    private var activityController: ActivityController<MainActivity>? = null
    private val service: RecorderService get() = serviceController!!.get()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("shake_recorder_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = SettingsManager(application).apply {
            webhookUrl = ""
            isTelegramEnabled = false
            telegramBotToken = ""
            telegramChatId = ""
            isSoundEnabled = false
            isShakeEnabled = false
            isVolumeHoldEnabled = false
            isVolumeTriplePressEnabled = false
            isServiceEnabled = false
        }
        RecorderService.setForegroundVolumeKeys(false)
        shadowOf(application).grantPermissions(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
        )
        idle()
    }

    @After
    fun tearDown() {
        activityController?.pause()?.stop()?.destroy()
        RecorderService.onRecordingSaved = null
        RecorderService.onLogMessage = null
        RecorderService.onStatusChanged = null
        serviceController?.destroy()
        RecorderService.setForegroundVolumeKeys(false)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun startService(action: String = RecorderService.ACTION_APPLY_SETTINGS) {
        serviceController = Robolectric.buildService(RecorderService::class.java).create()
        command(action)
    }

    private fun command(action: String) {
        service.onStartCommand(Intent(application, RecorderService::class.java).setAction(action), 0, 1)
        idle()
    }

    private fun launchActivity(): MainActivity {
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        return activityController!!.get()
    }

    private fun field(target: Any, name: String): Any? = target.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun detector(): VolumeButtonDetector? =
        field(service, "volumeButtonDetector") as VolumeButtonDetector?

    private fun tap(dispatch: (KeyEvent) -> Boolean = RecorderService::dispatchVolumeKeyEvent) {
        val down = SystemClock.uptimeMillis()
        assertTrue(dispatch(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertTrue(dispatch(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_VOLUME_UP, 0)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180))
    }

    @Test
    fun enablingVolumeWhileServiceAliveCreatesDetectorWithoutRestartOrRecording() {
        startService()
        assertNull(detector())
        val originalService = service
        settings.isVolumeTriplePressEnabled = true
        idle()
        assertSame(originalService, service)
        assertTrue(RecorderService.isRunning)
        assertTrue(detector()!!.isActive())
        assertFalse(RecorderService.isRecording)
        tap()
        tap()
        assertFalse(RecorderService.isRecording)
        tap()
        assertTrue(RecorderService.isRecording)
    }

    @Test
    fun changingPressCountAppliesLiveAndDiscardsPartialOldGesture() {
        settings.isVolumeTriplePressEnabled = true
        startService()
        val oldDetector = detector()!!
        tap()
        settings.volumePressCount = 2
        idle()
        assertNotSame(oldDetector, detector())
        assertFalse(oldDetector.isActive())
        tap()
        assertFalse(RecorderService.isRecording)
        tap()
        assertTrue(RecorderService.isRecording)
    }

    @Test
    fun disablingVolumeDuringRecordingDoesNotStopOrSaveRecording() {
        settings.isVolumeTriplePressEnabled = true
        startService(RecorderService.ACTION_START_RECORDING)
        var saved = 0
        RecorderService.onRecordingSaved = { saved++ }
        val oldDetector = detector()!!
        settings.isVolumeTriplePressEnabled = false
        idle()
        assertNull(detector())
        assertFalse(oldDetector.isActive())
        assertTrue(RecorderService.isRunning)
        assertTrue(RecorderService.isRecording)
        assertEquals(0, saved)
        assertFalse(RecorderService.dispatchVolumeKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        command(RecorderService.ACTION_STOP_RECORDING)
        assertEquals(1, saved)
    }

    @Test
    fun unchangedApplyAndRepeatedStartKeepSameDetectorsAndRecording() {
        settings.isVolumeTriplePressEnabled = true
        startService(RecorderService.ACTION_START_RECORDING)
        val volume = detector()
        val shake = field(service, "shakeDetector")
        repeat(3) {
            command(RecorderService.ACTION_APPLY_SETTINGS)
            service.onStartCommand(Intent(application, RecorderService::class.java), 0, it + 2)
        }
        idle()
        assertSame(volume, detector())
        assertSame(shake, field(service, "shakeDetector"))
        assertTrue(RecorderService.isRecording)
    }

    @Test
    fun shakeCountChangesReplaceDetectorWithNewCountWhileServiceRuns() {
        settings.isShakeEnabled = true
        startService()
        val oldShake = field(service, "shakeDetector")!!
        settings.shakeCount = 5
        idle()
        val newShake = field(service, "shakeDetector")!!
        assertNotSame(oldShake, newShake)
        assertEquals(5, field(newShake, "requiredShakes"))
        assertTrue(RecorderService.isRunning)
        assertFalse(RecorderService.isRecording)
    }

    @Test
    fun activityRoutesThreeRealPressesEvenWhenHoldIsAlsoEnabled() {
        settings.isVolumeTriplePressEnabled = true
        settings.isVolumeHoldEnabled = true
        startService()
        val activity = launchActivity()
        activity.onWindowFocusChanged(true)
        tap(activity::dispatchKeyEvent)
        tap(activity::dispatchKeyEvent)
        assertFalse(RecorderService.isRecording)
        tap(activity::dispatchKeyEvent)
        assertTrue(RecorderService.isRecording)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue("Released taps must not produce a delayed hold toggle", RecorderService.isRecording)
    }

    @Test
    fun enablingTriggerInActivityArmsServiceWithoutStartingRecording() {
        val activity = launchActivity()
        // Robolectric records service-start intents; it does not launch services for us.
        assertNull(shadowOf(application).nextStartedService)
        activity.findViewById<CompoundButton>(R.id.volumeTripleSwitch).isChecked = true
        idle()
        val requested = shadowOf(application).nextStartedService
        assertNotNull(requested)
        assertEquals(RecorderService.ACTION_APPLY_SETTINGS, requested.action)
        assertTrue(settings.isServiceEnabled)
        assertFalse(RecorderService.isRecording)
        startService(requested.action!!)
        assertTrue(RecorderService.isRunning)
        assertTrue(detector()!!.isActive())
        assertFalse(RecorderService.isRecording)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun monitoringRenewsWakeLockPastOriginalLeaseAndReleasesOnDestroy() {
        startService()
        val wakeLock = field(service, "wakeLock") as android.os.PowerManager.WakeLock
        assertTrue(wakeLock.isHeld)
        // Simulated Handler time exercises renewal scheduling. This does not prove
        // physical-device sleep, Doze, or Samsung power-management behavior.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11))
        assertTrue("Monitoring must outlive the original ten-minute lease", wakeLock.isHeld)
        serviceController!!.destroy()
        serviceController = null
        assertFalse(wakeLock.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertFalse("A destroyed service must not reacquire its wake lock", wakeLock.isHeld)
        assertFalse(RecorderService.isRunning)
    }

    @Test
    fun stopActionPersistsMonitoringDisabledForNextActivityLaunch() {
        settings.isServiceEnabled = true
        startService()
        val result = service.onStartCommand(
            Intent(application, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
            0, 2
        )
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(SettingsManager(application).isServiceEnabled)
        // stopSelf is recorded by Robolectric; explicitly complete service teardown.
        serviceController!!.destroy()
        serviceController = null
        idle()
        launchActivity()
        assertNull(shadowOf(application).nextStartedService)
        assertFalse(RecorderService.isRunning)
    }

    @Test
    fun queuedPreferenceCallbacksCannotReenableVolumeAfterItWasTurnedOff() {
        settings.isVolumeTriplePressEnabled = true
        startService()
        val oldDetector = detector()!!
        // Queue several preference callbacks before permitting the main looper to run.
        settings.volumePressCount = 4
        settings.isVolumeHoldEnabled = true
        settings.isVolumeTriplePressEnabled = false
        settings.isVolumeHoldEnabled = false
        idle()
        assertNull(detector())
        assertFalse(oldDetector.isActive())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        command(RecorderService.ACTION_APPLY_SETTINGS)
        assertNull(detector())
        assertTrue(RecorderService.isRunning)
        assertFalse(RecorderService.isRecording)
    }

}
