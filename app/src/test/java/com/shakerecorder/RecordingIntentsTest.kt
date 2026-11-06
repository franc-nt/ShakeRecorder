package com.shakerecorder

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Makes public Music and FileProvider use the same sandbox root, as they do on Android. */
internal object RecordingProviderFixture {
    private fun freshProvider(context: Context): FileProvider {
        val authority = "${context.packageName}.fileprovider"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        // AndroidX attachInfo invalidates its static, authority-keyed path cache.
        return FileProvider().also { it.attachInfo(context, info) }
    }

    fun prepare(context: Context) {
        // Robolectric otherwise gives these APIs unrelated external-files and
        // external-cache roots. Keep the production XML and path validation intact.
        ShadowEnvironment.setExternalStoragePublicDirectory(Environment.getExternalStorageDirectory().toPath())
        ShadowContentResolver.registerProviderInternal("${context.packageName}.fileprovider", freshProvider(context))
    }

    fun clearCachedPaths(context: Context) {
        // A subsequent test has a different temporary root even when Robolectric
        // reuses the sandbox classloader. Do not leak paths into updater tests.
        freshProvider(context)
    }
}

/** Synthetic sandbox files only. Provider reads do not simulate a real receiving app's UID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35, 36])
class RecordingIntentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root: File get() = RecordingIntents.recordingsDirectory()
    private val created = mutableListOf<File>()
    private val bytes = "synthetic recording fixture; not microphone audio".toByteArray()
    private val grantMask = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    @Before fun prepare() {
        SettingsManager(context).apply {
            webhookUrl = ""
            isTelegramEnabled = false
            telegramBotToken = ""
            telegramChatId = ""
            isServiceEnabled = false
            isSoundEnabled = false
        }
        // Exercise the real FileProvider implementation with sandbox files, rather
        // than ShadowContentResolver's fallback stream for an unknown provider.
        RecordingProviderFixture.prepare(context)
        root.mkdirs()
    }

    @After fun cleanUp() {
        created.asReversed().forEach { it.delete() }
        RecordingProviderFixture.clearCachedPaths(context)
    }

    private fun fixture(file: File): File {
        file.parentFile!!.mkdirs()
        created += file
        file.writeBytes(bytes)
        return file
    }

    @Suppress("DEPRECATION")
    private fun sendIntent(chooser: Intent): Intent = chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)!!

    private fun assertReadOnlySingleUri(intent: Intent, uri: Uri) {
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and grantMask)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertNull(intent.clipData!!.getItemAt(0).intent)
        assertNull(intent.clipData!!.getItemAt(0).text)
        assertNull(intent.`package`)
        assertNull(intent.component)
    }

    @Test fun providerIsPrivateAndAllowsIndividualUriGrants() {
        val info = context.packageManager.resolveContentProvider(
            "${context.packageName}.fileprovider", PackageManager.GET_META_DATA)!!
        assertEquals(FileProvider::class.java.name, info.name)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertNotEquals(0, info.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
    }

    @Test fun chooserSharesOnlySelectedContentUriWithTemporaryReadAccess() {
        val chosen = fixture(File(root, "synthetic-selected.m4a"))
        val other = fixture(File(root, "synthetic-not-selected.m4a"))
        val chooser = RecordingIntents.share(context, chosen)
        val send = sendIntent(chooser)
        val uri = stream(send)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(context.getString(R.string.share_recording_title), chooser.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/mp4", send.type)
        assertNull(send.data)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(chooser.hasExtra(Intent.EXTRA_INITIAL_INTENTS))
        assertEquals(setOf(Intent.EXTRA_STREAM), send.extras!!.keySet())
        assertReadOnlySingleUri(send, uri)
        assertReadOnlySingleUri(chooser, uri)
        assertEquals(chosen.name, uri.lastPathSegment)
        assertNotEquals(other.name, uri.lastPathSegment)
    }

    @Test fun supportedExtensionsUseSpecificAudioMimeTypesIncludingUppercase() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            for ((extension, mime) in listOf("m4a" to "audio/mp4", "mp3" to "audio/mpeg",
                "wav" to "audio/wav", "ogg" to "audio/ogg", "M4A" to "audio/mp4")) {
                val file = fixture(File(root, "synthetic-mime.$extension"))
                assertTrue(RecordingIntents.isSupportedAudio(file))
                assertEquals(mime, sendIntent(RecordingIntents.share(context, file)).type)
                assertEquals(mime, RecordingIntents.play(context, file).type)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun playKeepsActionViewAndSameReadOnlyProviderUri() {
        val file = fixture(File(root, "synthetic-play.m4a"))
        val play = RecordingIntents.play(context, file)
        val uri = stream(sendIntent(RecordingIntents.share(context, file)))
        assertEquals(Intent.ACTION_VIEW, play.action)
        assertEquals("audio/mp4", play.type)
        assertEquals(uri, play.data)
        assertFalse(play.hasExtra(Intent.EXTRA_STREAM))
        assertReadOnlySingleUri(play, uri)
    }

    @Test fun realProviderReadsExactBytesAndExposesOnlySelectedFileMetadata() {
        val file = fixture(File(root, "synthetic-áudio com espaço.m4a"))
        val uri = stream(sendIntent(RecordingIntents.share(context, file)))
        val resolver = context.contentResolver
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        // FileProvider delegates this lookup to the platform MIME table. The
        // Robolectric fixture maps m4a to audio/mpeg; our outgoing Intent's
        // explicit, correct audio/mp4 MIME is asserted independently above.
        assertEquals(MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension), resolver.getType(uri))
        assertTrue(resolver.getType(uri)!!.startsWith("audio/"))
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(file.name, it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(file.length(), it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    private fun assertRejected(file: File) {
        // Both actions share the same boundary; neither may open a privileged path.
        for (action in listOf<(Context, File) -> Intent>(RecordingIntents::share, RecordingIntents::play)) {
            try {
                action(context, file)
                fail("Must reject ${file.name}")
            } catch (expected: IllegalArgumentException) {
                // Outside the supported, direct-child audio set.
            } catch (expected: java.io.IOException) {
                // Missing, empty, unreadable, or not a regular file.
            }
        }
    }

    @Test fun filesOutsideRecordingsAndSiblingPrefixDirectoriesAreRejected() {
        assertRejected(fixture(File(root.parentFile, "synthetic-outside.m4a")))
        val sibling = File(root.parentFile, "ShakeRecorder-other").also { it.mkdirs(); created += it }
        assertRejected(fixture(File(sibling, "synthetic-prefix.m4a")))
    }

    @Test fun traversalAndNestedRecordingsAreRejected() {
        val outside = fixture(File(root.parentFile, "synthetic-traversal.m4a"))
        assertRejected(File(root, "../${outside.name}"))
        val nested = File(root, "synthetic-subdirectory").also { it.mkdirs(); created += it }
        assertRejected(fixture(File(nested, "synthetic-nested.m4a")))
    }

    @Test fun symlinkEscapingRecordingsDirectoryIsRejected() {
        val outside = fixture(File(root.parentFile, "synthetic-symlink-target.m4a"))
        val link = File(root, "synthetic-link.m4a").also { created += it }
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertRejected(link)
        assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun updaterCacheIsNeverShareableEvenWithAudioExtension() {
        assertRejected(fixture(File(context.cacheDir, "updates/synthetic-update.apk")))
        assertRejected(fixture(File(context.cacheDir, "updates/synthetic-disguised.m4a")))
    }

    @Test fun missingEmptyUnsupportedAndDirectoriesAreRejected() {
        assertRejected(File(root, "synthetic-missing.m4a"))
        assertRejected(fixture(File(root, "synthetic-empty.m4a")).apply { writeBytes(byteArrayOf()) })
        assertRejected(fixture(File(root, "synthetic-document.txt")))
        assertRejected(fixture(File(root, "synthetic-payload.apk")))
        assertRejected(fixture(File(root, "synthetic-no-extension")))
        val directory = File(root, "synthetic-directory.m4a").also { it.mkdirs(); created += it }
        assertRejected(directory)
    }

    @Test fun preparingSharingAndPlaybackPreservesEverySettingAndRecordingByte() {
        SettingsManager(context).apply {
            shakeCount = 6
            volumeHoldDuration = 7
            volumePressCount = 4
            isVolumeHoldEnabled = true
        }
        val preferences = context.getSharedPreferences("shake_recorder_prefs", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val file = fixture(File(root, "synthetic-preserved.m4a"))
        repeat(3) {
            val chooser = RecordingIntents.share(context, file)
            RecordingIntents.play(context, file)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(stream(sendIntent(chooser)))!!.use { it.readBytes() })
        }
        assertEquals(before, preferences.all)
        assertArrayEquals(bytes, file.readBytes())
    }
}
