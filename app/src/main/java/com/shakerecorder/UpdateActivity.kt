package com.shakerecorder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.shakerecorder.databinding.ActivityUpdateBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

open class UpdateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUpdateBinding
    private lateinit var updater: AppUpdater
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var operation: Job? = null
    private var available: AvailableUpdate? = null
    private var downloaded: UpdateManifest? = null
    private var permissionDialog: AlertDialog? = null

    private val sourcePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) runOperation { installDownloaded() }
        else showStatus("Permissão não concedida. O arquivo continua disponível para tentar novamente.")
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Launching the installer or returning RESULT_OK does not prove replacement.
        // Successful Android replacement normally terminates this old app process.
        showStatus("Se a versão continua ${BuildConfig.VERSION_NAME}, a instalação não foi concluída. Você pode tentar novamente, sem desinstalar.")
        resumeMonitoring()
    }

    internal open fun createUpdater(): AppUpdater = AppUpdater(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updater = createUpdater()
        val padding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(padding + safe.left, padding + safe.top, padding + safe.right, padding + safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.currentVersion.text = "Versão instalada: ${BuildConfig.VERSION_NAME}"
        binding.backButton.setOnClickListener { finish() }
        binding.updateButton.setOnClickListener {
            runOperation {
                when {
                    downloaded != null -> installDownloaded()
                    available != null -> {
                        val update = available!!
                        showStatus("Baixando atualização...")
                        updater.download(update) { progress -> runOnUiThread {
                            if (!isDestroyed && !isFinishing) showStatus("Baixando atualização: $progress%")
                        } }
                        downloaded = update.manifest
                        available = null
                        installDownloaded()
                    }
                    else -> {
                        showStatus("Consultando GitHub Releases...")
                        available = updater.check()
                        showStatus(available?.let { "Versão ${it.manifest.versionName} disponível. O Android pedirá sua confirmação para instalar." }
                            ?: "Nenhuma versão mais nova está disponível nas Releases públicas do projeto.")
                    }
                }
            }
        }
        runOperation {
            downloaded = updater.downloadedUpdate()
            if (downloaded != null) showStatus("Atualização verificada e já baixada. Você pode instalar sem desinstalar o aplicativo.")
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = scope.launch {
            binding.updateButton.isEnabled = false
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showStatus(error.message ?: "Não foi possível atualizar. Tente novamente.") }
            finally { refreshButton() }
        }
    }

    private fun refreshButton() {
        binding.updateButton.isEnabled = true
        binding.updateButton.text = when {
            downloaded != null -> "Instalar atualização"
            available != null -> "Baixar e atualizar"
            else -> "Verificar atualização"
        }
    }

    private fun showStatus(message: String) { binding.updateStatus.text = message }

    private suspend fun installDownloaded() {
        val manifest = downloaded ?: updater.downloadedUpdate()?.also { downloaded = it }
            ?: return showStatus("Nenhuma atualização válida foi baixada. Verifique novamente.")
        if (RecorderService.isRecording) return showStatus("Termine e salve a gravação antes de instalar. Atualizar reinicia o aplicativo.")
        updater.verifyDownloaded(manifest)
        if (!packageManager.canRequestPackageInstalls()) {
            permissionDialog?.dismiss()
            permissionDialog = AlertDialog.Builder(this)
                .setTitle("Permitir atualização pelo ShakeRecorder")
                .setMessage("O Android precisa permitir que este app abra arquivos de atualização. Você decide na próxima tela. A instalação também exige sua confirmação; não é necessário desinstalar.")
                .setNegativeButton("Agora não", null)
                .setPositiveButton("Abrir permissão") { _, _ ->
                    try { sourcePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"))) }
                    catch (_: ActivityNotFoundException) { showStatus("Abra as configurações do Android e procure Instalar apps desconhecidos para o ShakeRecorder.") }
                }.show()
            return
        }
        // Recheck after asynchronous file validation and pause shortcuts so a new
        // recording cannot begin while Android is replacing the application.
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", updater.apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("ShakeRecorder update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!RecorderService.prepareForUpdate()) return showStatus("Há uma gravação em andamento. Salve-a antes de atualizar.")
        try {
            installer.launch(intent)
            showStatus("Confirme a atualização no instalador Android. Não desinstale. O monitoramento fica pausado durante a instalação.")
        } catch (_: ActivityNotFoundException) {
            showStatus("O Android não disponibilizou um instalador para este arquivo.")
            resumeMonitoring()
        } catch (_: SecurityException) {
            showStatus("A política de segurança do aparelho bloqueou a instalação. Nenhuma proteção foi desativada.")
            resumeMonitoring()
        }
    }

    private fun resumeMonitoring() {
        RecorderService.resumeAfterUpdate()
        if (SettingsManager(this).isServiceEnabled && !RecorderService.isRunning &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java))
        }
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }
}
