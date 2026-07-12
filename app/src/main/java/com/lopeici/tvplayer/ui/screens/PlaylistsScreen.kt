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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Playlist
import com.lopeici.tvplayer.data.PlaylistSource
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.EmptyState
import com.lopeici.tvplayer.ui.components.TvTextField

@Composable
fun PlaylistsScreen(vm: TvViewModel, onImportFile: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeId by vm.activePlaylistId.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val castAsHls by vm.castAsHls.collectAsStateWithLifecycle()
    var showAddUrl by remember { mutableStateOf(false) }
    var epgFor by remember { mutableStateOf<Playlist?>(null) }
    var editing by remember { mutableStateOf<Playlist?>(null) }

    editing?.let { playlist ->
        // Full-screen show/hide editor replaces the tab content while open.
        PlaylistEditScreen(vm, playlist, onClose = { editing = null })
        return
    }

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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cast in HLS", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Casts each channel's .m3u8 version (needed for Chromecast); local playback is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = castAsHls, onCheckedChange = { vm.setCastAsHls(it) })
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
                        onEdit = { editing = playlist },
                        onRefresh = { vm.refreshPlaylist(playlist.id) },
                        onSetEpg = { epgFor = playlist },
                        onDelete = { vm.deletePlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (showAddUrl) {
        AddUrlDialog(
            onConfirm = { name, url, epgUrl ->
                vm.addUrlPlaylist(name, url, epgUrl)
                showAddUrl = false
            },
            onDismiss = { showAddUrl = false },
        )
    }

    epgFor?.let { playlist ->
        EpgUrlDialog(
            playlist = playlist,
            onSave = { epgUrl -> vm.setEpgUrl(playlist.id, epgUrl); epgFor = null },
            onRefresh = { vm.refreshEpg(playlist.id); epgFor = null },
            onDismiss = { epgFor = null },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onSetEpg: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val label = if (playlist.source == PlaylistSource.URL) playlist.uri else "Local file"
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playlist.epgUrl != null) {
                    Text(
                        "Guide enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit channels (show/hide)")
                }
                IconButton(onClick = onSetEpg) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "EPG / guide",
                        tint = if (playlist.epgUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        },
    )
}

@Composable
private fun AddUrlDialog(onConfirm: (String, String, String?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add playlist URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U URL") },
                    placeholder = { Text("http://...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text("EPG / XMLTV URL (optional)") },
                    placeholder = { Text("http://...xmltv.xml") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim(), epgUrl.trim().ifBlank { null }) },
                enabled = url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EpgUrlDialog(
    playlist: Playlist,
    onSave: (String?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var epgUrl by remember { mutableStateOf(playlist.epgUrl.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EPG for ${playlist.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste an XMLTV guide URL. Channels are matched to the guide by their tvg-id.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text("XMLTV URL") },
                    placeholder = { Text("http://...xmltv.xml(.gz)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (playlist.epgUrl != null) {
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("Refresh guide now")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(epgUrl.trim().ifBlank { null }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
