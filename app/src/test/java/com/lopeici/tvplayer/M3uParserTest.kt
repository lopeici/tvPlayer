package com.lopeici.tvplayer

import com.lopeici.tvplayer.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun parsesAttributesAndUrls() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1" tvg-logo="http://logo/bbc.png" group-title="UK",BBC One
            http://example.com/bbc1.m3u8
            #EXTINF:-1 group-title="News",CNN
            http://example.com/cnn.ts
        """.trimIndent()

        val channels = M3uParser.parse(m3u, "pl1")

        assertEquals(2, channels.size)
        assertEquals("BBC One", channels[0].name)
        assertEquals("UK", channels[0].group)
        assertEquals("http://logo/bbc.png", channels[0].logo)
        assertEquals("bbc1", channels[0].tvgId)
        assertEquals("http://example.com/bbc1.m3u8", channels[0].url)
        assertEquals("pl1", channels[0].playlistId)
        assertEquals("CNN", channels[1].name)
        assertEquals("News", channels[1].group)
        assertNull(channels[1].logo)
    }

    @Test
    fun handlesExtGrpDirective() {
        val m3u = "#EXTM3U\n#EXTGRP:Sports\n#EXTINF:-1,ESPN\nhttp://host/espn"
        val channels = M3uParser.parse(m3u, "p")
        assertEquals(1, channels.size)
        assertEquals("ESPN", channels[0].name)
        assertEquals("Sports", channels[0].group)
    }

    @Test
    fun toleratesMissingHeaderAndNames() {
        val m3u = "#EXTINF:-1,\nhttp://host/stream1\nhttp://host/stream2.ts"
        val channels = M3uParser.parse(m3u, "p")
        assertEquals(2, channels.size)
        assertEquals("stream1", channels[0].name)
        assertEquals("stream2.ts", channels[1].name)
    }

    @Test
    fun commaInsideAttributeDoesNotSplitName() {
        val m3u = "#EXTINF:-1 tvg-id=\"hbo\" group-title=\"Movies, HD\",HBO, East\nhttp://host/hbo"
        val channels = M3uParser.parse(m3u, "p")
        assertEquals("HBO, East", channels[0].name)
        assertEquals("Movies, HD", channels[0].group)
        assertEquals("hbo", channels[0].tvgId)
    }

    @Test
    fun duplicateUrlsKeepFirstOccurrence() {
        val m3u = """
            #EXTINF:-1 group-title="Sports",ESPN
            http://host/espn
            #EXTINF:-1 group-title="Favorites",ESPN HD
            http://host/espn
            #EXTINF:-1,CNN
            http://host/cnn
        """.trimIndent()
        val channels = M3uParser.parse(m3u, "p")
        assertEquals(listOf("ESPN", "CNN"), channels.map { it.name })
        assertEquals("Sports", channels[0].group)
    }

    @Test
    fun parsesFromStreamWithBom() {
        val m3u = "\uFEFF#EXTM3U\n#EXTINF:-1 group-title=\"News\",CNN\nhttp://host/cnn\n"
        val channels = M3uParser.parsePlaylist(m3u.byteInputStream(), "p").channels
        assertEquals(1, channels.size)
        assertEquals("CNN", channels[0].name)
        assertEquals("News", channels[0].group)
    }

    @Test
    fun headerGuideUrlIsReturned() {
        val m3u = """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://other/guide.xml" tvg-shift="0"
            #EXTINF:-1,One
            http://example.com/1.ts
        """.trimIndent()

        assertEquals("http://epg.example/guide.xml.gz", M3uParser.parsePlaylist(m3u, "p").epgUrl)
        assertEquals(
            "http://x/g.xml",
            M3uParser.parsePlaylist("#EXTM3U x-tvg-url=\"http://x/g.xml\"\n#EXTINF:-1,A\nhttp://a", "p").epgUrl,
        )
        assertNull(M3uParser.parsePlaylist("#EXTM3U\n#EXTINF:-1,A\nhttp://a", "p").epgUrl)
    }

    @Test
    fun vlcOptionsSetPerChannelHeaders() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,With headers
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11)
            #EXTVLCOPT:http-referrer=http://site.example/
            http://example.com/1.m3u8
            #EXTINF:-1,Plain
            http://example.com/2.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(m3u, "p")

        assertEquals("Mozilla/5.0 (X11)", channels[0].userAgent)
        assertEquals("http://site.example/", channels[0].referrer)
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0 (X11)", "Referer" to "http://site.example/"),
            channels[0].httpHeaders,
        )
        assertNull(channels[1].userAgent)
        assertEquals(emptyMap<String, String>(), channels[1].httpHeaders)
    }

    @Test
    fun pipeHeadersAreStrippedFromUrl() {
        val m3u = "#EXTINF:-1,Kodi\nhttp://example.com/1.ts|User-Agent=My%20Agent&Referer=http://r/"

        val channel = M3uParser.parse(m3u, "p").single()

        assertEquals("http://example.com/1.ts", channel.url)
        assertEquals("My Agent", channel.userAgent)
        assertEquals("http://r/", channel.referrer)
    }

    @Test
    fun channelNumberAndTvgNameAreParsed() {
        val m3u = """
            #EXTINF:-1 tvg-chno="101" tvg-name="BBC One HD",BBC 1
            http://example.com/1.ts
            #EXTINF:-1 tvg-chno="abc",Two
            http://example.com/2.ts
        """.trimIndent()

        val channels = M3uParser.parse(m3u, "p")

        assertEquals(101, channels[0].number)
        assertEquals("BBC One HD", channels[0].tvgName)
        assertEquals("BBC 1", channels[0].name)
        assertNull(channels[1].number)
        assertNull(channels[1].tvgName)
    }
}
