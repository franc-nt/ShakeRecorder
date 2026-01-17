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
    private val isTriplePressEnabled: () -> Boolean,
    private val getHoldDurationMs: () -> Long = { 3000L },
    private val getRequiredPresses: () -> Int = { 3 },
    private val triplePressWindowMs: Long = 5000L
) {
    companion object {
        private const val TRIGGER_DEBOUNCE_MS = 2000L   // 2 segundos entre triggers
        private const val MIN_PRESS_INTERVAL_MS = 150L  // Intervalo mínimo entre cliques (filtra auto-repeat)
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
            // IMPORTANTE: Só processa triple-press se NÃO estiver em hold detection
            // Isso evita que eventos de auto-repeat do Android contem como cliques
            if (isTriplePressEnabled() && !isHolding) handleTriplePress()
            if (isHoldEnabled()) startHoldDetection()
        } else {  // Volume DOWN ou release
            cancelHoldDetection()
        }
    }

    private fun handleTriplePress() {
        val now = System.currentTimeMillis()
        val timeSinceLastPress = now - lastPressTime

        // Ignorar eventos de auto-repeat (muito rápidos para ser cliques reais)
        if (timeSinceLastPress < MIN_PRESS_INTERVAL_MS && lastPressTime > 0) {
            return
        }

        // Reset se passou muito tempo desde o último clique
        if (timeSinceLastPress > triplePressWindowMs) {
            pressCount = 0
        }

        pressCount++
        lastPressTime = now

        if (pressCount >= getRequiredPresses()) {
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
                // Reset estado após debounce para próximo hold funcionar
                // (VolumeProviderCompat não envia evento de "soltar botão")
                handler.postDelayed({
                    isHolding = false
                    holdTriggered = false
                }, TRIGGER_DEBOUNCE_MS)
            }
        }
        handler.postDelayed(holdRunnable!!, getHoldDurationMs())
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
