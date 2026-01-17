package com.shakerecorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat

class VolumeButtonDetector(
    private val context: Context,
    private val onTrigger: () -> Unit,
    private val isHoldEnabled: () -> Boolean,
    private val isTriplePressEnabled: () -> Boolean
) {
    companion object {
        private const val HOLD_DURATION_MS = 3000L      // 3 segundos
        private const val TRIPLE_PRESS_WINDOW_MS = 800L // 800ms para 3 cliques
        private const val REQUIRED_PRESSES = 3
        private const val TRIGGER_DEBOUNCE_MS = 2000L   // 2 segundos entre triggers
    }

    private var mediaSession: MediaSessionCompat? = null
    private val handler = Handler(Looper.getMainLooper())

    // Hold detection
    private var holdRunnable: Runnable? = null
    private var isHolding = false
    private var holdTriggered = false  // Prevents multiple triggers while holding

    // Triple press detection
    private var pressCount = 0
    private var lastPressTime = 0L

    // Debounce para evitar triggers múltiplos
    private var lastTriggerTime = 0L

    fun start() {
        if (mediaSession != null) return

        val volumeProvider = object : VolumeProviderCompat(
            VOLUME_CONTROL_RELATIVE, 100, 50
        ) {
            override fun onAdjustVolume(direction: Int) {
                handleVolumeEvent(direction)
            }
        }

        mediaSession = MediaSessionCompat(context, "ShakeRecorderVolume").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        resetState()
    }

    private fun handleVolumeEvent(direction: Int) {
        if (direction == 1) {  // Volume UP
            if (isTriplePressEnabled()) handleTriplePress()
            if (isHoldEnabled()) startHoldDetection()
        } else {  // Volume DOWN ou release
            cancelHoldDetection()
        }
    }

    private fun handleTriplePress() {
        val now = System.currentTimeMillis()

        if (now - lastPressTime > TRIPLE_PRESS_WINDOW_MS) {
            pressCount = 0
        }

        pressCount++
        lastPressTime = now

        if (pressCount >= REQUIRED_PRESSES) {
            pressCount = 0
            cancelHoldDetection()
            triggerWithDebounce()
        }
    }

    private fun startHoldDetection() {
        // If already holding or already triggered this hold, ignore
        if (isHolding || holdTriggered) return
        isHolding = true

        holdRunnable = Runnable {
            if (isHolding && !holdTriggered) {
                holdTriggered = true  // Mark as triggered to prevent repeats
                pressCount = 0
                triggerWithDebounce()
            }
        }
        handler.postDelayed(holdRunnable!!, HOLD_DURATION_MS)
    }

    private fun cancelHoldDetection() {
        isHolding = false
        holdTriggered = false  // Reset when button is released
        holdRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun triggerWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < TRIGGER_DEBOUNCE_MS) {
            return  // Ignorar - muito próximo do último trigger
        }
        lastTriggerTime = now
        onTrigger()
    }

    private fun resetState() {
        pressCount = 0
        lastPressTime = 0
        lastTriggerTime = 0
        isHolding = false
        holdTriggered = false
    }

    fun isActive(): Boolean = mediaSession?.isActive == true
}
