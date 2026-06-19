package com.pcmobilelink.nearshare.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NearShareDiagnostics {
    private const val TAG = "NearShare"
    private const val FILE_NAME = "nearshare-diagnostics.log"
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(context: Context, message: String) {
        Log.i(TAG, message)
        append(context, "I", message)
    }

    fun warn(context: Context, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
        append(context, "W", message + throwable?.message?.let { ": $it" }.orEmpty())
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun append(context: Context, level: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = file(context)
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.writeText("")
                }
                file.appendText("${timestampFormat.format(Date())} $level $message\n")
            }
        }
    }
}
