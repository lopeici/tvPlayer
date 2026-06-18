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
fun FavoritesScreen(vm: TvViewModel, onPlay: (Channel, List<Channel>) -> Unit) {
    val favoriteChannels by vm.favoriteChannels.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val currentProgrammes by vm.currentProgrammes.collectAsStateWithLifecycle()

    if (favoriteChannels.isEmpty()) {
        EmptyState("No favorites yet", "Tap the heart on any channel to add it here.")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(favoriteChannels, key = { it.key }) { channel ->
                ChannelRow(
                    channel = channel,
                    isFavorite = true,
                    isPlaying = channel.key == currentChannel?.key,
                    currentProgramme = channel.tvgId?.let { currentProgrammes[it] },
                    onClick = { onPlay(channel, favoriteChannels) },
                    onToggleFavorite = { vm.toggleFavorite(channel) },
                )
                HorizontalDivider()
            }
        }
    }
}
