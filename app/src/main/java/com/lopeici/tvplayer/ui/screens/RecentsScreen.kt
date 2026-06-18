package com.lopeici.tvplayer.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.ChannelRow
import com.lopeici.tvplayer.ui.components.EmptyState

@Composable
fun RecentsScreen(vm: TvViewModel, onPlay: (Channel, List<Channel>) -> Unit) {
    val recentChannels by vm.recentChannels.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val currentProgrammes by vm.currentProgrammes.collectAsStateWithLifecycle()

    if (recentChannels.isEmpty()) {
        EmptyState("Nothing watched yet", "Channels you play will show up here.")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(recentChannels, key = { it.key }) { channel ->
                ChannelRow(
                    channel = channel,
                    isFavorite = channel.key in favorites,
                    isPlaying = channel.key == currentChannel?.key,
                    currentProgramme = channel.tvgId?.let { currentProgrammes[it] },
                    onClick = { onPlay(channel, recentChannels) },
                    onToggleFavorite = { vm.toggleFavorite(channel) },
                )
                HorizontalDivider()
            }
        }
    }
}
