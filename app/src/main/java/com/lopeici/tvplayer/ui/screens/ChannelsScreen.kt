package com.lopeici.tvplayer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.CenterProgress
import com.lopeici.tvplayer.ui.components.ChannelRow
import com.lopeici.tvplayer.ui.components.EmptyState
import com.lopeici.tvplayer.ui.components.SearchTextField

@Composable
fun ChannelsScreen(vm: TvViewModel, onPlay: (Channel, List<Channel>) -> Unit) {
    val all by vm.channels.collectAsStateWithLifecycle()
    val visible by vm.visibleChannels.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selectedGroup by vm.selectedGroup.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val currentProgrammes by vm.currentProgrammes.collectAsStateWithLifecycle()
    val searchHidden by vm.searchHidden.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        SearchTextField(
            value = query,
            onValueChange = vm::setSearch,
            placeholder = "Search channels",
            // While searching, an eye toggle includes user-hidden channels in the results.
            trailingIcon = if (query.isBlank()) null else {
                {
                    IconButton(onClick = { vm.setSearchHidden(!searchHidden) }) {
                        Icon(
                            if (searchHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (searchHidden) "Exclude hidden channels" else "Include hidden channels",
                            tint = if (searchHidden) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (groups.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    FilterChip(
                        selected = selectedGroup == null,
                        onClick = { vm.setGroup(null) },
                        label = { Text("All") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                items(groups) { group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { vm.setGroup(group) },
                        label = { Text(group) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        when {
            loading && all.isEmpty() -> CenterProgress()
            all.isEmpty() -> EmptyState("No channels yet", "Add a playlist from the Playlists tab to get started.")
            visible.isEmpty() -> EmptyState("No matches", "Try a different search term or group.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.key }) { channel ->
                    ChannelRow(
                        channel = channel,
                        isFavorite = channel.key in favorites,
                        isPlaying = channel.key == currentChannel?.key,
                        currentProgramme = channel.tvgId?.let { currentProgrammes[it] },
                        onClick = { onPlay(channel, visible) },
                        onToggleFavorite = { vm.toggleFavorite(channel) },
                        dimmed = hidden[channel.playlistId]?.isHidden(channel) == true,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
