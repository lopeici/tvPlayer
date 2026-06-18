package com.lopeici.tvplayer.data

import kotlinx.serialization.Serializable

/** A single EPG programme (from an XMLTV `<programme>`), matched to a channel by [channelId] = tvg-id. */
@Serializable
data class Programme(
    val channelId: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val desc: String? = null,
)

/** On-disk cache of a parsed XMLTV guide for one playlist. */
@Serializable
data class EpgCache(
    val fetchedAt: Long,
    val programmes: Map<String, List<Programme>>,
)
