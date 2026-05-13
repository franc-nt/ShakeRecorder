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
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_SHAKE_COUNT = "shake_count"
        private const val KEY_VOLUME_HOLD_DURATION = "volume_hold_duration"
        private const val KEY_VOLUME_PRESS_COUNT = "volume_press_count"
        private const val KEY_GEMINI_ENABLED = "gemini_enabled"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GEMINI_PROMPT = "gemini_prompt"
        private const val KEY_HERMES_ENABLED = "hermes_enabled"
        private const val KEY_HERMES_URL = "hermes_url"
        private const val KEY_HERMES_API_KEY = "hermes_api_key"
        private const val KEY_HERMES_INSTRUCTIONS = "hermes_instructions"
        private const val KEY_HERMES_SESSION_ID = "hermes_session_id"
        private const val DEFAULT_WEBHOOK_URL = "https://webhook.site/620bad6b-72ab-43ec-b0c2-a975290d210f"
        private const val DEFAULT_GEMINI_MODEL = "gemini-3-flash-preview"
        private const val DEFAULT_GEMINI_PROMPT = "Transcreva este áudio em português brasileiro. Retorne apenas a transcrição literal, sem comentários ou explicações."
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

    var isTelegramEnabled: Boolean
        get() = prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TELEGRAM_ENABLED, value).apply()

    var telegramBotToken: String
        get() = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, value).apply()

    var telegramChatId: String
        get() = prefs.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_CHAT_ID, value).apply()

    var shakeCount: Int
        get() = prefs.getInt(KEY_SHAKE_COUNT, 3)  // Default: 3 balançadas
        set(value) = prefs.edit().putInt(KEY_SHAKE_COUNT, value).apply()

    var volumeHoldDuration: Int
        get() = prefs.getInt(KEY_VOLUME_HOLD_DURATION, 3)  // Default: 3 segundos
        set(value) = prefs.edit().putInt(KEY_VOLUME_HOLD_DURATION, value).apply()

    var volumePressCount: Int
        get() = prefs.getInt(KEY_VOLUME_PRESS_COUNT, 3)  // Default: 3 toques
        set(value) = prefs.edit().putInt(KEY_VOLUME_PRESS_COUNT, value).apply()

    var isGeminiEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEMINI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GEMINI_ENABLED, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    var geminiPrompt: String
        get() = prefs.getString(KEY_GEMINI_PROMPT, DEFAULT_GEMINI_PROMPT) ?: DEFAULT_GEMINI_PROMPT
        set(value) = prefs.edit().putString(KEY_GEMINI_PROMPT, value).apply()

    var isHermesEnabled: Boolean
        get() = prefs.getBoolean(KEY_HERMES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_ENABLED, value).apply()

    var hermesUrl: String
        get() = prefs.getString(KEY_HERMES_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_URL, value).apply()

    var hermesApiKey: String
        get() = prefs.getString(KEY_HERMES_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_API_KEY, value).apply()

    var hermesInstructions: String
        get() = prefs.getString(KEY_HERMES_INSTRUCTIONS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_INSTRUCTIONS, value).apply()

    var hermesSessionId: String
        get() = prefs.getString(KEY_HERMES_SESSION_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_SESSION_ID, value).apply()
}
