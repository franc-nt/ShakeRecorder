package com.shakerecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RecorderService : Service() {

    companion object {
        const val CHANNEL_ID = "shake_recorder_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.shakerecorder.STOP"
        const val ACTION_STOP_RECORDING = "com.shakerecorder.STOP_RECORDING"
        const val ACTION_START_RECORDING = "com.shakerecorder.START_RECORDING"
        const val ACTION_TOGGLE_RECORDING = "com.shakerecorder.TOGGLE_RECORDING"
        const val ACTION_APPLY_SETTINGS = "com.shakerecorder.APPLY_SETTINGS"

        private var instance: RecorderService? = null
        private var foregroundVolumeKeys = false

        /** Pause only after the user chooses Install; never interrupt an active recording. */
        fun prepareForUpdate(): Boolean {
            val service = instance ?: return true
            if (isRecording) return false
            service.installingUpdate = true
            service.sensorManager.unregisterListener(service.shakeDetector)
            service.volumeButtonDetector?.stop()
            service.updateNotification(false)
            return true
        }

        fun resumeAfterUpdate() {
            instance?.let { service ->
                if (service.installingUpdate && service.settingsManager.isServiceEnabled) {
                    service.installingUpdate = false
                    service.appliedTriggerSettings = null
                    service.applyTriggerSettings()
                    service.updateNotification(isRecording)
                }
            }
        }

        fun setForegroundVolumeKeys(enabled: Boolean) {
            foregroundVolumeKeys = enabled
            instance?.volumeButtonDetector?.setForegroundKeys(enabled)
        }

        fun dispatchVolumeKeyEvent(event: KeyEvent): Boolean =
            instance?.volumeButtonDetector?.onKeyEvent(event) ?: false

        var isRunning = false
            private set
        var isRecording = false
            private set
        internal var activeRecordingPath: String? = null
            private set

        var onStatusChanged: ((Boolean, Boolean) -> Unit)? = null
        var onLogMessage: ((String) -> Unit)? = null
        var onRecordingSaved: (() -> Unit)? = null
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var webhookUploader: WebhookUploader
    private lateinit var telegramUploader: TelegramUploader
    private lateinit var uploadQueueManager: UploadQueueManager
    private lateinit var shakeDetector: ShakeDetector
    private var volumeButtonDetector: VolumeButtonDetector? = null

    private var toneGenerator: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    private var installingUpdate = false
    private var appliedTriggerSettings: List<Any>? = null
    private val applySettings = Runnable { if (isRunning) applyTriggerSettings() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (SettingsManager.isTriggerSetting(key)) {
            handler.removeCallbacks(applySettings)
            handler.post(applySettings)
        }
    }
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // A timeout remains a fail-safe, but monitoring must not silently stop
            // after the old ten-minute lease. Cancel renewal when the service stops.
            wakeLock?.acquire(10 * 60 * 1000L)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        settingsManager = SettingsManager(this)
        instance = this
        settingsManager.registerListener(settingsListener)
        audioRecorder = AudioRecorder(this)
        webhookUploader = WebhookUploader()
        telegramUploader = TelegramUploader()
        uploadQueueManager = UploadQueueManager(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        shakeDetector = ShakeDetector(
            onShakeDetected = { onShakeDetected() },
            requiredShakes = settingsManager.shakeCount
        )

        setupToneGenerator()
        createNotificationChannel()
    }

    private fun setupToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            log("Erro ao criar ToneGenerator: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settingsManager.isServiceEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            val notification = createNotification(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            acquireWakeLock()
            applyTriggerSettings()
            log("Servico iniciado")
            notifyStatusChanged()
        }

        when (intent?.action) {
            ACTION_APPLY_SETTINGS -> applyTriggerSettings()
            ACTION_START_RECORDING -> if (!isRecording) startRecording()
            ACTION_STOP_RECORDING -> if (isRecording) stopRecording()
            ACTION_TOGGLE_RECORDING -> onShakeDetected()
        }
        return START_STICKY
    }

    private fun applyTriggerSettings() {
        if (installingUpdate) return
        val next = listOf(settingsManager.isShakeEnabled, settingsManager.shakeCount,
            settingsManager.isVolumeHoldEnabled, settingsManager.isVolumeTriplePressEnabled,
            settingsManager.volumeHoldDuration, settingsManager.volumePressCount)
        if (next == appliedTriggerSettings) return
        sensorManager.unregisterListener(shakeDetector)
        shakeDetector = ShakeDetector(::onShakeDetected, settingsManager.shakeCount)
        registerShakeListener()
        volumeButtonDetector?.stop()
        volumeButtonDetector = null
        initVolumeButtonDetector()
        appliedTriggerSettings = next
        log("Gatilhos aplicados sem reiniciar o servico")
    }

    private fun registerShakeListener() {
        if (!settingsManager.isShakeEnabled) {
            log("Shake detection desativado")
            return
        }
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(
                shakeDetector,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    private fun initVolumeButtonDetector() {
        if (!settingsManager.isVolumeHoldEnabled && !settingsManager.isVolumeTriplePressEnabled) {
            log("Volume button triggers desativados")
            return
        }

        volumeButtonDetector = VolumeButtonDetector(
            context = this,
            onTrigger = { onShakeDetected() },
            isHoldEnabled = { settingsManager.isVolumeHoldEnabled },
            isTriplePressEnabled = { settingsManager.isVolumeTriplePressEnabled },
            getHoldDurationMs = { settingsManager.volumeHoldDuration * 1000L },
            getRequiredPresses = { settingsManager.volumePressCount },
            triplePressWindowMs = 5000L
        )
        volumeButtonDetector?.start()
        volumeButtonDetector?.setForegroundKeys(foregroundVolumeKeys)

        val triggers = mutableListOf<String>()
        if (settingsManager.isVolumeHoldEnabled) triggers.add("segurar ${settingsManager.volumeHoldDuration}s")
        if (settingsManager.isVolumeTriplePressEnabled) triggers.add("${settingsManager.volumePressCount}x clique")
        log("Volume trigger ativo: ${triggers.joinToString(", ")}")
    }

    private fun onShakeDetected() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (installingUpdate) return
        vibrate()
        playStartSound()

        val filePath = audioRecorder.startRecording()
        if (filePath != null) {
            activeRecordingPath = filePath
            isRecording = true
            updateNotification(true)
            log("Gravacao iniciada: ${filePath.substringAfterLast("/")}")
            notifyStatusChanged()
        } else {
            log("Erro ao iniciar gravacao")
        }
    }

    private fun stopRecording() {
        vibrate()
        playStopSound()

        val filePath = audioRecorder.stopRecording()
        isRecording = false
        activeRecordingPath = null
        updateNotification(false)

        if (filePath != null) {
            log("Gravacao salva: ${filePath.substringAfterLast("/")}")
            uploadRecording(filePath)
            onRecordingSaved?.invoke()
        } else {
            log("Erro ao parar gravacao")
        }
        notifyStatusChanged()
    }

    private fun uploadRecording(filePath: String) {
        // Upload para webhook
        if (settingsManager.webhookUrl.isNotBlank()) {
            uploadToDestination(filePath, UploadItem.UploadDestination.WEBHOOK)
        }

        // Upload para Telegram
        if (settingsManager.isTelegramEnabled &&
            settingsManager.telegramBotToken.isNotBlank() &&
            settingsManager.telegramChatId.isNotBlank()) {
            uploadToDestination(filePath, UploadItem.UploadDestination.TELEGRAM)
        }
    }

    private fun uploadToDestination(filePath: String, destination: UploadItem.UploadDestination) {
        val destName = if (destination == UploadItem.UploadDestination.WEBHOOK) "webhook" else "Telegram"
        log("Enviando para $destName...")

        serviceScope.launch {
            val result = when (destination) {
                UploadItem.UploadDestination.WEBHOOK -> {
                    webhookUploader.uploadFile(filePath, settingsManager.webhookUrl)
                }
                UploadItem.UploadDestination.TELEGRAM -> {
                    telegramUploader.uploadAudio(
                        filePath,
                        settingsManager.telegramBotToken,
                        settingsManager.telegramChatId
                    )
                }
            }

            result.fold(
                onSuccess = {
                    log("Upload $destName concluido!")
                },
                onFailure = { error ->
                    log("Erro no upload $destName: ${error.message}")
                    log("Adicionando a fila de retry...")
                    queueForRetry(filePath, destination)
                }
            )
        }
    }

    private fun queueForRetry(filePath: String, destination: UploadItem.UploadDestination) {
        val item = uploadQueueManager.addToQueue(filePath, destination)
        scheduleRetryWork(item.id)
    }

    private fun scheduleRetryWork(itemId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(UploadWorker.KEY_ITEM_ID to itemId))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "upload_$itemId",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    private fun playStartSound() {
        if (!settingsManager.isSoundEnabled) return
        try {
            // One beep for start
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            log("Erro ao tocar som: ${e.message}")
        }
    }

    private fun playStopSound() {
        if (!settingsManager.isSoundEnabled) return
        try {
            // Two beeps for stop
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }, 200)
        } catch (e: Exception) {
            log("Erro ao tocar som: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(recording: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (installingUpdate) {
            "Atualização em andamento"
        } else if (recording) {
            getString(R.string.notification_title_recording)
        } else {
            getString(R.string.notification_title_active)
        }

        val text = if (installingUpdate) {
            "Monitoramento pausado até voltar ao aplicativo"
        } else if (recording) {
            getString(R.string.notification_text_recording)
        } else {
            getString(R.string.notification_text_active)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Parar",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(recording: Boolean) {
        val notification = createNotification(recording)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ShakeRecorder::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
        handler.postDelayed(renewWakeLock, 5 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        onLogMessage?.invoke("[$timestamp] $message")
    }

    private fun notifyStatusChanged() {
        onStatusChanged?.invoke(isRunning, isRecording)
    }

    override fun onDestroy() {
        settingsManager.unregisterListener(settingsListener)
        if (isRecording) stopRecording()
        sensorManager.unregisterListener(shakeDetector)
        volumeButtonDetector?.stop()
        volumeButtonDetector = null
        audioRecorder.release()
        toneGenerator?.release()
        toneGenerator = null
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()

        isRunning = false
        isRecording = false
        activeRecordingPath = null
        instance = null
        notifyStatusChanged()
        log("Servico parado")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
