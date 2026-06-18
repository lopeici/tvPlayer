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

    fun parse(input: InputStream, windowStart: Long, windowEnd: Long): Map<String, List<Programme>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null) // null => auto-detect encoding from the XML declaration

        val byChannel = HashMap<String, MutableList<Programme>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                readProgramme(parser)?.let { p ->
                    if (p.stop > windowStart && p.start < windowEnd) {
                        byChannel.getOrPut(p.channelId) { mutableListOf() }.add(p)
                    }
                }
            }
            event = parser.next()
        }
        return byChannel.mapValues { (_, list) -> list.sortedBy { it.start } }
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

    /** Parser at a START_TAG; returns its text content and leaves parser on the matching END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.next()
        }
        return text
    }

    private fun parseTime(value: String?): Long? {
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
