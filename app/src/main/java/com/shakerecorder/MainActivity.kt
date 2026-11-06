package com.shakerecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.shakerecorder.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var recordingsAdapter: RecordingsAdapter
    private val logMessages = StringBuilder()
    private var recordAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Notification permission is optional on Android 13+: denying it must not
        // prevent a microphone foreground service from recording.
        if (hasRequiredPermissions()) {
            if (recordAfterPermission) startAndRecord() else armService()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()
        setupDrawerNavigation()

        settingsManager = SettingsManager(this)
        recordAfterPermission = savedInstanceState?.getBoolean("recordAfterPermission") ?: false

        setupUI()
        setupRecordingsList()
        setupServiceCallbacks()
        updateUI()
        loadRecordings()
        checkForCrashLog()

        // Auto-iniciar serviço se estava ativo antes
        autoStartServiceIfNeeded()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(safe.bottom, keyboard.bottom)
            // Apply to both siblings ourselves: DrawerLayout and fitsSystemWindows
            // must not consume or add these insets a second time.
            binding.mainContent.updatePadding(safe.left, safe.top, safe.right, bottom)
            binding.drawerContent.updatePadding(safe.left, safe.top, safe.right, bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.drawerLayout)
    }

    private fun setupDrawerNavigation() {
        val closeDrawer = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.drawerLayout.closeDrawer(GravityCompat.END)
            }
        }
        onBackPressedDispatcher.addCallback(this, closeDrawer)
        // DrawerLayout restores an open drawer during its first layout without
        // necessarily dispatching onDrawerOpened (for example after rotation).
        binding.drawerLayout.doOnLayout {
            closeDrawer.isEnabled = binding.drawerLayout.isDrawerOpen(GravityCompat.END)
        }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                closeDrawer.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                closeDrawer.isEnabled = false
            }
        })
        binding.drawerLayout.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(
                (320 * resources.displayMetrics.density).toInt(),
                (right - left - 64 * resources.displayMetrics.density).toInt().coerceAtLeast(0)
            )
            if (binding.drawerContent.layoutParams.width != width) {
                binding.drawerContent.layoutParams = binding.drawerContent.layoutParams.apply {
                    this.width = width
                }
            }
        }
    }

    private fun checkForCrashLog() {
        val crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last_crash", null)
        if (crash != null) {
            appendLog("=== CRASH ANTERIOR ===")
            appendLog(crash.take(500))
            appendLog("=== FIM DO CRASH ===")
            appendLog("Log salvo em: Documents/ShakeRecorder/crash_log.txt")
            getSharedPreferences("crash", MODE_PRIVATE).edit().remove("last_crash").apply()
        }
    }

    private fun setupUI() {
        binding.webhookInput.setText(settingsManager.webhookUrl)
        binding.soundSwitch.isChecked = settingsManager.isSoundEnabled

        // Show version in drawer
        binding.versionText.text = "ShakeRecorder v${BuildConfig.VERSION_NAME}"

        // Setup expandable sections
        setupExpandableSections()

        // Menu button opens drawer
        binding.menuButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }

        binding.saveButton.setOnClickListener {
            val url = binding.webhookInput.text.toString().trim()
            settingsManager.webhookUrl = url

            // Save Telegram settings
            settingsManager.telegramBotToken = binding.telegramTokenInput.text.toString().trim()
            settingsManager.telegramChatId = binding.telegramChatIdInput.text.toString().trim()

            Toast.makeText(this, "Configuracoes salvas", Toast.LENGTH_SHORT).show()
        }

        binding.checkUpdateButton.setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        binding.soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isSoundEnabled = isChecked
        }

        // Telegram settings
        binding.telegramSwitch.isChecked = settingsManager.isTelegramEnabled
        binding.telegramTokenInput.setText(settingsManager.telegramBotToken)
        binding.telegramChatIdInput.setText(settingsManager.telegramChatId)
        updateTelegramFieldsEnabled(settingsManager.isTelegramEnabled)

        binding.telegramSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isTelegramEnabled = isChecked
            updateTelegramFieldsEnabled(isChecked)
        }

        // Trigger switches
        binding.shakeSwitch.isChecked = settingsManager.isShakeEnabled
        binding.volumeHoldSwitch.isChecked = settingsManager.isVolumeHoldEnabled
        binding.volumeTripleSwitch.isChecked = settingsManager.isVolumeTriplePressEnabled

        // Setup NumberPickers
        setupTriggerNumberPickers()

        // Initial visibility based on saved state
        binding.shakeConfigLayout.visibility = if (settingsManager.isShakeEnabled) View.VISIBLE else View.GONE
        binding.volumeHoldConfigLayout.visibility = if (settingsManager.isVolumeHoldEnabled) View.VISIBLE else View.GONE
        binding.volumeTripleConfigLayout.visibility = if (settingsManager.isVolumeTriplePressEnabled) View.VISIBLE else View.GONE

        binding.shakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isShakeEnabled = isChecked
            binding.shakeConfigLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyTriggerSettings()
        }

        binding.volumeHoldSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isVolumeHoldEnabled = isChecked
            binding.volumeHoldConfigLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyTriggerSettings()
        }

        binding.volumeTripleSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isVolumeTriplePressEnabled = isChecked
            binding.volumeTripleConfigLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyTriggerSettings()
        }

        // Main circular button - toggles recording
        binding.mainButton.setOnClickListener {
            if (RecorderService.isRecording) {
                // Stop recording
                stopRecording()
            } else {
                // Start recording (start service if needed, then record)
                checkPermissionsAndStart()
            }
        }
    }

    private fun setupRecordingsList() {
        recordingsAdapter = RecordingsAdapter { file ->
            playRecording(file)
        }
        binding.recordingsList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recordingsAdapter
        }
    }

    private fun loadRecordings() {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val recordingsDir = File(musicDir, "ShakeRecorder")

        if (recordingsDir.exists()) {
            val files = recordingsDir.listFiles { file ->
                file.isFile && file.extension.lowercase() in listOf("m4a", "mp3", "wav", "ogg")
            }?.toList() ?: emptyList()
            recordingsAdapter.updateRecordings(files)
        }
    }

    private fun playRecording(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir arquivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupServiceCallbacks() {
        RecorderService.onStatusChanged = { _, _ ->
            runOnUiThread {
                updateUI()
            }
        }

        RecorderService.onLogMessage = { message ->
            runOnUiThread {
                appendLog(message)
            }
        }

        RecorderService.onRecordingSaved = {
            runOnUiThread {
                loadRecordings()
            }
        }
    }

    private fun updateUI() {
        val isRecording = RecorderService.isRecording

        // Update main button appearance
        if (isRecording) {
            binding.mainButton.text = getString(R.string.parar)
            binding.mainButton.setBackgroundResource(R.drawable.circular_button_red)
        } else {
            binding.mainButton.text = getString(R.string.iniciar)
            binding.mainButton.setBackgroundResource(R.drawable.circular_button)
        }

        // Update status text
        binding.statusText.text = when {
            isRecording -> getString(R.string.recording)
            RecorderService.isRunning -> getString(R.string.service_active)
            else -> getString(R.string.service_inactive)
        }

        val statusColor = when {
            isRecording -> ContextCompat.getColor(this, R.color.recording_red)
            RecorderService.isRunning -> ContextCompat.getColor(this, R.color.active_green)
            else -> ContextCompat.getColor(this, R.color.text_secondary)
        }
        binding.statusText.setTextColor(statusColor)
    }

    private fun appendLog(message: String) {
        logMessages.append(message).append("\n")
        binding.logText.text = logMessages.toString()
    }

    private fun checkPermissionsAndStart(record: Boolean = true) {
        recordAfterPermission = record
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            if (record) startAndRecord() else armService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startAndRecord() {
        // Deliver the command in the same intent that starts the service. A timer
        // can race cold starts and can fire after this activity leaves the screen.
        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START_RECORDING
        }
        ContextCompat.startForegroundService(this, intent)
        settingsManager.isServiceEnabled = true
    }

    private fun stopRecording() {
        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP_RECORDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissao Necessaria")
            .setMessage("O app precisa de permissao para gravar audio. Por favor, conceda a permissao nas configuracoes.")
            .setPositiveButton("Configuracoes") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyTriggerSettings() {
        if (RecorderService.isRunning) {
            // The service also listens to preferences, including writes outside this screen.
            startService(Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_APPLY_SETTINGS
            })
        } else if (settingsManager.hasEnabledTriggers) {
            // Enabling a shortcut arms monitoring; it must not start recording.
            checkPermissionsAndStart(record = false)
        }
    }

    private fun armService() {
        if (!settingsManager.hasEnabledTriggers) return
        ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_APPLY_SETTINGS
        })
        settingsManager.isServiceEnabled = true
    }

    private fun autoStartServiceIfNeeded() {
        // Auto-iniciar serviço se estava ativo antes e já tem permissões
        if (settingsManager.isServiceEnabled && !RecorderService.isRunning) {
            if (hasRequiredPermissions()) {
                val intent = Intent(this, RecorderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
               PackageManager.PERMISSION_GRANTED
    }

    private fun updateTelegramFieldsEnabled(enabled: Boolean) {
        binding.telegramTokenInput.isEnabled = enabled
        binding.telegramChatIdInput.isEnabled = enabled
        binding.telegramTokenLayout.alpha = if (enabled) 1.0f else 0.5f
        binding.telegramChatIdLayout.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun setupExpandableSections() {
        setupExpandableSection(binding.envioHeader, binding.envioContent, binding.envioArrow)
        setupExpandableSection(binding.gatilhosHeader, binding.gatilhosContent, binding.gatilhosArrow)
        setupExpandableSection(binding.opcoesHeader, binding.opcoesContent, binding.opcoesArrow)
        setupExpandableSection(binding.logsHeader, binding.logsContent, binding.logsArrow)
    }

    private fun setupExpandableSection(header: View, content: View, arrow: ImageView) {
        header.setOnClickListener {
            val isExpanded = content.visibility == View.VISIBLE
            if (isExpanded) {
                content.visibility = View.GONE
                arrow.setImageResource(R.drawable.ic_expand_more)
            } else {
                content.visibility = View.VISIBLE
                arrow.setImageResource(R.drawable.ic_expand_less)
            }
        }
    }

    private fun setupTriggerNumberPickers() {
        // Shake count picker (2-10)
        binding.shakeCountPicker.apply {
            minValue = 2
            maxValue = 10
            value = settingsManager.shakeCount
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, newVal ->
                settingsManager.shakeCount = newVal
                applyTriggerSettings()
            }
        }

        // Hold duration picker (1-10 seconds)
        binding.holdDurationPicker.apply {
            minValue = 1
            maxValue = 10
            value = settingsManager.volumeHoldDuration
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, newVal ->
                settingsManager.volumeHoldDuration = newVal
                applyTriggerSettings()
            }
        }

        // Press count picker (2-5)
        binding.pressCountPicker.apply {
            minValue = 2
            maxValue = 5
            value = settingsManager.volumePressCount
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, newVal ->
                settingsManager.volumePressCount = newVal
                applyTriggerSettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RecorderService.resumeAfterUpdate()
        loadRecordings()
        autoStartServiceIfNeeded()
        updateUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        RecorderService.setForegroundVolumeKeys(hasFocus)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RecorderService.dispatchVolumeKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        RecorderService.setForegroundVolumeKeys(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recordAfterPermission", recordAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        RecorderService.onStatusChanged = null
        RecorderService.onLogMessage = null
        RecorderService.onRecordingSaved = null
        super.onDestroy()
    }
}
