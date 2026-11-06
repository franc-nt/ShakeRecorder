package com.shakerecorder

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Locale

/** Gives the chosen Android receiver temporary read access to one existing audio file. */
internal object RecordingIntents {
    private val audioTypes = mapOf(
        "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg"
    )

    class ActiveRecordingException : IllegalStateException()

    fun recordingsDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "ShakeRecorder"
    )

    fun isSupportedAudio(file: File): Boolean = audioTypes.containsKey(file.extension.lowercase(Locale.ROOT))

    private fun readableRecording(file: File): File {
        val root = recordingsDirectory().canonicalFile
        val recording = file.canonicalFile
        // FileProvider also exposes the updater cache. Do not let this feature share
        // that cache, parent paths, subdirectories, or symlinks escaping the audio folder.
        require(recording.parentFile == root && isSupportedAudio(recording)) { "Not a recording" }
        val activePath = RecorderService.activeRecordingPath
        if (RecorderService.isRecording &&
            (activePath == null || File(activePath).canonicalFile == recording)) throw ActiveRecordingException()
        if (!recording.isFile || !recording.canRead() || recording.length() == 0L) {
            throw IOException("Recording is missing or unreadable")
        }
        return recording
    }

    private fun audioIntent(context: Context, file: File, action: String): Intent {
        val recording = readableRecording(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", recording)
        val mime = audioTypes.getValue(recording.extension.lowercase(Locale.ROOT))
        return Intent(action).apply {
            if (action == Intent.ACTION_SEND) {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            } else setDataAndType(uri, mime)
            clipData = ClipData.newUri(context.contentResolver, recording.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun play(context: Context, file: File): Intent = audioIntent(context, file, Intent.ACTION_VIEW)

    fun share(context: Context, file: File): Intent {
        val send = audioIntent(context, file, Intent.ACTION_SEND)
        return Intent.createChooser(send, context.getString(R.string.share_recording_title)).apply {
            // Propagate the one-URI, read-only grant through the Android sharesheet.
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
