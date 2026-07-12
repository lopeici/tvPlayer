package com.lopeici.tvplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Single source of truth for playlists, channels, favorites, recents and EPG.
 * Persists to small JSON files in [Context.getFilesDir] (no Room / annotation processors).
 */
class TvRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = context.filesDir

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val epgFreshnessMs = 3 * 60 * 60 * 1000L      // re-fetch guide if older than 3h
    private val epgWindowPastMs = 3 * 60 * 60 * 1000L     // keep programmes from 3h ago...
    private val epgWindowFutureMs = 48 * 60 * 60 * 1000L  // ...to 48h ahead

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _activePlaylistId = MutableStateFlow<String?>(null)
    val activePlaylistId: StateFlow<String?> = _activePlaylistId.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    /** User-hidden groups/channels per playlist id (see [HiddenState]). */
    private val _hidden = MutableStateFlow<Map<String, HiddenState>>(emptyMap())
    val hidden: StateFlow<Map<String, HiddenState>> = _hidden.asStateFlow()

    /** EPG programmes for the active playlist, keyed by XMLTV channel id (matched to Channel.tvgId). */
    private val _epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epg: StateFlow<Map<String, List<Programme>>> = _epg.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When true, cast playback uses each channel's HLS (.m3u8) variant (local playback unchanged). */
    private val _castAsHls = MutableStateFlow(false)
    val castAsHls: StateFlow<Boolean> = _castAsHls.asStateFlow()

    init {
        _castAsHls.value = readBoolFile("cast_hls.txt")
        _playlists.value = readJson("playlists.json", ListSerializer(Playlist.serializer()), emptyList())
        _favorites.value = readJson("favorites.json", ListSerializer(String.serializer()), emptyList()).toSet()
        _recents.value = readJson("recents.json", ListSerializer(String.serializer()), emptyList())
        _hidden.value = _playlists.value
            .associate { it.id to readJson("hidden_${it.id}.json", HiddenState.serializer(), HiddenState()) }
            .filterValues { !it.isEmpty }
        val active = readActiveId()?.takeIf { id -> _playlists.value.any { it.id == id } }
        _activePlaylistId.value = active
        if (active != null) scope.launch {
            val chans = readChannels(active)
            _channels.value = chans
            val pl = _playlists.value.firstOrNull { it.id == active }
            if (chans.isEmpty() && pl?.source == PlaylistSource.URL) {
                // Entry was saved but channels never loaded (e.g. a prior fetch failed) — retry now.
                runCatching { refresh(active) }
            } else if (pl?.epgUrl != null) {
                loadEpg(pl)
            }
        }
    }

    // ---- Playlists -------------------------------------------------------

    suspend fun addUrlPlaylist(name: String, url: String, epgUrl: String?) = guarded {
        val id = UUID.randomUUID().toString()
        val text = fetchUrl(url.trim())
        val parsed = M3uParser.parse(text, id)
        require(parsed.isNotEmpty()) { "No channels found in that playlist." }
        writeChannels(id, parsed)
        val pl = Playlist(
            id = id,
            name = name.ifBlank { hostOf(url) },
            source = PlaylistSource.URL,
            uri = url.trim(),
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            addedAt = now(),
        )
        _playlists.update { it + pl }
        persistPlaylists()
        activate(id, parsed)
    }

    suspend fun addFilePlaylist(name: String, contentUri: String) = guarded {
        val id = UUID.randomUUID().toString()
        val text = readContentUri(contentUri)
        val parsed = M3uParser.parse(text, id)
        require(parsed.isNotEmpty()) { "No channels found in that file." }
        writeChannels(id, parsed)
        val pl = Playlist(id, name.ifBlank { "Imported playlist" }, PlaylistSource.FILE, contentUri, null, now())
        _playlists.update { it + pl }
        persistPlaylists()
        activate(id, parsed)
    }

    suspend fun setActive(id: String) = withContext(Dispatchers.IO) {
        activate(id, readChannels(id))
    }

    /**
     * One-time seed of a built-in playlist (used by the `personal` build flavor). Runs only when
     * there are no playlists yet and we haven't seeded before; a marker file makes it permanent, so
     * if the user later deletes the playlist it does not silently come back. No-op when [url] is blank.
     *
     * The playlist *entry* is persisted immediately, before any network call, so a fetch failure
     * (e.g. the server returns HTTP 400) never loses it — the channels load via [refresh] here and
     * are retried on every launch until they succeed (see the auto-retry in `init`).
     */
    fun seedIfNeeded(name: String, url: String) {
        if (url.isBlank()) return
        val marker = File(dir, "seeded.txt")
        if (marker.exists()) return
        runCatching { marker.writeText("1") }       // seed exactly once, success or not
        if (_playlists.value.isNotEmpty()) return    // user already has playlists: don't seed

        val id = UUID.randomUUID().toString()
        val pl = Playlist(
            id = id,
            name = name.ifBlank { hostOf(url) },
            source = PlaylistSource.URL,
            uri = url.trim(),
            epgUrl = null,
            addedAt = now(),
        )
        _playlists.value = listOf(pl)
        persistPlaylists()
        _activePlaylistId.value = id
        writeActiveId(id)
        scope.launch { runCatching { refresh(id) } }
    }

    suspend fun refresh(id: String) = guarded {
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return@guarded
        val text = when (pl.source) {
            PlaylistSource.URL -> fetchUrl(pl.uri)
            PlaylistSource.FILE -> readContentUri(pl.uri)
        }
        val parsed = M3uParser.parse(text, id)
        require(parsed.isNotEmpty()) { "Playlist is empty after refresh." }
        writeChannels(id, parsed)
        if (_activePlaylistId.value == id) _channels.value = parsed
        if (pl.epgUrl != null && _activePlaylistId.value == id) {
            File(dir, "epg_$id.json").delete()
            loadEpg(pl)
        }
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        _playlists.update { list -> list.filterNot { it.id == id } }
        persistPlaylists()
        File(dir, "channels_$id.json").delete()
        File(dir, "epg_$id.json").delete()
        File(dir, "hidden_$id.json").delete()
        _hidden.update { it - id }
        if (_activePlaylistId.value == id) {
            val next = _playlists.value.firstOrNull()
            if (next != null) {
                activate(next.id, readChannels(next.id))
            } else {
                _activePlaylistId.value = null; writeActiveId(null)
                _channels.value = emptyList(); _epg.value = emptyMap()
            }
        }
    }

    // ---- EPG -------------------------------------------------------------

    suspend fun setEpgUrl(id: String, epgUrl: String?) = guarded {
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return@guarded
        val updated = pl.copy(epgUrl = epgUrl?.trim()?.ifBlank { null })
        _playlists.update { list -> list.map { if (it.id == id) updated else it } }
        persistPlaylists()
        File(dir, "epg_$id.json").delete()
        if (_activePlaylistId.value == id) {
            _epg.value = emptyMap()
            if (updated.epgUrl != null) loadEpg(updated)
        }
    }

    suspend fun refreshEpg(id: String) = guarded {
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return@guarded
        if (pl.epgUrl == null) error("This playlist has no EPG URL.")
        File(dir, "epg_$id.json").delete()
        loadEpg(pl)
    }

    /** Programmes for a channel's tvg-id, sorted by start time. */
    fun scheduleFor(tvgId: String?): List<Programme> =
        if (tvgId.isNullOrBlank()) emptyList() else _epg.value[tvgId].orEmpty()

    // ---- Hidden channels / groups ---------------------------------------

    /** Channels of any saved playlist (for the playlist editor); the active one comes from memory. */
    suspend fun channelsFor(id: String): List<Channel> = withContext(Dispatchers.IO) {
        if (_activePlaylistId.value == id) _channels.value else readChannels(id)
    }

    suspend fun setChannelHidden(channel: Channel, hidden: Boolean) = withContext(Dispatchers.IO) {
        updateHidden(channel.playlistId) { h ->
            when {
                hidden -> h.copy(channels = h.channels + channel.url, unhidden = h.unhidden - channel.url)
                // Showing a channel whose group is hidden records an explicit exception.
                channel.group in h.groups ->
                    h.copy(channels = h.channels - channel.url, unhidden = h.unhidden + channel.url)
                else -> h.copy(channels = h.channels - channel.url)
            }
        }
    }

    /**
     * Hide/show a whole group. Individual overrides for [urlsInGroup] are cleared so the group
     * toggle always wins; hiding stores the group *name*, keeping future channels hidden too.
     */
    suspend fun setGroupHidden(playlistId: String, group: String, urlsInGroup: Collection<String>, hidden: Boolean) =
        withContext(Dispatchers.IO) {
            updateHidden(playlistId) { h ->
                HiddenState(
                    groups = if (hidden) h.groups + group else h.groups - group,
                    channels = h.channels - urlsInGroup.toSet(),
                    unhidden = h.unhidden - urlsInGroup.toSet(),
                )
            }
        }

    /** Bulk hide/show individual channels (used for the "no group" bucket, which has no name to store). */
    suspend fun setChannelsHidden(playlistId: String, urls: Collection<String>, hidden: Boolean) =
        withContext(Dispatchers.IO) {
            updateHidden(playlistId) { h ->
                if (hidden) h.copy(channels = h.channels + urls, unhidden = h.unhidden - urls.toSet())
                else h.copy(channels = h.channels - urls.toSet())
            }
        }

    private fun updateHidden(playlistId: String, transform: (HiddenState) -> HiddenState) {
        val next = transform(_hidden.value[playlistId] ?: HiddenState())
        _hidden.update { if (next.isEmpty) it - playlistId else it + (playlistId to next) }
        if (next.isEmpty) File(dir, "hidden_$playlistId.json").delete()
        else writeJson("hidden_$playlistId.json", HiddenState.serializer(), next)
    }

    // ---- Favorites / recents --------------------------------------------

    suspend fun toggleFavorite(channel: Channel) = withContext(Dispatchers.IO) {
        _favorites.update { set -> if (channel.key in set) set - channel.key else set + channel.key }
        writeJson("favorites.json", ListSerializer(String.serializer()), _favorites.value.toList())
    }

    suspend fun recordRecent(channel: Channel) = withContext(Dispatchers.IO) {
        _recents.update { (listOf(channel.key) + it.filterNot { k -> k == channel.key }).take(50) }
        writeJson("recents.json", ListSerializer(String.serializer()), _recents.value)
    }

    fun clearError() { _error.value = null }

    fun setCastAsHls(value: Boolean) {
        _castAsHls.value = value
        scope.launch { writeBoolFile("cast_hls.txt", value) }
    }

    private fun readBoolFile(name: String): Boolean =
        runCatching { File(dir, name).takeIf { it.exists() }?.readText()?.trim() == "true" }.getOrDefault(false)

    private fun writeBoolFile(name: String, value: Boolean) {
        runCatching { File(dir, name).writeText(value.toString()) }
    }

    // ---- Internals -------------------------------------------------------

    private fun activate(id: String, channels: List<Channel>) {
        _activePlaylistId.value = id
        writeActiveId(id)
        _channels.value = channels
        _epg.value = emptyMap()
        val pl = _playlists.value.firstOrNull { it.id == id }
        if (pl?.epgUrl != null) scope.launch { loadEpg(pl) }
    }

    /** Loads EPG from cache when fresh, otherwise fetches + parses. Failures are non-fatal. */
    private fun loadEpg(pl: Playlist) {
        val epgUrl = pl.epgUrl ?: return
        val cache = readEpgCache(pl.id)
        if (cache != null && (now() - cache.fetchedAt) < epgFreshnessMs) {
            if (_activePlaylistId.value == pl.id) _epg.value = cache.programmes
            return
        }
        runCatching { fetchAndParseEpg(epgUrl) }
            .onSuccess { parsed ->
                writeJson("epg_${pl.id}.json", EpgCache.serializer(), EpgCache(now(), parsed))
                if (_activePlaylistId.value == pl.id) _epg.value = parsed
            }
            .onFailure {
                if (cache != null && _activePlaylistId.value == pl.id) _epg.value = cache.programmes
            }
    }

    private fun fetchAndParseEpg(url: String): Map<String, List<Programme>> {
        val request = Request.Builder().url(url).header("User-Agent", "tvPlayer/1.0").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("EPG server returned HTTP ${resp.code}")
            val body = resp.body ?: error("Empty EPG response")
            val stream = if (url.endsWith(".gz", ignoreCase = true)) GZIPInputStream(body.byteStream()) else body.byteStream()
            val nowMs = now()
            return stream.use { XmltvParser.parse(it, nowMs - epgWindowPastMs, nowMs + epgWindowFutureMs) }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        _loading.value = true
        _error.value = null
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            _error.value = e.message ?: "Something went wrong."
        } finally {
            _loading.value = false
        }
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "tvPlayer/1.0").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Server returned HTTP ${resp.code}")
            return resp.body?.string()?.takeIf { it.isNotBlank() } ?: error("Empty response from server")
        }
    }

    private fun readContentUri(uriString: String): String {
        val uri = Uri.parse(uriString)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not read the selected file")
    }

    private fun writeChannels(id: String, channels: List<Channel>) =
        writeJson("channels_$id.json", ListSerializer(Channel.serializer()), channels)

    private fun readChannels(id: String): List<Channel> =
        readJson("channels_$id.json", ListSerializer(Channel.serializer()), emptyList())

    private fun readEpgCache(id: String): EpgCache? = runCatching {
        File(dir, "epg_$id.json").takeIf { it.exists() }?.let { json.decodeFromString(EpgCache.serializer(), it.readText()) }
    }.getOrNull()

    private fun persistPlaylists() =
        writeJson("playlists.json", ListSerializer(Playlist.serializer()), _playlists.value)

    private fun readActiveId(): String? =
        runCatching { File(dir, "active.txt").takeIf { it.exists() }?.readText()?.ifBlank { null } }.getOrNull()

    private fun writeActiveId(id: String?) {
        runCatching { File(dir, "active.txt").writeText(id ?: "") }
    }

    private fun <T> readJson(name: String, serializer: KSerializer<T>, default: T): T = runCatching {
        File(dir, name).takeIf { it.exists() }?.let { json.decodeFromString(serializer, it.readText()) } ?: default
    }.getOrDefault(default)

    private fun <T> writeJson(name: String, serializer: KSerializer<T>, value: T) {
        runCatching { File(dir, name).writeText(json.encodeToString(serializer, value)) }
    }

    private fun now() = System.currentTimeMillis()

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: "Playlist"
}
