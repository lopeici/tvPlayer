package com.lopeici.tvplayer.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.R
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.ui.TvViewModel
import com.lopeici.tvplayer.ui.components.ChannelRow
import com.lopeici.tvplayer.ui.components.EmptyState

@Composable
fun FavoritesScreen(vm: TvViewModel, onPlay: (Channel, List<Channel>) -> Unit) {
    val favoriteChannels by vm.favoriteChannels.collectAsStateWithLifecycle()
    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val currentProgrammes by vm.currentProgrammes.collectAsStateWithLifecycle()
    val now by vm.nowTick.collectAsStateWithLifecycle()

    if (favoriteChannels.isEmpty()) {
        EmptyState(stringResource(R.string.empty_favorites_title), stringResource(R.string.empty_favorites_body))
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(favoriteChannels, key = { it.key }) { channel ->
                ChannelRow(
                    channel = channel,
                    isFavorite = true,
                    isPlaying = channel.key == currentChannel?.key,
                    currentProgramme = channel.epgKey?.let { currentProgrammes[it] },
                    now = now,
                    onClick = { onPlay(channel, favoriteChannels) },
                    onToggleFavorite = { vm.toggleFavorite(channel) },
                )
                HorizontalDivider()
            }
        }
    }
}
