package com.example.proxybypass

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val default = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("=== ProxyBypass Crash Report ===")
                appendLine("Time   : $timestamp")
                appendLine("Thread : ${t.name}")
                appendLine("Device : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine()
                appendLine(sw.toString())
            }

            val file = File(context.getExternalFilesDir(null), "crash_$timestamp.txt")
            file.writeText(text)

            // Share intent fires in a new task so it survives the crash
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ProxyBypass crash $timestamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(share, "Send crash log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            Thread.sleep(2000) // give share sheet time to appear
        } catch (_: Exception) {}

        default?.uncaughtException(t, e)
    }
}
