package com.lopeici.tvplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Playlist
import com.lopeici.tvplayer.data.PlaylistSource
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.EmptyState

@Composable
fun PlaylistsScreen(vm: TvViewModel, onImportFile: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeId by vm.activePlaylistId.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAddUrl by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { showAddUrl = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add URL")
            }
            OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text("Import file")
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        error?.let { message ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
                }
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                "No playlists",
                "Add an M3U URL or import an .m3u file to load your channels.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        isActive = playlist.id == activeId,
                        onActivate = { vm.setActivePlaylist(playlist.id) },
                        onRefresh = { vm.refreshPlaylist(playlist.id) },
                        onDelete = { vm.deletePlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (showAddUrl) {
        AddUrlDialog(
            onConfirm = { name, url ->
                vm.addUrlPlaylist(name, url)
                showAddUrl = false
            },
            onDismiss = { showAddUrl = false },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    isActive: Boolean,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val label = if (playlist.source == PlaylistSource.URL) playlist.uri else "Local file"
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            IconButton(onClick = onActivate) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = if (isActive) "Active" else "Set active",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        },
    )
}

@Composable
private fun AddUrlDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add playlist URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U URL") },
                    placeholder = { Text("http://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), url.trim()) }, enabled = url.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
