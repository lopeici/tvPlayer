package com.lopeici.tvplayer.ui.components

import android.os.Build
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.lopeici.tvplayer.R

/** True on the Android emulator, where SurfaceView overlays often render green/garbled. */
private val isEmulator: Boolean by lazy {
    Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("Android SDK built for") || Build.PRODUCT.contains("sdk_gphone")
}

/**
 * Hosts a Media3 [PlayerView] inflated from XML. Real devices get a SurfaceView surface
 * (cheaper composition, HDR passthrough); the emulator keeps TextureView, which is what
 * renders correctly there. No built-in controller — the player screen draws its own overlay.
 */
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val layout = if (isEmulator) R.layout.player_view else R.layout.player_view_surface
            (LayoutInflater.from(context).inflate(layout, null) as PlayerView).apply {
                this.player = player
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}
