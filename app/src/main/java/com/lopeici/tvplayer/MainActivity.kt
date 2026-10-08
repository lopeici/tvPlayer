package com.lopeici.tvplayer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lopeici.tvplayer.playback.PlaybackService
import com.lopeici.tvplayer.ui.TvApp
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.LocalIsTelevision
import com.lopeici.tvplayer.ui.components.isTelevision
import com.lopeici.tvplayer.ui.theme.TvPlayerTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: TvViewModel by viewModels()
    private lateinit var openDocument: ActivityResultLauncher<Array<String>>

    private val isInPip = mutableStateOf(false)

    /** True when a channel is playing locally (not casting) so it can continue in PiP. */
    private var pipEligible = false
    private var pipAspect = Rational(16, 9)
    private var pipPlaying = false

    /** Connection to [PlaybackService] while started; connecting is what starts the service. */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Taps on the PiP window's play/pause and next-channel buttons. */
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_PLAY_PAUSE -> vm.togglePlayPause()
                ACTION_PIP_NEXT -> vm.zapNext()
            }
        }
    }

    private val isTelevision: Boolean by lazy { isTelevision() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFilePlaylist(queryDisplayName(uri) ?: getString(R.string.imported_playlist), uri.toString())
        }

        // Enable Picture-in-Picture auto-enter whenever a channel is open locally (not casting),
        // so leaving the app keeps the video in a floating window even through brief buffering.
        // The PiP window follows the video's real shape and shows play/pause + next channel.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(vm.isCasting, vm.currentChannel, vm.playerManager.videoSize, vm.isPlaying) { casting, channel, size, playing ->
                    pipEligible = channel != null && !casting && !isTelevision
                    pipAspect = size.toPipRational()
                    pipPlaying = playing
                }.collect { updatePipParams() }
            }
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter().apply { addAction(ACTION_PIP_PLAY_PAUSE); addAction(ACTION_PIP_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            TvPlayerTheme {
                CompositionLocalProvider(LocalIsTelevision provides isTelevision) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        TvApp(
                            vm = vm,
                            isInPip = isInPip.value,
                            onImportFile = { openDocument.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
        }
    }

    private fun hasPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspect)
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEligible).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Also refreshes the buttons/aspect of a PiP window that's already showing. */
    private fun updatePipParams() {
        if (!hasPip() || isTelevision) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipActions(): List<RemoteAction> = listOf(
        if (pipPlaying) {
            pipAction(android.R.drawable.ic_media_pause, R.string.pause, ACTION_PIP_PLAY_PAUSE, requestCode = 1)
        } else {
            pipAction(android.R.drawable.ic_media_play, R.string.play, ACTION_PIP_PLAY_PAUSE, requestCode = 1)
        },
        pipAction(android.R.drawable.ic_media_next, R.string.player_next_channel, ACTION_PIP_NEXT, requestCode = 2),
    )

    private fun pipAction(icon: Int, title: Int, action: String, requestCode: Int): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = getString(title)
        return RemoteAction(Icon.createWithResource(this, icon), label, label, intent)
    }

    /**
     * Video aspect (incl. non-square pixels). PiP rejects anything outside 1:2.39…2.39:1, and the
     * system compares as floats, so out-of-range videos get a bound pulled slightly inside
     * (2.38) rather than one that could round to just past the limit.
     */
    private fun VideoSize.toPipRational(): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val displayWidth = Math.round(width * pixelWidthHeightRatio)
        val ratio = displayWidth.toFloat() / height
        return when {
            ratio < 1 / 2.38f -> Rational(100, 238)
            ratio > 2.38f -> Rational(238, 100)
            else -> Rational(displayWidth, height)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pre-Android 12 has no auto-enter; enter PiP manually when leaving while playing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipEligible && hasPip()) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onStart() {
        super.onStart()
        controllerFuture = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
        ).buildAsync()
        vm.refreshIfStale()
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Playback carries on without the controller: the service stays in the foreground while
        // something plays.
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        // On TV there is no PiP or casting, so leaving the app (Home / backing out) would keep the
        // stream playing audio in the background. Kill playback entirely; the app reopens idle.
        // Config changes don't pass through here (the Activity handles them via configChanges).
        if (isTelevision) vm.stop()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip.value = isInPictureInPictureMode
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()

    private companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.lopeici.tvplayer.PIP_PLAY_PAUSE"
        const val ACTION_PIP_NEXT = "com.lopeici.tvplayer.PIP_NEXT"
    }
}
