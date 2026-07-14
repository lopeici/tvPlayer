package com.lopeici.tvplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lopeici.tvplayer.ui.TvApp
import com.lopeici.tvplayer.ui.TvViewModel
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

    private val isTelevision: Boolean by lazy { isTelevision() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFilePlaylist(queryDisplayName(uri) ?: "Imported playlist", uri.toString())
        }

        // Enable Picture-in-Picture auto-enter whenever a channel is open locally (not casting),
        // so leaving the app keeps the video in a floating window even through brief buffering.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.isCasting, vm.currentChannel) { casting, channel ->
                    channel != null && !casting && !isTelevision
                }.collect { eligible ->
                    pipEligible = eligible
                    updatePipParams(eligible)
                }
            }
        }

        setContent {
            TvPlayerTheme {
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

    private fun hasPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun updatePipParams(autoEnter: Boolean) {
        if (!hasPip() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(autoEnter)
            .setSeamlessResizeEnabled(true)
            .build()
        runCatching { setPictureInPictureParams(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pre-Android 12 has no auto-enter; enter PiP manually when leaving while playing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipEligible && hasPip()) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
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
}
