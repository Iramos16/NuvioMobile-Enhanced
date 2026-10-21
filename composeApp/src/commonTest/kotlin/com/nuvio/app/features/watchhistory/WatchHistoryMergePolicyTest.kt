package com.nuvio.app.features.watchhistory

import com.nuvio.app.features.tracking.TrackingProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchHistoryMergePolicyTest {

    @Test
    fun fromStorage_knownValues_roundTrip() {
        WatchHistoryMergePolicy.entries.forEach { policy ->
            assertEquals(policy, WatchHistoryMergePolicy.fromStorage(policy.name))
        }
    }

    @Test
    fun fromStorage_unknownOrNull_returnsDefault() {
        listOf(null, "", "bogus").forEach { value ->
            assertEquals(WatchHistoryMergePolicy.MOST_RECENT, WatchHistoryMergePolicy.fromStorage(value))
        }
        assertEquals(WatchHistoryMergePolicy.MOST_RECENT, DEFAULT_WATCH_HISTORY_MERGE_POLICY)
    }

    @Test
    fun aniList_isApproximate_othersExact() {
        WatchHistorySourceId.entries.forEach { id ->
            val expected = if (id == WatchHistorySourceId.ANILIST) {
                WatchHistoryDateConfidence.APPROXIMATE
            } else {
                WatchHistoryDateConfidence.EXACT
            }
            assertEquals(expected, id.dateConfidence)
        }
    }

    @Test
    fun fromTrackingProvider_mapsAllButMdbList() {
        assertEquals(WatchHistorySourceId.TRAKT, WatchHistorySourceId.fromTrackingProvider(TrackingProviderId.TRAKT))
        assertEquals(WatchHistorySourceId.SIMKL, WatchHistorySourceId.fromTrackingProvider(TrackingProviderId.SIMKL))
        assertEquals(WatchHistorySourceId.ANILIST, WatchHistorySourceId.fromTrackingProvider(TrackingProviderId.ANILIST))
        assertEquals(WatchHistorySourceId.WETRAKR, WatchHistorySourceId.fromTrackingProvider(TrackingProviderId.WETRAKR))
        assertNull(WatchHistorySourceId.fromTrackingProvider(TrackingProviderId.MDBLIST))
    }
}
