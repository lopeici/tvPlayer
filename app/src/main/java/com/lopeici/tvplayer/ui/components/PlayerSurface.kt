package com.lopeici.tvplayer.ui.components

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.lopeici.tvplayer.R

/**
 * Hosts a Media3 [PlayerView] inflated from XML so it uses a TextureView surface
 * (renders correctly on the emulator; SurfaceView overlays often show green there).
 * No built-in controller — the player screen draws its own overlay.
 */
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                this.player = player
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}
