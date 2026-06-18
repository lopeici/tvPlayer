package com.lopeici.tvplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lopeici.tvplayer.TvPlayerApp
import com.lopeici.tvplayer.data.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Single Activity-scoped ViewModel shared by all screens. */
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as TvPlayerApp).container
    private val repo = container.repository
    val playerManager = container.playerManager

    // Repository state
    val playlists = repo.playlists
    val activePlaylistId = repo.activePlaylistId
    val channels = repo.channels
    val favorites = repo.favorites
    val loading = repo.loading
    val error = repo.error

    // Player state
    val isPlaying = playerManager.isPlaying
    val isCasting = playerManager.isCasting
    val playerError = playerManager.error

    // Browsing filters
    val searchQuery = MutableStateFlow("")
    val selectedGroup = MutableStateFlow<String?>(null)

    val groups: StateFlow<List<String>> = channels
        .map { list -> list.mapNotNull { it.group }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visibleChannels: StateFlow<List<Channel>> =
        combine(channels, searchQuery, selectedGroup) { list, query, group ->
            list.filter { ch ->
                (group == null || ch.group == group) &&
                    (query.isBlank() || ch.name.contains(query, ignoreCase = true))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteChannels: StateFlow<List<Channel>> =
        combine(channels, favorites) { list, favs -> list.filter { it.key in favs } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentChannels: StateFlow<List<Channel>> =
        combine(channels, repo.recents) { list, recents ->
            recents.mapNotNull { key -> list.firstOrNull { it.key == key } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Currently playing — derived from the player's active media id + the playback queue.
    private val queue = MutableStateFlow<List<Channel>>(emptyList())
    val currentChannel: StateFlow<Channel?> =
        combine(playerManager.currentMediaId, queue) { id, q -> q.firstOrNull { it.key == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Actions ----

    fun setSearch(value: String) { searchQuery.value = value }
    fun setGroup(value: String?) { selectedGroup.value = value }

    fun isFavorite(channel: Channel): Boolean = channel.key in favorites.value
    fun toggleFavorite(channel: Channel) = viewModelScope.launch { repo.toggleFavorite(channel) }

    fun addUrlPlaylist(name: String, url: String) =
        viewModelScope.launch { repo.addUrlPlaylist(name, url) }

    fun addFilePlaylist(name: String, contentUri: String) =
        viewModelScope.launch { repo.addFilePlaylist(name, contentUri) }

    fun setActivePlaylist(id: String) = viewModelScope.launch { repo.setActive(id) }
    fun refreshPlaylist(id: String) = viewModelScope.launch { repo.refresh(id) }
    fun deletePlaylist(id: String) = viewModelScope.launch { repo.deletePlaylist(id) }

    fun clearError() { repo.clearError(); playerManager.clearError() }

    /** Play [channel] within [queue] (used for next/previous zapping and channel-number jump). */
    fun play(channel: Channel, withinQueue: List<Channel>) {
        val q = withinQueue.ifEmpty { listOf(channel) }
        queue.value = q
        val idx = q.indexOfFirst { it.key == channel.key }.coerceAtLeast(0)
        playerManager.play(q, idx)
        viewModelScope.launch { repo.recordRecent(channel) }
    }

    fun zapNext() = playerManager.next()
    fun zapPrevious() = playerManager.previous()
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun retry() = playerManager.retry()

    /** Jump to a 1-based channel number within the current queue. */
    fun jumpToNumber(number: Int) {
        val idx = number - 1
        if (idx in queue.value.indices) {
            playerManager.playIndex(idx)
            viewModelScope.launch { repo.recordRecent(queue.value[idx]) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // PlayerManager is app-scoped (in the container); do not release here.
    }
}
