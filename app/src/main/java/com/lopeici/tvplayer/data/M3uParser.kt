package com.lopeici.tvplayer.data

import java.io.InputStream
import java.net.URLDecoder

/**
 * Tolerant parser for IPTV M3U / M3U8 playlists (the channel-list format, not an HLS manifest).
 *
 * Handles `#EXTM3U` (with its `url-tvg` guide URL), `#EXTINF:<dur> key="value"...,Display Name`,
 * the `#EXTGRP:` and `#EXTVLCOPT:` directives, and the URL line that follows each `#EXTINF`.
 * Unknown lines are skipped rather than failing.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(content: String, playlistId: String): List<Channel> =
        parsePlaylist(content.lineSequence(), playlistId).channels

    /** Parses line by line as the stream is read, so a large playlist is never held as one String. */
    fun parsePlaylist(input: InputStream, playlistId: String): M3uPlaylist =
        input.bufferedReader(Charsets.UTF_8).useLines { parsePlaylist(it, playlistId) }

    fun parsePlaylist(content: String, playlistId: String): M3uPlaylist =
        parsePlaylist(content.lineSequence(), playlistId)

    fun parsePlaylist(lines: Sequence<String>, playlistId: String): M3uPlaylist {
        val channels = mutableListOf<Channel>()
        var epgUrl: String? = null

        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var tvgName: String? = null
        var number: Int? = null
        var userAgent: String? = null
        var referrer: String? = null

        fun reset() {
            name = null; logo = null; group = null; tvgId = null; tvgName = null; number = null
            userAgent = null; referrer = null
        }

        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    // Header attributes: url-tvg / x-tvg-url name the playlist's own XMLTV guide
                    // (sometimes several, comma-separated — the first is used).
                    val attrs = attributes(line)
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                        ?: epgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIdx = nameSeparatorIndex(line)
                    val display = if (commaIdx != -1) line.substring(commaIdx + 1).trim() else ""
                    val attrs = attributes(line)
                    tvgName = attrs["tvg-name"]?.trim()?.ifBlank { null }
                    name = display.ifBlank { tvgName.orEmpty() }.ifBlank { null }
                    // Only overwrite when the attribute is present, so a separate #EXTGRP
                    // directive (which may appear before or after #EXTINF) is not clobbered.
                    attrs["tvg-logo"]?.ifBlank { null }?.let { logo = it }
                    attrs["group-title"]?.ifBlank { null }?.let { group = it }
                    attrs["tvg-id"]?.ifBlank { null }?.let { tvgId = it }
                    number = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull()
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    group = line.substringAfter(':').trim().ifBlank { group }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim().ifBlank { null }
                    when (option.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> userAgent = value ?: userAgent
                        "http-referrer", "http-referer" -> referrer = value ?: referrer
                    }
                }

                line.startsWith("#") -> { /* other directive — ignore */ }

                else -> {
                    // A media URL line completes the current entry. Kodi-style playlists append
                    // headers after a pipe: `http://host/stream|User-Agent=x&Referer=y`.
                    val url = line.substringBefore('|').trim()
                    val piped = pipeHeaders(line)
                    channels += Channel(
                        name = name ?: url.substringAfterLast('/').ifBlank { "Channel ${channels.size + 1}" },
                        url = url,
                        logo = logo,
                        group = group,
                        tvgId = tvgId,
                        playlistId = playlistId,
                        tvgName = tvgName,
                        number = number,
                        userAgent = userAgent ?: piped["user-agent"],
                        referrer = referrer ?: piped["referer"] ?: piped["referrer"],
                    )
                    reset()
                }
            }
        }
        // The same stream often appears under several groups; keep the first so Channel.key
        // (playlistId|url) stays unique — lazy-list keys and now-playing detection rely on it.
        return M3uPlaylist(channels.distinctBy { it.url }, epgUrl)
    }

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** `url|Key=value&Key2=value2` → lowercase keys to url-decoded values. */
    private fun pipeHeaders(line: String): Map<String, String> {
        val spec = line.substringAfter('|', "").ifBlank { return emptyMap() }
        return spec.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').trim().lowercase()
            val value = pair.substringAfter('=', "").trim()
            if (key.isEmpty() || value.isEmpty()) null
            else key to (runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value)
        }.toMap()
    }

    /** Index of the comma that starts the display name: the first one outside quoted attributes. */
    private fun nameSeparatorIndex(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return i
            }
        }
        return -1
    }
}

/** A parsed playlist: its channels plus the guide URL from the `#EXTM3U` header, if any. */
data class M3uPlaylist(val channels: List<Channel>, val epgUrl: String?)
