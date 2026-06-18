package com.lopeici.tvplayer.di

import android.content.Context
import com.lopeici.tvplayer.data.TvRepository
import com.lopeici.tvplayer.playback.PlayerManager

/** Lightweight manual dependency container (no Hilt). Holds app-scoped singletons. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val repository: TvRepository by lazy { TvRepository(appContext) }

    /** Survives Activity recreation so playback (and an active cast session) is not torn down. */
    val playerManager: PlayerManager by lazy { PlayerManager(appContext) }
}
