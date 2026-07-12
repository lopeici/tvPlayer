package com.lopeici.tvplayer.ui.screens

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.CastButton
import com.lopeici.tvplayer.ui.components.isTelevision
import com.lopeici.tvplayer.ui.components.PlayerSurface
import com.lopeici.tvplayer.ui.components.findActivity
import com.lopeici.tvplayer.ui.components.formatClock
import com.lopeici.tvplayer.ui.components.timeRange
import kotlinx.coroutines.delay

/** Full-screen player route (compact / folded layout). */
@Composable
fun PlayerScreen(vm: TvViewModel, onBack: () -> Unit) {
    PlayerContent(vm, fullScreen = true, onBack = onBack)
}

/**
 * The player UI. [fullScreen] = true drives the immersive full-screen route (hidden system bars,
 * a back button); false is the side pane used in the wide / unfolded two-pane layout.
 */
@Composable
fun PlayerContent(
    vm: TvViewModel,
    fullScreen: Boolean,
    onBack: (() -> Unit)? = null,
    onToggleFullScreen: (() -> Unit)? = null,
    onToggleList: (() -> Unit)? = null,
    listVisible: Boolean = true,
) {
    val current by vm.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isCasting by vm.isCasting.collectAsStateWithLifecycle()
    val playbackState by vm.playerManager.playbackState.collectAsStateWithLifecycle()
    val error by vm.playerError.collectAsStateWithLifecycle()
    val nowNext by vm.currentNowNext.collectAsStateWithLifecycle()
    val nowProg = nowNext.first
    val nextProg = nowNext.second

    var controlsVisible by remember { mutableStateOf(true) }
    var wakeNonce by remember { mutableStateOf(0) }
    var showJump by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val scrim = Color.Black.copy(alpha = 0.45f)

    val context = LocalContext.current
    val isTv = remember { context.isTelevision() }
    val interaction = remember { MutableInteractionSource() }
    val playFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    // Reveal the controls and reset the idle timer — called on any tap / D-pad key / pointer move.
    val wake: () -> Unit = { controlsVisible = true; wakeNonce++ }

    // Auto-hide the controls after 5s idle. Any wake() bumps wakeNonce, which restarts this timer.
    LaunchedEffect(controlsVisible, wakeNonce, current?.key) {
        if (controlsVisible && current != null) {
            delay(5_000)
            controlsVisible = false
        }
    }

    // Briefly show the controls (channel name / guide) whenever the channel changes.
    LaunchedEffect(current?.key) { if (current != null) wake() }

    // On a TV there is no touch: while the controls are visible keep D-pad focus on them; while hidden,
    // park focus on the root surface so the first remote key press brings them back (see onPreviewKeyEvent).
    LaunchedEffect(isTv, controlsVisible, current?.key) {
        if (!isTv || current == null) return@LaunchedEffect
        if (controlsVisible) {
            repeat(8) {
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(60)
            }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    PlayerWindowEffects(hideBars = fullScreen)

    // In the side pane, show a hint until something is playing.
    if (!fullScreen && current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                "Pick a channel to start watching",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    // Wake the controls on pointer movement (mouse / air-mouse); taps and D-pad are handled below.
    val pointerWake = Modifier.pointerInput(current?.key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) wake()
            }
        }
    }

    // Touch: tap toggles the controls. TV: a focusable surface catches D-pad keys even while the
    // controls are hidden, so the first press reveals them (and is consumed) rather than being lost.
    // TV remote media keys (play/pause, stop, next/previous, channel up/down) act directly.
    val platformInteraction = if (isTv) {
        Modifier
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || current == null) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        vm.togglePlayPause(); wake(); true
                    }
                    Key.MediaStop -> {
                        vm.stop()
                        if (fullScreen) (onBack ?: onToggleFullScreen)?.invoke()
                        true
                    }
                    Key.MediaNext, Key.ChannelUp -> { vm.zapNext(); wake(); true }
                    Key.MediaPrevious, Key.ChannelDown -> { vm.zapPrevious(); wake(); true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter,
                    -> {
                        val wasHidden = !controlsVisible
                        wake()
                        wasHidden   // consume only when the press merely revealed hidden controls
                    }
                    else -> false   // never swallow Back etc. — let the system handle them
                }
            }
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null) {
            if (controlsVisible) controlsVisible = false else wake()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(pointerWake)
            .then(platformInteraction),
    ) {
        PlayerSurface(player = vm.playerManager.player, modifier = Modifier.fillMaxSize())

        if (playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        if (error != null || playbackState == Player.STATE_ENDED) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error ?: "This channel stopped transmitting.", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.retry() }) { Text("Refresh") }
                    TextButton(onClick = { vm.zapNext() }) { Text("Next channel") }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(scrim)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        current?.name ?: "—",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    nowProg?.let {
                        Text(
                            "Now · ${it.title}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    nextProg?.let {
                        Text(
                            "Next · ${formatClock(it.start)}  ${it.title}",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isCasting) {
                        Text("Casting to TV", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (onToggleList != null) {
                    IconButton(onClick = onToggleList) {
                        Icon(
                            if (listVisible) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                            contentDescription = if (listVisible) "Hide channel list" else "Show channel list",
                            tint = Color.White,
                        )
                    }
                }
                if (onToggleFullScreen != null) {
                    IconButton(onClick = onToggleFullScreen, modifier = Modifier.tvFocusHighlight()) {
                        Icon(
                            if (fullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (fullScreen) "Exit full screen" else "Full screen",
                            tint = Color.White,
                        )
                    }
                }
                CastButton()
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(scrim)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.zapPrevious() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous channel", tint = Color.White)
                }
                IconButton(
                    onClick = { vm.togglePlayPause() },
                    modifier = Modifier.focusRequester(playFocus).tvFocusHighlight(),
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        vm.stop()
                        // Nothing is playing anymore: leave the full-screen player (back on the
                        // phone route; back to the two-pane layout on tablet).
                        if (fullScreen) (onBack ?: onToggleFullScreen)?.invoke()
                    },
                    modifier = Modifier.tvFocusHighlight(),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color.White)
                }
                IconButton(onClick = { vm.zapNext() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next channel", tint = Color.White)
                }
                IconButton(onClick = { vm.retry() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh channel", tint = Color.White)
                }
                IconButton(onClick = { showGuide = true }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Schedule, contentDescription = "TV guide", tint = Color.White)
                }
                IconButton(onClick = { showJump = true }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Dialpad, contentDescription = "Go to channel number", tint = Color.White)
                }
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

    if (showGuide) {
        val schedule = remember(current?.key) { vm.scheduleFor(current?.tvgId) }
        ModalBottomSheet(onDismissRequest = { showGuide = false }) {
            Text(
                text = current?.name ?: "TV guide",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (schedule.isEmpty()) {
                Text(
                    "No guide available for this channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(schedule) { p ->
                        ListItem(
                            overlineContent = { Text(p.timeRange()) },
                            headlineContent = { Text(p.title) },
                            supportingContent = p.desc?.let {
                                { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
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

/** Keeps the screen on while the player is shown; hides the system bars only in full-screen. */
@Composable
private fun PlayerWindowEffects(hideBars: Boolean) {
    val view = LocalView.current
    DisposableEffect(hideBars) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (hideBars) {
            controller?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (hideBars) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** A visible focus ring for D-pad (TV) navigation; invisible on touch, where nothing holds focus. */
@Composable
private fun Modifier.tvFocusHighlight(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .background(
            color = if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            shape = CircleShape,
        )
}
