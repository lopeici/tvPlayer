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
    val addedAt: Long = 0L,
)

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
