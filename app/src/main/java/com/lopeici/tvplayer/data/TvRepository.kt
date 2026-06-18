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

/**
 * Single source of truth for playlists, channels, favorites and recents.
 * Persists to small JSON files in [Context.getFilesDir] (no Room / annotation processors).
 */
class TvRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = context.filesDir

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        _playlists.value = readJson("playlists.json", ListSerializer(Playlist.serializer()), emptyList())
        _favorites.value = readJson("favorites.json", ListSerializer(String.serializer()), emptyList()).toSet()
        _recents.value = readJson("recents.json", ListSerializer(String.serializer()), emptyList())
        val active = readActiveId()?.takeIf { id -> _playlists.value.any { it.id == id } }
        _activePlaylistId.value = active
        if (active != null) scope.launch { _channels.value = readChannels(active) }
    }

    // ---- Playlists -------------------------------------------------------

    suspend fun addUrlPlaylist(name: String, url: String) = guarded {
        val id = UUID.randomUUID().toString()
        val text = fetchUrl(url.trim())
        val parsed = M3uParser.parse(text, id)
        require(parsed.isNotEmpty()) { "No channels found in that playlist." }
        writeChannels(id, parsed)
        val pl = Playlist(id, name.ifBlank { hostOf(url) }, PlaylistSource.URL, url.trim(), now())
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
        val pl = Playlist(id, name.ifBlank { "Imported playlist" }, PlaylistSource.FILE, contentUri, now())
        _playlists.update { it + pl }
        persistPlaylists()
        activate(id, parsed)
    }

    suspend fun setActive(id: String) = withContext(Dispatchers.IO) {
        activate(id, readChannels(id))
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
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        _playlists.update { list -> list.filterNot { it.id == id } }
        persistPlaylists()
        File(dir, "channels_$id.json").delete()
        if (_activePlaylistId.value == id) {
            val next = _playlists.value.firstOrNull()
            if (next != null) activate(next.id, readChannels(next.id))
            else { _activePlaylistId.value = null; writeActiveId(null); _channels.value = emptyList() }
        }
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

    // ---- Internals -------------------------------------------------------

    private fun activate(id: String, channels: List<Channel>) {
        _activePlaylistId.value = id
        writeActiveId(id)
        _channels.value = channels
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
