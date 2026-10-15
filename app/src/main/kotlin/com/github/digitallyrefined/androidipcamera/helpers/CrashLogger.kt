package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A log that survives the process dying, so a crash/kill that happened with nobody watching the
 * phone is still diagnosable afterwards (fetch it via GET /control/logs once the server is back).
 *
 * logcat alone isn't enough for this: its buffer is lost the moment the process dies, and on a
 * background service that nobody is tailing live, the only way to see what led up to a death is
 * something that was already flushed to disk *before* it happened.
 *
 * Deliberately simple - a single capped plain-text file, synchronously appended (one write per
 * line, immediately flushed) so a line is only "logged" once it's actually durable. No rotation,
 * no background writer thread that could itself get stuck. [MAX_BYTES] keeps it from growing
 * forever on a long-running service; once over the cap, it's rewritten keeping only the tail.
 */
object CrashLogger {
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 512 * 1024L
    private const val TRIM_TO_BYTES = 256 * 1024L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null
    private val lock = Any()

    /** Call once, as early as possible (Application.onCreate) - installs the crash handler too. */
    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.filesDir, FILE_NAME)
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                line("FATAL", "Uncaught exception on thread '${thread.name}':\n$sw")
            } catch (_: Throwable) {
                // Logging the crash must never prevent the crash from being handled normally.
            }
            // Chain to whatever was there before (Android's own crash reporting/dialog), so this
            // never changes the app's actual crash behavior - only adds a durable record of it.
            previousHandler?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(10)
        }
        line("CrashLogger", "Logging initialized (pid=${android.os.Process.myPid()})")
    }

    /** Appends one timestamped line, tagged like logcat. Safe to call before [init] (no-ops). */
    fun line(tag: String, message: String) {
        val file = logFile ?: return
        val entry = "${dateFormat.format(Date())} [$tag] $message\n"
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) trimToTail(file)
                file.appendText(entry)
            } catch (e: Exception) {
                Log.e("CrashLogger", "Failed to write crash log: ${e.message}")
            }
        }
    }

    /** Keeps only the most recent [TRIM_TO_BYTES] of the file, dropping whole lines from the front. */
    private fun trimToTail(file: File) {
        try {
            val text = file.readText()
            val startAt = (text.length - TRIM_TO_BYTES).coerceAtLeast(0).toInt()
            val cut = text.indexOf('\n', startAt).let { if (it == -1) startAt else it + 1 }
            file.writeText("--- earlier log trimmed ---\n" + text.substring(cut))
        } catch (_: Exception) {
            // If trimming itself fails, just let the next appendText keep growing the file rather
            // than lose everything to a delete-and-rewrite that half-failed.
        }
    }

    /** Current log contents for the /control/logs endpoint, or null if nothing has been logged yet. */
    fun read(): String? {
        val file = logFile ?: return null
        return try {
            synchronized(lock) { if (file.exists()) file.readText() else null }
        } catch (e: Exception) {
            "Failed to read crash log: ${e.message}"
        }
    }
}
