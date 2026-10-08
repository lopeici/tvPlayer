package com.lopeici.tvplayer

import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.EpgCache
import com.lopeici.tvplayer.data.EpgNames
import com.lopeici.tvplayer.data.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgNamesTest {

    @Test
    fun normalizeIgnoresCasePunctuationPrefixTagsAndQuality() {
        val expected = "bbcone"
        assertEquals(expected, EpgNames.normalize("BBC One"))
        assertEquals(expected, EpgNames.normalize("UK: BBC One HD"))
        assertEquals(expected, EpgNames.normalize("uk | bbc.one fhd"))
        assertEquals(expected, EpgNames.normalize("BBC One (1080p) [UK]"))
    }

    @Test
    fun keyIsNullWhenNothingIsLeft() {
        assertNull(EpgNames.key("HD"))
        assertNull(EpgNames.key("  "))
        assertEquals("name:cnn", EpgNames.key("CNN"))
    }

    @Test
    fun channelEpgKeyPrefersTvgIdThenTvgNameThenName() {
        assertEquals("bbc1.uk", Channel(name = "BBC", url = "u", tvgId = "bbc1.uk").epgKey)
        assertEquals("name:bbcone", Channel(name = "BBC 1", url = "u", tvgName = "BBC One").epgKey)
        assertEquals("name:bbcone", Channel(name = "BBC One HD", url = "u").epgKey)
    }

    @Test
    fun cacheIsAlsoKeyedByMatchedNames() {
        val programme = Programme("bbc1.uk", "News", 0L, 1L)
        val cache = EpgCache(0L, mapOf("bbc1.uk" to listOf(programme)), mapOf("bbcone" to "bbc1.uk"))

        val byKey = cache.byEpgKey()

        assertEquals(listOf(programme), byKey["bbc1.uk"])
        assertEquals(listOf(programme), byKey[Channel(name = "UK: BBC One", url = "u").epgKey])
    }
}
