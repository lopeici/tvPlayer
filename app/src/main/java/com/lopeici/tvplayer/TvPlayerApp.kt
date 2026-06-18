package com.lopeici.tvplayer

import android.app.Application
import com.lopeici.tvplayer.di.AppContainer

class TvPlayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
