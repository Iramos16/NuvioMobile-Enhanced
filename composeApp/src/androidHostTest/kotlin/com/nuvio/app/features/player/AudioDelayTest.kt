package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioDelayTest {
    @Test
    fun clampLimitsToSixtySeconds() {
        assertEquals(60_000, clampAudioDelayMs(70_000))
        assertEquals(-60_000, clampAudioDelayMs(-70_000))
        assertEquals(250, clampAudioDelayMs(250))
    }

    @Test
    fun sameStreamKeepsDelay() {
        assertEquals(300, audioDelayForStream(AudioDelayState("a", 300), "a").delayMs)
    }

    @Test
    fun differentStreamResetsDelay() {
        val next = audioDelayForStream(AudioDelayState("a", 300), "b")
        assertEquals(0, next.delayMs)
        assertEquals("b", next.streamUrl)
    }

    @Test
    fun formatsSignedSeconds() {
        assertEquals("+0.25 s", formatAudioDelay(250))
        assertEquals("-1.50 s", formatAudioDelay(-1500))
        assertEquals("0.00 s", formatAudioDelay(0))
    }

    @Test
    fun holdRepeatAccelerates() {
        assertEquals(50, holdRepeatStepMs(500))
        assertEquals(250, holdRepeatStepMs(2_000))
        assertEquals(1_000, holdRepeatStepMs(4_000))
    }
}
