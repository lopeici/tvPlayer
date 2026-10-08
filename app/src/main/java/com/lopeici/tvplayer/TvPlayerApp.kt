package com.lopeici.tvplayer

import android.app.Application
import android.os.Build
import com.lopeici.tvplayer.data.TvRepository
import com.lopeici.tvplayer.di.AppContainer

class TvPlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
        // Personal flavor only: seed the built-in playlist on first launch (no-op when unset).
        container.repository.seedIfNeeded(BuildConfig.SEED_PLAYLIST_NAME, BuildConfig.SEED_PLAYLIST_URL)
    }

    /** Persists uncaught-exception stack traces to filesDir/crash_log.txt for later diagnosis. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val header = "KaboomIPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, " +
                    "${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}), Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Time: ${java.time.Instant.now()}\n"
                java.io.File(filesDir, TvRepository.CRASH_LOG_FILE)
                    .writeText("${header}Crashed on thread '${thread.name}':\n\n$sw")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
