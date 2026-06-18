package com.lopeici.tvplayer.data

/**
 * Tolerant parser for IPTV M3U / M3U8 playlists (the channel-list format, not an HLS manifest).
 *
 * Handles `#EXTM3U`, `#EXTINF:<dur> key="value"...,Display Name`, the `#EXTGRP:` directive,
 * and the URL line that follows each `#EXTINF`. Unknown lines are skipped rather than failing.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(content: String, playlistId: String): List<Channel> {
        val channels = mutableListOf<Channel>()

        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null

        fun reset() {
            name = null; logo = null; group = null; tvgId = null
        }

        for (raw in content.lineSequence()) {
            val line = raw.trim().removePrefix("﻿").trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> { /* header */ }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIdx = line.indexOf(',')
                    val display = if (commaIdx != -1) line.substring(commaIdx + 1).trim() else ""
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = display.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { null }
                    // Only overwrite when the attribute is present, so a separate #EXTGRP
                    // directive (which may appear before or after #EXTINF) is not clobbered.
                    attrs["tvg-logo"]?.ifBlank { null }?.let { logo = it }
                    attrs["group-title"]?.ifBlank { null }?.let { group = it }
                    attrs["tvg-id"]?.ifBlank { null }?.let { tvgId = it }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    group = line.substringAfter(':').trim().ifBlank { group }

                line.startsWith("#") -> { /* other directive — ignore */ }

                else -> {
                    // A media URL line completes the current entry.
                    channels += Channel(
                        name = name ?: line.substringAfterLast('/').ifBlank { "Channel ${channels.size + 1}" },
                        url = line,
                        logo = logo,
                        group = group,
                        tvgId = tvgId,
                        playlistId = playlistId,
                    )
                    reset()
                }
            }
        }
        return channels
    }
}
