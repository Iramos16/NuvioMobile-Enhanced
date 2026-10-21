package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerStreamFailoverTest {
    private fun stream(url: String?, addon: String = "A") =
        StreamItem(url = url, addonName = addon, addonId = "addon:$addon")

    private val streams = (0..4).map { stream("u$it") }

    @Test
    fun `first failure retries`() {
        assertEquals(StreamFailoverAction.Retry, decideStreamFailover(StreamFailoverState(), "u1", hasController = true))
    }

    @Test
    fun `second failure of same url moves to next stream`() {
        val state = StreamFailoverState(retriedUrl = "u1")
        assertEquals(StreamFailoverAction.NextStream, decideStreamFailover(state, "u1", hasController = true))
    }

    @Test
    fun `no controller skips retry`() {
        assertEquals(StreamFailoverAction.NextStream, decideStreamFailover(StreamFailoverState(), "u1", hasController = false))
    }

    @Test
    fun `gives up after three alternatives`() {
        val state = StreamFailoverState(retriedUrl = "u1", alternativesUsed = 3)
        assertEquals(StreamFailoverAction.GiveUp, decideStreamFailover(state, "u1", hasController = true))
    }

    @Test
    fun `new stream gets its own retry`() {
        val state = StreamFailoverState(retriedUrl = "u1", alternativesUsed = 1)
        assertEquals(StreamFailoverAction.Retry, decideStreamFailover(state, "u2", hasController = true))
    }

    @Test
    fun `candidate is the next stream after the failed one`() {
        val candidate = selectFailoverCandidate(streams.take(4), "u1", null, setOf("u1")) { true }
        assertEquals("u2", candidate?.url)
    }

    @Test
    fun `missing failed stream starts from top`() {
        val candidate = selectFailoverCandidate(streams, "zz", null, setOf("zz")) { true }
        assertEquals("u0", candidate?.url)
    }

    @Test
    fun `excluded and unplayable streams are skipped`() {
        val candidate = selectFailoverCandidate(streams, "u1", null, setOf("u1", "u2")) { it.url != "u3" }
        assertEquals("u4", candidate?.url)
    }

    @Test
    fun `streams without a direct url are skipped`() {
        val list = listOf(stream("u1"), stream(null), stream("magnet:?xt=urn:btih:abc"), stream("u4"))
        val candidate = selectFailoverCandidate(list, "u1", null, setOf("u1")) { true }
        assertEquals("u4", candidate?.url)
    }

    @Test
    fun `end of list yields null`() {
        assertNull(selectFailoverCandidate(streams.take(4), "u3", null, setOf("u3")) { true })
    }

    @Test
    fun `identity key locates failed stream when url changed`() {
        val hashed = stream("u1").copy(behaviorHints = StreamBehaviorHints(videoHash = "abc"))
        val refreshed = streams.toMutableList().apply { set(1, hashed) }
        val candidate = selectFailoverCandidate(
            refreshed,
            failedUrl = "old",
            failedIdentityKey = hashed.copy(url = "old").playerSourceIdentityKey(),
            excludedUrls = setOf("old"),
        ) { true }
        assertEquals("u2", candidate?.url)
    }

    private val armed = StreamFailoverState(
        retriedUrl = "u2",
        alternativesUsed = 2,
        failedUrls = setOf("u1"),
        lastProgressPositionMs = 60_000,
    )

    private fun StreamFailoverState.playThrough(fromMs: Long, toMs: Long, stepMs: Long = 1_000): StreamFailoverState {
        var state = this
        var position = fromMs
        while (position < toMs) {
            position += stepMs
            state = state.afterProgress(position)
        }
        return state
    }

    @Test
    fun `progress under 30s does not reset budget`() {
        val state = armed.playThrough(60_000, 89_000)
        assertEquals(2, state.alternativesUsed)
        assertEquals(29_000, state.playedMs)
    }

    @Test
    fun `30s of playback resets budget but keeps failed urls`() {
        assertEquals(StreamFailoverState(failedUrls = setOf("u1")), armed.playThrough(60_000, 90_000))
    }

    @Test
    fun `seeking forward does not count as played time`() {
        val state = armed.afterProgress(120_000)
        assertEquals(2, state.alternativesUsed)
        assertEquals(0, state.playedMs)
        assertEquals(120_000, state.lastProgressPositionMs)
    }

    @Test
    fun `default state ignores progress`() {
        assertEquals(StreamFailoverState(), StreamFailoverState().afterProgress(10_000_000))
    }

    @Test
    fun `new video resets budget but keeps failed urls`() {
        val state = armed.copy(videoId = "tt1:1:1")
        assertEquals(
            StreamFailoverState(failedUrls = setOf("u1"), videoId = "tt1:1:2"),
            state.forVideo("tt1:1:2"),
        )
        assertSame(state, state.forVideo("tt1:1:1"))
    }

    @Test
    fun `leftover source loading stops only when nothing else uses it`() {
        assertTrue(shouldStopLeftoverSourceLoading(isLoading = true, sourcesPanelOpen = false, failoverRunning = false))
        assertFalse(shouldStopLeftoverSourceLoading(isLoading = false, sourcesPanelOpen = false, failoverRunning = false))
        assertFalse(shouldStopLeftoverSourceLoading(isLoading = true, sourcesPanelOpen = true, failoverRunning = false))
        assertFalse(shouldStopLeftoverSourceLoading(isLoading = true, sourcesPanelOpen = false, failoverRunning = true))
    }
}
