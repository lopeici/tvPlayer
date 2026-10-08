package com.lopeici.tvplayer.data

import kotlinx.serialization.Serializable

/** A single EPG programme (from an XMLTV `<programme>`), matched to a channel by [channelId] (see [Channel.epgKey]). */
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
    /** Normalized channel name → XMLTV channel id, for channels matched by name (see [EpgNames]). */
    val idsByName: Map<String, String> = emptyMap(),
) {
    /** Programmes keyed the way channels look them up ([Channel.epgKey]): by id and by name key. */
    fun byEpgKey(): Map<String, List<Programme>> {
        if (idsByName.isEmpty()) return programmes
        val byName = idsByName.mapNotNull { (name, id) ->
            programmes[id]?.let { EpgNames.keyOfNormalized(name) to it }
        }
        return programmes + byName
    }
}
