package com.shakerecorder

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Exercises command sequencing using Robolectric's MediaRecorder, not a real microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class RecorderServiceCompatibilityTest {
    private lateinit var controller: ServiceController<RecorderService>
    private lateinit var service: RecorderService

    @Before
    fun setUp() {
        SettingsManager(ApplicationProvider.getApplicationContext()).apply {
            // Never send test audio to any real destination.
            webhookUrl = ""
            isTelegramEnabled = false
            isSoundEnabled = false
        }
        controller = Robolectric.buildService(RecorderService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        RecorderService.onRecordingSaved = null
        controller.destroy()
    }

    private fun command(action: String) {
        service.onStartCommand(Intent(service, RecorderService::class.java).setAction(action), 0, 1)
    }

    @Test
    fun coldStartBecomesForegroundAndStartsWithoutTimer() {
        command(RecorderService.ACTION_START_RECORDING)
        assertTrue(RecorderService.isRunning)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(RecorderService.isRecording)
    }

    @Test
    fun duplicateStartAndStopCommandsDoNotToggleUnexpectedly() {
        var saved = 0
        RecorderService.onRecordingSaved = { saved++ }
        command(RecorderService.ACTION_START_RECORDING)
        command(RecorderService.ACTION_START_RECORDING)
        assertTrue(RecorderService.isRecording)
        assertEquals(0, saved)
        command(RecorderService.ACTION_STOP_RECORDING)
        command(RecorderService.ACTION_STOP_RECORDING)
        assertFalse(RecorderService.isRecording)
        assertEquals(1, saved)
    }
}
