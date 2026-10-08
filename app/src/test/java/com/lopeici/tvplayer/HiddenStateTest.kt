package com.lopeici.tvplayer

import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.data.HiddenState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenStateTest {

    private val news = Channel(name = "CNN", url = "http://x/cnn", group = "News")
    private val bbc = Channel(name = "BBC", url = "http://x/bbc", group = "News")
    private val noGroup = Channel(name = "Other", url = "http://x/other")

    @Test
    fun emptyStateHidesNothing() {
        val state = HiddenState()
        assertTrue(state.isEmpty)
        assertFalse(state.isHidden(news))
        assertFalse(state.isHidden(noGroup))
    }

    @Test
    fun hiddenGroupHidesItsChannels() {
        val state = HiddenState(groups = setOf("News"))
        assertTrue(state.isHidden(news))
        assertTrue(state.isHidden(bbc))
        assertFalse(state.isHidden(noGroup))
    }

    @Test
    fun unhiddenChannelInHiddenGroupIsShown() {
        val state = HiddenState(groups = setOf("News"), unhidden = setOf(bbc.url))
        assertTrue(state.isHidden(news))
        assertFalse(state.isHidden(bbc))
    }

    @Test
    fun individuallyHiddenChannelStaysHiddenEvenIfUnhidden() {
        val state = HiddenState(channels = setOf(noGroup.url, bbc.url), unhidden = setOf(bbc.url))
        assertTrue(state.isHidden(noGroup))
        assertTrue(state.isHidden(bbc))
        assertFalse(state.isHidden(news))
    }
}
