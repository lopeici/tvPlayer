package com.lopeici.tvplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(context, button) }
            }
        },
    )
}
