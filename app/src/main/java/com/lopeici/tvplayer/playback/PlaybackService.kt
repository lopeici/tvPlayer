package com.lopeici.tvplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lopeici.tvplayer.MainActivity
import com.lopeici.tvplayer.TvPlayerApp

/**
 * Publishes the app-scoped player as a media session: notification and lock-screen controls,
 * headset / remote media buttons, the Android TV now-playing card, and a foreground service that
 * keeps playback alive in the background. MainActivity connects a MediaController while started,
 * which is what starts this service; Media3 promotes it to the foreground while something plays.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    private val playerManager: PlayerManager
        get() = (application as TvPlayerApp).container.playerManager

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, playerManager.sessionPlayer)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: a cast keeps going (it's on the TV); local playback stops. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playerManager.isCasting.value) {
            playerManager.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Only the session goes: the player belongs to PlayerManager and outlives this service.
        session?.release()
        session = null
        super.onDestroy()
    }
}
