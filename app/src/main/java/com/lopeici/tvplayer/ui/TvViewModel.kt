package com.lopeici.tvplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lopeici.tvplayer.TvPlayerApp
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.Programme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
    val castAsHls = repo.castAsHls

    // Ticks roughly every 30s so "now playing" advances over time.
    private val nowTick: StateFlow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(30_000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    // Browsing filters
    val searchQuery = MutableStateFlow("")
    val selectedGroup = MutableStateFlow<String?>(null)

    /** When on, search results also include user-hidden channels. Off by default; only applies while searching. */
    val searchHidden = MutableStateFlow(false)

    /** Per-playlist hidden groups/channels (edited via the playlist editor). */
    val hidden = repo.hidden

    // Channels minus user-hidden ones — everything the browsing UI shows derives from this.
    private val shownChannels: StateFlow<List<Channel>> =
        combine(channels, repo.hidden) { list, hidden ->
            if (hidden.isEmpty()) list
            else list.filterNot { ch -> hidden[ch.playlistId]?.isHidden(ch) == true }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<String>> = shownChannels
        .map { list -> list.mapNotNull { it.group }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visibleChannels: StateFlow<List<Channel>> =
        combine(channels, repo.hidden, searchQuery, selectedGroup, searchHidden) { list, hidden, query, group, withHidden ->
            val includeHidden = withHidden && query.isNotBlank()
            list.filter { ch ->
                (includeHidden || hidden[ch.playlistId]?.isHidden(ch) != true) &&
                    (group == null || ch.group == group) &&
                    (query.isBlank() || ch.name.contains(query, ignoreCase = true))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteChannels: StateFlow<List<Channel>> =
        combine(shownChannels, favorites) { list, favs -> list.filter { it.key in favs } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentChannels: StateFlow<List<Channel>> =
        combine(shownChannels, repo.recents) { list, recents ->
            recents.mapNotNull { key -> list.firstOrNull { it.key == key } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The now-playing programme per XMLTV channel id (look up by Channel.tvgId). */
    val currentProgrammes: StateFlow<Map<String, Programme>> =
        combine(repo.epg, nowTick) { epg, now ->
            buildMap {
                for ((channelId, list) in epg) {
                    list.firstOrNull { now in it.start until it.stop }?.let { put(channelId, it) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Currently playing — derived from the player's active media id + the playback queue.
    private val queue = MutableStateFlow<List<Channel>>(emptyList())
    val currentChannel: StateFlow<Channel?> =
        combine(playerManager.currentMediaId, queue) { id, q -> q.firstOrNull { it.key == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** (now, next) programme for the channel currently on the player. */
    val currentNowNext: StateFlow<Pair<Programme?, Programme?>> =
        combine(currentChannel, repo.epg, nowTick) { channel, epg, now ->
            val list = channel?.tvgId?.let { epg[it] }.orEmpty()
            val nowProg = list.firstOrNull { now in it.start until it.stop }
            val nextProg = list.firstOrNull { it.start >= (nowProg?.stop ?: now) }
            nowProg to nextProg
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    init {
        // When casting starts/stops (or the HLS toggle changes) while a channel is open, reload the
        // current channel so casting uses the HLS variant and local playback uses the original stream.
        viewModelScope.launch {
            combine(playerManager.isCasting, repo.castAsHls) { casting, hls -> casting && hls }
                .collect { useHls ->
                    val q = queue.value
                    if (q.isNotEmpty()) {
                        val idx = q.indexOfFirst { it.key == currentChannel.value?.key }.coerceAtLeast(0)
                        playerManager.play(q, idx, castHls = useHls)
                    }
                }
        }
    }

    // ---- Actions ----

    fun setSearch(value: String) { searchQuery.value = value }
    fun setGroup(value: String?) { selectedGroup.value = value }
    fun setSearchHidden(value: Boolean) { searchHidden.value = value }

    fun isFavorite(channel: Channel): Boolean = channel.key in favorites.value
    fun toggleFavorite(channel: Channel) = viewModelScope.launch { repo.toggleFavorite(channel) }

    fun addUrlPlaylist(name: String, url: String, epgUrl: String?) =
        viewModelScope.launch { repo.addUrlPlaylist(name, url, epgUrl) }

    fun addFilePlaylist(name: String, contentUri: String) =
        viewModelScope.launch { repo.addFilePlaylist(name, contentUri) }

    fun setActivePlaylist(id: String) = viewModelScope.launch { repo.setActive(id) }
    fun refreshPlaylist(id: String) = viewModelScope.launch { repo.refresh(id) }
    fun deletePlaylist(id: String) = viewModelScope.launch { repo.deletePlaylist(id) }
    fun setEpgUrl(id: String, epgUrl: String?) = viewModelScope.launch { repo.setEpgUrl(id, epgUrl) }
    fun refreshEpg(id: String) = viewModelScope.launch { repo.refreshEpg(id) }

    // ---- Playlist editor (hide/show) ----

    /** All channels of a playlist, including hidden ones (the editor lists everything). */
    suspend fun channelsOf(playlistId: String): List<Channel> = repo.channelsFor(playlistId)

    fun setChannelHidden(channel: Channel, hidden: Boolean) =
        viewModelScope.launch { repo.setChannelHidden(channel, hidden) }

    /** Hide/show a whole group; `group == null` is the "no group" bucket (bulk individual hide). */
    fun setGroupHidden(playlistId: String, group: String?, urlsInGroup: Collection<String>, hidden: Boolean) =
        viewModelScope.launch {
            if (group == null) repo.setChannelsHidden(playlistId, urlsInGroup, hidden)
            else repo.setGroupHidden(playlistId, group, urlsInGroup, hidden)
        }

    /** Upcoming programmes (incl. current) for a channel's tvg-id. */
    fun scheduleFor(tvgId: String?): List<Programme> {
        val now = System.currentTimeMillis()
        return repo.scheduleFor(tvgId).filter { it.stop > now }
    }

    fun clearError() { repo.clearError(); playerManager.clearError() }
    fun setCastAsHls(value: Boolean) = repo.setCastAsHls(value)

    /** Play [channel] within [queue] (used for next/previous zapping and channel-number jump). */
    fun play(channel: Channel, withinQueue: List<Channel>) {
        val q = withinQueue.ifEmpty { listOf(channel) }
        queue.value = q
        val idx = q.indexOfFirst { it.key == channel.key }.coerceAtLeast(0)
        playerManager.play(q, idx, castHls = isCasting.value && repo.castAsHls.value)
        viewModelScope.launch { repo.recordRecent(channel) }
    }

    /** Stop the stream entirely; clearing the queue also keeps the cast watcher from restarting it. */
    fun stop() {
        queue.value = emptyList()
        playerManager.stop()
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
