package com.shakerecorder

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }

        fun getLogFile(): File {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ShakeRecorder"
            )
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "crash_log.txt")
        }

        fun getLastCrash(): String? {
            val file = getLogFile()
            return if (file.exists()) file.readText() else null
        }

        fun clearLog() {
            getLogFile().delete()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val log = """
                |=== CRASH LOG ===
                |Time: $timestamp
                |Thread: ${thread.name}
                |
                |Exception: ${throwable.javaClass.name}
                |Message: ${throwable.message}
                |
                |Stack Trace:
                |$sw
            """.trimMargin()

            getLogFile().writeText(log)

            // Also save to shared prefs for quick access
            context.getSharedPreferences("crash", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash", log)
                .apply()

        } catch (e: Exception) {
            e.printStackTrace()
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}
