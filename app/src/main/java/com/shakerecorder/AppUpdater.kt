package com.shakerecorder

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class UpdateManifest(
    val versionCode: Long, val versionName: String, val sizeBytes: Long, val sha256: String
) {
    fun toJson(): String = JSONObject().put("schemaVersion", 1)
        .put("versionCode", versionCode).put("versionName", versionName)
        .put("apkName", AppUpdater.APK_NAME).put("sizeBytes", sizeBytes)
        .put("sha256", sha256).toString()

    companion object {
        private fun integer(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Int || value is Long) { "Campo inteiro inválido: $key" }
            return (value as Number).toLong()
        }

        fun parse(text: String): UpdateManifest {
            val json = JSONObject(text)
            require(integer(json, "schemaVersion") == 1L) { "Formato de atualização não suportado." }
            require(json.getString("apkName") == AppUpdater.APK_NAME) { "Nome de APK inválido." }
            val code = integer(json, "versionCode")
            val name = json.getString("versionName")
            val size = integer(json, "sizeBytes")
            val hash = json.getString("sha256").lowercase()
            require(code in 1..Int.MAX_VALUE.toLong() && name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) { "Versão inválida." }
            require(size in 1..AppUpdater.MAX_APK_BYTES) { "Tamanho de APK inválido." }
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 inválido." }
            return UpdateManifest(code, name, size, hash)
        }
    }
}

internal data class AvailableUpdate(val manifest: UpdateManifest, val apkUrl: String)
internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Reads only public release metadata. Never embeds or requests a GitHub access token. */
internal open class AppUpdater(
    private val context: Context,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    companion object {
        const val REPOSITORY = "franc-nt/ShakeRecorder"
        const val APK_NAME = "ShakeRecorder.apk"
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

        fun assetUrl(release: JSONObject, name: String): String {
            require(release.get("draft") == false && release.get("prerelease") == false) {
                "A versão ainda não foi publicada como estável."
            }
            val tag = release.getString("tag_name")
            require(tag.matches(Regex("v[0-9]+(?:\\.[0-9]+){1,3}"))) { "Tag de release inválida." }
            val assets = release.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == name }
            require(matches.size == 1) { "Release incompleta: arquivo $name ausente ou duplicado." }
            val asset = matches.single()
            require(asset.optString("state") == "uploaded") { "O arquivo da release ainda não está pronto." }
            val url = asset.getString("browser_download_url")
            require(url == "https://github.com/$REPOSITORY/releases/download/$tag/$name") {
                "O arquivo não pertence à release esperada."
            }
            return url
        }

        fun requireCompatible(installed: ApkIdentity, archive: ApkIdentity, manifest: UpdateManifest) {
            require(installed.packageName == "com.shakerecorder" && archive.packageName == installed.packageName) {
                "O APK é de outro aplicativo. Instalação bloqueada."
            }
            require(archive.versionCode == manifest.versionCode && archive.versionCode > installed.versionCode) {
                "A versão do APK não é uma atualização válida."
            }
            require(installed.signers.isNotEmpty() && archive.signers == installed.signers) {
                "A assinatura é diferente da versão instalada. Não desinstale o aplicativo; peça uma versão com a mesma assinatura."
            }
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val updatesDir get() = File(context.cacheDir, "updates").apply { mkdirs() }
    open val apkFile: File get() = File(updatesDir, APK_NAME)
    private val manifestFile get() = File(updatesDir, "update.json")

    open suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val releaseText = readText(LATEST_URL, 512 * 1024, noReleaseIsOkay = true) ?: return@withContext null
        val release = JSONObject(releaseText)
        val manifestUrl = assetUrl(release, "update.json")
        val apkUrl = assetUrl(release, APK_NAME)
        val manifest = UpdateManifest.parse(readText(manifestUrl, 64 * 1024)!!)
        require(release.getString("tag_name") == "v${manifest.versionName}") { "Versão e tag não correspondem." }
        if (manifest.versionCode <= installedIdentity().versionCode) null else AvailableUpdate(manifest, apkUrl)
    }

    open suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // Repeat the allow-list before a request even when callers provide their own object.
        require(update.apkUrl == "https://github.com/$REPOSITORY/releases/download/v${update.manifest.versionName}/$APK_NAME")
        val partial = File(updatesDir, "$APK_NAME.part")
        partial.delete()
        try {
            request(update.apkUrl).use { response ->
                if (!response.isSuccessful) throw IOException("Não foi possível baixar o APK (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("O download retornou vazio.")
                if (body.contentLength() >= 0) require(body.contentLength() == update.manifest.sizeBytes) {
                    "O tamanho do download não corresponde à release."
                }
                var total = 0L
                var lastProgress = -1
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= update.manifest.sizeBytes && total <= MAX_APK_BYTES) { "Download maior que o esperado." }
                        output.write(buffer, 0, read)
                        val progress = (100 * total / update.manifest.sizeBytes).toInt()
                        if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                    }
                } }
            }
            verify(partial, update.manifest)
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(apkFile)) throw IOException("Não foi possível guardar a atualização.")
            manifestFile.writeText(update.manifest.toJson())
            apkFile
        } finally {
            partial.delete()
        }
    }

    /** Used after returning from Android's source permission screen or recreating this Activity. */
    open suspend fun downloadedUpdate(): UpdateManifest? = withContext(Dispatchers.IO) {
        if (!apkFile.isFile || !manifestFile.isFile) return@withContext null
        try {
            require(manifestFile.length() <= 64 * 1024)
            UpdateManifest.parse(manifestFile.readText()).also { verify(apkFile, it) }
        } catch (_: Exception) {
            clearDownload()
            null
        }
    }

    open suspend fun verifyDownloaded(manifest: UpdateManifest) = withContext(Dispatchers.IO) { verify(apkFile, manifest) }

    fun clearDownload() { apkFile.delete(); manifestFile.delete() }

    private fun verify(file: File, manifest: UpdateManifest) {
        require(file.length() == manifest.sizeBytes) { "Download incompleto. Tente novamente." }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) {
            "A verificação do download falhou. Não é seguro instalar este arquivo."
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw IllegalArgumentException("O arquivo baixado não é um APK válido.")
        requireCompatible(installedIdentity(), identity(archive), manifest)
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): ApkIdentity = identity(context.packageManager.getPackageInfo(context.packageName, signatureFlags()))

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return ApkIdentity(info.packageName, version, signatures?.map { digest(it.toByteArray()) }?.toSet() ?: emptySet())
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun request(url: String): Response {
        var target = url
        repeat(6) {
            val parsed = java.net.URI(target)
            require(parsed.scheme == "https" && parsed.userInfo == null && parsed.port in listOf(-1, 443) &&
                parsed.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
                "Redirecionamento de download não autorizado."
            }
            val call = http.newCall(Request.Builder().url(target)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ShakeRecorder/${BuildConfig.VERSION_NAME}").build())
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { response.close() }
                    }
                })
            }
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            target = next ?: throw IOException("Redirecionamento inválido.")
        }
        throw IOException("Redirecionamentos demais no download.")
    }

    private suspend fun readText(url: String, maxBytes: Int, noReleaseIsOkay: Boolean = false): String? = request(url).use { response ->
        if (response.code == 404 && noReleaseIsOkay) return null
        if (response.code == 403 || response.code == 429) throw IOException("O GitHub limitou o acesso. Tente novamente mais tarde.")
        if (!response.isSuccessful) throw IOException("Falha ao consultar o GitHub (HTTP ${response.code}).")
        val body = response.body ?: throw IOException("Resposta vazia do GitHub.")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= maxBytes) { "Resposta de atualização maior que o esperado." }
                output.write(buffer, 0, n)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
}
