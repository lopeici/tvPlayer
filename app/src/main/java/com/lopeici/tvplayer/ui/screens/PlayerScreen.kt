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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.lopeici.tvplayer.R
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.CastButton
import com.lopeici.tvplayer.ui.components.LocalIsTelevision
import com.lopeici.tvplayer.ui.components.PlayerSurface
import com.lopeici.tvplayer.ui.components.findActivity
import com.lopeici.tvplayer.ui.components.formatClock
import com.lopeici.tvplayer.ui.components.timeRange
import java.util.Locale
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
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val resizeMode by vm.resizeMode.collectAsStateWithLifecycle()
    val nowProg = nowNext.first
    val nextProg = nowNext.second

    var controlsVisible by remember { mutableStateOf(true) }
    var wakeNonce by remember { mutableStateOf(0) }
    var showJump by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val scrim = Color.Black.copy(alpha = 0.45f)

    val isTv = LocalIsTelevision.current
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
    // park focus on the root surface so remote keys land here (see onPreviewKeyEvent). Full-screen
    // only — in the windowed pane this would steal focus from the channel list.
    LaunchedEffect(isTv, fullScreen, controlsVisible, current?.key) {
        if (!isTv || !fullScreen || current == null) return@LaunchedEffect
        if (controlsVisible) {
            repeat(8) {
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(60)
            }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    PlayerWindowEffects(
        hideBars = fullScreen,
        keepAwake = isPlaying || playbackState == Player.STATE_BUFFERING,
    )

    // In the side pane, show a hint until something is playing.
    if (!fullScreen && current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.player_pick_channel),
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
                    // Classic TV zapping: with the controls hidden, up/down change channel directly.
                    Key.DirectionUp -> if (!controlsVisible) { vm.zapNext(); true } else { wake(); false }
                    Key.DirectionDown -> if (!controlsVisible) { vm.zapPrevious(); true } else { wake(); false }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter -> {
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
        PlayerSurface(
            player = vm.playerManager.player,
            modifier = Modifier.fillMaxSize(),
            resizeMode = resizeMode,
        )

        if (playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        if (error != null || playbackState == Player.STATE_ENDED) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error ?: stringResource(R.string.player_stream_ended), color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.retry() }) { Text(stringResource(R.string.action_refresh)) }
                    TextButton(onClick = { vm.zapNext() }) { Text(stringResource(R.string.player_next_channel)) }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
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
                            stringResource(R.string.player_now, it.title),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    nextProg?.let {
                        Text(
                            stringResource(R.string.player_next, formatClock(it.start), it.title),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isCasting) {
                        Text(stringResource(R.string.player_casting), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (onToggleList != null) {
                    IconButton(onClick = onToggleList) {
                        Icon(
                            if (listVisible) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                            contentDescription = stringResource(if (listVisible) R.string.player_hide_list else R.string.player_show_list),
                            tint = Color.White,
                        )
                    }
                }
                if (onToggleFullScreen != null) {
                    IconButton(onClick = onToggleFullScreen, modifier = Modifier.tvFocusHighlight()) {
                        Icon(
                            if (fullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = stringResource(if (fullScreen) R.string.player_exit_full_screen else R.string.player_full_screen),
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
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous_channel), tint = Color.White)
                }
                IconButton(
                    onClick = { vm.togglePlayPause() },
                    modifier = Modifier.focusRequester(playFocus).tvFocusHighlight(),
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.play_pause),
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
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop), tint = Color.White)
                }
                IconButton(onClick = { vm.zapNext() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next_channel), tint = Color.White)
                }
                IconButton(onClick = { vm.retry() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.player_refresh_channel), tint = Color.White)
                }
                IconButton(
                    onClick = { current?.let { vm.toggleFavorite(it) } },
                    modifier = Modifier.tvFocusHighlight(),
                ) {
                    val isFav = current?.key?.let { it in favorites } == true
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (isFav) R.string.remove_favorite else R.string.add_favorite),
                        tint = if (isFav) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                // Only offered when there is something to choose (several audio tracks or subtitles).
                if (audioGroups.sumOf { it.length } > 1 || textGroups.isNotEmpty()) {
                    IconButton(onClick = { showTracks = true }, modifier = Modifier.tvFocusHighlight()) {
                        Icon(Icons.Filled.Subtitles, contentDescription = stringResource(R.string.audio_subtitles), tint = Color.White)
                    }
                }
                IconButton(onClick = { vm.cycleResizeMode() }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(
                        Icons.Filled.AspectRatio,
                        contentDescription = stringResource(
                            when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.player_scaling_zoom
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.player_scaling_stretch
                                else -> R.string.player_scaling_fit
                            },
                        ),
                        tint = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                IconButton(onClick = { showGuide = true }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.tv_guide), tint = Color.White)
                }
                IconButton(onClick = { showJump = true }, modifier = Modifier.tvFocusHighlight()) {
                    Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.go_to_channel_number), tint = Color.White)
                }
            }
            }
        }
    }

    if (showTracks) {
        TrackSelectionDialog(
            audioGroups = audioGroups,
            textGroups = textGroups,
            onSelectTrack = { group, index -> vm.selectTrack(group, index) },
            onAutoAudio = { vm.autoAudio() },
            onSubtitlesOff = { vm.disableSubtitles() },
            onDismiss = { showTracks = false },
        )
    }

    if (showJump) {
        ChannelNumberDialog(
            onConfirm = { number -> vm.jumpToNumber(number); showJump = false },
            onDismiss = { showJump = false },
        )
    }

    if (showGuide) {
        val schedule = remember(current?.key) { vm.scheduleFor(current?.epgKey) }
        ModalBottomSheet(onDismissRequest = { showGuide = false }) {
            Text(
                text = current?.name ?: stringResource(R.string.tv_guide),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (schedule.isEmpty()) {
                Text(
                    stringResource(R.string.no_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(schedule) { p ->
                        // Rows are focusable so the D-pad can scroll the schedule on TV; the
                        // focused row is tinted since there's no other focus indication.
                        var focused by remember { mutableStateOf(false) }
                        ListItem(
                            modifier = Modifier
                                .onFocusChanged { focused = it.isFocused }
                                .focusable(),
                            colors = ListItemDefaults.colors(
                                containerColor = if (focused) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    ListItemDefaults.containerColor
                                },
                            ),
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

/** Picker for the stream's audio languages and subtitle tracks. Rows are focusable for TV. */
@Composable
private fun TrackSelectionDialog(
    audioGroups: List<Tracks.Group>,
    textGroups: List<Tracks.Group>,
    onSelectTrack: (Tracks.Group, Int) -> Unit,
    onAutoAudio: () -> Unit,
    onSubtitlesOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_subtitles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (audioGroups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.audio),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    TrackRow(stringResource(R.string.track_auto), selected = false) { onAutoAudio(); onDismiss() }
                    audioGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            if (!group.isTrackSupported(i)) continue
                            TrackRow(group.getTrackFormat(i).trackLabel(stringResource(R.string.track_number, i + 1)), group.isTrackSelected(i)) {
                                onSelectTrack(group, i); onDismiss()
                            }
                        }
                    }
                }
                if (textGroups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.subtitles),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    val anyTextSelected = textGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
                    TrackRow(stringResource(R.string.track_off), selected = !anyTextSelected) { onSubtitlesOff(); onDismiss() }
                    textGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            if (!group.isTrackSupported(i)) continue
                            TrackRow(group.getTrackFormat(i).trackLabel(stringResource(R.string.track_number, i + 1)), group.isTrackSelected(i)) {
                                onSelectTrack(group, i); onDismiss()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Human-readable track name: explicit label, else the language name, else a numbered fallback. */
private fun Format.trackLabel(fallback: String): String {
    label?.takeIf { it.isNotBlank() }?.let { return it }
    language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let { lang ->
        val display = Locale.forLanguageTag(lang).displayLanguage
        if (display.isNotBlank()) return display.replaceFirstChar { it.uppercase() }
        return lang
    }
    return fallback
}

@Composable
private fun ChannelNumberDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_channel)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.channel_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) { Text(stringResource(R.string.action_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Keeps the screen on while a channel is playing/buffering (not while idle on a list, so the
 * screensaver can start); hides the system bars only in full-screen.
 */
@Composable
private fun PlayerWindowEffects(hideBars: Boolean, keepAwake: Boolean) {
    val view = LocalView.current
    DisposableEffect(hideBars, keepAwake) {
        val window = view.context.findActivity()?.window
        if (keepAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
