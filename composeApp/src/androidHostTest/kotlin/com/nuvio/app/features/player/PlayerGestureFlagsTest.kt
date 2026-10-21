package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerGestureFlagsTest {
    private val allOn = PlayerGestureFlags(
        master = true,
        brightnessSwipe = true,
        volumeSwipe = true,
        holdToSpeed = true,
        doubleTapSeek = true,
        swipeToSeek = true,
        swapSides = false,
    )

    @Test
    fun leftIsBrightnessRightIsVolumeAndCenterIsNeither() {
        assertEquals(PlayerSideGesture.Brightness, allOn.sideGestureFor(0.1f))
        assertEquals(PlayerSideGesture.Volume, allOn.sideGestureFor(0.9f))
        assertNull(allOn.sideGestureFor(0.5f))
    }

    @Test
    fun swappingSidesExchangesTheGestures() {
        val swapped = allOn.copy(swapSides = true)
        assertEquals(PlayerSideGesture.Volume, swapped.sideGestureFor(0.1f))
        assertEquals(PlayerSideGesture.Brightness, swapped.sideGestureFor(0.9f))
        assertNull(swapped.sideGestureFor(0.5f))
    }

    @Test
    fun disabledGestureYieldsNullForItsSideOnly() {
        val noBrightness = allOn.copy(brightnessSwipe = false)
        assertNull(noBrightness.sideGestureFor(0.1f))
        assertEquals(PlayerSideGesture.Volume, noBrightness.sideGestureFor(0.9f))
        val swappedNoBrightness = noBrightness.copy(swapSides = true)
        assertEquals(PlayerSideGesture.Volume, swappedNoBrightness.sideGestureFor(0.1f))
        assertNull(swappedNoBrightness.sideGestureFor(0.9f))
    }

    @Test
    fun masterOffDisablesEverything() {
        val off = allOn.copy(master = false)
        assertNull(off.sideGestureFor(0.1f))
        assertNull(off.sideGestureFor(0.9f))
        assertFalse(off.doubleTapEnabled)
        assertFalse(off.holdToSpeedEnabled)
        assertTrue(allOn.doubleTapEnabled)
        assertTrue(allOn.holdToSpeedEnabled)
    }
}
