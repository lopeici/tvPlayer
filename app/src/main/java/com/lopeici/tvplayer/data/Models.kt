package com.lopeici.tvplayer.data

import kotlinx.serialization.Serializable

/** Where a playlist's guide comes from (chosen in the guide dialog). */
@Serializable
enum class EpgMode {
    /** Follow the playlist's own `url-tvg` / `x-tvg-url` header ([Playlist.playlistEpgUrl]). */
    PLAYLIST,
    /** A URL the user typed ([Playlist.epgUrl]). */
    CUSTOM,
    /** No guide. */
    OFF,
}

/** Where a playlist's M3U content comes from. */
@Serializable
enum class PlaylistSource { URL, FILE }

/** A saved playlist (remote URL or imported local file). */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val source: PlaylistSource,
    /** http(s) URL for [PlaylistSource.URL], or a content:// uri string for [PlaylistSource.FILE]. */
    val uri: String,
    /** The XMLTV (EPG) guide URL in use (null = no guide), resolved from [guideMode]. */
    val epgUrl: String? = null,
    val addedAt: Long = 0L,
    /**
     * Where [epgUrl] comes from. Null on playlists saved before this existed: see [guideMode].
     */
    val epgMode: EpgMode? = null,
    /** Guide URL from the playlist's `#EXTM3U` header at the last load. */
    val playlistEpgUrl: String? = null,
    /** Last successful channel load; 0 = not tracked yet (use [addedAt]). */
    val refreshedAt: Long = 0L,
) {
    /**
     * [epgMode], or for older playlists: a URL they had was typed by the user (keep it), none
     * means follow the playlist's header.
     */
    val guideMode: EpgMode get() = epgMode ?: if (epgUrl != null) EpgMode.CUSTOM else EpgMode.PLAYLIST
}

/**
 * Which parts of a playlist the user has hidden (persisted per playlist as hidden_<id>.json).
 *
 * Hiding a *group* stores its name, so channels that appear in that group after a refresh stay
 * hidden too. [unhidden] holds urls the user re-enabled inside a hidden group; [channels] holds
 * urls hidden individually. A channel is hidden iff its url is in [channels], or its group is in
 * [groups] and its url is not in [unhidden].
 */
@Serializable
data class HiddenState(
    val groups: Set<String> = emptySet(),
    val channels: Set<String> = emptySet(),
    val unhidden: Set<String> = emptySet(),
) {
    fun isHidden(channel: Channel): Boolean =
        channel.url in channels || (channel.group in groups && channel.url !in unhidden)

    val isEmpty: Boolean get() = groups.isEmpty() && channels.isEmpty() && unhidden.isEmpty()
}

/** A single TV channel parsed from an M3U playlist. */
@Serializable
data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val playlistId: String = "",
    /** `tvg-name`, the EPG name to match on when there's no [tvgId] (falls back to [name]). */
    val tvgName: String? = null,
    /** `tvg-chno`: the provider's channel number, used by "Go to channel". */
    val number: Int? = null,
    /** Per-channel HTTP headers from `#EXTVLCOPT:http-user-agent=` / `http-referrer=`. */
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    /** Stable identity used for favorites / recents / the active media id. */
    val key: String get() = "$playlistId|$url"

    /** Key into the EPG map: the tvg-id, or a normalized-name key when there is none. */
    val epgKey: String? get() = tvgId ?: EpgNames.key(tvgName ?: name)

    /** Extra request headers this stream needs (empty for most channels). */
    val httpHeaders: Map<String, String>
        get() = buildMap {
            userAgent?.let { put("User-Agent", it) }
            referrer?.let { put("Referer", it) }
        }
}
