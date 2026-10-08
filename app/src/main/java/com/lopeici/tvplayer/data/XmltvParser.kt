package com.lopeici.tvplayer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Streaming XMLTV parser (uses the platform [XmlPullParser], no extra dependency).
 * Reads `<programme start=".." stop=".." channel="..">` entries with `<title>`/`<desc>`,
 * keeping only those overlapping the given time window to bound memory/storage.
 */
object XmltvParser {

    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * @param channelIds when non-null, programmes for any other channel are skipped without being
     *   parsed (full guides are mostly channels the playlist doesn't have).
     * @param channelNames normalized names (see [EpgNames.normalize]) of playlist channels that have
     *   no tvg-id. A guide `<channel>` whose `<display-name>` matches one is kept too, and reported
     *   in [XmltvGuide.idsByName]. Relies on `<channel>` elements preceding the programmes, as the
     *   XMLTV DTD requires.
     */
    fun parse(
        input: InputStream,
        windowStart: Long,
        windowEnd: Long,
        channelIds: Set<String>? = null,
        channelNames: Set<String> = emptySet(),
    ): XmltvGuide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null) // null => auto-detect encoding from the XML declaration

        val byChannel = HashMap<String, MutableList<Programme>>()
        val idsByName = HashMap<String, String>()
        val matchedIds = HashSet<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "channel" -> {
                    val id = parser.getAttributeValue(null, "id")
                    if (channelNames.isEmpty() || id.isNullOrBlank()) {
                        skipElement(parser)
                    } else {
                        for (name in readDisplayNames(parser)) {
                            val normalized = EpgNames.normalize(name)
                            if (normalized in channelNames && normalized !in idsByName) {
                                idsByName[normalized] = id
                                matchedIds += id
                            }
                        }
                    }
                }
                "programme" -> {
                    val channel = parser.getAttributeValue(null, "channel")
                    if (channelIds != null && channel !in channelIds && channel !in matchedIds) {
                        skipElement(parser)
                    } else readProgramme(parser)?.let { p ->
                        if (p.stop > windowStart && p.start < windowEnd) {
                            byChannel.getOrPut(p.channelId) { mutableListOf() }.add(p)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return XmltvGuide(byChannel.mapValues { (_, list) -> list.sortedBy { it.start } }, idsByName)
    }

    /** Parser on a `<channel>` START_TAG; consumes through its END_TAG, returning its display names. */
    private fun readDisplayNames(parser: XmlPullParser): List<String> {
        val names = mutableListOf<String>()
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.name == "channel") break
            if (e == XmlPullParser.START_TAG && parser.name == "display-name") {
                readText(parser).takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        return names
    }

    /** Parser must be positioned on a `<programme>` START_TAG; consumes through its END_TAG. */
    private fun readProgramme(parser: XmlPullParser): Programme? {
        val channel = parser.getAttributeValue(null, "channel").orEmpty()
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null

        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.name == "programme") break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = readText(parser)
                    "desc" -> if (desc == null) desc = readText(parser)
                }
            }
        }

        if (channel.isBlank() || start == null || stop == null || title.isNullOrBlank()) return null
        return Programme(channel, title.trim(), start, stop, desc?.trim()?.ifBlank { null })
    }

    /** Parser at a START_TAG; consumes through its matching END_TAG without reading anything. */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Parser at a START_TAG; returns its text content and leaves parser on the matching END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.next()
        }
        return text
    }

    /** XMLTV `yyyyMMddHHmmss [+-]hhmm` to epoch millis; a missing/invalid offset means UTC. */
    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val t = value.trim()
        if (t.length < 14) return null
        return runCatching {
            val local = LocalDateTime.parse(t.take(14), timeFormat)
            val offset = if (t.length > 14) {
                runCatching { ZoneOffset.of(t.substring(14).trim()) }.getOrDefault(ZoneOffset.UTC)
            } else {
                ZoneOffset.UTC
            }
            local.toInstant(offset).toEpochMilli()
        }.getOrNull()
    }
}

/**
 * Programmes keyed by XMLTV channel id, plus which guide channel each name-matched playlist
 * channel resolved to (normalized name → channel id).
 */
data class XmltvGuide(
    val programmes: Map<String, List<Programme>>,
    val idsByName: Map<String, String> = emptyMap(),
)
