package com.lopeici.tvplayer

import com.lopeici.tvplayer.data.EpgMode
import com.lopeici.tvplayer.data.Playlist
import com.lopeici.tvplayer.data.PlaylistSource
import com.lopeici.tvplayer.data.hlsVariant
import com.lopeici.tvplayer.data.migratedUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUrlsTest {

    @Test
    fun tsBecomesM3u8() {
        assertEquals("http://h:8080/live/u/p/123.m3u8", hlsVariant("http://h:8080/live/u/p/123.ts"))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals("http://h/live/1.m3u8", hlsVariant("http://h/live/1.TS"))
        assertEquals("http://h/live/1.m3u8", hlsVariant("http://h/live/1.mpegts"))
    }

    @Test
    fun noExtensionGetsM3u8Appended() {
        assertEquals("http://h/u/p/123.m3u8", hlsVariant("http://h/u/p/123"))
    }

    @Test
    fun queryStringIsPreserved() {
        assertEquals("http://h/live/1.m3u8?token=a.b", hlsVariant("http://h/live/1.ts?token=a.b"))
    }

    @Test
    fun m3u8AndUnknownExtensionsAreUnchanged() {
        assertEquals("http://h/live/1.m3u8", hlsVariant("http://h/live/1.m3u8"))
        assertEquals("http://h/movie/1.mp4", hlsVariant("http://h/movie/1.mp4"))
    }

    @Test
    fun kodiSuffixIsMigratedOnlyWhenStrippedUrlExists() {
        val current = setOf("http://h/1.ts")
        assertEquals("http://h/1.ts", migratedUrl("http://h/1.ts|User-Agent=x", current))
        assertEquals("http://h/2.ts|User-Agent=x", migratedUrl("http://h/2.ts|User-Agent=x", current))
        assertEquals("http://h/1.ts", migratedUrl("http://h/1.ts", current))
    }

    @Test
    fun olderPlaylistsInferTheirGuideMode() {
        fun pl(epgUrl: String?, mode: EpgMode? = null) =
            Playlist("id", "n", PlaylistSource.URL, "http://p", epgUrl = epgUrl, epgMode = mode)
        assertEquals(EpgMode.CUSTOM, pl("http://g").guideMode)
        assertEquals(EpgMode.PLAYLIST, pl(null).guideMode)
        assertEquals(EpgMode.OFF, pl(null, EpgMode.OFF).guideMode)
        assertEquals(EpgMode.PLAYLIST, pl("http://g", EpgMode.PLAYLIST).guideMode)
    }
}
