package com.lopeici.tvplayer.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.R
import com.lopeici.tvplayer.data.EpgMode
import com.lopeici.tvplayer.data.Playlist
import com.lopeici.tvplayer.data.PlaylistSource
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.EmptyState
import com.lopeici.tvplayer.ui.components.LocalIsTelevision
import com.lopeici.tvplayer.ui.components.TvTextField

@Composable
fun PlaylistsScreen(vm: TvViewModel, onImportFile: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val activeId by vm.activePlaylistId.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val castAsHls by vm.castAsHls.collectAsStateWithLifecycle()
    val crashLog by vm.crashLog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddUrl by remember { mutableStateOf(false) }
    var epgFor by remember { mutableStateOf<Playlist?>(null) }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

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
                Text(stringResource(R.string.add_url))
            }
            OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text(stringResource(R.string.import_file))
            }
        }

        // Casting doesn't exist on a TV (the TV is the display), so hide the cast setting there.
        if (!LocalIsTelevision.current) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cast_hls_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.cast_hls_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = castAsHls, onCheckedChange = { vm.setCastAsHls(it) })
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        error?.let { message ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }

        crashLog?.let { log ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.crash_log_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.crash_log_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = { vm.clearCrashLog() }) { Text(stringResource(R.string.action_delete)) }
                        TextButton(onClick = { shareCrashLog(context, log) }) { Text(stringResource(R.string.action_share)) }
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                stringResource(R.string.empty_playlists_title),
                stringResource(R.string.empty_playlists_body),
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
                        onDelete = { deleting = playlist },
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
            onSave = { mode, customUrl -> vm.setEpgMode(playlist.id, mode, customUrl); epgFor = null },
            onRefresh = { vm.refreshEpg(playlist.id); epgFor = null },
            onDismiss = { epgFor = null },
        )
    }

    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text(stringResource(R.string.delete_playlist_body, playlist.name)) },
            confirmButton = {
                TextButton(onClick = { vm.deletePlaylist(playlist.id); deleting = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
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
                val label = if (playlist.source == PlaylistSource.URL) playlist.uri else stringResource(R.string.local_file)
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playlist.epgUrl != null) {
                    Text(
                        stringResource(R.string.guide_enabled),
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
                    contentDescription = stringResource(if (isActive) R.string.playlist_active else R.string.playlist_set_active),
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_channels))
                }
                IconButton(onClick = onSetEpg) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = stringResource(R.string.epg_guide),
                        tint = if (playlist.epgUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
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
        title = { Text(stringResource(R.string.add_playlist_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.m3u_url)) },
                    placeholder = { Text("http://...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text(stringResource(R.string.epg_url_optional)) },
                    placeholder = { Text("http://...xmltv.xml") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim(), epgUrl.trim().ifBlank { null }) },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EpgUrlDialog(
    playlist: Playlist,
    onSave: (EpgMode, String?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(playlist.guideMode) }
    var customUrl by remember {
        mutableStateOf(if (playlist.guideMode == EpgMode.CUSTOM) playlist.epgUrl.orEmpty() else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.epg_for, playlist.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EpgModeRow(stringResource(R.string.epg_mode_playlist), mode == EpgMode.PLAYLIST) { mode = EpgMode.PLAYLIST }
                Text(
                    playlist.playlistEpgUrl ?: stringResource(R.string.epg_playlist_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 48.dp),
                )
                EpgModeRow(stringResource(R.string.epg_mode_custom), mode == EpgMode.CUSTOM) { mode = EpgMode.CUSTOM }
                TvTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it; mode = EpgMode.CUSTOM },
                    label = { Text(stringResource(R.string.xmltv_url)) },
                    placeholder = { Text("http://...xmltv.xml(.gz)") },
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                )
                EpgModeRow(stringResource(R.string.epg_mode_off), mode == EpgMode.OFF) { mode = EpgMode.OFF }
                Text(
                    stringResource(R.string.epg_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (playlist.epgUrl != null) {
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text(stringResource(R.string.refresh_guide_now))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(mode, customUrl.trim().ifBlank { null }) },
                enabled = mode != EpgMode.CUSTOM || customUrl.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EpgModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Hands the crash log to any app that accepts text (email, chat, notes, ...). */
private fun shareCrashLog(context: Context, log: String) {
    val subject = context.getString(R.string.crash_log_subject)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, log)
    // Some TV devices have no share target at all.
    runCatching { context.startActivity(Intent.createChooser(send, subject)) }
}
