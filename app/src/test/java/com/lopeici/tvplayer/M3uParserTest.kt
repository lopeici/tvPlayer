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
}
