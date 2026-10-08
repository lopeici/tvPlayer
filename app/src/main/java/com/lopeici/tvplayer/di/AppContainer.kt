package com.lopeici.tvplayer.di

import android.content.Context
import com.lopeici.tvplayer.data.TvRepository
import com.lopeici.tvplayer.playback.PlayerManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Lightweight manual dependency container (no Hilt). Holds app-scoped singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process, like the singletons below. */
    private val appScope = MainScope()

    /** Playlist/EPG downloads: patient timeouts, since guides can be tens of MB from slow servers. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Stream playback: same connection pool, but ExoPlayer's default 8s timeouts, so a stalled
     * stream errors quickly and the player's silent retry can recover it.
     */
    private val streamClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    val repository: TvRepository by lazy { TvRepository(appContext, httpClient) }

    /** Survives Activity recreation so playback (and an active cast session) is not torn down. */
    val playerManager: PlayerManager by lazy {
        PlayerManager(appContext, streamClient, castAsHls = repository.castAsHls).also(::recordRecents)
    }

    /**
     * Records every channel that comes on air — taps, next/previous, D-pad, number zapping, and the
     * media session (notification, headset, PiP) — app-wide, so zaps made with no Activity alive
     * still reach Recents and TV auto-resume.
     */
    private fun recordRecents(player: PlayerManager) {
        appScope.launch {
            player.current.filterNotNull()
                .distinctUntilChangedBy { it.key }
                .collect { repository.recordRecent(it) }
        }
    }
}
