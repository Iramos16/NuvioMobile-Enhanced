package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSeekStepsTest {
    @Test
    fun normalizeFallsBackToTenForNullAndUnknownValues() {
        assertEquals(10, normalizeSeekStepSeconds(null))
        assertEquals(10, normalizeSeekStepSeconds(7))
    }

    @Test
    fun normalizeKeepsSupportedChoices() {
        assertEquals(30, normalizeSeekStepSeconds(30))
        assertEquals(90, normalizeSeekStepSeconds(90))
    }

    @Test
    fun stackDoubleTapSeekStartsAtOneStepThenAddsOneStepPerTap() {
        assertEquals(15_000L, stackDoubleTapSeek(null, 15_000L))
        assertEquals(30_000L, stackDoubleTapSeek(15_000L, 15_000L))
    }
}
