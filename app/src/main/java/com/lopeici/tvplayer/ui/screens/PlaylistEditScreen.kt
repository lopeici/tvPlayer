package com.lopeici.tvplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.HiddenState
import com.lopeici.tvplayer.data.Playlist
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.CenterProgress
import com.lopeici.tvplayer.ui.components.EmptyState
import com.lopeici.tvplayer.ui.components.SearchTextField
import com.lopeici.tvplayer.ui.components.isTelevision

/**
 * Full-screen editor to show/hide a playlist's channels. Groups are collapsible headers with a
 * tri-state checkbox (on = all shown, off = all hidden, dash = mixed); channels have plain
 * checkboxes. Checked means visible in the app.
 */
@Composable
fun PlaylistEditScreen(vm: TvViewModel, playlist: Playlist, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    var channels by remember(playlist.id) { mutableStateOf<List<Channel>?>(null) }
    LaunchedEffect(playlist.id) { channels = vm.channelsOf(playlist.id) }

    val hiddenMap by vm.hidden.collectAsStateWithLifecycle()
    val hidden = hiddenMap[playlist.id] ?: HiddenState()
    var query by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String?, Boolean>() }
    val context = LocalContext.current
    val isTv = remember { context.isTelevision() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Edit ${playlist.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                channels?.let { list ->
                    val shown = list.count { !hidden.isHidden(it) }
                    Text(
                        "$shown of ${list.size} channels visible",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SearchTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search channels",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        val list = channels
        when {
            list == null -> CenterProgress()
            list.isEmpty() -> EmptyState("No channels", "Refresh this playlist first, then edit it.")
            else -> {
                // Toggling a group always applies to the *full* group, even while searching.
                val fullGroups: Map<String?, List<Channel>> = remember(list) { list.groupBy { it.group } }
                val filtered = if (query.isBlank()) list
                else list.filter { it.name.contains(query, ignoreCase = true) }
                val searching = query.isNotBlank()

                LazyColumn(Modifier.fillMaxSize()) {
                    val sortedGroups = filtered.groupBy { it.group }.entries
                        .sortedBy { (group, _) -> (group ?: "No group").lowercase() }
                    sortedGroups.forEach { (group, visibleInGroup) ->
                        val all = fullGroups[group].orEmpty()
                        val shownCount = all.count { !hidden.isHidden(it) }
                        val isExpanded = searching || expanded[group] == true
                        item(key = "group:${group ?: "\u0000"}") {
                            GroupHeader(
                                name = group ?: "No group",
                                matched = visibleInGroup.size,
                                total = all.size,
                                state = when (shownCount) {
                                    0 -> ToggleableState.Off
                                    all.size -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                },
                                expanded = isExpanded,
                                tv = isTv,
                                onToggleExpand = { expanded[group] = !(expanded[group] ?: false) },
                                onToggleChecked = { nowChecked ->
                                    vm.setGroupHidden(playlist.id, group, all.map { it.url }, hidden = !nowChecked)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (isExpanded) {
                            items(visibleInGroup, key = { "ch:${it.key}" }) { channel ->
                                ChannelEditRow(
                                    channel = channel,
                                    checked = !hidden.isHidden(channel),
                                    tv = isTv,
                                    onCheckedChange = { checked -> vm.setChannelHidden(channel, hidden = !checked) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * On touch, tapping the row expands/collapses and the checkbox/chevron are their own tap targets.
 * On TV the row is a single focus stop: D-pad center selects/deselects the group, right expands,
 * left collapses; the checkbox and chevron become display-only.
 */
@Composable
private fun GroupHeader(
    name: String,
    matched: Int,
    total: Int,
    state: ToggleableState,
    expanded: Boolean,
    tv: Boolean,
    onToggleExpand: () -> Unit,
    onToggleChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (!tv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> if (!expanded) { onToggleExpand(); true } else false
                    Key.DirectionLeft -> if (expanded) { onToggleExpand(); true } else false
                    else -> false
                }
            }
            .clickable(onClick = {
                if (tv) onToggleChecked(state != ToggleableState.On) else onToggleExpand()
            })
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            // Standard cycle: fully-on unchecks; off/mixed checks everything.
            onClick = if (tv) null else ({ onToggleChecked(state != ToggleableState.On) }),
        )
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (matched == total) "$total" else "$matched/$total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tv) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.padding(12.dp),
            )
        } else {
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }
    }
}

@Composable
private fun ChannelEditRow(channel: Channel, checked: Boolean, tv: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // On TV the checkbox is display-only so the row is a single focus stop (click toggles).
        Checkbox(checked = checked, onCheckedChange = if (tv) null else onCheckedChange)
        Text(
            channel.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
