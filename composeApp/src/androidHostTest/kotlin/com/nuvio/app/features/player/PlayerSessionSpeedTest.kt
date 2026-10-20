package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSessionSpeedTest {
    @Test
    fun noSessionSpeedAndNoPartyDefaultsToNormal() {
        assertEquals(1f, speedForNewEpisode(null, null))
    }

    @Test
    fun sessionSpeedIsCarriedOver() {
        assertEquals(2f, speedForNewEpisode(2f, null))
    }

    @Test
    fun partyRateWinsOverSessionSpeed() {
        assertEquals(1.25f, speedForNewEpisode(2f, 1.25f))
    }

    @Test
    fun holdToSpeedDoesNotOverwriteSessionSpeed() {
        assertEquals(2f, recordUserSpeed(2f, 3f, holdActive = true))
    }

    @Test
    fun pickerRecordsSessionSpeed() {
        assertEquals(2f, recordUserSpeed(null, 2f, holdActive = false))
    }
}
