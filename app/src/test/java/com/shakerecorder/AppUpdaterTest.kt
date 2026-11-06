package com.shakerecorder

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Every HTTP response is intercepted locally; no real repository or user data is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 35, 36])
class AppUpdaterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun manifest() = UpdateManifest(1000, "10.0", 3, "0".repeat(64))
    private fun url(name: String = AppUpdater.APK_NAME) =
        "https://github.com/${AppUpdater.REPOSITORY}/releases/download/v10.0/$name"
    private fun asset(name: String = AppUpdater.APK_NAME) = JSONObject()
        .put("name", name).put("state", "uploaded").put("browser_download_url", url(name))
    private fun release() = JSONObject().put("tag_name", "v10.0")
        .put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(asset()).put(asset("update.json")))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun client(code: Int = 200, body: String = "abc", location: String? = null) =
        OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody())
                .apply { if (location != null) header("Location", location) }.build()
        }.build()

    @Test fun manifestRoundTripAndRejectsInvalidLimits() {
        assertEquals(manifest(), UpdateManifest.parse(manifest().toJson()))
        val cases = listOf("schemaVersion" to 2, "apkName" to "other.apk",
            "versionCode" to 0, "versionName" to "../10.0", "sizeBytes" to 0,
            "sizeBytes" to AppUpdater.MAX_APK_BYTES + 1, "sha256" to "not-a-hash",
            "schemaVersion" to 1.5, "versionCode" to "1000", "versionCode" to 1000.5,
            "sizeBytes" to "3", "sizeBytes" to 3.5)
        cases.forEach { (key, value) ->
            rejected { UpdateManifest.parse(JSONObject(manifest().toJson()).put(key, value).toString()) }
        }
    }

    @Test fun releaseRequiresUniqueUploadedStableAsset() {
        assertEquals(url(), AppUpdater.assetUrl(release(), AppUpdater.APK_NAME))
        listOf("draft", "prerelease").forEach { key ->
            rejected { AppUpdater.assetUrl(release().put(key, true), AppUpdater.APK_NAME) }
        }
        rejected { AppUpdater.assetUrl(release().put("assets", JSONArray()), AppUpdater.APK_NAME) }
        rejected { AppUpdater.assetUrl(release().put("assets", JSONArray().put(asset()).put(asset())), AppUpdater.APK_NAME) }
        rejected { AppUpdater.assetUrl(release().put("assets", JSONArray().put(asset().put("state", "new"))), AppUpdater.APK_NAME) }
        rejected { AppUpdater.assetUrl(release().put("tag_name", "../10.0"), AppUpdater.APK_NAME) }
    }

    @Test fun releaseRejectsUntrustedOrAmbiguousUrls() {
        listOf(url().replace("https:", "http:"), url().replace("github.com", "github.com.evil.test"),
            url().replace(AppUpdater.REPOSITORY, "other/ShakeRecorder"), url() + "?download=1",
            url().replace("v10.0", "v9.0"), url().replace("github.com/", "user@github.com/")).forEach { bad ->
            rejected { AppUpdater.assetUrl(release().put("assets", JSONArray().put(asset().put("browser_download_url", bad))), AppUpdater.APK_NAME) }
        }
    }

    @Test fun identitiesRequirePackageNewerVersionAndCompleteSignerSet() {
        val installed = ApkIdentity("com.shakerecorder", 23, setOf("certificate-a"))
        val valid = installed.copy(versionCode = 1000)
        AppUpdater.requireCompatible(installed, valid, manifest())
        listOf(valid.copy(packageName = "other.app"), valid.copy(versionCode = 23),
            valid.copy(versionCode = 22), valid.copy(versionCode = 999), valid.copy(signers = emptySet()),
            valid.copy(signers = setOf("certificate-b")), valid.copy(signers = setOf("certificate-a", "certificate-b"))).forEach {
            rejected { AppUpdater.requireCompatible(installed, it, manifest()) }
        }
        rejected { AppUpdater.requireCompatible(installed.copy(signers = emptySet()), valid.copy(signers = emptySet()), manifest()) }
        rejected { AppUpdater.requireCompatible(installed.copy(packageName = "other.app"), valid.copy(packageName = "other.app"), manifest()) }
        val multi = installed.copy(signers = setOf("certificate-a", "certificate-b"))
        AppUpdater.requireCompatible(multi, multi.copy(versionCode = 1000), manifest())
        rejected { AppUpdater.requireCompatible(multi, valid, manifest()) }
    }

    @Test fun platformIdentityUsesExpectedSignatureApiAndVersion() {
        val updater = AppUpdater(context, client())
        val flags = AppUpdater::class.java.getDeclaredMethod("signatureFlags").apply { isAccessible = true }.invoke(updater)
        assertEquals(if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES, flags)
        val info = PackageInfo().apply { packageName = "com.shakerecorder"; versionCode = 42
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3))) }
        val identity = AppUpdater::class.java.getDeclaredMethod("identity", PackageInfo::class.java)
            .apply { isAccessible = true }.invoke(updater, info) as ApkIdentity
        assertEquals(42L, identity.versionCode)
        // Modern API must fail closed rather than fall back to legacy signatures.
        if (Build.VERSION.SDK_INT >= 28) assertTrue(identity.signers.isEmpty())
        else assertEquals(1, identity.signers.size)
    }

    @Test fun noReleaseIsNormalAndExternalRedirectRejected() = runBlocking {
        assertNull(AppUpdater(context, client(404)).check())
        rejected { runBlocking { AppUpdater(context, client(302, location = "https://evil.test/app.apk")).check() } }
    }

    @Test fun incompleteAndBadHashDownloadsRemovePartialFiles() {
        for (body in listOf("ab", "abc")) {
            val updater = AppUpdater(context, client(body = body))
            updater.clearDownload()
            rejected { runBlocking { updater.download(AvailableUpdate(manifest(), url())) {} } }
            assertFalse(File(context.cacheDir, "updates/${AppUpdater.APK_NAME}.part").exists())
            assertFalse(updater.apkFile.exists())
        }
    }

    @Test fun corruptCachedUpdateIsRemovedWithoutInstall() = runBlocking {
        val updater = AppUpdater(context, client())
        updater.apkFile.writeText("abc")
        File(updater.apkFile.parentFile, "update.json").writeText(manifest().toJson())
        assertNull(updater.downloadedUpdate())
        assertFalse(updater.apkFile.exists())
        assertFalse(File(updater.apkFile.parentFile, "update.json").exists())
    }
}
