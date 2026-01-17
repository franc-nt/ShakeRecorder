package com.shakerecorder

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "shake_recorder_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VOLUME_TRIGGER_ENABLED = "volume_trigger_enabled"
        private const val KEY_SHAKE_ENABLED = "shake_enabled"
        private const val KEY_VOLUME_HOLD_ENABLED = "volume_hold_enabled"
        private const val KEY_VOLUME_TRIPLE_PRESS_ENABLED = "volume_triple_press_enabled"
        private const val DEFAULT_WEBHOOK_URL = "https://webhook.site/620bad6b-72ab-43ec-b0c2-a975290d210f"
    }

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    var isVolumeTriggerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_TRIGGER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_TRIGGER_ENABLED, value).apply()

    var isShakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE_ENABLED, true)  // Default: ON
        set(value) = prefs.edit().putBoolean(KEY_SHAKE_ENABLED, value).apply()

    var isVolumeHoldEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_HOLD_ENABLED, false)  // Default: OFF
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_HOLD_ENABLED, value).apply()

    var isVolumeTriplePressEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_TRIPLE_PRESS_ENABLED, false)  // Default: OFF
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_TRIPLE_PRESS_ENABLED, value).apply()
}
