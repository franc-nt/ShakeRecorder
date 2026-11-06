package com.shakerecorder

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.VolumeProviderCompat

/** Real key events in the app; best-effort MediaSession volume pulses outside it. */
class VolumeButtonDetector(
    private val context: Context,
    private val onTrigger: () -> Unit,
    private val isHoldEnabled: () -> Boolean,
    private val isTriplePressEnabled: () -> Boolean,
    private val getHoldDurationMs: () -> Long = { 3000L },
    private val getRequiredPresses: () -> Int = { 3 },
    private val triplePressWindowMs: Long = 5000L
) {
    companion object {
        private const val TRIGGER_DEBOUNCE_MS = 2000L
        // VolumeProvider has no DOWN/UP or repeatCount. Keep its pulse fallback
        // separate from precise foreground keys; never interpret ADJUST_SAME as UP.
        private const val REMOTE_REPEAT_INTERVAL_MS = 150L
        private const val REMOTE_RELEASE_GAP_MS = 750L
        private const val REMOTE_HOLD_FRESHNESS_MS = 300L
    }

    private var mediaSession: MediaSessionCompat? = null
    private val handler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var foregroundKeys = false
    private var keyDown = false
    private var holdTriggered = false
    private var pressCount = 0
    private var lastPressTime: Long? = null
    private var lastTriggerTime: Long? = null
    private var lastRemotePulse: Long? = null
    private var remotePulseCount = 0

    fun start() {
        if (mediaSession != null) return
        val provider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                handler.post { onRemoteVolume(direction) }
            }
        }
        mediaSession = MediaSessionCompat(context, "ShakeRecorderVolume").apply {
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build())
            setPlaybackToRemote(provider)
            isActive = true
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        mediaSession?.apply { isActive = false; release() }
        mediaSession = null
        resetGesture()
    }

    fun setForegroundKeys(enabled: Boolean) {
        if (foregroundKeys != enabled) {
            foregroundKeys = enabled
            resetGesture()
        }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isActive() || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP ||
            (!isHoldEnabled() && !isTriplePressEnabled())) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!keyDown && event.repeatCount == 0) {
                    keyDown = true
                    holdTriggered = false
                    if (isHoldEnabled()) scheduleHold { keyDown && isHoldEnabled() }
                }
            }
            KeyEvent.ACTION_UP -> {
                val countTap = keyDown && !holdTriggered && !event.isCanceled
                cancelHold()
                keyDown = false
                holdTriggered = false
                if (event.isCanceled) resetGesture()
                else if (countTap && isTriplePressEnabled()) countPress()
            }
            else -> return false
        }
        return true
    }

    internal fun onRemoteVolume(direction: Int) {
        if (!isActive() || foregroundKeys) return
        if (direction == AudioManager.ADJUST_LOWER) {
            resetGesture()
            return
        }
        if (direction != AudioManager.ADJUST_RAISE) return
        val now = SystemClock.elapsedRealtime()
        val gap = lastRemotePulse?.let { now - it }
        if (gap == null || gap > REMOTE_RELEASE_GAP_MS) {
            cancelHold()
            holdTriggered = false
            remotePulseCount = 0
            if (isHoldEnabled()) scheduleHold {
                // A solitary tap must never turn into a delayed "hold".
                remotePulseCount > 1 && lastRemotePulse?.let {
                    SystemClock.elapsedRealtime() - it <= REMOTE_HOLD_FRESHNESS_MS
                } == true && isHoldEnabled()
            }
        }
        remotePulseCount++
        lastRemotePulse = now
        if (!holdTriggered && isTriplePressEnabled() &&
            (gap == null || gap >= REMOTE_REPEAT_INTERVAL_MS)) countPress()
    }

    private fun scheduleHold(stillHeld: () -> Boolean) {
        cancelHold()
        holdRunnable = Runnable {
            holdRunnable = null
            if (stillHeld() && !holdTriggered) {
                holdTriggered = true
                pressCount = 0
                triggerWithDebounce()
            }
        }.also { handler.postDelayed(it, getHoldDurationMs()) }
    }

    private fun countPress() {
        val now = SystemClock.elapsedRealtime()
        if (lastPressTime?.let { now - it > triplePressWindowMs } != false) pressCount = 0
        lastPressTime = now
        pressCount++
        if (pressCount >= getRequiredPresses().coerceAtLeast(2)) {
            pressCount = 0
            cancelHold()
            triggerWithDebounce()
        }
    }

    private fun triggerWithDebounce() {
        val now = SystemClock.elapsedRealtime()
        if (lastTriggerTime?.let { now - it < TRIGGER_DEBOUNCE_MS } == true) return
        lastTriggerTime = now
        onTrigger()
    }

    private fun cancelHold() {
        holdRunnable?.let(handler::removeCallbacks)
        holdRunnable = null
    }

    private fun resetGesture() {
        cancelHold()
        keyDown = false
        holdTriggered = false
        pressCount = 0
        lastPressTime = null
        lastRemotePulse = null
        remotePulseCount = 0
    }

    fun isActive(): Boolean = mediaSession?.isActive == true
}
