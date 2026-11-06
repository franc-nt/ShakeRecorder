package com.shakerecorder

import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class VolumeButtonDetectorTest {
    private lateinit var detector: VolumeButtonDetector
    private var holds = true
    private var taps = true
    private var count = 3
    private var duration = 3000L
    private var triggered = 0

    @Before fun setUp() {
        detector = VolumeButtonDetector(ApplicationProvider.getApplicationContext(),
            { triggered++ }, { holds }, { taps }, { duration }, { count })
        detector.start()
        detector.setForegroundKeys(true)
    }

    @After fun tearDown() { detector.stop() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(action: Int, repeats: Int = 0, flags: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return detector.onKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_VOLUME_UP,
            repeats, 0, 0, 0, flags))
    }
    private fun tap() { key(KeyEvent.ACTION_DOWN); advance(20); key(KeyEvent.ACTION_UP); advance(80) }

    @Test fun tripleTapWorksEvenWhenHoldIsEnabled() {
        repeat(3) { tap() }
        assertEquals(1, triggered)
        advance(3000)
        assertEquals(1, triggered)
        repeat(3) { tap() }
        assertEquals(2, triggered)
    }

    @Test fun singleTapNeverBecomesDelayedHold() {
        tap()
        advance(4000)
        assertEquals(0, triggered)
    }

    @Test fun realHoldFiresOnceAndRepeatsAreNotTaps() {
        key(KeyEvent.ACTION_DOWN)
        repeat(35) { advance(100); key(KeyEvent.ACTION_DOWN, repeats = it + 1) }
        assertEquals(1, triggered)
        advance(3000)
        assertEquals(1, triggered)
        key(KeyEvent.ACTION_UP)
        repeat(2) { tap() }
        assertEquals(1, triggered)
        tap()
        assertEquals(2, triggered)
    }

    @Test fun canceledKeyDoesNotCountOrKeepHoldPending() {
        repeat(2) { tap() }
        key(KeyEvent.ACTION_DOWN)
        key(KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED)
        advance(4000)
        tap()
        assertEquals(0, triggered)
    }

    @Test fun everyConfiguredTapCountIsRespected() {
        holds = false
        for (required in 2..5) {
            count = required
            repeat(required - 1) { tap() }
            assertEquals(required - 2, triggered)
            tap()
            assertEquals(required - 1, triggered)
            advance(2100)
        }
    }

    @Test fun slowTapsExpireAndOtherKeysAreUntouched() {
        repeat(2) { tap() }
        advance(5100)
        tap()
        assertEquals(0, triggered)
        assertFalse(detector.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
    }

    @Test fun foregroundIgnoresDuplicateRemoteEvents() {
        repeat(3) {
            tap()
            detector.onRemoteVolume(AudioManager.ADJUST_RAISE)
            advance(200)
        }
        assertEquals(1, triggered)
    }

    @Test fun leavingForegroundCancelsHeldKeyAndPartialSequence() {
        tap(); tap(); key(KeyEvent.ACTION_DOWN)
        detector.setForegroundKeys(false)
        advance(4000)
        assertEquals(0, triggered)
        detector.onRemoteVolume(AudioManager.ADJUST_RAISE)
        assertEquals(0, triggered)
    }

    @Test fun remoteTriplePulsesWorkWithBothTriggersEnabled() {
        detector.setForegroundKeys(false)
        repeat(3) { detector.onRemoteVolume(AudioManager.ADJUST_RAISE); advance(250) }
        assertEquals(1, triggered)
        advance(4000)
        assertEquals(1, triggered)
    }

    @Test fun remoteSinglePulseCannotBecomeHoldAndSameIsNotUp() {
        detector.setForegroundKeys(false)
        detector.onRemoteVolume(AudioManager.ADJUST_RAISE)
        detector.onRemoteVolume(AudioManager.ADJUST_SAME)
        advance(4000)
        assertEquals(0, triggered)
    }

    @Test fun remoteSustainedPulseStreamCanHoldWithoutCountingRepeats() {
        detector.setForegroundKeys(false)
        detector.onRemoteVolume(AudioManager.ADJUST_RAISE)
        advance(500)
        repeat(70) { detector.onRemoteVolume(AudioManager.ADJUST_RAISE); advance(50) }
        assertEquals(1, triggered)
    }

    @Test fun stopDropsQueuedTimersAndStartIsIdempotent() {
        detector.start()
        key(KeyEvent.ACTION_DOWN)
        detector.stop()
        advance(4000)
        assertEquals(0, triggered)
        assertFalse(detector.isActive())
        assertFalse(key(KeyEvent.ACTION_DOWN))
    }

    @Test fun disabledTriggersDoNotConsumeKeys() {
        holds = false; taps = false
        assertFalse(key(KeyEvent.ACTION_DOWN))
    }
}
