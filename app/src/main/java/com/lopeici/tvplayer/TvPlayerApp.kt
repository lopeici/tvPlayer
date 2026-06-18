package com.lopeici.tvplayer

import android.app.Application
import com.lopeici.tvplayer.di.AppContainer

class TvPlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
    }

    /** Persists uncaught-exception stack traces to filesDir/crash_log.txt for later diagnosis. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash_log.txt")
                    .writeText("Crashed on thread '${thread.name}':\n\n$sw")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
