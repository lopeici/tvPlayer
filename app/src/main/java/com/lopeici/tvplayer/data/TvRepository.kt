package com.lopeici.tvplayer.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.StringRes
import androidx.core.util.readText
import androidx.core.util.writeText
import com.lopeici.tvplayer.R
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Single source of truth for playlists, channels, favorites, recents and EPG.
 * Persists to small JSON files in [Context.getFilesDir] (no Room / annotation processors).
 */
class TvRepository(private val context: Context, private val http: OkHttpClient) {

    // explicitNulls = false: most Channel fields are usually null, and channel files can hold tens
    // of thousands of entries; missing keys decode back to their defaults.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = context.filesDir

    private val epgFreshnessMs = 3 * 60 * 60 * 1000L      // re-fetch guide if older than 3h
    private val epgWindowPastMs = 3 * 60 * 60 * 1000L     // keep programmes from 3h ago...
    private val epgWindowFutureMs = 48 * 60 * 60 * 1000L  // ...to 48h ahead
    private val playlistMaxAgeMs = 24 * 60 * 60 * 1000L   // auto-refresh URL playlists after 24h

    /** When the guide in [epg] was fetched (0 = none loaded), to re-check freshness on resume. */
    @Volatile private var epgFetchedAt = 0L

    /** Background (non-user) refreshes in flight, by kind + playlist id, so they never overlap. */
    private val autoJobs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Serializes read-modify-write of a playlist entry against its slow parts: a reload downloads
     * first, then re-reads the entry under this lock, so edits or a delete made meanwhile win.
     */
    private val playlistLock = Mutex()

    /**
     * Latest guide load started per playlist. A load only shows/caches its result if no newer one
     * started since (and the playlist still exists), so an older, slower download never wins.
     */
    private val epgGenerations = ConcurrentHashMap<String, Long>()

    /** One lock per file name: writes to the same file are serialized, different files don't wait. */
    private val fileLocks = ConcurrentHashMap<String, Any>()

    /** Number of guarded operations in flight; [loading] is true while it's above zero. */
    private var activeOps = 0

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

    /** EPG programmes for the active playlist, keyed by [Channel.epgKey] (tvg-id or name key). */
    private val _epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epg: StateFlow<Map<String, List<Programme>>> = _epg.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When true, cast playback uses each channel's HLS (.m3u8) variant (local playback unchanged). */
    private val _castAsHls = MutableStateFlow(false)
    val castAsHls: StateFlow<Boolean> = _castAsHls.asStateFlow()

    /**
     * Initial load of the persisted state, off the main thread (the repository is created from
     * `Application.onCreate`). Every operation that reads or writes that state waits for it first
     * (see [io] / [guarded]), so nothing can act on — or overwrite — the not-yet-loaded defaults.
     */
    private val ready: Job

    init {
        trackLoading(+1)
        ready = scope.launch {
            try {
                _castAsHls.value = readBoolFile("cast_hls.txt")
                _playlists.value = readJson("playlists.json", ListSerializer(Playlist.serializer()), emptyList())
                _favorites.value = readJson("favorites.json", ListSerializer(String.serializer()), emptyList()).toSet()
                _recents.value = readJson("recents.json", ListSerializer(String.serializer()), emptyList())
                _hidden.value = _playlists.value
                    .associate { it.id to readJson("hidden_${it.id}.json", HiddenState.serializer(), HiddenState()) }
                    .filterValues { !it.isEmpty }
                val active = readActiveId()?.takeIf { id -> _playlists.value.any { it.id == id } }
                _activePlaylistId.value = active
                if (active != null) _channels.value = readChannels(active)
            } finally {
                trackLoading(-1)
            }
        }
        // Network follow-ups run separately: `refresh` itself waits for `ready`.
        scope.launch {
            ready.join()
            val active = _activePlaylistId.value ?: return@launch
            val pl = _playlists.value.firstOrNull { it.id == active }
            if (_channels.value.isEmpty() && pl?.source == PlaylistSource.URL) {
                // Entry was saved but channels never loaded (e.g. a prior fetch failed) — retry now.
                runCatching { refresh(active) }
            } else if (pl?.epgUrl != null) {
                autoOnce("epg:${pl.id}") { loadEpg(pl.id) }
            }
        }
    }

    // ---- Playlists -------------------------------------------------------

    suspend fun addUrlPlaylist(name: String, url: String, epgUrl: String?) = guarded {
        val id = UUID.randomUUID().toString()
        val m3u = fetchPlaylist(url.trim(), id)
        val parsed = m3u.channels
        require(parsed.isNotEmpty()) { text(R.string.error_no_channels_playlist) }
        writeChannels(id, parsed)
        // A guide URL typed by the user wins; otherwise follow the playlist's url-tvg header.
        val userEpg = epgUrl?.trim()?.ifBlank { null }
        val mode = if (userEpg != null) EpgMode.CUSTOM else EpgMode.PLAYLIST
        val pl = Playlist(
            id = id,
            name = name.ifBlank { hostOf(url) },
            source = PlaylistSource.URL,
            uri = url.trim(),
            epgUrl = resolveEpgUrl(mode, headerUrl = m3u.epgUrl, customUrl = userEpg),
            addedAt = now(),
            epgMode = mode,
            playlistEpgUrl = m3u.epgUrl,
            refreshedAt = now(),
        )
        _playlists.update { it + pl }
        persistPlaylists()
        activate(id, parsed)
    }

    suspend fun addFilePlaylist(name: String, contentUri: String) = guarded {
        val id = UUID.randomUUID().toString()
        val m3u = readPlaylistFile(contentUri, id)
        val parsed = m3u.channels
        require(parsed.isNotEmpty()) { text(R.string.error_no_channels_file) }
        writeChannels(id, parsed)
        val pl = Playlist(
            id = id,
            name = name.ifBlank { text(R.string.imported_playlist) },
            source = PlaylistSource.FILE,
            uri = contentUri,
            epgUrl = m3u.epgUrl,
            addedAt = now(),
            epgMode = EpgMode.PLAYLIST,
            playlistEpgUrl = m3u.epgUrl,
            refreshedAt = now(),
        )
        _playlists.update { it + pl }
        persistPlaylists()
        activate(id, parsed)
    }

    suspend fun setActive(id: String) = io {
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
        scope.launch {
            ready.join()                                  // need the loaded playlists to decide
            val marker = File(dir, "seeded.txt")
            if (marker.exists()) return@launch
            runCatching { marker.writeText("1") }         // seed exactly once, success or not
            if (_playlists.value.isNotEmpty()) return@launch  // user already has playlists: don't seed

            val id = UUID.randomUUID().toString()
            val pl = Playlist(
                id = id,
                name = name.ifBlank { hostOf(url) },
                source = PlaylistSource.URL,
                uri = url.trim(),
                epgUrl = null,
                addedAt = now(),
                epgMode = EpgMode.PLAYLIST,
            )
            _playlists.value = listOf(pl)
            persistPlaylists()
            _activePlaylistId.value = id
            writeActiveId(id)
            runCatching { refresh(id) }
        }
    }

    suspend fun refresh(id: String) = guarded { reload(id) }

    /**
     * Background upkeep, run whenever the app comes to the foreground: re-downloads the active URL
     * playlist once it's older than a day, and re-checks the guide's freshness (the process can
     * outlive the 3h EPG window, especially on TV). Failures stay silent.
     */
    fun refreshIfStale() {
        scope.launch {
            ready.join()
            val pl = _playlists.value.firstOrNull { it.id == _activePlaylistId.value } ?: return@launch
            val lastLoad = maxOf(pl.refreshedAt, pl.addedAt)
            if (pl.source == PlaylistSource.URL && now() - lastLoad > playlistMaxAgeMs) {
                autoOnce("playlist:${pl.id}") {
                    trackLoading(+1)
                    try {
                        runCatching { reload(pl.id) }.onFailure { if (it is CancellationException) throw it }
                    } finally {
                        trackLoading(-1)
                    }
                }
            } else if (pl.epgUrl != null && now() - epgFetchedAt >= epgFreshnessMs) {
                autoOnce("epg:${pl.id}") { loadEpg(pl.id) }
            }
        }
    }

    /**
     * Re-reads a playlist's channels, and its url-tvg guide when that's the guide in use. The
     * download runs unlocked; the result is applied to the entry as it is *now*, so a guide change
     * or delete made meanwhile isn't overwritten (a deleted playlist's result is dropped).
     */
    private suspend fun reload(id: String) {
        val source = _playlists.value.firstOrNull { it.id == id } ?: return
        val m3u = when (source.source) {
            PlaylistSource.URL -> fetchPlaylist(source.uri, id)
            PlaylistSource.FILE -> readPlaylistFile(source.uri, id)
        }
        val parsed = m3u.channels
        require(parsed.isNotEmpty()) { text(R.string.error_empty_after_refresh) }
        val isActive = playlistLock.withLock {
            val pl = _playlists.value.firstOrNull { it.id == id } ?: return
            writeChannels(id, parsed)
            migrateChannelUrls(id, parsed)
            val mode = pl.guideMode
            replacePlaylist(
                pl.copy(
                    epgMode = mode,   // stored explicitly from now on (see Playlist.guideMode)
                    epgUrl = resolveEpgUrl(mode, headerUrl = m3u.epgUrl, customUrl = pl.epgUrl),
                    playlistEpgUrl = m3u.epgUrl,
                    refreshedAt = now(),
                ),
            )
            deleteFile("epg_$id.json")   // the channel set (and maybe the guide URL) changed
            (_activePlaylistId.value == id).also { if (it) _channels.value = parsed }
        }
        if (isActive) loadEpg(id)
    }

    /**
     * Channels used to keep a Kodi-style `url|User-Agent=…` suffix in their url; M3uParser now
     * strips it, which changes their [Channel.key]. Re-point this playlist's saved favorites,
     * recents and hidden entries at the stripped urls so none are lost.
     */
    private fun migrateChannelUrls(id: String, channels: List<Channel>) {
        val urls = channels.mapTo(HashSet()) { it.url }
        val prefix = "$id|"
        fun key(k: String) = if (k.startsWith(prefix)) prefix + migratedUrl(k.removePrefix(prefix), urls) else k

        val favorites = _favorites.value
        if (favorites.any { key(it) != it }) {
            _favorites.update { set -> set.mapTo(LinkedHashSet(), ::key) }
            persistFavorites()
        }
        val recents = _recents.value
        if (recents.any { key(it) != it }) {
            _recents.update { list -> list.map(::key).distinct() }
            persistRecents()
        }
        val hidden = _hidden.value[id] ?: return
        if ((hidden.channels + hidden.unhidden).any { migratedUrl(it, urls) != it }) {
            updateHidden(id) { h ->
                h.copy(
                    channels = h.channels.mapTo(HashSet()) { migratedUrl(it, urls) },
                    unhidden = h.unhidden.mapTo(HashSet()) { migratedUrl(it, urls) },
                )
            }
        }
    }

    private fun replacePlaylist(updated: Playlist) {
        _playlists.update { list -> list.map { if (it.id == updated.id) updated else it } }
        persistPlaylists()
    }

    private fun resolveEpgUrl(mode: EpgMode, headerUrl: String?, customUrl: String?): String? = when (mode) {
        EpgMode.PLAYLIST -> headerUrl
        EpgMode.CUSTOM -> customUrl
        EpgMode.OFF -> null
    }

    /** Runs [block] unless a background job with the same [key] is already running. */
    private inline fun autoOnce(key: String, block: () -> Unit) {
        if (!autoJobs.add(key)) return
        try { block() } finally { autoJobs.remove(key) }
    }

    suspend fun deletePlaylist(id: String) = io { playlistLock.withLock { deleteLocked(id) } }

    private fun deleteLocked(id: String) {
        // Under the guide-commit lock, so a download still in flight can't re-create its files.
        synchronized(epgGenerations) {
            epgGenerations.remove(id)
            _playlists.update { list -> list.filterNot { it.id == id } }
        }
        persistPlaylists()
        deleteFile("channels_$id.json")
        deleteFile("epg_$id.json")
        deleteFile("hidden_$id.json")
        _hidden.update { it - id }
        // Drop the playlist's favorites/recents too (keys are "$id|url", see Channel.key).
        val prefix = "$id|"
        _favorites.update { set -> set.filterNot { it.startsWith(prefix) }.toSet() }
        persistFavorites()
        _recents.update { list -> list.filterNot { it.startsWith(prefix) } }
        persistRecents()
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

    /**
     * Sets where the guide comes from (see [EpgMode]); [customUrl] is only used for CUSTOM.
     * Saving the dialog unchanged is a no-op, so it never pins the automatic URL as a custom one.
     */
    suspend fun setEpgMode(id: String, mode: EpgMode, customUrl: String?) = guarded {
        val custom = customUrl?.trim()?.ifBlank { null }
        require(mode != EpgMode.CUSTOM || custom != null) { text(R.string.error_no_epg_url) }
        playlistLock.withLock {
            val pl = _playlists.value.firstOrNull { it.id == id } ?: return@guarded
            val url = resolveEpgUrl(mode, headerUrl = pl.playlistEpgUrl, customUrl = custom)
            if (mode == pl.guideMode && url == pl.epgUrl) return@guarded
            replacePlaylist(pl.copy(epgMode = mode, epgUrl = url))
            deleteFile("epg_$id.json")
        }
        if (_activePlaylistId.value == id) loadEpg(id)
    }

    suspend fun refreshEpg(id: String) = guarded {
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return@guarded
        if (pl.epgUrl == null) error(text(R.string.error_no_epg_url))
        deleteFile("epg_$id.json")
        loadEpg(id)
    }

    /** Programmes for a channel's [Channel.epgKey], sorted by start time. */
    fun scheduleFor(epgKey: String?): List<Programme> =
        if (epgKey.isNullOrBlank()) emptyList() else _epg.value[epgKey].orEmpty()

    // ---- Hidden channels / groups ---------------------------------------

    /** Channels of any saved playlist (for the playlist editor); the active one comes from memory. */
    suspend fun channelsFor(id: String): List<Channel> = io { channelsOf(id) }

    suspend fun setChannelHidden(channel: Channel, hidden: Boolean) = io {
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
        io {
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
        io {
            updateHidden(playlistId) { h ->
                if (hidden) h.copy(channels = h.channels + urls, unhidden = h.unhidden - urls.toSet())
                else h.copy(channels = h.channels - urls.toSet())
            }
        }

    private fun updateHidden(playlistId: String, transform: (HiddenState) -> HiddenState) {
        val name = "hidden_$playlistId.json"
        _hidden.update { map ->
            val next = transform(map[playlistId] ?: HiddenState())
            if (next.isEmpty) map - playlistId else map + (playlistId to next)
        }
        // Persist whatever is current when the lock is held, so racing toggles can't write stale state.
        withFileLock(name) {
            val current = _hidden.value[playlistId]
            if (current == null) deleteUnlocked(name)
            else writeUnlocked(name, json.encodeToString(HiddenState.serializer(), current))
        }
    }

    // ---- Favorites / recents --------------------------------------------

    suspend fun toggleFavorite(channel: Channel) = io {
        _favorites.update { set -> if (channel.key in set) set - channel.key else set + channel.key }
        persistFavorites()
    }

    suspend fun recordRecent(channel: Channel) = io {
        _recents.update { (listOf(channel.key) + it.filterNot { k -> k == channel.key }).take(50) }
        persistRecents()
    }

    fun clearError() { _error.value = null }

    /** The last uncaught crash written by TvPlayerApp (tail only, to fit in a share Intent), or null. */
    suspend fun crashLog(): String? = withContext(Dispatchers.IO) {
        runCatching { File(dir, CRASH_LOG_FILE).takeIf { it.exists() }?.readText()?.takeLast(100_000) }
            .getOrNull()
    }

    suspend fun clearCrashLog() = withContext(Dispatchers.IO) { File(dir, CRASH_LOG_FILE).delete() }

    fun setCastAsHls(value: Boolean) {
        _castAsHls.value = value
        scope.launch { ready.join(); writeBoolFile("cast_hls.txt", value) }
    }

    private fun readBoolFile(name: String): Boolean = readText(name)?.trim() == "true"

    private fun writeBoolFile(name: String, value: Boolean) = writeText(name) { value.toString() }

    // ---- Internals -------------------------------------------------------

    private fun activate(id: String, channels: List<Channel>) {
        _activePlaylistId.value = id
        writeActiveId(id)
        _channels.value = channels
        _epg.value = emptyMap()
        epgFetchedAt = 0L
        val pl = _playlists.value.firstOrNull { it.id == id }
        if (pl?.epgUrl != null) scope.launch { autoOnce("epg:$id") { loadEpg(id) } }
    }

    private fun channelsOf(id: String): List<Channel> =
        if (_activePlaylistId.value == id) _channels.value else readChannels(id)

    /**
     * Loads the playlist's current guide from cache when fresh, otherwise fetches + parses it;
     * with no guide URL it clears the guide. Failures are non-fatal. Only the latest load started
     * for a playlist may show or cache its result (see [epgGenerations]).
     */
    private fun loadEpg(id: String) {
        val generation = epgGenerations.merge(id, 1L, Long::plus)!!
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return
        val epgUrl = pl.epgUrl ?: return commitEpg(id, generation) { showEpg(id, null) }
        val cache = readEpgCache(id)
        if (cache != null && (now() - cache.fetchedAt) < epgFreshnessMs) {
            return commitEpg(id, generation) { showEpg(id, cache) }
        }
        // Only keep guide entries for channels this playlist actually has — full XMLTV guides are
        // mostly other channels, which would bloat memory and the on-disk cache. Channels without
        // a tvg-id are looked up by name instead.
        val channels = channelsOf(id)
        val ids = channels.mapNotNullTo(HashSet()) { it.tvgId }
        val names = channels.filter { it.tvgId == null }
            .mapNotNullTo(HashSet()) { EpgNames.normalize(it.tvgName ?: it.name).ifEmpty { null } }
        runCatching { fetchAndParseEpg(epgUrl, ids, names) }
            .onSuccess { guide ->
                val fresh = EpgCache(now(), guide.programmes, guide.idsByName)
                commitEpg(id, generation) {
                    writeJson("epg_$id.json", EpgCache.serializer(), fresh)
                    showEpg(id, fresh)
                }
            }
            .onFailure { if (cache != null) commitEpg(id, generation) { showEpg(id, cache) } }
    }

    /** Runs [block] only if load [generation] is still the latest for a playlist that still exists. */
    private inline fun commitEpg(id: String, generation: Long, block: () -> Unit) = synchronized(epgGenerations) {
        if (epgGenerations[id] == generation && _playlists.value.any { it.id == id }) block()
    }

    /** Shows [cache] (null = no guide) if [playlistId] is the active playlist. */
    private fun showEpg(playlistId: String, cache: EpgCache?) {
        if (_activePlaylistId.value != playlistId) return
        _epg.value = cache?.byEpgKey().orEmpty()
        epgFetchedAt = cache?.fetchedAt ?: 0L
    }

    private fun fetchAndParseEpg(url: String, channelIds: Set<String>, channelNames: Set<String>): XmltvGuide {
        val nowMs = now()
        return fetch(url, R.string.error_epg_server_http) {
            XmltvParser.parse(it, nowMs - epgWindowPastMs, nowMs + epgWindowFutureMs, channelIds, channelNames)
        }
    }

    /** Runs on IO after the initial load; for state changes that don't drive [loading]/[error]. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        ready.join()
        block()
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        trackLoading(+1)
        _error.value = null
        try {
            io { block() }
        } catch (e: CancellationException) {
            throw e   // the caller went away; that's not an error to show
        } catch (e: Exception) {
            _error.value = e.message ?: text(R.string.error_generic)
        } finally {
            trackLoading(-1)
        }
    }

    private fun trackLoading(delta: Int) = synchronized(this) {
        activeOps += delta
        _loading.value = activeOps > 0
    }

    /** Streams and parses an M3U playlist straight from the response (no full-body String). */
    private fun fetchPlaylist(url: String, id: String): M3uPlaylist =
        fetch(url, R.string.error_server_http) { M3uParser.parsePlaylist(it, id) }

    private fun <T> fetch(url: String, @StringRes httpError: Int, read: (InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error(context.getString(httpError, resp.code))
            return resp.body.byteStream().gunzipIfCompressed().use(read)
        }
    }

    private fun readPlaylistFile(uriString: String, id: String): M3uPlaylist {
        val uri = Uri.parse(uriString)
        return context.contentResolver.openInputStream(uri)?.use { M3uParser.parsePlaylist(it, id) }
            ?: error(text(R.string.error_read_file))
    }

    private fun writeChannels(id: String, channels: List<Channel>) =
        writeJson("channels_$id.json", ListSerializer(Channel.serializer()), channels)

    // distinctBy: channel files saved before M3uParser de-duplicated urls may still hold repeats.
    private fun readChannels(id: String): List<Channel> =
        readJson("channels_$id.json", ListSerializer(Channel.serializer()), emptyList()).distinctBy { it.url }

    private fun readEpgCache(id: String): EpgCache? =
        readText("epg_$id.json")?.let { runCatching { json.decodeFromString(EpgCache.serializer(), it) }.getOrNull() }

    private fun persistPlaylists() =
        writeText("playlists.json") { json.encodeToString(ListSerializer(Playlist.serializer()), _playlists.value) }

    private fun persistFavorites() =
        writeText("favorites.json") { json.encodeToString(ListSerializer(String.serializer()), _favorites.value.toList()) }

    private fun persistRecents() =
        writeText("recents.json") { json.encodeToString(ListSerializer(String.serializer()), _recents.value) }

    private fun readActiveId(): String? = readText("active.txt")?.ifBlank { null }

    private fun writeActiveId(id: String?) = writeText("active.txt") { id ?: "" }

    private fun <T> readJson(name: String, serializer: KSerializer<T>, default: T): T =
        readText(name)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default

    private fun <T> writeJson(name: String, serializer: KSerializer<T>, value: T) =
        writeText(name) { json.encodeToString(serializer, value) }

    // ---- Files: atomic (write to a side file, then rename) so a crash mid-write never leaves a
    // truncated file that would silently load as the default. -----------------------------------

    private fun atomic(name: String) = AtomicFile(File(dir, name))

    private inline fun <T> withFileLock(name: String, block: () -> T): T =
        synchronized(fileLocks.computeIfAbsent(name) { Any() }, block)

    /** Null when the file doesn't exist or can't be read. */
    private fun readText(name: String): String? = withFileLock(name) {
        runCatching { atomic(name).readText() }.getOrNull()
    }

    /** [text] is evaluated under the file's lock, so the latest in-memory state is what gets written. */
    private fun writeText(name: String, text: () -> String) = withFileLock(name) {
        writeUnlocked(name, text())
    }

    private fun deleteFile(name: String) = withFileLock(name) { deleteUnlocked(name) }

    private fun writeUnlocked(name: String, text: String) {
        runCatching { atomic(name).writeText(text) }
    }

    private fun deleteUnlocked(name: String) = atomic(name).delete()

    private fun now() = System.currentTimeMillis()

    private fun text(@StringRes id: Int): String = context.getString(id)

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull() ?: text(R.string.default_playlist_name)

    /**
     * Gunzips when the stream starts with the gzip magic bytes. The URL can't be trusted for this:
     * `guide.xml.gz?token=…` hides the extension and some servers serve gzip under any name.
     */
    private fun InputStream.gunzipIfCompressed(): InputStream {
        val buffered = BufferedInputStream(this)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    companion object {
        /** Written by the uncaught-exception handler in TvPlayerApp. */
        const val CRASH_LOG_FILE = "crash_log.txt"
    }
}
