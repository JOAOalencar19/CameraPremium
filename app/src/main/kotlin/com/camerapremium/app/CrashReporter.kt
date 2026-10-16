package com.camerapremium.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the stack trace of any uncaught exception (any thread) so the next launch can show it.
 * Lets a crash be diagnosed from the phone itself, without adb.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("CameraPremium - falha em ").append(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())).append('\n')
                    append("Aparelho: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" (").append(Build.DEVICE).append("), Android ").append(Build.VERSION.RELEASE)
                    append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(sw.toString())
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        return if (f.exists()) try { f.readText() } catch (_: Throwable) { null } else null
    }

    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }
}
