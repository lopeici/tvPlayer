package com.lopeici.tvplayer.data

import kotlinx.serialization.Serializable

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
    /** Optional XMLTV (EPG) guide URL for this playlist. */
    val epgUrl: String? = null,
    val addedAt: Long = 0L,
)

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
) {
    /** Stable identity used for favorites / recents / the active media id. */
    val key: String get() = "$playlistId|$url"
}
