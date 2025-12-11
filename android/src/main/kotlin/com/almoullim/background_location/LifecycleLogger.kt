package com.almoullim.background_location

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object LifecycleLogger {

    var enabled: Boolean = false

    private fun getLogFile(context: Context): File? {
        try {
            // Use the same directory as Flutter's getApplicationDocumentsDirectory()
            val directory = context.filesDir

            // Find the most recent oetx_log file
            val logFiles = directory.listFiles { file ->
                file.name.startsWith("oetx_log_android") && file.name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() }

            // Return the most recent one, or create a new one if none exist
            return if (logFiles?.isNotEmpty() == true) {
                logFiles.first()
            } else {
                // Create a new file with the same naming pattern as Dart
                val dateTime = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                File(directory, "oetx_log_android_$dateTime.txt")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun log(context: Context, message: String) {
        if (!enabled) return

        try {
            val file = getLogFile(context) ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            // Match the Dart log format: timestamp - functionName - message
            // For Android, we'll use "ANDROID" as the function identifier
            val logMessage = "$timestamp - ANDROID - [LIFECYCLE] $message"

            FileWriter(file, true).use { writer ->
                writer.append("$logMessage\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}