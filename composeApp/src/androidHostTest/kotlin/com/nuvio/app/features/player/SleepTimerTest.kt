package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {

    @Test
    fun startComputesEndAndRemainingClampsAtZero() {
        val timer = startSleepTimer(15, 1_000)
        assertEquals(901_000L, timer.endsAtMs)
        assertEquals(0L, timer.remainingMs(901_500))
        assertEquals(900_000L, timer.remainingMs(1_000))
    }

    @Test
    fun remainingIsNullForOffAndEndOfEpisode() {
        assertNull(SleepTimerMode.Off.remainingMs(0))
        assertNull(SleepTimerMode.EndOfEpisode.remainingMs(0))
    }

    @Test
    fun durationFiresAtEndNotBefore() {
        val timer = startSleepTimer(15, 1_000)
        assertFalse(timer.shouldFire(900_999, 0, 5_000_000, null))
        assertTrue(timer.shouldFire(901_000, 0, 5_000_000, null))
    }

    @Test
    fun durationFadeStartsTenSecondsBeforeEnd() {
        val timer = startSleepTimer(15, 1_000)
        assertFalse(timer.fadeStartReached(890_999, 0, 5_000_000, null))
        assertTrue(timer.fadeStartReached(891_000, 0, 5_000_000, null))
        assertTrue(timer.fadeStartReached(901_000, 0, 5_000_000, null))
    }

    @Test
    fun endOfEpisodeFiresAtThreshold() {
        val mode = SleepTimerMode.EndOfEpisode
        assertFalse(mode.shouldFire(0, 1_189_999, 5_400_000, 1_200_000))
        assertTrue(mode.shouldFire(0, 1_200_000, 5_400_000, 1_200_000))
        assertFalse(mode.fadeStartReached(0, 1_189_999, 5_400_000, 1_200_000))
        assertTrue(mode.fadeStartReached(0, 1_190_000, 5_400_000, 1_200_000))
    }

    @Test
    fun endOfEpisodeWithoutThresholdFiresOneSecondBeforeEnd() {
        val mode = SleepTimerMode.EndOfEpisode
        assertFalse(mode.shouldFire(0, 5_398_999, 5_400_000, null))
        assertTrue(mode.shouldFire(0, 5_399_000, 5_400_000, null))
    }

    @Test
    fun unknownDurationNeverFiresEndOfEpisode() {
        val mode = SleepTimerMode.EndOfEpisode
        assertFalse(mode.shouldFire(0, 10_000_000, 0, null))
        assertFalse(mode.fadeStartReached(0, 10_000_000, 0, null))
        assertFalse(mode.shouldFire(0, 10_000_000, 0, 1_200_000))
    }

    @Test
    fun offNeverFires() {
        assertFalse(SleepTimerMode.Off.shouldFire(Long.MAX_VALUE, Long.MAX_VALUE, 1, null))
        assertFalse(SleepTimerMode.Off.fadeStartReached(Long.MAX_VALUE, Long.MAX_VALUE, 1, null))
    }

    @Test
    fun fadeVolumeIsLinearAndClamped() {
        assertEquals(0.4f, fadeVolumeFraction(0.8f, 5_000), 0.0001f)
        assertEquals(0f, fadeVolumeFraction(0.8f, 12_000), 0.0001f)
        assertEquals(0.8f, fadeVolumeFraction(0.8f, -500), 0.0001f)
    }
}
