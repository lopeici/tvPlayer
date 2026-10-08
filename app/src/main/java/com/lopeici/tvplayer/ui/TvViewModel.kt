package com.lopeici.tvplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.lopeici.tvplayer.TvPlayerApp
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.EpgMode
import com.lopeici.tvplayer.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    val tracks = playerManager.tracks

    /** Video scaling: fit (letterbox) → zoom (crop) → fill (stretch). Session-scoped. */
    val resizeMode = MutableStateFlow(AspectRatioFrameLayout.RESIZE_MODE_FIT)

    fun cycleResizeMode() {
        resizeMode.value = when (resizeMode.value) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /** Ticks roughly every 30s so "now playing" (and its progress bar) advances over time. */
    val nowTick: StateFlow<Long> = flow {
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
    val shownChannels: StateFlow<List<Channel>> =
        combine(channels, repo.hidden) { list, hidden ->
            if (hidden.isEmpty()) list
            else list.filterNot { ch -> hidden[ch.playlistId]?.isHidden(ch) == true }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<String>> = shownChannels
        .map { list -> list.mapNotNull { it.group }.distinct().sortedBy { it.lowercase() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Filtering can cover tens of thousands of channels: run it off the main thread, and only once
    // typing pauses (an emptied query applies at once, so clearing the search feels instant).
    @OptIn(FlowPreview::class)
    private val debouncedQuery = searchQuery.debounce { if (it.isEmpty()) 0L else 250L }

    val visibleChannels: StateFlow<List<Channel>> =
        combine(channels, repo.hidden, debouncedQuery, selectedGroup, searchHidden) { list, hidden, query, group, withHidden ->
            val includeHidden = withHidden && query.isNotBlank()
            list.filter { ch ->
                (includeHidden || hidden[ch.playlistId]?.isHidden(ch) != true) &&
                    (group == null || ch.group == group) &&
                    (query.isBlank() || ch.name.contains(query, ignoreCase = true))
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteChannels: StateFlow<List<Channel>> =
        combine(shownChannels, favorites) { list, favs -> list.filter { it.key in favs } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentChannels: StateFlow<List<Channel>> =
        combine(shownChannels, repo.recents) { list, recents ->
            val byKey = list.associateBy { it.key }
            recents.mapNotNull { byKey[it] }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The now-playing programme per EPG key (look up by [Channel.epgKey]). */
    val currentProgrammes: StateFlow<Map<String, Programme>> =
        combine(repo.epg, nowTick) { epg, now ->
            buildMap {
                for ((channelId, list) in epg) {
                    list.firstOrNull { now in it.start until it.stop }?.let { put(channelId, it) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Currently playing (app-scoped, so it survives Activity recreation). */
    val currentChannel: StateFlow<Channel?> = playerManager.current

    /** (now, next) programme for the channel currently on the player. */
    val currentNowNext: StateFlow<Pair<Programme?, Programme?>> =
        combine(currentChannel, repo.epg, nowTick) { channel, epg, now ->
            val list = channel?.epgKey?.let { epg[it] }.orEmpty()
            val nowProg = list.firstOrNull { now in it.start until it.stop }
            val nextProg = list.firstOrNull { it.start >= (nowProg?.stop ?: now) }
            nowProg to nextProg
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    init {
        viewModelScope.launch { crashLog.value = repo.crashLog() }
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
    fun setEpgMode(id: String, mode: EpgMode, customUrl: String?) =
        viewModelScope.launch { repo.setEpgMode(id, mode, customUrl) }
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

    /** Upcoming programmes (incl. current) for a channel's [Channel.epgKey]. */
    fun scheduleFor(epgKey: String?): List<Programme> {
        val now = System.currentTimeMillis()
        return repo.scheduleFor(epgKey).filter { it.stop > now }
    }

    /** Called when the app comes to the foreground: refreshes a day-old playlist / stale guide. */
    fun refreshIfStale() = repo.refreshIfStale()

    fun clearError() { repo.clearError(); playerManager.clearError() }

    /** Text of the last crash log, or null when there is none (loaded once per ViewModel). */
    val crashLog = MutableStateFlow<String?>(null)

    fun clearCrashLog() = viewModelScope.launch {
        repo.clearCrashLog()
        crashLog.value = null
    }
    fun setCastAsHls(value: Boolean) = repo.setCastAsHls(value)

    /**
     * Play [channel] within [withinQueue] (used for next/previous zapping and channel-number jump).
     * If the channel isn't in that queue it is put first, so it's always what starts playing.
     */
    fun play(channel: Channel, withinQueue: List<Channel>) {
        val idx = withinQueue.indexOfFirst { it.key == channel.key }
        if (idx >= 0) playerManager.play(withinQueue, idx)
        else playerManager.play(listOf(channel) + withinQueue, 0)
    }

    /** Stop the stream entirely and clear the queue. */
    fun stop() = playerManager.stop()

    fun zapNext() = playerManager.next()
    fun zapPrevious() = playerManager.previous()
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun retry() = playerManager.retry()

    // ---- Track selection (audio languages / subtitles) ----

    fun selectTrack(group: Tracks.Group, trackIndex: Int) = playerManager.selectTrack(group, trackIndex)
    fun autoAudio() = playerManager.clearAudioOverride()
    fun disableSubtitles() = playerManager.disableTextTracks()

    /**
     * "Go to channel [number]". Playlists that number their channels (`tvg-chno`) are matched on
     * that — first within the current queue, else anywhere in the (non-hidden) playlist. Playlists
     * without numbers fall back to the 1-based position in the current queue.
     */
    fun jumpToNumber(number: Int) {
        val queue = playerManager.queue.value
        val queueIdx = queue.indexOfFirst { it.number == number }
        if (queueIdx >= 0) return playerManager.playIndex(queueIdx)
        // Not shownChannels.value: that flow is only kept up to date while something collects it.
        val hiddenState = repo.hidden.value
        val shown = channels.value.filterNot { hiddenState[it.playlistId]?.isHidden(it) == true }
        shown.firstOrNull { it.number == number }?.let { return play(it, queue) }
        if (shown.none { it.number != null } && number - 1 in queue.indices) playerManager.playIndex(number - 1)
    }

    override fun onCleared() {
        super.onCleared()
        // PlayerManager is app-scoped (in the container); do not release here.
    }
}
