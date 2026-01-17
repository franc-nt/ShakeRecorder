package com.shakerecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.shakerecorder.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var recordingsAdapter: RecordingsAdapter
    private val logMessages = StringBuilder()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startAndRecord()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        setupUI()
        setupRecordingsList()
        setupServiceCallbacks()
        updateUI()
        loadRecordings()
        checkForCrashLog()
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

        // Menu button opens drawer
        binding.menuButton.setOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.END)
        }

        binding.saveButton.setOnClickListener {
            val url = binding.webhookInput.text.toString().trim()
            if (url.isNotEmpty()) {
                settingsManager.webhookUrl = url
                Toast.makeText(this, "Configuracoes salvas", Toast.LENGTH_SHORT).show()
            }
        }

        binding.soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isSoundEnabled = isChecked
        }

        // Trigger switches
        binding.shakeSwitch.isChecked = settingsManager.isShakeEnabled
        binding.volumeHoldSwitch.isChecked = settingsManager.isVolumeHoldEnabled
        binding.volumeTripleSwitch.isChecked = settingsManager.isVolumeTriplePressEnabled

        binding.shakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isShakeEnabled = isChecked
            showRestartServiceToast()
        }

        binding.volumeHoldSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isVolumeHoldEnabled = isChecked
            showRestartServiceToast()
        }

        binding.volumeTripleSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isVolumeTriplePressEnabled = isChecked
            showRestartServiceToast()
        }

        // Main circular button - toggles recording
        binding.mainButton.setOnClickListener {
            if (RecorderService.isRecording) {
                // Stop recording
                toggleRecording()
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
            else -> ContextCompat.getColor(this, android.R.color.darker_gray)
        }
        binding.statusText.setTextColor(statusColor)
    }

    private fun appendLog(message: String) {
        logMessages.append(message).append("\n")
        binding.logText.text = logMessages.toString()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startAndRecord()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startAndRecord() {
        if (!RecorderService.isRunning) {
            // Start service first, then toggle recording
            val intent = Intent(this, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            settingsManager.isServiceEnabled = true
            // Wait a moment for service to start, then toggle recording
            binding.mainButton.postDelayed({
                toggleRecording()
            }, 300)
        } else {
            toggleRecording()
        }
    }

    private fun toggleRecording() {
        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_TOGGLE_RECORDING
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

    private fun showRestartServiceToast() {
        if (RecorderService.isRunning) {
            Toast.makeText(this, "Reinicie o servico para aplicar", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    override fun onDestroy() {
        RecorderService.onStatusChanged = null
        RecorderService.onLogMessage = null
        RecorderService.onRecordingSaved = null
        super.onDestroy()
    }
}
