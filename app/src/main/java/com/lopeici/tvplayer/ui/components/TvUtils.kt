package com.lopeici.tvplayer.ui.components

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/** True when running on an Android TV / leanback device (no touchscreen, remote-driven). */
fun Context.isTelevision(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Whether the UI runs on a TV — computed once by the Activity and provided to the whole tree,
 * rather than each composable querying the PackageManager (a binder call) on recomposition.
 */
val LocalIsTelevision = staticCompositionLocalOf { false }
