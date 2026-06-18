package com.lopeici.tvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lopeici.tvplayer.ui.TvApp
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.theme.TvPlayerTheme

class MainActivity : AppCompatActivity() {

    private val vm: TvViewModel by viewModels()
    private lateinit var openDocument: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addFilePlaylist(queryDisplayName(uri) ?: "Imported playlist", uri.toString())
        }

        setContent {
            TvPlayerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TvApp(vm = vm, onImportFile = { openDocument.launch(arrayOf("*/*")) })
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
