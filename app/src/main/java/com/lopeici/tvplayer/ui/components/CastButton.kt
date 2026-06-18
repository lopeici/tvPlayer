package com.lopeici.tvplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * The standard Cast button. Hosts the View-based [MediaRouteButton] inside Compose, which is more
 * reliable than the newer Compose-only cast button. Tapping it opens the device chooser dialog
 * (which is why the host Activity uses an AppCompat theme).
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    // Casting from a TV makes no sense — the TV is the display.
    if (LocalContext.current.isTelevision()) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ctx).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, button) }
            }
        },
    )
}
