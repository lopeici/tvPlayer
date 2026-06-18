package com.lopeici.tvplayer.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.CastButton
import com.lopeici.tvplayer.ui.components.PlayerSurface
import com.lopeici.tvplayer.ui.components.findActivity

@Composable
fun PlayerScreen(vm: TvViewModel, onBack: () -> Unit) {
    val current by vm.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isCasting by vm.isCasting.collectAsStateWithLifecycle()
    val playbackState by vm.playerManager.playbackState.collectAsStateWithLifecycle()
    val error by vm.playerError.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var showJump by remember { mutableStateOf(false) }
    val scrim = Color.Black.copy(alpha = 0.45f)

    KeepScreenOnImmersive()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controlsVisible = !controlsVisible },
    ) {
        PlayerSurface(player = vm.playerManager.player, modifier = Modifier.fillMaxSize())

        if (playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error!!, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.retry() }) { Text("Retry") }
                    TextButton(onClick = { vm.zapNext() }) { Text("Next channel") }
                }
            }
        }

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(scrim)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(current?.name ?: "—", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (isCasting) {
                        Text("Casting to TV", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                CastButton()
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(scrim)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.zapPrevious() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous channel", tint = Color.White)
                }
                IconButton(onClick = { vm.togglePlayPause() }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { vm.zapNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next channel", tint = Color.White)
                }
                IconButton(onClick = { showJump = true }) {
                    Icon(Icons.Filled.Dialpad, contentDescription = "Go to channel number", tint = Color.White)
                }
            }
        }
    }

    if (showJump) {
        ChannelNumberDialog(
            onConfirm = { number -> vm.jumpToNumber(number); showJump = false },
            onDismiss = { showJump = false },
        )
    }
}

@Composable
private fun ChannelNumberDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to channel") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
                label = { Text("Channel number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hides system bars and keeps the screen on while the player is on screen; restores on exit. */
@Composable
private fun KeepScreenOnImmersive() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
