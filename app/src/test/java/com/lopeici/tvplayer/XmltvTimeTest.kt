package com.lopeici.tvplayer

import com.lopeici.tvplayer.data.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class XmltvTimeTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun noOffsetMeansUtc() {
        assertEquals(millis("2026-10-08T20:30:00Z"), XmltvParser.parseTime("20261008203000"))
    }

    @Test
    fun appliesPositiveAndNegativeOffsets() {
        assertEquals(millis("2026-10-08T19:30:00Z"), XmltvParser.parseTime("20261008203000 +0100"))
        assertEquals(millis("2026-10-08T23:30:00Z"), XmltvParser.parseTime("20261008203000 -0300"))
    }

    @Test
    fun toleratesMissingSpaceAndSurroundingWhitespace() {
        assertEquals(millis("2026-10-08T18:30:00Z"), XmltvParser.parseTime("  20261008203000+0200 "))
    }

    @Test
    fun invalidOffsetFallsBackToUtc() {
        assertEquals(millis("2026-10-08T20:30:00Z"), XmltvParser.parseTime("20261008203000 bogus"))
    }

    @Test
    fun rejectsMissingShortOrInvalidValues() {
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime(""))
        assertNull(XmltvParser.parseTime("202610082030"))
        assertNull(XmltvParser.parseTime("20261340203000"))
    }
}
